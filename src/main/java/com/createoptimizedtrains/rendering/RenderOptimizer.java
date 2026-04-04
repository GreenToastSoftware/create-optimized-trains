package com.createoptimizedtrains.rendering;

import com.createoptimizedtrains.config.ModConfig;
import com.createoptimizedtrains.lod.LODLevel;
import com.createoptimizedtrains.lod.LODSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.UUID;

@OnlyIn(Dist.CLIENT)
public class RenderOptimizer {

    private static LODSystem lodSystem;
    private static boolean initialized = false;

    public static void init(LODSystem lod) {
        lodSystem = lod;
        initialized = true;
    }

    /**
     * Verifica se uma carruagem deve ser renderizada com detalhe completo.
     */
    public static boolean shouldRenderDetailed(UUID trainId) {
        if (!initialized || !ModConfig.RENDER_OPTIMIZATION_ENABLED.get()) {
            return true;
        }
        LODLevel lod = lodSystem.getTrainLOD(trainId);
        return lod.shouldRenderDetailed();
    }

    /**
     * Verifica se animações devem estar ativas para este comboio.
     */
    public static boolean shouldAnimate(UUID trainId) {
        if (!initialized || !ModConfig.DISABLE_DISTANT_ANIMATIONS.get()) {
            return true;
        }
        LODLevel lod = lodSystem.getTrainLOD(trainId);
        return lod == LODLevel.FULL || lod == LODLevel.MEDIUM;
    }

    /**
     * Verifica se partículas devem ser emitidas para este comboio.
     */
    public static boolean shouldEmitParticles(UUID trainId) {
        if (!initialized || !ModConfig.DISABLE_DISTANT_PARTICLES.get()) {
            return true;
        }
        LODLevel lod = lodSystem.getTrainLOD(trainId);
        return lod == LODLevel.FULL;
    }

    /**
     * Verifica se o interior da carruagem deve ser renderizado.
     * Interior só é visível quando o jogador está perto.
     */
    public static boolean shouldRenderInterior(UUID trainId) {
        if (!initialized || !ModConfig.RENDER_OPTIMIZATION_ENABLED.get()) {
            return true;
        }
        LODLevel lod = lodSystem.getTrainLOD(trainId);
        return lod == LODLevel.FULL;
    }

    /**
     * Obter o nível de simplificação do modelo (0.0 = completo, 1.0 = máxima simplificação).
     */
    public static float getModelSimplification(UUID trainId) {
        if (!initialized || !ModConfig.RENDER_OPTIMIZATION_ENABLED.get()) {
            return 0.0f;
        }

        LODLevel lod = lodSystem.getTrainLOD(trainId);
        return switch (lod) {
            case FULL -> 0.0f;
            case MEDIUM -> 0.3f;
            case LOW -> 0.7f;
            case GHOST -> 1.0f;
        };
    }

    /**
     * Verifica se um comboio deve ser completamente invisível.
     * NUNCA retorna true — mesmo comboios GHOST devem ser renderizados
     * (com simplificação máxima) para evitar comboios invisíveis.
     * A otimização real é feita via throttling de ticks e simplificação de modelo.
     */
    public static boolean shouldSkipRender(UUID trainId) {
        return false;
    }

    /**
     * Verifica se o Flywheel deve saltar a atualização visual deste comboio.
     * Usado pelo caminho Flywheel (CarriageContraptionVisual.beginFrame).
     * Comboios fantasma saltam completamente; comboios LOW atualizam a cada N frames.
     */
    public static boolean shouldSkipFlywheelUpdate(UUID trainId) {
        if (!initialized || !ModConfig.RENDER_OPTIMIZATION_ENABLED.get()) {
            return false;
        }
        LODLevel lod = lodSystem.getTrainLOD(trainId);
        if (lod == LODLevel.GHOST) {
            return true;
        }
        // Comboios LOW: atualizar visual apenas a cada 3 frames para poupar GPU
        if (lod == LODLevel.LOW) {
            return (System.nanoTime() / 16_666_666L) % 3 != 0; // ~60fps / 3 = ~20 updates/s
        }
        return false;
    }

    /**
     * Calcular distância ao quadrado entre a câmara e uma posição.
     * Usado para decisões rápidas de renderização sem sqrt.
     */
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
}
