package com.createoptimizedtrains.rendering;

import com.createoptimizedtrains.config.ModConfig;
import com.createoptimizedtrains.lod.LODLevel;
import com.createoptimizedtrains.lod.LODSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@OnlyIn(Dist.CLIENT)
public class RenderOptimizer {

    private static LODSystem lodSystem;
    private static boolean initialized = false;

    // Cache de frame: evita chamar System.nanoTime() por entidade
    private static long currentFrameId = 0;
    private static long lastFrameNanos = 0;

    // Cache de LOD por frame: evita map lookups repetidos para o mesmo comboio no mesmo frame
    private static final Map<UUID, LODLevel> frameLODCache = new ConcurrentHashMap<>();
    private static long frameLODCacheFrameId = -1;

    // Contadores de skip para LOW LOD (por comboio em vez de global nanoTime)
    private static final Map<UUID, Integer> flywheelSkipCounters = new ConcurrentHashMap<>();
    private static final int LOW_LOD_UPDATE_EVERY_N_FRAMES = 3;

    // FPS tracking para adaptação do cliente
    private static double clientFPS = 60.0;
    private static long lastFPSSampleTime = 0;
    private static int framesSinceLastSample = 0;
    private static final int FPS_SAMPLE_FRAMES = 30;

    // === Render Transition System ===
    // Comboios aparecem IMEDIATAMENTE em modo físico completo (sem ghost/warmup).
    // O sistema de proximity buffer garante que chunks e entidades já estão carregados.
    // Desaparecimento é vanilla (sem fade-out server-side para evitar bugs de invisibilidade).
    private static final Map<UUID, Long> trainFirstSeenFrame = new ConcurrentHashMap<>();

    public static void init(LODSystem lod) {
        lodSystem = lod;
        initialized = true;
    }

    /**
     * Chamar no início de cada frame para atualizar caches.
     * Deve ser invocado uma vez por frame (ex: no mixin de LevelRenderer).
     */
    public static void onFrameStart() {
        currentFrameId++;
        lastFrameNanos = System.nanoTime();

        // Invalidar cache de LOD por frame
        if (frameLODCacheFrameId != currentFrameId) {
            frameLODCache.clear();
            frameLODCacheFrameId = currentFrameId;
        }

        // Calcular FPS do cliente
        framesSinceLastSample++;
        if (framesSinceLastSample >= FPS_SAMPLE_FRAMES) {
            long now = lastFrameNanos;
            if (lastFPSSampleTime > 0) {
                double elapsed = (now - lastFPSSampleTime) / 1_000_000_000.0;
                if (elapsed > 0) {
                    clientFPS = FPS_SAMPLE_FRAMES / elapsed;
                }
            }
            lastFPSSampleTime = now;
            framesSinceLastSample = 0;
        }
    }

    /**
     * Obter LOD com cache por frame — evita map lookup repetido.
     */
    private static LODLevel getCachedLOD(UUID trainId) {
        if (!initialized) return LODLevel.FULL;

        LODLevel cached = frameLODCache.get(trainId);
        if (cached != null) return cached;

        LODLevel lod = lodSystem.getTrainLOD(trainId);
        frameLODCache.put(trainId, lod);
        return lod;
    }

    public static boolean shouldRenderDetailed(UUID trainId) {
        if (!initialized || !ModConfig.RENDER_OPTIMIZATION_ENABLED.get()) {
            return true;
        }
        return getCachedLOD(trainId).shouldRenderDetailed();
    }

    public static boolean shouldAnimate(UUID trainId) {
        if (!initialized || !ModConfig.DISABLE_DISTANT_ANIMATIONS.get()) {
            return true;
        }
        LODLevel lod = getCachedLOD(trainId);
        return lod == LODLevel.FULL || lod == LODLevel.MEDIUM;
    }

    public static boolean shouldEmitParticles(UUID trainId) {
        if (!initialized || !ModConfig.DISABLE_DISTANT_PARTICLES.get()) {
            return true;
        }
        return getCachedLOD(trainId) == LODLevel.FULL;
    }

    public static boolean shouldRenderInterior(UUID trainId) {
        if (!initialized || !ModConfig.RENDER_OPTIMIZATION_ENABLED.get()) {
            return true;
        }
        return getCachedLOD(trainId) == LODLevel.FULL;
    }

    public static float getModelSimplification(UUID trainId) {
        if (!initialized || !ModConfig.RENDER_OPTIMIZATION_ENABLED.get()) {
            return 0.0f;
        }

        LODLevel lod = getCachedLOD(trainId);
        return switch (lod) {
            case FULL -> 0.0f;
            case MEDIUM -> 0.3f;
            case LOW -> 0.7f;
            case GHOST -> 1.0f;
        };
    }

    /**
     * GHOST trains podem ser skipados no render vanilla quando Flywheel está ativo.
     * Se FPS do cliente está abaixo de 30, skip mais agressivo.
     */
    public static boolean shouldSkipRender(UUID trainId) {
        if (!initialized || !ModConfig.RENDER_OPTIMIZATION_ENABLED.get()) {
            return false;
        }
        LODLevel lod = getCachedLOD(trainId);

        // Skip GHOST quando FPS baixo (cliente a sofrer)
        if (lod == LODLevel.GHOST && clientFPS < 40.0) {
            return true;
        }

        return false;
    }

    /**
     * Flywheel visual update skip — usa contadores por comboio em vez de nanoTime().
     * Mais eficiente e determinístico.
     */
    public static boolean shouldSkipFlywheelUpdate(UUID trainId) {
        if (!initialized || !ModConfig.RENDER_OPTIMIZATION_ENABLED.get()) {
            return false;
        }
        LODLevel lod = getCachedLOD(trainId);

        if (lod == LODLevel.GHOST) {
            // GHOST: atualizar apenas 1 em cada 6 frames
            return incrementAndCheckSkip(trainId, 6);
        }

        if (lod == LODLevel.LOW) {
            // LOW: atualizar apenas 1 em cada 3 frames
            return incrementAndCheckSkip(trainId, LOW_LOD_UPDATE_EVERY_N_FRAMES);
        }

        // Sob pressão de FPS, saltar frames mesmo para MEDIUM
        if (lod == LODLevel.MEDIUM && clientFPS < 30.0) {
            return incrementAndCheckSkip(trainId, 2);
        }

        return false;
    }

    /**
     * Incrementa contador e retorna true se este frame deve ser skipado.
     */
    private static boolean incrementAndCheckSkip(UUID trainId, int interval) {
        int counter = flywheelSkipCounters.merge(trainId, 1, Integer::sum);
        if (counter >= interval) {
            flywheelSkipCounters.put(trainId, 0);
            return false; // Este frame é processado
        }
        return true; // Skip este frame
    }

    public static double distanceSqToCamera(double x, double y, double z) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer == null || mc.gameRenderer.getMainCamera() == null) {
            return 0;
        }
        Vec3 cameraPos = mc.gameRenderer.getMainCamera().getPosition();
        double dx = cameraPos.x - x;
        double dy = cameraPos.y - y;
        double dz = cameraPos.z - z;
        return dx * dx + dy * dy + dz * dz;
    }

    public static double getClientFPS() {
        return clientFPS;
    }

    public static long getCurrentFrameId() {
        return currentFrameId;
    }

    /**
     * Limpar caches quando um comboio é removido.
     */
    public static void onTrainRemoved(UUID trainId) {
        frameLODCache.remove(trainId);
        flywheelSkipCounters.remove(trainId);
        trainFirstSeenFrame.remove(trainId);
    }

    // === Render Transition API ===

    /**
     * NUNCA diferir a renderização na chegada. O comboio aparece 100% físico
     * desde o primeiro frame. O sistema de proximity buffer + snap de posição
     * garante que os dados já estão prontos quando a entidade entra no view distance.
     *
     * Retorna sempre false — aparecimento instantâneo.
     */
    public static boolean shouldDeferForWarmup(UUID trainId) {
        trainFirstSeenFrame.putIfAbsent(trainId, currentFrameId);
        return false;
    }

    /**
     * No-op: não precisamos de tracking de posição porque não há warmup defer.
     */
    public static void updatePositionStability(UUID trainId, double x, double y, double z) {
        // Nada a fazer — aparecimento instantâneo
    }

    /**
     * Obter a escala — sempre 1.0 (sem transição na chegada nem na saída).
     * Aparecimento e desaparecimento são vanilla.
     */
    public static float getWarmupScale(UUID trainId) {
        return 1.0f;
    }

    /**
     * Não há fade-out server-side. Retorna sempre 1.0 (render normal).
     */
    public static float getDepartureScale(UUID trainId) {
        return 1.0f;
    }

    /**
     * No-op: fade-out removido para evitar bugs de invisibilidade.
     */
    public static void startFadeOut(UUID trainId) {
        // Desactivado: o fade-out server-side causava comboios invisíveis
        // porque o estado nunca era limpo quando os comboios voltavam ao view distance.
    }

    /**
     * Sempre false — sem fade-out.
     */
    public static boolean isFadingOut(UUID trainId) {
        return false;
    }
}
