package com.createoptimizedtrains.events;

import com.createoptimizedtrains.CreateOptimizedTrains;
import com.createoptimizedtrains.chunks.ChunkLoadManager;
import com.createoptimizedtrains.grouping.TrainGroupManager;
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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Event handler central que orquestra todos os sistemas de otimização a cada tick.
 */
public class TrainEventHandler {

    private static final int LOD_UPDATE_INTERVAL = 10; // Atualizar LOD a cada 10 ticks
    private static final int CONFLICT_CHECK_INTERVAL = 40; // Verificar conflitos a cada 2 segundos
    private static final int CHUNK_UPDATE_INTERVAL = 5; // Atualizar chunks a cada 5 ticks

    private long tickCounter = 0;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        CreateOptimizedTrains mod = CreateOptimizedTrains.getInstance();
        if (mod == null) return;

        tickCounter++;

        // 1. Monitor de performance - a cada tick
        PerformanceMonitor monitor = mod.getPerformanceMonitor();
        if (monitor != null) {
            monitor.onServerTick();
        }

        // 2. Processar tarefas async pendentes no main thread
        AsyncTaskManager async = mod.getAsyncTaskManager();
        if (async != null) {
            async.processMainThreadQueue();
        }

        // 3. Obter todos os comboios do Create
        var server = event.getServer();
        if (server == null) return;

        // Aceder ao GlobalRailwayManager do Create para obter comboios
        Collection<Train> trains = getActiveTrains();
        if (trains == null || trains.isEmpty()) return;

        List<ServerPlayer> players = server.getPlayerList().getPlayers();

        // 4. Atualizar LOD periodicamente
        if (tickCounter % LOD_UPDATE_INTERVAL == 0) {
            updateLODLevels(mod, trains, players);
        }

        // 5. Gestão de agrupamento/proxy baseado no LOD
        if (tickCounter % LOD_UPDATE_INTERVAL == 0) {
            updateGroupingAndProxies(mod, trains, server.overworld());
        }

        // 6. Gestão de chunks
        if (tickCounter % CHUNK_UPDATE_INTERVAL == 0) {
            updateChunkLoading(mod, trains, server.overworld());
        }

        // 7. Verificar conflitos de via (assíncrono)
        if (tickCounter % CONFLICT_CHECK_INTERVAL == 0) {
            PriorityScheduler scheduler = mod.getPriorityScheduler();
            if (scheduler != null) {
                scheduler.analyzeConflictsAsync(trains);
            }
        }

        // 8. Recalcular distâncias de LOD se o monitor de performance atualizou
        if (tickCounter % (LOD_UPDATE_INTERVAL * 10) == 0) {
            LODSystem lod = mod.getLODSystem();
            if (lod != null) {
                lod.recalculateDistances();
            }
        }
    }

    private void updateLODLevels(CreateOptimizedTrains mod, Collection<Train> trains,
                                   List<ServerPlayer> players) {
        LODSystem lodSystem = mod.getLODSystem();
        if (lodSystem == null) return;

        for (Train train : trains) {
            LODLevel level = lodSystem.calculateLODForTrain(train, players);
            LODLevel previous = lodSystem.getTrainLOD(train.id);

            lodSystem.updateTrainLOD(train.id, level);

            // Se o LOD mudou, disparar ações
            if (previous != level) {
                onTrainLODChanged(mod, train, previous, level);
            }
        }
    }

    private void onTrainLODChanged(CreateOptimizedTrains mod, Train train,
                                     LODLevel oldLevel, LODLevel newLevel) {
        TrainGroupManager groups = mod.getGroupManager();
        if (groups == null) return;

        // Colapsar quando entra em LOD baixo/fantasma
        if (newLevel.isAtLeast(LODLevel.LOW) && !oldLevel.isAtLeast(LODLevel.LOW)) {
            if (groups.shouldCollapse(train)) {
                groups.collapseTrain(train);
            }
        }

        // Expandir quando volta a LOD médio/completo
        if (!newLevel.isAtLeast(LODLevel.LOW) && oldLevel.isAtLeast(LODLevel.LOW)) {
            groups.expandTrain(train);
        }
    }

    private void updateGroupingAndProxies(CreateOptimizedTrains mod, Collection<Train> trains,
                                            ServerLevel level) {
        ProxyEntityManager proxies = mod.getProxyEntityManager();
        if (proxies == null) return;

        for (Train train : trains) {
            proxies.updateProxy(train, level);
        }
    }

    private void updateChunkLoading(CreateOptimizedTrains mod, Collection<Train> trains,
                                      ServerLevel level) {
        ChunkLoadManager chunks = mod.getChunkLoadManager();
        if (chunks == null) return;

        for (Train train : trains) {
            chunks.updateTrainChunks(train, level);
        }
    }

    /**
     * Obter todos os comboios ativos do Create mod.
     * Create 6.x usa RAILWAYS.sided(level) para acesso aos comboios.
     */
    private Collection<Train> getActiveTrains() {
        try {
            GlobalRailwayManager manager = com.simibubi.create.Create.RAILWAYS;
            if (manager != null) {
                // Create 6.x: trains são acedidos via sided manager
                // Recolher de todos os lados (overworld + outras dimensões)
                Map<UUID, Train> allTrains = new java.util.HashMap<>();
                for (var entry : manager.trackNetworks.entrySet()) {
                    // Iterar todos os grafos de trilhos conhecidos
                }
                // Acesso direto ao mapa de trains (campo público em Create 6.x)
                if (manager.trains != null) {
                    return manager.trains.values();
                }
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
            // Limpar chunks force-loaded
            ChunkLoadManager chunks = mod.getChunkLoadManager();
            if (chunks != null) {
                chunks.releaseAll(event.getServer().overworld());
            }

            // Remover todas as proxies
            ProxyEntityManager proxies = mod.getProxyEntityManager();
            if (proxies != null) {
                proxies.removeAllProxies();
            }

            // Encerrar thread pool
            mod.shutdown();
        }
    }
}
