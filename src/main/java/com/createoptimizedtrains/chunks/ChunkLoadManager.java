package com.createoptimizedtrains.chunks;

import com.createoptimizedtrains.CreateOptimizedTrains;
import com.createoptimizedtrains.config.ModConfig;
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
    // 30 chunks forçados ~= 480MB de heap. Com 13GB de heap e 32 mods, 30 é seguro.
    private static final int MAX_GLOBAL_FORCED_CHUNKS = 30;

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

        // Chunks novas necessárias (não estavam forçadas)
        Set<ChunkPos> toLoad = new HashSet<>(needed);
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

        // Respeitar cap global: contar chunks de outros comboios + as que mantemos
        int otherTrainsCount = getLoadedChunkCount() - current.size();
        int globalAfterUnload = otherTrainsCount + actuallyForced.size();
        int allowedToLoad = Math.max(0, MAX_GLOBAL_FORCED_CHUNKS - globalAfterUnload);

        int loaded = 0;
        for (ChunkPos pos : toLoad) {
            if (loaded >= allowedToLoad) break;
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
        Set<ChunkPos> chunks = new HashSet<>();

        if (train.carriages.isEmpty()) {
            return chunks;
        }

        int lookahead = ModConfig.CHUNK_LOOKAHEAD.get();

        // Chunks atuais de TODAS as carruagens (não só a primeira/última)
        // Usa positionAnchor do DimensionalCarriageEntity como fallback
        // quando a carruagem não tem entidade (chunk descarregado)
        for (Carriage carriage : train.carriages) {
            addCarriageChunks(carriage, chunks, level);
        }

        // Pré-carregamento direcional baseado no movimento real do comboio
        if (train.speed != 0) {
            addDirectionalLookahead(train, chunks, lookahead, level);
        }

        return chunks;
    }

    /**
     * Pré-carregar chunks na direção de movimento do comboio.
     * Usa a diferença de posição entre ticks para determinar o vetor de movimento real.
     *
     * Force-load é limitado a 6 chunks à frente (era 12) porque entity-ticking de
     * muitos chunks sobrecarrega o server e atrasa o carregamento dos chunks visuais
     * que o jogador realmente precisa ver.
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

        // Lookahead adaptativo: limitado a 6 chunks (entity-ticking é caro)
        double speedBlocks = Math.abs(train.speed) * 20.0;
        int adaptiveLookahead = Math.max(lookahead, (int) Math.ceil(speedBlocks * 2.0 / 16.0) + 1);
        adaptiveLookahead = Math.min(adaptiveLookahead, 6); // Cap baixo para não sobrecarregar

        // Pré-carregar na direção de movimento
        // Só o centro para chunks distantes (>3), centro + lados para chunks próximos
        for (int ahead = 1; ahead <= adaptiveLookahead; ahead++) {
            double projX = currentX + dirX * ahead * 16;
            double projZ = currentZ + dirZ * ahead * 16;
            int projChunkX = (int) Math.floor(projX) >> 4;
            int projChunkZ = (int) Math.floor(projZ) >> 4;

            chunks.add(new ChunkPos(projChunkX, projChunkZ));

            // Só adicionar lados para os 3 chunks mais próximos (para curvas)
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
    }

    private void addCarriageChunks(Carriage carriage, Set<ChunkPos> chunks, ServerLevel level) {
        Vec3 pos = getCarriagePosition(carriage, level);
        if (pos != null) {
            int cx = (int) Math.floor(pos.x) >> 4;
            int cz = (int) Math.floor(pos.z) >> 4;
            chunks.add(new ChunkPos(cx, cz));
        }
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
                        if (globalCount < MAX_GLOBAL_FORCED_CHUNKS) {
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
