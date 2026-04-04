package com.createoptimizedtrains.networking;

import com.createoptimizedtrains.config.ModConfig;
import com.createoptimizedtrains.lod.LODLevel;
import com.createoptimizedtrains.lod.LODSystem;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NetworkOptimizer {

    private final LODSystem lodSystem;

    // Cache do último estado enviado por comboio para delta compression
    private final Map<UUID, TrainNetState> lastSentState = new ConcurrentHashMap<>();
    // Contador de ticks desde o último envio por comboio
    private final Map<UUID, Integer> ticksSinceLastSync = new ConcurrentHashMap<>();

    public NetworkOptimizer(LODSystem lodSystem) {
        this.lodSystem = lodSystem;
    }

    /**
     * Verifica se uma atualização de rede deve ser enviada para este comboio.
     * Enviar apenas quando:
     * - Velocidade mudou significativamente
     * - Direção mudou
     * - Jogador está perto
     * - Intervalo mínimo atingido
     */
    public boolean shouldSendUpdate(UUID trainId, double currentSpeed, double currentX, double currentZ) {
        if (!ModConfig.NETWORK_OPTIMIZATION_ENABLED.get()) {
            return true;
        }

        LODLevel lod = lodSystem.getTrainLOD(trainId);

        // Comboios com LOD completo: sync normal
        if (lod == LODLevel.FULL) {
            return true;
        }

        // Verificar intervalo mínimo
        int ticks = ticksSinceLastSync.getOrDefault(trainId, 0) + 1;
        ticksSinceLastSync.put(trainId, ticks);

        int syncInterval = getSyncInterval(lod);
        if (ticks < syncInterval) {
            return false;
        }

        // Verificar se houve mudança significativa
        TrainNetState lastState = lastSentState.get(trainId);
        if (lastState != null) {
            boolean speedChanged = Math.abs(currentSpeed - lastState.speed) > 0.1;
            boolean posChanged = Math.abs(currentX - lastState.posX) > 1.0
                    || Math.abs(currentZ - lastState.posZ) > 1.0;

            if (!speedChanged && !posChanged) {
                return false;
            }
        }

        // Enviar e resetar
        lastSentState.put(trainId, new TrainNetState(currentSpeed, currentX, currentZ));
        ticksSinceLastSync.put(trainId, 0);
        return true;
    }

    /**
     * Obter intervalo de sincronização baseado no LOD.
     */
    private int getSyncInterval(LODLevel lod) {
        return switch (lod) {
            case FULL -> 1;
            case MEDIUM -> 5;
            case LOW -> ModConfig.DISTANT_SYNC_INTERVAL.get();
            case GHOST -> ModConfig.DISTANT_SYNC_INTERVAL.get() * 2;
        };
    }

    /**
     * Verificar se um pacote deve ser filtrado (não enviado ao cliente).
     */
    public boolean shouldFilterPacket(UUID trainId) {
        if (!ModConfig.NETWORK_OPTIMIZATION_ENABLED.get()) {
            return false;
        }

        LODLevel lod = lodSystem.getTrainLOD(trainId);
        int ticks = ticksSinceLastSync.getOrDefault(trainId, 0);
        int syncInterval = getSyncInterval(lod);

        return ticks < syncInterval;
    }

    public void removeTrain(UUID trainId) {
        lastSentState.remove(trainId);
        ticksSinceLastSync.remove(trainId);
    }

    /**
     * Obter a redução de tráfego (percentagem de pacotes que NÃO são enviados).
     */
    public double getTrafficReduction(UUID trainId) {
        LODLevel lod = lodSystem.getTrainLOD(trainId);
        int interval = getSyncInterval(lod);
        return 1.0 - (1.0 / interval);
    }

    private static class TrainNetState {
        final double speed;
        final double posX;
        final double posZ;

        TrainNetState(double speed, double posX, double posZ) {
            this.speed = speed;
            this.posX = posX;
            this.posZ = posZ;
        }
    }
}
