package com.createoptimizedtrains.mixin.client;

import com.createoptimizedtrains.rendering.BufferSourceResolver;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;

/**
 * Fallback para o flush da porta Copycats+ (translucentMovingBlock) quando não há OIT.
 *
 * === Ordem de execução em popPush("blockentities") ===
 *   Priority 500  (LevelRendererFlushMixin): flush de entidades Iris
 *   Priority 1000 (Flywheel afterEntities):  OIT pipeline (se houver vidro)
 *     - OitFramebufferMixin @HEAD composite(): flush da porta COM depth correcto
 *   Priority 1500 (este mixin): flush da porta se ainda não foi flushed pelo OIT
 *
 * Com vidro: OitFramebufferMixin @HEAD já flushed a porta — buffer está vazio aqui → no-op.
 * Sem vidro:  composite() nunca chamado → buffer tem dados → flushed aqui com depthMask=true.
 */
@Mixin(value = LevelRenderer.class, priority = 1500)
public class LevelRendererPostFlywheelMixin {

    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE_STRING",
            target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
            args = "ldc=blockentities"
        )
    )
    private void flushDoorFallback(CallbackInfo ci) {
        // No-op se OitFramebufferMixin já esvaziou o buffer (caso com vidro).
        // Flush real só acontece quando não há OIT (sem vidro na contraption).
        // depthMask(true) por defeito: porta escreve depth correcto no item entity FBO.
        BufferSourceResolver.getRawMainBufferSource()
            .endBatch(Objects.requireNonNull(RenderType.translucentMovingBlock()));
    }
}
