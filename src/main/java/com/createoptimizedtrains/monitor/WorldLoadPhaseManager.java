package com.createoptimizedtrains.monitor;

import com.createoptimizedtrains.CreateOptimizedTrains;
import com.createoptimizedtrains.config.ModConfig;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Gestor de fases de carregamento do mundo, para evitar que tudo (comboios distantes,
 * Distant Horizons, route preload) seja carregado em simultâneo no arranque — causa
 * de congelamentos e picos de RAM.
 *
 * Em vez de atrasos fixos por tempo, avança de fase com base em sinais reais do
 * servidor (TPS, % de heap usado). Isto prolonga naturalmente o ecrã de loading do
 * jogador (porque o chunk do próprio comboio dele continua prioritário e fica pronto
 * mais depressa, sem competir com o resto), e só depois liberta o resto do mapa.
 *
 * Fases:
 *  CORE    — só o(s) comboio(s) ocupado(s) pelo jogador têm chunks carregados.
 *            Distant Horizons e route preload ficam suspensos.
 *  RAMPING — comboios restantes carregados em pequenos lotes rotativos.
 *  FULL    — comportamento normal, todos os comboios tratados.
 *
 * Cada fase tem um tecto máximo de ticks (segurança): se os sinais nunca ficarem
 * saudáveis (servidor genuinamente fraco), avança-se na mesma para não bloquear
 * o mapa indefinidamente.
 */
public class WorldLoadPhaseManager {

    public enum Phase {
        CORE,
        RAMPING,
        FULL
    }

    private static volatile Phase phase = Phase.CORE;
    private static long tickCounter = 0;
    private static long phaseStartTick = 0;
    private static long healthyStreak = 0;

    private WorldLoadPhaseManager() {
    }

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        // Reiniciar a máquina de fases a cada novo mundo/servidor (singleplayer reentra
        // no mesmo processo, por isso não basta confiar em campos estáticos "de arranque").
        phase = Phase.CORE;
        tickCounter = 0;
        phaseStartTick = 0;
        healthyStreak = 0;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!ModConfig.STAGED_LOADING_ENABLED.get()) {
            phase = Phase.FULL;
            return;
        }
        if (phase == Phase.FULL) return;

        tickCounter++;

        boolean healthy = isHealthy();
        healthyStreak = healthy ? healthyStreak + 1 : 0;

        long ticksInPhase = tickCounter - phaseStartTick;
        boolean stableEnough = healthyStreak >= ModConfig.STAGED_LOADING_STABLE_TICKS.get();

        if (phase == Phase.CORE) {
            long cap = ModConfig.STAGED_LOADING_MAX_CORE_TICKS.get();
            if (stableEnough || ticksInPhase >= cap) {
                advanceTo(Phase.RAMPING);
            }
        } else if (phase == Phase.RAMPING) {
            long cap = ModConfig.STAGED_LOADING_MAX_RAMPING_TICKS.get();
            if (stableEnough || ticksInPhase >= cap) {
                advanceTo(Phase.FULL);
            }
        }
    }

    private static boolean isHealthy() {
        CreateOptimizedTrains mod = CreateOptimizedTrains.getInstance();
        PerformanceMonitor monitor = mod != null ? mod.getPerformanceMonitor() : null;
        double tps = monitor != null ? monitor.getCurrentTPS() : 20.0;

        Runtime rt = Runtime.getRuntime();
        double memUsedPercent = 100.0 * (rt.totalMemory() - rt.freeMemory()) / rt.maxMemory();

        return tps >= ModConfig.STAGED_LOADING_TPS_THRESHOLD.get()
            && memUsedPercent <= ModConfig.STAGED_LOADING_MEMORY_THRESHOLD.get();
    }

    private static void advanceTo(Phase next) {
        CreateOptimizedTrains.LOGGER.info("COT: fase de carregamento do mundo: {} -> {}", phase, next);
        phase = next;
        phaseStartTick = tickCounter;
        healthyStreak = 0;
    }

    public static Phase getPhase() {
        return phase;
    }

    public static boolean isCore() {
        return phase == Phase.CORE;
    }

    public static boolean isRamping() {
        return phase == Phase.RAMPING;
    }

    public static boolean isFull() {
        return phase == Phase.FULL;
    }

    /**
     * Progresso estimado (0..1) da fase actual, para mostrar numa barra de progresso.
     * É o máximo entre o progresso por tempo (tecto de segurança) e por estabilidade
     * (streak de ticks saudáveis), já que o avanço ocorre quando qualquer um completa.
     */
    public static float getPhaseProgress() {
        if (phase == Phase.FULL) return 1.0f;

        long cap = phase == Phase.CORE
                ? ModConfig.STAGED_LOADING_MAX_CORE_TICKS.get()
                : ModConfig.STAGED_LOADING_MAX_RAMPING_TICKS.get();
        long required = ModConfig.STAGED_LOADING_STABLE_TICKS.get();

        long ticksInPhase = tickCounter - phaseStartTick;
        float byTime = cap > 0 ? (float) ticksInPhase / cap : 0f;
        float byStability = required > 0 ? (float) healthyStreak / required : 0f;

        return Math.max(0f, Math.min(1f, Math.max(byTime, byStability)));
    }
}
