package com.createoptimizedtrains.throttling;

import com.createoptimizedtrains.config.ModConfig;
import com.createoptimizedtrains.lod.LODLevel;
import com.createoptimizedtrains.lod.LODSystem;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TickThrottler {

    private final LODSystem lodSystem;
    private final Map<UUID, Long> lastTickMap = new ConcurrentHashMap<>();

    public TickThrottler(LODSystem lodSystem) {
        this.lodSystem = lodSystem;
    }

    /**
     * Verifica se um comboio deve ser atualizado neste tick.
     * Retorna true se deve processar, false se deve saltar.
     */
    public boolean shouldTick(UUID trainId, long currentTick) {
        if (!ModConfig.THROTTLING_ENABLED.get()) {
            return true;
        }

        LODLevel lod = lodSystem.getTrainLOD(trainId);
        int interval = getTickInterval(lod);

        Long lastTick = lastTickMap.get(trainId);
        if (lastTick == null || (currentTick - lastTick) >= interval) {
            lastTickMap.put(trainId, currentTick);
            return true;
        }

        return false;
    }

    /**
     * Verifica se deve fazer check de colisão neste tick.
     * Colisões são verificadas com menos frequência que updates normais.
     */
    public boolean shouldCheckCollision(UUID trainId, long currentTick) {
        if (!ModConfig.THROTTLING_ENABLED.get()) {
            return true;
        }

        LODLevel lod = lodSystem.getTrainLOD(trainId);
        if (!lod.shouldCheckCollisions()) {
            return false;
        }

        // Colisões checadas a cada 2x o intervalo normal
        int interval = getTickInterval(lod) * 2;
        Long lastTick = lastTickMap.get(trainId);
        return lastTick == null || (currentTick - lastTick) % interval == 0;
    }

    /**
     * Verifica se deve sincronizar com o servidor neste tick.
     */
    public boolean shouldSync(UUID trainId, long currentTick) {
        LODLevel lod = lodSystem.getTrainLOD(trainId);
        if (lod == LODLevel.FULL) {
            return true;
        }

        int syncInterval = ModConfig.DISTANT_SYNC_INTERVAL.get();
        Long lastTick = lastTickMap.get(trainId);
        return lastTick == null || (currentTick - lastTick) % syncInterval == 0;
    }

    private int getTickInterval(LODLevel lod) {
        return switch (lod) {
            case FULL -> 1; // Cada tick
            case MEDIUM -> ModConfig.THROTTLE_MEDIUM_INTERVAL.get();
            case LOW -> ModConfig.THROTTLE_LOW_INTERVAL.get();
            case GHOST -> ModConfig.THROTTLE_GHOST_INTERVAL.get();
        };
    }

    public void removeTrain(UUID trainId) {
        lastTickMap.remove(trainId);
    }

    /**
     * Obter a fração de updates que um comboio recebe (para estatísticas).
     * 1.0 = cada tick, 0.05 = cada 20 ticks
     */
    public double getUpdateRate(UUID trainId) {
        LODLevel lod = lodSystem.getTrainLOD(trainId);
        return 1.0 / getTickInterval(lod);
    }
}
