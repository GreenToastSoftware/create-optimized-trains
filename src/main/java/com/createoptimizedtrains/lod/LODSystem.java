package com.createoptimizedtrains.lod;

import com.createoptimizedtrains.config.ModConfig;
import com.createoptimizedtrains.monitor.PerformanceMonitor;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LODSystem {

    private final Map<UUID, LODLevel> trainLODLevels = new ConcurrentHashMap<>();
    private final PerformanceMonitor performanceMonitor;

    private double fullDistanceSq;
    private double mediumDistanceSq;
    private double lowDistanceSq;

    public LODSystem(PerformanceMonitor performanceMonitor) {
        this.performanceMonitor = performanceMonitor;
        recalculateDistances();
    }

    public void recalculateDistances() {
        double fullDist = ModConfig.LOD_FULL_DISTANCE.get();
        double mediumDist = ModConfig.LOD_MEDIUM_DISTANCE.get();
        double lowDist = ModConfig.LOD_LOW_DISTANCE.get();

        // Ajustar distâncias baseado no TPS se o monitor estiver ativo
        if (performanceMonitor != null && ModConfig.PERFORMANCE_MONITOR_ENABLED.get()) {
            double tpsFactor = performanceMonitor.getPerformanceFactor();
            fullDist *= tpsFactor;
            mediumDist *= tpsFactor;
            lowDist *= tpsFactor;
        }

        this.fullDistanceSq = fullDist * fullDist;
        this.mediumDistanceSq = mediumDist * mediumDist;
        this.lowDistanceSq = lowDist * lowDist;
    }

    public LODLevel calculateLODForTrain(Train train, Iterable<ServerPlayer> players) {
        double minDistanceSq = Double.MAX_VALUE;

        // Calcular distância usando a carruagem MAIS PRÓXIMA de qualquer jogador,
        // não apenas a primeira. Isto evita que comboios longos fiquem com LOD
        // errado quando o jogador está perto de uma carruagem traseira.
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

        // Se nenhuma carruagem tem entidade disponível, manter FULL por segurança
        if (minDistanceSq == Double.MAX_VALUE) {
            return LODLevel.FULL;
        }

        return classifyDistance(minDistanceSq);
    }

    private LODLevel classifyDistance(double distanceSq) {
        if (distanceSq <= fullDistanceSq) {
            return LODLevel.FULL;
        } else if (distanceSq <= mediumDistanceSq) {
            return LODLevel.MEDIUM;
        } else if (distanceSq <= lowDistanceSq) {
            return LODLevel.LOW;
        } else {
            return LODLevel.GHOST;
        }
    }

    public void updateTrainLOD(UUID trainId, LODLevel level) {
        LODLevel previous = trainLODLevels.put(trainId, level);
        // Retornar se houve mudança para processamento adicional
        if (previous != level && previous != null) {
            onLODChanged(trainId, previous, level);
        }
    }

    private void onLODChanged(UUID trainId, LODLevel oldLevel, LODLevel newLevel) {
        // Gatilhos para outros sistemas quando o LOD muda
        // - Expandir/colapsar grupos de carruagens
        // - Ajustar frequência de ticks
        // - Ativar/desativar proxy entities
        // Isto é tratado pelo TrainEventHandler
    }

    public LODLevel getTrainLOD(UUID trainId) {
        return trainLODLevels.getOrDefault(trainId, LODLevel.FULL);
    }

    public void removeTrain(UUID trainId) {
        trainLODLevels.remove(trainId);
    }

    public Map<UUID, LODLevel> getAllLODLevels() {
        return trainLODLevels;
    }

    /**
     * Obter posição da carruagem mais central do comboio.
     * Usado como fallback — o cálculo principal de LOD já itera todas as carruagens.
     */
    private Vec3 getTrainPosition(Train train) {
        if (train.carriages.isEmpty()) {
            return null;
        }
        // Usar carruagem do meio para melhor representação
        int midIndex = train.carriages.size() / 2;
        var midCarriage = train.carriages.get(midIndex);
        CarriageContraptionEntity entity = midCarriage.anyAvailableEntity();
        if (entity != null) {
            return entity.position();
        }
        // Fallback: tentar qualquer carruagem
        for (var carriage : train.carriages) {
            entity = carriage.anyAvailableEntity();
            if (entity != null) {
                return entity.position();
            }
        }
        return null;
    }
}
