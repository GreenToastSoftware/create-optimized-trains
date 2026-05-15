package com.createoptimizedtrains.rendering;

import com.createoptimizedtrains.config.ModConfig;
import com.createoptimizedtrains.lod.LODLevel;
import com.createoptimizedtrains.lod.LODSystem;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
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

    // === Player Train Detection (client-side) ===
    // Cache do train ID do jogador local, atualizado uma vez por frame
    private static UUID cachedLocalPlayerTrainId = null;
    private static long localPlayerTrainCacheFrame = -1;

    // === Render Transition System ===
    // Comboios aparecem IMEDIATAMENTE em modo físico completo (sem ghost/warmup).
    // O sistema de proximity buffer garante que chunks e entidades já estão carregados.
    // Desaparecimento é vanilla (sem fade-out server-side para evitar bugs de invisibilidade).
    private static final Map<UUID, Long> trainFirstSeenFrame = new ConcurrentHashMap<>();

    private static boolean isShaderBoostActive() {
        return ModConfig.SHADER_BOOST_ENABLED.get() && ShaderCompat.isShaderPackActive();
    }

    private static LODLevel getEffectiveLOD(UUID trainId) {
        // Paridade com 1.2.0: distâncias LOD são exclusivamente as do LODSystem
        // (full/medium/low/ghost), sem degradação extra client-side.
        return getCachedLOD(trainId);
    }

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
        return getEffectiveLOD(trainId).shouldRenderDetailed();
    }

    public static boolean shouldAnimate(UUID trainId) {
        if (!initialized || !ModConfig.DISABLE_DISTANT_ANIMATIONS.get()) {
            return true;
        }
        LODLevel lod = getEffectiveLOD(trainId);
        return lod != LODLevel.GHOST;
    }

    public static boolean shouldEmitParticles(UUID trainId) {
        if (!initialized || !ModConfig.DISABLE_DISTANT_PARTICLES.get()) {
            return true;
        }
        return getEffectiveLOD(trainId) == LODLevel.FULL;
    }

    public static boolean shouldRenderInterior(UUID trainId) {
        if (!initialized || !ModConfig.RENDER_OPTIMIZATION_ENABLED.get()) {
            return true;
        }
        return getEffectiveLOD(trainId) == LODLevel.FULL;
    }

    public static float getModelSimplification(UUID trainId) {
        if (!initialized || !ModConfig.RENDER_OPTIMIZATION_ENABLED.get()) {
            return 0.0f;
        }
        LODLevel lod = getEffectiveLOD(trainId);
        return switch (lod) {
            case FULL -> 0.0f;
            case MEDIUM -> 0.3f;
            case LOW -> 0.7f;
            case GHOST -> 1.0f;
        };
    }

    /**
     * GHOST trains só são skipados quando FPS está crítico (<20).
     * A 40+ FPS o custo visual de stutter não compensa o ganho de performance.
     */
    public static boolean shouldSkipRender(UUID trainId) {
        if (!initialized || !ModConfig.RENDER_OPTIMIZATION_ENABLED.get()) {
            return false;
        }
        LODLevel lod = getEffectiveLOD(trainId);
        if (lod == LODLevel.GHOST && clientFPS < 20.0) {
            return true;
        }
        if (isShaderBoostActive() && lod == LODLevel.LOW && clientFPS < 35.0) {
            return true;
        }
        return false;
    }

    /**
     * Flywheel visual update — NUNCA saltar posição/transformação para comboios EM MOVIMENTO.
     * Saltar beginFrame() em comboios móveis causa stutter visível porque a posição congela.
     *
     * Retorna sempre false — comboios móveis correm em todos os frames.
     */
    public static boolean shouldSkipFlywheelUpdate(UUID trainId) {
        return false;
    }

    /**
     * Skip de beginFrame()/animate() para comboios PARADOS onde o jogador NÃO está.
     *
     * Para comboios estacionários, a posição não muda — saltar beginFrame() não causa
     * stutter visível. Isto poupa recalcular matrizes de transformação, light sections,
     * e child visuals para todas as carruagens dos outros comboios na estação.
     *
     * Redução: só processa 1 em cada 4 frames (75% menos trabalho GPU/CPU por comboio parado).
     *
     * @param trainId ID do comboio
     * @param entity  Entidade da carruagem (para verificar movimento)
     * @return true se este frame deve ser skipado
     */
    // Cache de posições anteriores para deteção de movimento real no cliente
    private static final Map<Integer, double[]> lastEntityPositions = new ConcurrentHashMap<>();
    private static final double NEAR_VISUAL_SKIP_RADIUS_SQ = 96.0 * 96.0;

    public static boolean shouldSkipStationaryNonOccupied(UUID trainId, Entity entity) {
        if (!initialized) return false;
        try {
            if (!ModConfig.REDUCE_OTHER_TRAINS_PHYSICS.get()) return false;
        } catch (Exception e) {
            return false;
        }

        // Nunca skipar o comboio do jogador
        UUID playerTrainId = getLocalPlayerTrainId();
        if (playerTrainId != null && trainId.equals(playerTrainId)) return false;

        // Nunca aplicar throttle visual a comboios perto da câmara/jogador.
        // Quando o jogador está ao lado do comboio sem estar sentado, saltar 75%
        // dos beginFrame() faz o comboio parecer estar em LOD distante/choppy mesmo
        // com chunks e entidades corretas.
        if (distanceSqToCamera(entity.getX(), entity.getY(), entity.getZ()) <= NEAR_VISUAL_SKIP_RADIUS_SQ) {
            return false;
        }

        // Detetar movimento real comparando posição atual com a do frame anterior.
        // Não usar getDeltaMovement() (sempre ~0) nem train.speed (pode não estar
        // sincronizado no cliente para comboios não-ocupados).
        int eid = entity.getId();
        double cx = entity.getX(), cy = entity.getY(), cz = entity.getZ();
        double[] last = lastEntityPositions.get(eid);
        lastEntityPositions.put(eid, new double[]{cx, cy, cz});
        if (last != null) {
            double dx = cx - last[0], dy = cy - last[1], dz = cz - last[2];
            if (dx * dx + dy * dy + dz * dz > 0.0001) return false; // em movimento
        } else {
            return false; // primeiro frame — não skipar
        }

        int interval = isShaderBoostActive()
            ? Math.max(2, ModConfig.SHADER_STATIONARY_SKIP_FRAMES.get())
            : 4;

        // Ex: interval=4 => processa 1 em cada 4 frames; interval=6 => 1 em cada 6
        return (currentFrameId + entity.getId()) % interval != 0;
    }

    /**
     * Obter o train ID do comboio onde o jogador local está sentado.
     * Cacheado por frame para evitar percorrer a cadeia de veículos por entidade.
     */
    private static UUID getLocalPlayerTrainId() {
        if (localPlayerTrainCacheFrame == currentFrameId) return cachedLocalPlayerTrainId;
        localPlayerTrainCacheFrame = currentFrameId;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            cachedLocalPlayerTrainId = null;
            return null;
        }
        Entity vehicle = mc.player.getVehicle();
        int depth = 0;
        while (vehicle != null && depth < 5) {
            if (vehicle instanceof CarriageContraptionEntity cce) {
                var carriage = cce.getCarriage();
                if (carriage != null && carriage.train != null) {
                    cachedLocalPlayerTrainId = carriage.train.id;
                    return cachedLocalPlayerTrainId;
                }
            }
            vehicle = vehicle.getVehicle();
            depth++;
        }
        cachedLocalPlayerTrainId = null;
        return null;
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

    public static boolean isShaderBoostCurrentlyActive() {
        return isShaderBoostActive();
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
