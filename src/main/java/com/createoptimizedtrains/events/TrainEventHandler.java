package com.createoptimizedtrains.events;

import com.createoptimizedtrains.CreateOptimizedTrains;
import com.createoptimizedtrains.chunks.ChunkLoadManager;
import com.createoptimizedtrains.chunks.DirectionalChunkShaper;
import com.createoptimizedtrains.chunks.RouteChunkPreloader;
import com.createoptimizedtrains.config.ModConfig;
import com.createoptimizedtrains.diagnostics.DebugLog;
import com.createoptimizedtrains.grouping.TrainGroupManager;
import com.createoptimizedtrains.util.PlayerTrainTracker;
import com.createoptimizedtrains.util.CarriageUtils;
import com.createoptimizedtrains.lod.LODLevel;
import com.createoptimizedtrains.lod.LODSystem;
import com.createoptimizedtrains.monitor.PerformanceMonitor;
import com.createoptimizedtrains.priority.PriorityScheduler;
import com.createoptimizedtrains.proxy.ProxyEntityManager;
import com.createoptimizedtrains.threading.AsyncTaskManager;
import com.simibubi.create.content.trains.GlobalRailwayManager;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.infrastructure.config.AllConfigs;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Event handler central que orquestra todos os sistemas de otimização a cada tick.
 * Usa staggering para distribuir carga entre ticks e paralelização para LOD.
 */
public class TrainEventHandler {

    private static final Logger PERF_LOGGER = LogManager.getLogger("COT/Perf");
    private static volatile long lastPerfLog = 0;

    private static final int LOD_UPDATE_INTERVAL = 10;
    private static final int CONFLICT_CHECK_INTERVAL = 40;
    private static final int CHUNK_UPDATE_INTERVAL = 1; // Cada tick: chunk loading é crítico para velocidade máxima
    private static final int ROUTE_PRELOAD_INTERVAL = 5; // Route pre-load a cada 5 ticks (rota muda lentamente)
    private static final int PROXY_UPDATE_INTERVAL = 10;

    // Intervalo para verificar zona de buffer e trigger de fade-out (cada 5 ticks)
    private static final int VISIBILITY_CHECK_INTERVAL = 5;

    // Atraso de arranque: não processar chunks nos primeiros ticks após o servidor iniciar.
    // Isto evita um pico de memória enorme ao competir com spawn chunks + Distant Horizons.
    private static final int STARTUP_DELAY_TICKS = 100; // 5 segundos — evita competir com DH no pico de init
    // Durante o período de ramp-up (após o delay), processar poucos comboios por tick
    private static final int RAMP_UP_TICKS = 200; // 10 segundos de ramp-up gradual
    private static final int RAMP_UP_BATCH_SIZE = 2; // 2 comboios por tick durante ramp-up (menos pressão no IO)
    // Comboios parados sem passageiros: atualizar chunks muito menos frequentemente
    // A posição não muda, logo as chunks necessárias também não mudam
    private static final int STOPPED_CHUNK_UPDATE_INTERVAL = 10;

    // Stagger: distribuir updates de chunks/proxies ao longo dos ticks
    // Em vez de atualizar TODOS os comboios num só tick, dividir em batches
    private static final int BATCH_SIZE = 8;

    private long tickCounter = 0;
    // Tracking de view distance original para restaurar quando o jogador sai do comboio
    private final Map<UUID, Integer> originalViewDistances = new ConcurrentHashMap<>();
    // Cache para evitar recriação de listas a cada tick
    private List<Train> trainListCache = new ArrayList<>();

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        CreateOptimizedTrains mod = CreateOptimizedTrains.getInstance();
        if (mod == null) return;

        tickCounter++;

        // Log de arranque para diagnóstico
        if (tickCounter == 1) {
            CreateOptimizedTrains.LOGGER.info("COT: Primeiro tick do servidor.");
        }
        if (tickCounter == STARTUP_DELAY_TICKS) {
            CreateOptimizedTrains.LOGGER.info("COT: Atraso de arranque terminado. Chunk systems ativos.");
        }

        // 1. Monitor de performance — cada tick (muito leve)
        PerformanceMonitor monitor = mod.getPerformanceMonitor();
        if (monitor != null) {
            monitor.onServerTick();
        }

        // 2. Processar tarefas async pendentes (com budget de tempo)
        AsyncTaskManager async = mod.getAsyncTaskManager();
        if (async != null) {
            async.processMainThreadQueue();
        }

        // 3. Obter comboios ativos
        var server = event.getServer();
        if (server == null) return;

        Collection<Train> trains = getActiveTrains();
        if (trains == null || trains.isEmpty()) return;

        // Reutilizar lista para evitar GC pressure
        trainListCache.clear();
        trainListCache.addAll(trains);

        List<ServerPlayer> players = server.getPlayerList().getPlayers();

        // 4. Directional chunk shaper + rastrear comboios ocupados
        Set<UUID> occupiedTrainIds = new HashSet<>();
        for (ServerPlayer player : players) {
            DirectionalChunkShaper.updatePlayer(player);
            // Verificar se este jogador está num comboio
            net.minecraft.world.entity.Entity vehicle = player.getVehicle();
            int depth = 0;
            while (vehicle != null && depth < 5) {
                if (vehicle instanceof com.simibubi.create.content.trains.entity.CarriageContraptionEntity cce) {
                    var carriage = cce.getCarriage();
                    if (carriage != null && carriage.train != null) {
                        occupiedTrainIds.add(carriage.train.id);
                    }
                    break;
                }
                vehicle = vehicle.getVehicle();
                depth++;
            }
        }
        PlayerTrainTracker.setOccupiedTrains(occupiedTrainIds);

        // 5. LOD — cálculo PARALELO em threads secundárias
        if (tickCounter % LOD_UPDATE_INTERVAL == 0) {
            updateLODLevelsParallel(mod, trainListCache, players);
        }

        // 6. Grouping/Proxies — staggered (só se pelo menos um estiver ativo)
        if (tickCounter % PROXY_UPDATE_INTERVAL == 0) {
            boolean proxyOn = ModConfig.PROXY_ENABLED.get();
            boolean groupOn = ModConfig.GROUPING_ENABLED.get();
            if (proxyOn || groupOn) {
                updateGroupingAndProxiesStaggered(mod, trainListCache, server.overworld());
            }
        }

        // 7. Chunks
        long t7Start = System.nanoTime();
        if (tickCounter % CHUNK_UPDATE_INTERVAL == 0 && tickCounter > STARTUP_DELAY_TICKS) {
            List<Train> chunkBatch = getChunkBatch(trainListCache);
            updateChunkLoadingAll(mod, chunkBatch, server.overworld(), players);
            ChunkLoadManager chunks = mod.getChunkLoadManager();
            if (chunks != null) {
                for (Train train : chunkBatch) {
                    chunks.updatePlayerProximityBuffer(train, server.overworld(), players);
                }
            }
        }
        long t7Ms = (System.nanoTime() - t7Start) / 1_000_000L;

        // 7b. Verificar zona de buffer e trigger de fade-out (a cada 5 ticks)
        // Usa background thread para não atrasar o server tick
        if (tickCounter % VISIBILITY_CHECK_INTERVAL == 0 && tickCounter > STARTUP_DELAY_TICKS) {
            updateVisibilityZones(mod, trainListCache, players, server.overworld());
        }

        // 8. Route chunk pre-loading — atraso extra para rota estabilizar
        if (tickCounter % ROUTE_PRELOAD_INTERVAL == 0 && tickCounter > STARTUP_DELAY_TICKS + 40) {
            RouteChunkPreloader preloader = mod.getRouteChunkPreloader();
            if (preloader != null) {
                List<Train> batch = getChunkBatch(trainListCache);
                for (Train train : batch) {
                    if (train.speed != 0) {
                        // Não pré-carregar rotas de comboios fantasma (longe do jogador, todas entidades ausentes)
                        // — desperdiçaria I/O de disco para comboios que o jogador não verá cedo.
                        boolean allMissing = true;
                        for (var carriage : train.carriages) {
                            if (CarriageUtils.safeAnyAvailableEntity(carriage) != null) { allMissing = false; break; }
                        }
                        if (allMissing && !PlayerTrainTracker.isOccupied(train.id)
                                && !isTrainNearAnyPlayer(train, players, server.overworld(), 400.0 * 400.0)) {
                            continue;
                        }
                        preloader.preloadRouteChunks(train, server.overworld());
                    }
                }
            }
        }

        // 9. Conflitos — assíncrono (já era)
        if (tickCounter % CONFLICT_CHECK_INTERVAL == 0) {
            PriorityScheduler scheduler = mod.getPriorityScheduler();
            if (scheduler != null) {
                scheduler.analyzeConflictsAsync(trainListCache);
            }
        }

        // 10. Recalcular distâncias LOD (adaptativo)
        if (tickCounter % (LOD_UPDATE_INTERVAL * 5) == 0) {
            LODSystem lod = mod.getLODSystem();
            if (lod != null) {
                lod.recalculateDistances();
            }
        }

        // Log de performance: logar o passo mais pesado quando ultrapassar 15ms
        if (DebugLog.ENABLED && t7Ms > 15) {
            long now = System.currentTimeMillis();
            if (now - lastPerfLog > 3_000) {
                lastPerfLog = now;
                PERF_LOGGER.warn("[COT/Perf] Tick lento: chunks={}ms, trains={}",
                    t7Ms, trainListCache.size());
            }
        }
    }

    /**
     * Cálculo de LOD paralelo: cacheia posições dos jogadores, depois submete
     * cálculo de todos os comboios para o ForkJoinPool.
     * Resultados são aplicados no main thread via callback.
     */
    private void updateLODLevelsParallel(CreateOptimizedTrains mod, List<Train> trains,
                                           List<ServerPlayer> players) {
        LODSystem lodSystem = mod.getLODSystem();
        AsyncTaskManager async = mod.getAsyncTaskManager();
        if (lodSystem == null || async == null) return;

        // Cachear posições dos jogadores uma vez (main thread, rápido)
        lodSystem.cachePlayerPositions(players);

        // Para poucos comboios (<= 8), calcular no main thread (overhead de threading > benefício)
        if (trains.size() <= 8) {
            for (Train train : trains) {
                LODLevel level = lodSystem.calculateLODForTrain(train, players);
                LODLevel previous = lodSystem.getTrainLOD(train.id);
                lodSystem.updateTrainLOD(train.id, level);
                if (previous != level) {
                    onTrainLODChanged(mod, train, previous, level);
                }
            }
            return;
        }

        // Para muitos comboios: paralelo via ForkJoinPool
        // Snapshot dos dados necessários (thread-safe)
        Map<UUID, LODLevel> previousLevels = new HashMap<>(trains.size());
        for (Train train : trains) {
            previousLevels.put(train.id, lodSystem.getTrainLOD(train.id));
        }

        async.submitParallelBatch(trains, train -> {
            LODLevel level = lodSystem.calculateLODForTrain(train, players);
            return new LODResult(train.id, level);
        }).thenAccept(results -> {
            // Aplicar resultados no main thread
            async.runOnMainThreadPriority(() -> {
                Map<UUID, LODLevel> newLevels = new HashMap<>(results.size());
                for (LODResult r : results) {
                    newLevels.put(r.trainId, r.level);
                }
                lodSystem.batchUpdateLOD(newLevels);

                // Disparar ações de mudança de LOD
                for (LODResult r : results) {
                    LODLevel previous = previousLevels.get(r.trainId);
                    if (previous != null && previous != r.level) {
                        // Encontrar o Train correspondente
                        for (Train train : trains) {
                            if (train.id.equals(r.trainId)) {
                                onTrainLODChanged(mod, train, previous, r.level);
                                break;
                            }
                        }
                    }
                }
            });
        });
    }

    private void onTrainLODChanged(CreateOptimizedTrains mod, Train train,
                                     LODLevel oldLevel, LODLevel newLevel) {
        // Grouping é desativado por padrão. Quando ativo, gestiona collapse/expand.
        if (!ModConfig.GROUPING_ENABLED.get()) return;

        TrainGroupManager groups = mod.getGroupManager();
        if (groups == null) return;

        if (newLevel.isAtLeast(LODLevel.LOW) && !oldLevel.isAtLeast(LODLevel.LOW)) {
            if (groups.shouldCollapse(train)) {
                groups.collapseTrain(train);
            }
        }

        if (!newLevel.isAtLeast(LODLevel.LOW) && oldLevel.isAtLeast(LODLevel.LOW)) {
            groups.expandTrain(train);
        }
    }

    /**
     * Atualizar proxies em batches staggered para distribuir carga.
     */
    private void updateGroupingAndProxiesStaggered(CreateOptimizedTrains mod,
                                                     List<Train> trains, ServerLevel level) {
        ProxyEntityManager proxies = mod.getProxyEntityManager();
        if (proxies == null) return;

        int batchIndex = (int) ((tickCounter / PROXY_UPDATE_INTERVAL) % Math.max(1, (trains.size() + BATCH_SIZE - 1) / BATCH_SIZE));
        int start = batchIndex * BATCH_SIZE;
        int end = Math.min(start + BATCH_SIZE, trains.size());

        for (int i = start; i < end; i++) {
            proxies.updateProxy(trains.get(i), level);
        }
    }

    /**
     * Atualizar chunks de comboios relevantes (próximos do jogador ou ocupados).
     *
     * GHOST MODE: Comboios com TODAS as carruagens sem entidade + longe de todos os
     * jogadores + não ocupados entram em "modo fantasma":
     *   - Train.tick() continua a correr via track graph (sem entidades)
     *   - DimensionalCarriageEntity mantém positionAnchor atualizado
     *   - carriageWaitingForChunks é sempre -1 (TrainMixin) — não param
     *   - Imunidade de stress ativa (isMoving && missingCount > 0) — não derailam
     *   - Não competem pelo cap de chunks com o comboio do jogador
     *   - Quando o jogador se aproxima (≤300 blocos), chunk loading é retomado
     *     e as entidades são criadas na posição correta (DCE positionAnchor)
     *
     * Comboios parados sem passageiros: atualizar a cada STOPPED_CHUNK_UPDATE_INTERVAL ticks.
     */
    private void updateChunkLoadingAll(CreateOptimizedTrains mod,
                                        List<Train> trains, ServerLevel level,
                                        List<ServerPlayer> players) {
        ChunkLoadManager chunks = mod.getChunkLoadManager();
        if (chunks == null) return;

        boolean isStoppedTick = (tickCounter % STOPPED_CHUNK_UPDATE_INTERVAL == 0);

        for (Train train : trains) {
            boolean isStopped = train.speed == 0;
            if (isStopped && !PlayerTrainTracker.isOccupied(train.id) && !isStoppedTick) {
                continue;
            }

            if (!PlayerTrainTracker.isOccupied(train.id)) {
                boolean allMissing = true;
                for (var carriage : train.carriages) {
                    if (CarriageUtils.safeAnyAvailableEntity(carriage) != null) { allMissing = false; break; }
                }
                if (allMissing && !isTrainNearAnyPlayer(train, players, level, 300.0 * 300.0)) {
                    chunks.releaseTrainChunks(train.id, level);
                    continue;
                }
            }

            long trainStart = System.nanoTime();
            chunks.updateTrainChunks(train, level);
            long trainMs = (System.nanoTime() - trainStart) / 1_000_000L;
            if (DebugLog.ENABLED && trainMs > 10) {
                long now = System.currentTimeMillis();
                if (now - lastPerfLog > 2_000) {
                    lastPerfLog = now;
                    String nameStr = train.id.toString().substring(0, 8);
                    PERF_LOGGER.warn("[COT/Perf] Comboio lento: id={}, chunk_update={}ms, speed={}, carriages={}",
                        nameStr, trainMs, String.format("%.1f", train.speed), train.carriages.size());
                }
            }
        }
    }

    /**
     * Verifica se alguma carruagem do comboio está dentro de maxDistSq blocos^2 de qualquer jogador.
     * Usa positionAnchor do DCE como fallback para carruagens sem entidade.
     */
    private boolean isTrainNearAnyPlayer(Train train, List<ServerPlayer> players,
                                          ServerLevel level, double maxDistSq) {
        for (var carriage : train.carriages) {
            double cx = Double.NaN, cy = Double.NaN, cz = Double.NaN;
            var entity = CarriageUtils.safeAnyAvailableEntity(carriage);
            if (entity != null) {
                cx = entity.getX(); cy = entity.getY(); cz = entity.getZ();
            } else {
                try {
                    var dce = carriage.getDimensional(level);
                    if (dce != null && dce.positionAnchor != null) {
                        cx = dce.positionAnchor.x;
                        cy = dce.positionAnchor.y;
                        cz = dce.positionAnchor.z;
                    }
                } catch (Exception ignored) {}
            }
            if (Double.isNaN(cx)) continue;
            for (var player : players) {
                double dx = player.getX() - cx, dy = player.getY() - cy, dz = player.getZ() - cz;
                if (dx * dx + dy * dy + dz * dz <= maxDistSq) return true;
            }
        }
        return false;
    }

    /**
     * Verificar zona de buffer para cada comboio.
     * Usa background thread via AsyncTaskManager para cálculos de distância (O(trains*players)).
     *
     * Comboios na zona de buffer (view distance a view distance + 3 chunks): LOD forçado a FULL,
     * para que entidades sejam criadas e posicionadas antes do jogador as ver.
     */
    private void updateVisibilityZones(CreateOptimizedTrains mod,
                                        List<Train> trains,
                                        List<ServerPlayer> players,
                                        ServerLevel level) {
        LODSystem lodSystem = mod.getLODSystem();
        AsyncTaskManager async = mod.getAsyncTaskManager();
        if (lodSystem == null || async == null) return;

        int viewDist = level.getServer().getPlayerList().getViewDistance();
        double viewDistBlocks = viewDist * 16.0;
        double bufferInnerSq = viewDistBlocks * viewDistBlocks;
        double bufferOuterSq = (viewDistBlocks + 48.0) * (viewDistBlocks + 48.0); // +3 chunks

        // Snapshot de posições de jogadores (thread-safe)
        double[][] playerPos = new double[players.size()][3];
        for (int i = 0; i < players.size(); i++) {
            playerPos[i][0] = players.get(i).getX();
            playerPos[i][1] = players.get(i).getY();
            playerPos[i][2] = players.get(i).getZ();
        }

        // Snapshot de dados de comboios (thread-safe)
        List<TrainVisData> trainData = new ArrayList<>(trains.size());
        for (Train train : trains) {
            double cx = 0, cy = 0, cz = 0;
            int count = 0;
            for (var carriage : train.carriages) {
                var entity = CarriageUtils.safeAnyAvailableEntity(carriage);
                if (entity != null) {
                    cx += entity.getX();
                    cy += entity.getY();
                    cz += entity.getZ();
                    count++;
                } else {
                    try {
                        var dce = carriage.getDimensional(level);
                        if (dce != null && dce.positionAnchor != null) {
                            cx += dce.positionAnchor.x;
                            cy += dce.positionAnchor.y;
                            cz += dce.positionAnchor.z;
                            count++;
                        }
                    } catch (Exception ignored) {}
                }
            }
            if (count > 0) {
                trainData.add(new TrainVisData(train.id, cx / count, cy / count, cz / count));
            }
        }

        // Processar em background thread
        async.submitAsync(() -> {
            List<UUID> inBuffer = new ArrayList<>();
            List<UUID> notInBuffer = new ArrayList<>();

            for (TrainVisData td : trainData) {
                double minDistSq = Double.MAX_VALUE;
                for (double[] pp : playerPos) {
                    double dx = pp[0] - td.x;
                    double dy = pp[1] - td.y;
                    double dz = pp[2] - td.z;
                    double distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq < minDistSq) minDistSq = distSq;
                }

                if (minDistSq > bufferInnerSq && minDistSq < bufferOuterSq) {
                    inBuffer.add(td.trainId);
                } else {
                    notInBuffer.add(td.trainId);
                }
            }

            return new VisibilityResult(inBuffer, notInBuffer);
        }, result -> {
            // Aplicar no main thread
            for (UUID id : result.inBuffer) {
                lodSystem.markInBufferZone(id);
            }
            for (UUID id : result.notInBuffer) {
                lodSystem.clearBufferZone(id);
            }
        });
    }

    private record TrainVisData(UUID trainId, double x, double y, double z) {}
    private record VisibilityResult(List<UUID> inBuffer, List<UUID> notInBuffer) {}

    // Comboios não-ocupados: actualizar a cada N ticks (reduz carga agregada de setChunkForced).
    private static final int NON_OCCUPIED_CHUNK_STRIDE = 3;
    // Máximo de comboios não-ocupados por batch; o resto roda para o próximo stride.
    private static final int NON_OCCUPIED_BATCH_SIZE = 5;

    private List<Train> getChunkBatch(List<Train> allTrains) {
        long ticksSinceReady = tickCounter - STARTUP_DELAY_TICKS;

        if (ticksSinceReady <= RAMP_UP_TICKS) {
            if (allTrains.size() <= RAMP_UP_BATCH_SIZE) return allTrains;
            int batchIndex = (int) ((ticksSinceReady / CHUNK_UPDATE_INTERVAL) % Math.max(1, (allTrains.size() + RAMP_UP_BATCH_SIZE - 1) / RAMP_UP_BATCH_SIZE));
            int start = batchIndex * RAMP_UP_BATCH_SIZE;
            int end = Math.min(start + RAMP_UP_BATCH_SIZE, allTrains.size());
            return allTrains.subList(start, end);
        }

        // Após ramp-up: comboios ocupados a cada tick + não-ocupados staggered.
        // Com 20 comboios e stride=3: ~5 não-ocupados/tick em vez de 20 → 3× menos I/O.
        List<Train> occupied = new ArrayList<>();
        List<Train> nonOccupied = new ArrayList<>();
        for (Train train : allTrains) {
            if (PlayerTrainTracker.isOccupied(train.id)) {
                occupied.add(train);
            } else {
                nonOccupied.add(train);
            }
        }

        List<Train> result = new ArrayList<>(occupied);
        if (!nonOccupied.isEmpty()) {
            int numBatches = Math.max(1, (nonOccupied.size() + NON_OCCUPIED_BATCH_SIZE - 1) / NON_OCCUPIED_BATCH_SIZE);
            int batchIdx = (int) ((ticksSinceReady / NON_OCCUPIED_CHUNK_STRIDE) % numBatches);
            int start = batchIdx * NON_OCCUPIED_BATCH_SIZE;
            int end = Math.min(start + NON_OCCUPIED_BATCH_SIZE, nonOccupied.size());
            result.addAll(nonOccupied.subList(start, end));
        }
        return result;
    }

    private Collection<Train> getActiveTrains() {
        try {
            GlobalRailwayManager manager = com.simibubi.create.Create.RAILWAYS;
            if (manager != null && manager.trains != null) {
                return manager.trains.values();
            }
        } catch (Exception e) {
            // Falha silenciosa se a API mudar
        }
        return List.of();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        CreateOptimizedTrains mod = CreateOptimizedTrains.getInstance();
        if (mod != null) {
            ChunkLoadManager chunks = mod.getChunkLoadManager();
            if (chunks != null) {
                chunks.releaseAll(event.getServer().overworld());
            }

            ProxyEntityManager proxies = mod.getProxyEntityManager();
            if (proxies != null) {
                proxies.removeAllProxies();
            }

            RouteChunkPreloader preloader = mod.getRouteChunkPreloader();
            if (preloader != null) {
                preloader.shutdown();
            }

            DirectionalChunkShaper.clear();

            mod.shutdown();
        }
    }

    private record LODResult(UUID trainId, LODLevel level) {}
}
