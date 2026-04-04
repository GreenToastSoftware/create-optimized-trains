package com.createoptimizedtrains.chunks;

import com.createoptimizedtrains.CreateOptimizedTrains;
import com.createoptimizedtrains.config.ModConfig;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ChunkLoadManager {

    // Chunks atualmente force-loaded por nós, por comboio
    private final Map<UUID, Set<ChunkPos>> trainChunks = new ConcurrentHashMap<>();
    // Histórico de chunks recentes por comboio (para evitar chunk thrashing)
    private final Map<UUID, Deque<ChunkPos>> recentChunks = new ConcurrentHashMap<>();

    private static final int THRASH_HISTORY_SIZE = 8;

    /**
     * Atualizar chunks carregados para um comboio.
     * - Pré-carregar chunks à frente
     * - Manter chunks essenciais
     * - Descarregar chunks atrás mais cedo
     */
    public void updateTrainChunks(Train train, ServerLevel level) {
        if (!ModConfig.SMART_CHUNK_LOADING.get()) {
            return;
        }

        UUID trainId = train.id;
        Set<ChunkPos> needed = calculateNeededChunks(train);
        Set<ChunkPos> current = trainChunks.getOrDefault(trainId, Collections.emptySet());

        // Chunks a descarregar (estavam carregados, já não são necessários)
        Set<ChunkPos> toUnload = new HashSet<>(current);
        toUnload.removeAll(needed);

        // Chunks a carregar (são necessários, não estavam carregados)
        Set<ChunkPos> toLoad = new HashSet<>(needed);
        toLoad.removeAll(current);

        // Verificar anti-thrashing antes de descarregar
        Deque<ChunkPos> recent = recentChunks.computeIfAbsent(trainId, k -> new ArrayDeque<>());
        toUnload.removeIf(chunk -> {
            // Não descarregar se foi carregado recentemente (anti-thrashing)
            return recent.contains(chunk);
        });

        // Aplicar mudanças
        for (ChunkPos pos : toUnload) {
            level.setChunkForced(pos.x, pos.z, false);
        }

        for (ChunkPos pos : toLoad) {
            level.setChunkForced(pos.x, pos.z, true);
            // Adicionar ao histórico recente
            recent.addLast(pos);
            if (recent.size() > THRASH_HISTORY_SIZE) {
                recent.removeFirst();
            }
        }

        trainChunks.put(trainId, needed);
    }

    private Set<ChunkPos> calculateNeededChunks(Train train) {
        Set<ChunkPos> chunks = new HashSet<>();

        if (train.carriages.isEmpty()) {
            return chunks;
        }

        int lookahead = ModConfig.CHUNK_LOOKAHEAD.get();
        int trailKeep = ModConfig.CHUNK_TRAIL_KEEP.get();

        // Chunk atual de cada carruagem (apenas primeira e última para comboios longos)
        var firstCarriage = train.carriages.get(0);
        var lastCarriage = train.carriages.get(train.carriages.size() - 1);

        addCarriageChunks(firstCarriage, chunks);
        if (train.carriages.size() > 1) {
            addCarriageChunks(lastCarriage, chunks);
        }

        // Pré-carregar chunks à frente baseado na velocidade e direção (Create 6.x API)
        if (train.speed != 0 && !train.carriages.isEmpty()) {
            var entity = firstCarriage.anyAvailableEntity();
            if (entity != null) {
                int currentChunkX = entity.blockPosition().getX() >> 4;
                int currentChunkZ = entity.blockPosition().getZ() >> 4;

                // Determinar direção aproximada do movimento
                // Pré-carregar chunks numa pequena área à frente
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int ahead = 0; ahead <= lookahead; ahead++) {
                            chunks.add(new ChunkPos(currentChunkX + dx + ahead, currentChunkZ + dz));
                            chunks.add(new ChunkPos(currentChunkX + dx - ahead, currentChunkZ + dz));
                            chunks.add(new ChunkPos(currentChunkX + dx, currentChunkZ + dz + ahead));
                            chunks.add(new ChunkPos(currentChunkX + dx, currentChunkZ + dz - ahead));
                        }
                    }
                }
            }
        }

        return chunks;
    }

    private void addCarriageChunks(com.simibubi.create.content.trains.entity.Carriage carriage, Set<ChunkPos> chunks) {
        // Create 6.x: usar anyAvailableEntity() para posição
        var entity = carriage.anyAvailableEntity();
        if (entity != null) {
            chunks.add(new ChunkPos(entity.blockPosition().getX() >> 4, entity.blockPosition().getZ() >> 4));
        }
    }

    /**
     * Limpar todos os chunks force-loaded por um comboio.
     */
    public void releaseTrainChunks(UUID trainId, ServerLevel level) {
        Set<ChunkPos> chunks = trainChunks.remove(trainId);
        if (chunks != null) {
            for (ChunkPos pos : chunks) {
                level.setChunkForced(pos.x, pos.z, false);
            }
        }
        recentChunks.remove(trainId);
    }

    /**
     * Limpar todos os chunks geridos por este sistema.
     */
    public void releaseAll(ServerLevel level) {
        for (var entry : trainChunks.entrySet()) {
            for (ChunkPos pos : entry.getValue()) {
                level.setChunkForced(pos.x, pos.z, false);
            }
        }
        trainChunks.clear();
        recentChunks.clear();
    }

    public int getLoadedChunkCount() {
        return trainChunks.values().stream().mapToInt(Set::size).sum();
    }

    public int getLoadedChunkCount(UUID trainId) {
        Set<ChunkPos> chunks = trainChunks.get(trainId);
        return chunks != null ? chunks.size() : 0;
    }
}
