package com.createoptimizedtrains.mixin.client;

import com.createoptimizedtrains.rendering.RenderOptimizer;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Mixin no renderer de CarriageContraptionEntity (Create 6.x) para otimizar renderização.
 *
 * Funcionalidades:
 * - Renderização instantânea ao aparecer (sem ghost/warmup)
 * - Skip de comboios fantasma em FPS baixo
 * - Culling rápido por distância
 */
@Mixin(value = com.simibubi.create.content.trains.entity.CarriageContraptionEntityRenderer.class)
public abstract class CarriageRendererMixin {

    /**
     * Intercertar renderização para:
     * 1. Skip comboios fantasma em FPS baixo
     * 2. Culling por distância
     */
    @Inject(
        method = "render(Lcom/simibubi/create/content/trains/entity/CarriageContraptionEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void onRender(CarriageContraptionEntity entity, float yaw, float partialTick,
                           PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                           CallbackInfo ci) {
        var carriage = entity.getCarriage();
        if (carriage == null || carriage.train == null) return;

        UUID trainId = carriage.train.id;

        // Registar visibilidade
        RenderOptimizer.shouldDeferForWarmup(trainId);

        // Skip renderização para comboios fantasma quando FPS está baixo
        if (RenderOptimizer.shouldSkipRender(trainId)) {
            ci.cancel();
            return;
        }

        // Culling rápido por distância à câmara
        if (!RenderOptimizer.shouldRenderDetailed(trainId)) {
            double distSq = RenderOptimizer.distanceSqToCamera(
                    entity.getX(), entity.getY(), entity.getZ());
            if (distSq > 65536.0) {
                ci.cancel();
                return;
            }
        }
    }
}
