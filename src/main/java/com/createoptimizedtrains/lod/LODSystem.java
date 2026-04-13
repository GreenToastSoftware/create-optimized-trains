package com.createoptimizedtrains.lod;

import com.createoptimizedtrains.config.ModConfig;
import com.createoptimizedtrains.monitor.PerformanceMonitor;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LODSystem {

    private final Map<UUID, LODLevel> trainLODLevels = new ConcurrentHashMap<>();
    private final PerformanceMonitor performanceMonitor;

    private volatile double fullDistanceSq;
    private volatile double mediumDistanceSq;
    private volatile double lowDistanceSq;
    private volatile double ghostDistanceSq;

    // Cache de posições de jogadores — refrescado uma vez por ciclo de LOD
    private volatile double[][] cachedPlayerPositions = new double[0][];

    // Histerese: evitar flip-flop de LOD entre frames adjacentes
    private static final double HYSTERESIS_FACTOR = 0.9;
    private final Map<UUID, Long> lodChangeTimestamps = new ConcurrentHashMap<>();
    private static final long MIN_LOD_CHANGE_INTERVAL_MS = 500; // 500ms mínimo entre mudanças

    // Tracking de comboios na zona de buffer (a aproximar-se do jogador)
    // Estes comboios devem ter LOD FULL forçado para que entidades sejam criadas e
    // posicionadas ANTES de o jogador as poder ver.
    private final Set<UUID> bufferZoneTrains = ConcurrentHashMap.newKeySet();

    public LODSystem(PerformanceMonitor performanceMonitor) {
        this.performanceMonitor = performanceMonitor;
        recalculateDistances();
    }

    public void recalculateDistances() {
        double fullDist = ModConfig.LOD_FULL_DISTANCE.get();
        double mediumDist = ModConfig.LOD_MEDIUM_DISTANCE.get();
        double lowDist = ModConfig.LOD_LOW_DISTANCE.get();
        double ghostDist = ModConfig.GHOST_DISTANCE.get();

        if (performanceMonitor != null && ModConfig.PERFORMANCE_MONITOR_ENABLED.get()) {
            double tpsFactor = performanceMonitor.getPerformanceFactor();
            fullDist *= tpsFactor;
            mediumDist *= tpsFactor;
            lowDist *= tpsFactor;
            ghostDist *= tpsFactor;
        }

        this.fullDistanceSq = fullDist * fullDist;
        this.mediumDistanceSq = mediumDist * mediumDist;
        this.lowDistanceSq = lowDist * lowDist;
        this.ghostDistanceSq = ghostDist * ghostDist;
    }

    /**
     * Pre-cachear posições de jogadores para evitar acessos repetidos durante o cálculo
     * de LOD de múltiplos comboios. Chamar uma vez antes de calcular LOD de todos os comboios.
     */
    public void cachePlayerPositions(List<ServerPlayer> players) {
        double[][] positions = new double[players.size()][3];
        for (int i = 0; i < players.size(); i++) {
            Vec3 pos = players.get(i).position();
            positions[i][0] = pos.x;
            positions[i][1] = pos.y;
            positions[i][2] = pos.z;
        }
        this.cachedPlayerPositions = positions;
    }

    /**
     * Calcula o LOD para um comboio. Thread-safe — usa apenas dados imutáveis/voláteis.
     * Pode ser chamado de threads secundárias com segurança.
     */
    public LODLevel calculateLODForTrain(Train train, Iterable<ServerPlayer> players) {
        double minDistanceSq = Double.MAX_VALUE;
        double[][] playerPos = this.cachedPlayerPositions;

        // Usar posições cacheadas se disponíveis (mais rápido, sem lock de entidade)
        if (playerPos.length > 0) {
            minDistanceSq = calculateMinDistanceWithCache(train, playerPos);
        } else {
            // Fallback: usar iterador de jogadores diretamente
            for (var carriage : train.carriages) {
                CarriageContraptionEntity entity = carriage.anyAvailableEntity();
                if (entity == null) continue;
                Vec3 carriagePos = entity.position();

                for (ServerPlayer player : players) {
                    double distSq = player.position().distanceToSqr(carriagePos);
                    if (distSq < minDistanceSq) {
                        minDistanceSq = distSq;
                    }
                }
            }
        }

        if (minDistanceSq == Double.MAX_VALUE) {
            return LODLevel.FULL;
        }

        // Comboios na zona de buffer: forçar LOD FULL para que entidades sejam
        // criadas e renderizadas instantaneamente quando entram no view distance
        if (bufferZoneTrains.contains(train.id)) {
            return LODLevel.FULL;
        }

        LODLevel rawLevel = classifyDistance(minDistanceSq);

        // Aplicar histerese para evitar flip-flop
        return applyHysteresis(train.id, rawLevel, minDistanceSq);
    }

    /**
     * Cálculo de distância mínima usando posições cacheadas.
     * Sem lock de entidades — usa apenas doubles.
     */
    private double calculateMinDistanceWithCache(Train train, double[][] playerPos) {
        double minDistSq = Double.MAX_VALUE;

        for (var carriage : train.carriages) {
            CarriageContraptionEntity entity = carriage.anyAvailableEntity();
            if (entity == null) continue;

            double cx = entity.getX();
            double cy = entity.getY();
            double cz = entity.getZ();

            for (double[] pp : playerPos) {
                double dx = pp[0] - cx;
                double dy = pp[1] - cy;
                double dz = pp[2] - cz;
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq < minDistSq) {
                    minDistSq = distSq;
                }
            }
        }

        return minDistSq;
    }

    /**
     * Histerese: se o LOD quer subir de tier (mais longe), exigir que
     * esteja além da distância * HYSTERESIS_FACTOR. Se quer descer (mais perto),
     * aplicar sem delay. Isto evita jittering.
     */
    private LODLevel applyHysteresis(UUID trainId, LODLevel rawLevel, double distanceSq) {
        LODLevel current = trainLODLevels.getOrDefault(trainId, LODLevel.FULL);

        // Se não mudou, retornar como está
        if (rawLevel == current) return rawLevel;

        // Se quer melhorar (jogador a aproximar-se), aplicar imediatamente
        if (rawLevel.getTier() < current.getTier()) {
            return rawLevel;
        }

        // Se quer degradar (jogador a afastar-se), aplicar histerese temporal
        long now = System.currentTimeMillis();
        Long lastChange = lodChangeTimestamps.get(trainId);
        if (lastChange != null && (now - lastChange) < MIN_LOD_CHANGE_INTERVAL_MS) {
            return current; // Manter LOD atual por mais tempo
        }

        lodChangeTimestamps.put(trainId, now);
        return rawLevel;
    }

    private LODLevel classifyDistance(double distanceSq) {
        if (distanceSq <= fullDistanceSq) {
            return LODLevel.FULL;
        } else if (distanceSq <= mediumDistanceSq) {
            return LODLevel.MEDIUM;
        } else if (distanceSq <= ghostDistanceSq) {
            return LODLevel.LOW;
        } else {
            return LODLevel.GHOST;
        }
    }

    public void updateTrainLOD(UUID trainId, LODLevel level) {
        LODLevel previous = trainLODLevels.put(trainId, level);
        if (previous != level && previous != null) {
            onLODChanged(trainId, previous, level);
        }
    }

    /**
     * Atualização em batch — aplicar múltiplos resultados de LOD de uma vez.
     * Para uso após cálculo paralelo.
     */
    public void batchUpdateLOD(Map<UUID, LODLevel> newLevels) {
        for (var entry : newLevels.entrySet()) {
            updateTrainLOD(entry.getKey(), entry.getValue());
        }
    }

    private void onLODChanged(UUID trainId, LODLevel oldLevel, LODLevel newLevel) {
        // Gatilhos tratados pelo TrainEventHandler
    }

    public LODLevel getTrainLOD(UUID trainId) {
        return trainLODLevels.getOrDefault(trainId, LODLevel.FULL);
    }

    /**
     * Marcar um comboio como estando na zona de buffer (a aproximar-se do jogador).
     * Comboios nesta zona têm LOD forçado a FULL para que entidades sejam
     * criadas e posicionadas antes de entrarem no view distance do jogador.
     */
    public void markInBufferZone(UUID trainId) {
        bufferZoneTrains.add(trainId);
    }

    /**
     * Remover comboio da zona de buffer (já está no view distance normal).
     */
    public void clearBufferZone(UUID trainId) {
        bufferZoneTrains.remove(trainId);
    }

    public void removeTrain(UUID trainId) {
        trainLODLevels.remove(trainId);
        lodChangeTimestamps.remove(trainId);
        bufferZoneTrains.remove(trainId);
    }

    public Map<UUID, LODLevel> getAllLODLevels() {
        return trainLODLevels;
    }

    private Vec3 getTrainPosition(Train train) {
        if (train.carriages.isEmpty()) {
            return null;
        }
        int midIndex = train.carriages.size() / 2;
        var midCarriage = train.carriages.get(midIndex);
        CarriageContraptionEntity entity = midCarriage.anyAvailableEntity();
        if (entity != null) {
            return entity.position();
        }
        for (var carriage : train.carriages) {
            entity = carriage.anyAvailableEntity();
            if (entity != null) {
                return entity.position();
            }
        }
        return null;
    }
}
