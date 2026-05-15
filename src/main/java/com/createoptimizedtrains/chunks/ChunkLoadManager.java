package com.createoptimizedtrains.chunks;

import com.createoptimizedtrains.CreateOptimizedTrains;
import com.createoptimizedtrains.compat.DistantHorizonsCompat;
import com.createoptimizedtrains.config.ModConfig;
import com.createoptimizedtrains.lod.LODLevel;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ChunkLoadManager {

    // Chunks atualmente force-loaded por nós, por comboio
    private final Map<UUID, Set<ChunkPos>> trainChunks = new ConcurrentHashMap<>();
    // Histórico de chunks recentes por comboio (para evitar chunk thrashing)
    private final Map<UUID, Deque<ChunkPos>> recentChunks = new ConcurrentHashMap<>();
    // Cache da última posição conhecida por comboio (para calcular direção de movimento)
    private final Map<UUID, double[]> lastKnownPositions = new ConcurrentHashMap<>();

    private static final int THRASH_HISTORY_SIZE = 12;

    // Cap global: máximo de chunks forçadas em simultâneo para TODOS os comboios.
    // Entity-ticking de muitos chunks consome CPU e memória.
    // 80 chunks forçados ~= 1.3GB de heap. Com 19GB+ de heap e hardware moderno, 80 é seguro.
    // Com DH, este cap é reduzido automaticamente (DistantHorizonsCompat).
    // Cap global (padrão de segurança); o valor efetivo vem de ModConfig.MAX_FORCED_CHUNKS
    private static final int MAX_GLOBAL_FORCED_CHUNKS_FALLBACK = 80;

    // Limite de novas chamadas setChunkForced por comboio por tick.
    // Evita picos de CPU/IO quando um novo comboio aparece e precisa de carregar
    // dezenas de chunks em simultâneo. As chunks de carruagem imediatas são
    // prioritárias (carregam primeiro) graças ao LinkedHashSet em calculateNeededChunks.
    private static final int MAX_CHUNK_LOADS_PER_TRAIN_PER_TICK = 8;

    /**
     * Atualizar chunks carregados para um comboio.
     * Usa direção de movimento real para pré-carregar à frente.
     * Usa positionAnchor do DCE para carruagens sem entidade (em chunks descarregados).
     *
     * CRÍTICO: trainChunks só guarda chunks REALMENTE forçadas no servidor.
     * Se o cap global impedir force-load, a chunk NÃO entra no tracking.
     * Isto evita inflação do contador e bloqueio permanente do cap.
     */
    public void updateTrainChunks(Train train, ServerLevel level) {
        if (!ModConfig.SMART_CHUNK_LOADING.get()) {
            return;
        }

        UUID trainId = train.id;
        Set<ChunkPos> needed = calculateNeededChunks(train, level);
        Set<ChunkPos> current = trainChunks.getOrDefault(trainId, Collections.emptySet());

        // Chunks a descarregar (estavam forçadas, já não são necessárias)
        Set<ChunkPos> toUnload = new HashSet<>(current);
        toUnload.removeAll(needed);

        // Chunks novas necessárias (não estavam forçadas).
        // LinkedHashSet preserva a ordem de inserção de 'needed', garantindo que
        // chunks de carruagens imediatas (prioridade 1) carregam antes do lookahead
        // e trailing (prioridade 2) quando o rate limit está ativo.
        Set<ChunkPos> toLoad = new LinkedHashSet<>(needed);
        toLoad.removeAll(current);

        // Anti-thrashing: manter chunks recentes mesmo que não estejam em needed
        Deque<ChunkPos> recent = recentChunks.computeIfAbsent(trainId, k -> new ArrayDeque<>());
        toUnload.removeIf(recent::contains);

        // Descarregar chunks desnecessárias
        for (ChunkPos pos : toUnload) {
            level.setChunkForced(pos.x, pos.z, false);
        }

        // Set de chunks REALMENTE forçadas após unload
        Set<ChunkPos> actuallyForced = new HashSet<>(current);
        actuallyForced.removeAll(toUnload);

        // PRIORIDADE CRÍTICA: carregar imediatamente (sem rate limit) os chunks de
        // carruagens que têm jogadores a bordo como passengers.
        // Entidades em chunks não-entity-ticking não são tracked pelo EntityTracker —
        // a sua posição não é sincronizada ao cliente, causando camera shake no jogador.
        // Carregar estes chunks imediatamente garante que a entidade é criada em
        // status entity-ticking desde o primeiro tick.
        for (Carriage carriage : train.carriages) {
            CarriageContraptionEntity cce = carriage.anyAvailableEntity();
            if (cce == null || cce.getPassengers().isEmpty()) continue;
            boolean hasPlayerPassenger = false;
            for (var passenger : cce.getPassengers()) {
                if (passenger instanceof net.minecraft.world.entity.player.Player) {
                    hasPlayerPassenger = true;
                    break;
                }
            }
            if (!hasPlayerPassenger) continue;
            int cx = (int) Math.floor(cce.getX()) >> 4;
            int cz = (int) Math.floor(cce.getZ()) >> 4;
            ChunkPos pChunk = new ChunkPos(cx, cz);
            if (!actuallyForced.contains(pChunk)) {
                level.setChunkForced(cx, cz, true);
                actuallyForced.add(pChunk);
                toLoad.remove(pChunk); // já carregado — não contar no rate limit
                recent.addLast(pChunk);
                if (recent.size() > THRASH_HISTORY_SIZE) recent.removeFirst();
            }
        }

        // Respeitar cap global: contar chunks de outros comboios + as que mantemos
        int configuredCap = safeGetMaxForcedChunks();
        int effectiveCap = DistantHorizonsCompat.getAdjustedGlobalChunkCap(configuredCap);
        int otherTrainsCount = getLoadedChunkCount() - current.size();
        int globalAfterUnload = otherTrainsCount + actuallyForced.size();
        int allowedToLoad = Math.max(0, effectiveCap - globalAfterUnload);

        // Rate limit: no máximo MAX_CHUNK_LOADS_PER_TRAIN_PER_TICK novas chunks por tick.
        // Distribui o trabalho de IO ao longo de vários ticks em vez de um pico único.
        int maxThisTick = Math.min(allowedToLoad, MAX_CHUNK_LOADS_PER_TRAIN_PER_TICK);
        int loaded = 0;
        for (ChunkPos pos : toLoad) {
            if (loaded >= maxThisTick) break;
            level.setChunkForced(pos.x, pos.z, true);
            actuallyForced.add(pos);
            recent.addLast(pos);
            if (recent.size() > THRASH_HISTORY_SIZE) {
                recent.removeFirst();
            }
            loaded++;
        }

        // CRÍTICO: só guardar chunks que foram REALMENTE forçadas no servidor
        trainChunks.put(trainId, actuallyForced);
    }

    private Set<ChunkPos> calculateNeededChunks(Train train, ServerLevel level) {
        // LinkedHashSet preserva a ordem de inserção.
        // Prioridade 1 (inseridas primeiro): chunks exatas de cada carruagem.
        // Prioridade 2 (inseridas depois): lookahead direcional + trailing buffer.
        // O rate limit em updateTrainChunks carrega nesta ordem quando há burst.
        Set<ChunkPos> chunks = new LinkedHashSet<>();

        if (train.carriages.isEmpty()) {
            return chunks;
        }

        int baseLookahead = ModConfig.CHUNK_LOOKAHEAD.get();
        int lookahead = DistantHorizonsCompat.getAdjustedLookahead(baseLookahead, train.speed);

        // Prioridade 1: chunk exata de cada carruagem.
        // Sem estas chunks, a entidade da carruagem não existe no servidor
        // e o Create não consegue simular a física dessa carruagem.
        for (Carriage carriage : train.carriages) {
            addCarriageChunks(carriage, chunks, level);
        }

        // Prioridade 2: lookahead direcional + trailing buffer.
        // Só corre quando o comboio está em movimento.
        if (train.speed != 0) {
            addDirectionalLookahead(train, chunks, lookahead, level);
        }

        return chunks;
    }

    /**
     * Pré-carregar chunks na direção de movimento do comboio.
     * Usa a diferença de posição entre ticks para determinar o vetor de movimento real.
     *
     * Force-load é limitado a 10 chunks à frente para cobrir comboios rápidos
     * (~40 bl/s = ~4 segundos de pre-load a 10 chunks).
     *
     * Também adiciona trailing buffer atrás da ÚLTIMA carruagem baseado no comprimento
     * total do comboio. Isto garante que carruagens traseiras (ex: composição 4+3+4)
     * nunca ficam em chunks descarregados, evitando stutters de física.
     */
    private void addDirectionalLookahead(Train train, Set<ChunkPos> chunks, int lookahead, ServerLevel level) {
        // Obter posição atual da carruagem da frente
        // Usa entidade se disponível, senão positionAnchor do DCE
        var frontCarriage = train.carriages.get(0);
        Vec3 frontPos = getCarriagePosition(frontCarriage, level);
        if (frontPos == null) return;

        double currentX = frontPos.x;
        double currentZ = frontPos.z;
        int currentChunkX = (int) Math.floor(currentX) >> 4;
        int currentChunkZ = (int) Math.floor(currentZ) >> 4;

        // Calcular direção de movimento real a partir do deltaMovement da entidade
        CarriageContraptionEntity frontEntity = frontCarriage.anyAvailableEntity();
        double motionX = 0;
        double motionZ = 0;
        if (frontEntity != null) {
            Vec3 motion = frontEntity.getDeltaMovement();
            motionX = motion.x;
            motionZ = motion.z;
        }

        // Se deltaMovement é zero (Create controla posição diretamente),
        // usar diferença de posição do último tick
        if (Math.abs(motionX) < 0.001 && Math.abs(motionZ) < 0.001) {
            double[] lastPos = lastKnownPositions.get(train.id);
            if (lastPos != null) {
                motionX = currentX - lastPos[0];
                motionZ = currentZ - lastPos[1];
            }
        }
        lastKnownPositions.put(train.id, new double[]{currentX, currentZ});

        double motionLength = Math.sqrt(motionX * motionX + motionZ * motionZ);
        if (motionLength < 0.01) {
            return; // Comboio parado — chunks da carruagem já foram adicionadas
        }

        // Normalizar direção de movimento
        double dirX = motionX / motionLength;
        double dirZ = motionZ / motionLength;

        // Lookahead adaptativo: limitado a 10 chunks (cobre comboios a ~40 bl/s = ~3.2s margem)
        double speedBlocks = Math.abs(train.speed) * 20.0;
        int adaptiveLookahead = Math.max(lookahead, (int) Math.ceil(speedBlocks * 2.0 / 16.0) + 1);
        // Cap configurável: simulationDistance define o limite máximo de lookahead.
        // 0 = desativado → fallback ao lookahead adaptativo puro (cap fixo de 10 chunks)
        int simDist = safeGetSimulationDistance();
        int cap = simDist > 0 ? simDist : 10;
        adaptiveLookahead = Math.min(adaptiveLookahead, cap);

        // Pré-carregar na direção de movimento
        // Chunks mais próximas têm lados (para curvas), distantes só centro
        // Chunks até 5 de distância: centro + lados (curvas)
        // Chunks 6-14: só centro (linha reta)
        for (int ahead = 1; ahead <= adaptiveLookahead; ahead++) {
            double projX = currentX + dirX * ahead * 16;
            double projZ = currentZ + dirZ * ahead * 16;
            int projChunkX = (int) Math.floor(projX) >> 4;
            int projChunkZ = (int) Math.floor(projZ) >> 4;

            chunks.add(new ChunkPos(projChunkX, projChunkZ));

            // Adicionar lados para os 3 chunks mais próximos (para curvas suaves)
            if (ahead <= 3) {
                double perpX = -dirZ;
                double perpZ = dirX;
                int sideChunkX1 = projChunkX + (int) Math.round(perpX);
                int sideChunkZ1 = projChunkZ + (int) Math.round(perpZ);
                int sideChunkX2 = projChunkX - (int) Math.round(perpX);
                int sideChunkZ2 = projChunkZ - (int) Math.round(perpZ);
                chunks.add(new ChunkPos(sideChunkX1, sideChunkZ1));
                chunks.add(new ChunkPos(sideChunkX2, sideChunkZ2));
            }
        }

        // Trailing buffer atrás da última carruagem.
        // Garante que as carruagens traseiras de composições longas (ex: 4+3+4) nunca ficam
        // em chunks descarregados enquanto o comboio se move. Sem isto, a física do comboio
        // inteiro é afetada porque Create não consegue simular corretamente uma carruagem
        // cujo chunk não está em entity-ticking status.
        //
        // O buffer é calculado com base no comprimento estimado do comboio:
        //   trailChunks = ceil(carriageCount * AVG_CARRIAGE_BLOCKS / 16) + CHUNK_TRAIL_KEEP
        // Onde AVG_CARRIAGE_BLOCKS ≈ 7 blocos por carruagem (bogeySpacing médio).
        var lastCarriage = train.carriages.get(train.carriages.size() - 1);
        Vec3 lastPos = getCarriagePosition(lastCarriage, level);
        if (lastPos != null) {
            int trailKeep = ModConfig.CHUNK_TRAIL_KEEP.get();
            // Estimar comprimento total: número de carruagens × ~7 blocos por carruagem
            int estimatedTrailChunks = (int) Math.ceil(train.carriages.size() * 7.0 / 16.0) + trailKeep + 1;
            estimatedTrailChunks = Math.min(estimatedTrailChunks, 8); // Cap de segurança

            for (int behind = 1; behind <= estimatedTrailChunks; behind++) {
                // Direção oposta ao movimento
                double projX = lastPos.x - dirX * behind * 16;
                double projZ = lastPos.z - dirZ * behind * 16;
                int projChunkX = (int) Math.floor(projX) >> 4;
                int projChunkZ = (int) Math.floor(projZ) >> 4;
                chunks.add(new ChunkPos(projChunkX, projChunkZ));
            }
        }
    }

    private void addCarriageChunks(Carriage carriage, Set<ChunkPos> chunks, ServerLevel level) {
        Vec3 pos = getCarriagePosition(carriage, level);
        if (pos != null) {
            int cx = (int) Math.floor(pos.x) >> 4;
            int cz = (int) Math.floor(pos.z) >> 4;

            // Raio por nível LOD: comboios próximos carregam área maior,
            // comboios distantes carregam apenas a chunk da carruagem.
            LODLevel lod = (carriage.train != null) ? getTrainLOD(carriage.train.id) : LODLevel.FULL;
            int radius = getCarriageRadius(lod);

            // Área configurada: radius=0 => 1 chunk, 1 => 3×3, 2 => 5×5, ...
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    chunks.add(new ChunkPos(cx + dx, cz + dz));
                }
            }
        }
    }

    /** Raio de chunks a carregar à volta da carruagem, consoante o nível LOD. */
    private int getCarriageRadius(LODLevel lod) {
        try {
            return switch (lod) {
                case FULL   -> ModConfig.LOD_CHUNK_RADIUS_FULL.get();
                case MEDIUM -> ModConfig.LOD_CHUNK_RADIUS_MEDIUM.get();
                case LOW    -> ModConfig.LOD_CHUNK_RADIUS_LOW.get();
                case GHOST  -> ModConfig.LOD_CHUNK_RADIUS_GHOST.get();
            };
        } catch (Exception e) {
            return 0; // config não inicializada ainda
        }
    }

    /** Obter LOD atual do comboio a partir do LODSystem. Devolve FULL se não disponivel. */
    private LODLevel getTrainLOD(UUID trainId) {
        try {
            CreateOptimizedTrains mod = CreateOptimizedTrains.getInstance();
            if (mod != null) {
                var lodSys = mod.getLODSystem();
                if (lodSys != null) return lodSys.getTrainLOD(trainId);
            }
        } catch (Exception ignored) {}
        return LODLevel.FULL;
    }

    private int safeGetSimulationDistance() {
        try { return ModConfig.TRAIN_SIMULATION_DISTANCE.get(); } catch (Exception e) { return 10; }
    }

    private int safeGetMaxForcedChunks() {
        try { return ModConfig.MAX_FORCED_CHUNKS.get(); } catch (Exception e) { return MAX_GLOBAL_FORCED_CHUNKS_FALLBACK; }
    }

    /**
     * Obter posição de uma carruagem, com ou sem entidade.
     * Prioridade: entidade viva > positionAnchor do DimensionalCarriageEntity.
     * O DCE mantém positionAnchor atualizado mesmo em chunks descarregados
     * porque Train.tick() corre sempre (carriageWaitingForChunks = -1).
     */
    private Vec3 getCarriagePosition(Carriage carriage, ServerLevel level) {
        var entity = carriage.anyAvailableEntity();
        if (entity != null) {
            return entity.position();
        }
        try {
            var dce = carriage.getDimensional(level);
            if (dce != null && dce.positionAnchor != null) {
                return dce.positionAnchor;
            }
        } catch (Exception e) {
            // API pode diferir entre versões do Create
        }
        return null;
    }

    /**
     * Pré-carregar chunks de buffer para carruagens perto do view distance de jogadores.
     * Isto cria uma "zona de buffer" de 1-2 chunks onde as entidades de carruagem
     * são criadas e posicionadas ANTES de o jogador as poder ver.
     *
     * Resultado: o comboio aparece completamente posicionado, sem ghost/stutter.
     */
    public void updatePlayerProximityBuffer(Train train, ServerLevel level, List<ServerPlayer> players) {
        if (players.isEmpty() || train.carriages.isEmpty()) return;

        int viewDist = level.getServer().getPlayerList().getViewDistance();
        // Buffer: 1-2 chunks depois do view distance (em blocos)
        int innerBoundary = (viewDist) * 16;
        int outerBoundary = (viewDist + 3) * 16;
        double innerSq = (double) innerBoundary * innerBoundary;
        double outerSq = (double) outerBoundary * outerBoundary;

        for (Carriage carriage : train.carriages) {
            Vec3 pos = getCarriagePosition(carriage, level);
            if (pos == null) continue;

            for (ServerPlayer player : players) {
                double distSq = player.distanceToSqr(pos.x, pos.y, pos.z);
                // Carruagem está na zona de buffer (entre view distance e view distance + 3)
                if (distSq > innerSq && distSq < outerSq) {
                    int cx = (int) Math.floor(pos.x) >> 4;
                    int cz = (int) Math.floor(pos.z) >> 4;
                    // Adicionar ao tracking deste comboio para ser gerido normalmente
                    Set<ChunkPos> current = trainChunks.computeIfAbsent(train.id, k -> new HashSet<>());
                    if (!current.contains(new ChunkPos(cx, cz))) {
                        int globalCount = getLoadedChunkCount();
                        if (globalCount < DistantHorizonsCompat.getAdjustedGlobalChunkCap(safeGetMaxForcedChunks())) {
                            level.setChunkForced(cx, cz, true);
                            current.add(new ChunkPos(cx, cz));
                        }
                    }
                    break; // Basta estar perto de um jogador
                }
            }
        }
    }

    public void releaseTrainChunks(UUID trainId, ServerLevel level) {
        Set<ChunkPos> chunks = trainChunks.remove(trainId);
        if (chunks != null) {
            for (ChunkPos pos : chunks) {
                level.setChunkForced(pos.x, pos.z, false);
            }
        }
        recentChunks.remove(trainId);
        lastKnownPositions.remove(trainId);
    }

    public void releaseAll(ServerLevel level) {
        for (var entry : trainChunks.entrySet()) {
            for (ChunkPos pos : entry.getValue()) {
                level.setChunkForced(pos.x, pos.z, false);
            }
        }
        trainChunks.clear();
        recentChunks.clear();
        lastKnownPositions.clear();
    }

    public int getLoadedChunkCount() {
        return trainChunks.values().stream().mapToInt(Set::size).sum();
    }

    public int getLoadedChunkCount(UUID trainId) {
        Set<ChunkPos> chunks = trainChunks.get(trainId);
        return chunks != null ? chunks.size() : 0;
    }
}
