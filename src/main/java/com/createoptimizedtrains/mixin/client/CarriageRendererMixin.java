package com.createoptimizedtrains.mixin.client;

import com.createoptimizedtrains.rendering.RenderOptimizer;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntityRenderer;
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
 * Create 6.x usa CarriageContraptionEntityRenderer que estende
 * ContraptionEntityRenderer<CarriageContraptionEntity>.
 */
@Mixin(value = com.simibubi.create.content.trains.entity.CarriageContraptionEntityRenderer.class)
public abstract class CarriageRendererMixin {

    /**
     * Intercertar renderização para saltar comboios fantasma.
     * Alvo: render(CarriageContraptionEntity, ...) — o override tipado declarado
     * pela classe Create (não o bridge method SRG). Usa descriptor completo para
     * desambiguar das versões bridge (AbstractContraptionEntity/Entity).
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

        // Saltar renderização completa para comboios fantasma
        if (RenderOptimizer.shouldSkipRender(trainId)) {
            ci.cancel();
        }
    }
}
