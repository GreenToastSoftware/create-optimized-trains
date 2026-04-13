package com.createoptimizedtrains.monitor;

import com.createoptimizedtrains.CreateOptimizedTrains;
import com.createoptimizedtrains.config.ModConfig;

public class PerformanceMonitor {

    private static final int SAMPLE_WINDOW = 20; // 20 ticks = 1 segundo
    private static final int FAST_SAMPLE_WINDOW = 5; // 5 ticks para detecção rápida de picos

    private final long[] tickTimes = new long[SAMPLE_WINDOW];
    private int tickIndex = 0;
    private long lastTickTime = 0;

    private double currentTPS = 20.0;
    private double averageMSPT = 50.0;
    private double peakMSPT = 50.0; // Pico recente (janela curta)
    private PerformanceState state = PerformanceState.NORMAL;

    // Fator gradual — transição suave em vez de saltos discretos
    private double smoothPerformanceFactor = 1.0;
    private static final double SMOOTH_FACTOR_UP = 0.02;   // Subir devagar (relaxar)
    private static final double SMOOTH_FACTOR_DOWN = 0.08;  // Descer rápido (reagir)

    public enum PerformanceState {
        NORMAL,
        DEGRADED,
        CRITICAL
    }

    public void onServerTick() {
        if (!ModConfig.PERFORMANCE_MONITOR_ENABLED.get()) {
            state = PerformanceState.NORMAL;
            smoothPerformanceFactor = 1.0;
            return;
        }

        long now = System.nanoTime();
        if (lastTickTime != 0) {
            tickTimes[tickIndex] = now - lastTickTime;
            tickIndex = (tickIndex + 1) % SAMPLE_WINDOW;

            // Janela rápida: recalcular a cada 5 ticks para detecção de picos
            if (tickIndex % FAST_SAMPLE_WINDOW == 0) {
                recalculateFast();
            }
        }
        lastTickTime = now;

        // Recalcular completo a cada janela
        if (tickIndex == 0) {
            recalculate();
        }

        // Atualizar fator suave a cada tick
        updateSmoothFactor();
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

        updateState();
    }

    /**
     * Janela rápida: 5 ticks para detetar picos de lag mais cedo.
     */
    private void recalculateFast() {
        long maxNanos = 0;
        int start = Math.max(0, tickIndex - FAST_SAMPLE_WINDOW);
        for (int i = start; i < tickIndex; i++) {
            int idx = ((i % SAMPLE_WINDOW) + SAMPLE_WINDOW) % SAMPLE_WINDOW;
            if (tickTimes[idx] > maxNanos) {
                maxNanos = tickTimes[idx];
            }
        }
        if (maxNanos > 0) {
            this.peakMSPT = maxNanos / 1_000_000.0;
        }

        // Se há picos acima de 100ms, reagir imediatamente sem esperar janela completa
        if (peakMSPT > 100.0 && state != PerformanceState.CRITICAL) {
            state = PerformanceState.CRITICAL;
            CreateOptimizedTrains.LOGGER.info(
                    "Performance spike detetado: {}ms — modo CRITICAL ativado",
                    String.format("%.1f", peakMSPT)
            );
        }
    }

    private void updateState() {
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
                    "Performance state: {} -> {} (TPS: {}, MSPT: {}, Peak: {})",
                    state, newState,
                    String.format("%.1f", currentTPS),
                    String.format("%.1f", averageMSPT),
                    String.format("%.1f", peakMSPT)
            );
            state = newState;
        }
    }

    /**
     * Atualizar fator suave — transição gradual para evitar jittering visual.
     * Desce rápido ao detetar problemas, sobe devagar ao recuperar.
     */
    private void updateSmoothFactor() {
        double targetFactor = switch (state) {
            case NORMAL -> 1.0;
            case DEGRADED -> 0.75;
            case CRITICAL -> 0.5;
        };

        if (targetFactor < smoothPerformanceFactor) {
            // Descer rápido (reagir a problemas)
            smoothPerformanceFactor = Math.max(targetFactor,
                    smoothPerformanceFactor - SMOOTH_FACTOR_DOWN);
        } else {
            // Subir devagar (relaxar com cuidado)
            smoothPerformanceFactor = Math.min(targetFactor,
                    smoothPerformanceFactor + SMOOTH_FACTOR_UP);
        }
    }

    /**
     * Fator gradual suave (0.5 a 1.0).
     * Transição suave em vez de saltos discretos.
     */
    public double getPerformanceFactor() {
        return smoothPerformanceFactor;
    }

    public double getCurrentTPS() {
        return currentTPS;
    }

    public double getAverageMSPT() {
        return averageMSPT;
    }

    public double getPeakMSPT() {
        return peakMSPT;
    }

    public PerformanceState getState() {
        return state;
    }

    public boolean shouldOptimizeAggressively() {
        return state == PerformanceState.CRITICAL;
    }

    public boolean isPerformanceNormal() {
        return state == PerformanceState.NORMAL;
    }

    public String getStatusString() {
        return String.format("TPS: %.1f | MSPT: %.1fms | Peak: %.1fms | Estado: %s | Fator: %.2f",
                currentTPS, averageMSPT, peakMSPT, state, smoothPerformanceFactor);
    }
}
