package com.createoptimizedtrains.monitor;

import com.createoptimizedtrains.CreateOptimizedTrains;
import com.createoptimizedtrains.config.ModConfig;

public class PerformanceMonitor {

    private static final int SAMPLE_WINDOW = 20; // 20 ticks = 1 segundo

    private final long[] tickTimes = new long[SAMPLE_WINDOW];
    private int tickIndex = 0;
    private long lastTickTime = 0;

    private double currentTPS = 20.0;
    private double averageMSPT = 50.0; // milliseconds per tick
    private PerformanceState state = PerformanceState.NORMAL;

    public enum PerformanceState {
        /** TPS normal (>= threshold alto) - fidelidade completa */
        NORMAL,
        /** TPS moderado (entre thresholds) - otimizações leves */
        DEGRADED,
        /** TPS baixo (< threshold baixo) - otimizações agressivas */
        CRITICAL
    }

    /**
     * Chamado a cada tick do servidor para registar tempo.
     */
    public void onServerTick() {
        if (!ModConfig.PERFORMANCE_MONITOR_ENABLED.get()) {
            state = PerformanceState.NORMAL;
            return;
        }

        long now = System.nanoTime();
        if (lastTickTime != 0) {
            tickTimes[tickIndex] = now - lastTickTime;
            tickIndex = (tickIndex + 1) % SAMPLE_WINDOW;
        }
        lastTickTime = now;

        // Recalcular TPS a cada janela completa
        if (tickIndex == 0) {
            recalculate();
        }
    }

    private void recalculate() {
        long totalNanos = 0;
        int validSamples = 0;

        for (long tickTime : tickTimes) {
            if (tickTime > 0) {
                totalNanos += tickTime;
                validSamples++;
            }
        }

        if (validSamples == 0) return;

        double avgNanosPerTick = (double) totalNanos / validSamples;
        this.averageMSPT = avgNanosPerTick / 1_000_000.0;
        this.currentTPS = Math.min(20.0, 1_000_000_000.0 / avgNanosPerTick);

        // Atualizar estado
        int lowThreshold = ModConfig.TPS_LOW_THRESHOLD.get();
        int highThreshold = ModConfig.TPS_HIGH_THRESHOLD.get();

        PerformanceState newState;
        if (currentTPS >= highThreshold) {
            newState = PerformanceState.NORMAL;
        } else if (currentTPS >= lowThreshold) {
            newState = PerformanceState.DEGRADED;
        } else {
            newState = PerformanceState.CRITICAL;
        }

        if (newState != state) {
            CreateOptimizedTrains.LOGGER.info(
                    "Performance state: {} -> {} (TPS: {}, MSPT: {})",
                    state, newState,
                    String.format("%.1f", currentTPS),
                    String.format("%.1f", averageMSPT)
            );
            state = newState;
        }
    }

    /**
     * Obter fator de performance (0.5 a 1.0).
     * Usado para escalar distâncias de LOD e outros parâmetros.
     * 1.0 = performance normal, 0.5 = performance crítica (reduzir tudo pela metade).
     */
    public double getPerformanceFactor() {
        return switch (state) {
            case NORMAL -> 1.0;
            case DEGRADED -> 0.75;
            case CRITICAL -> 0.5;
        };
    }

    public double getCurrentTPS() {
        return currentTPS;
    }

    public double getAverageMSPT() {
        return averageMSPT;
    }

    public PerformanceState getState() {
        return state;
    }

    /**
     * Verificar se devemos usar otimizações agressivas.
     */
    public boolean shouldOptimizeAggressively() {
        return state == PerformanceState.CRITICAL;
    }

    /**
     * Verificar se estamos em modo normal (sem otimizações extra necessárias).
     */
    public boolean isPerformanceNormal() {
        return state == PerformanceState.NORMAL;
    }

    /**
     * Informação formatada para debug/logs.
     */
    public String getStatusString() {
        return String.format("TPS: %.1f | MSPT: %.1fms | Estado: %s | Fator: %.2f",
                currentTPS, averageMSPT, state, getPerformanceFactor());
    }
}
