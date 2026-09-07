package com.createoptimizedtrains.mixin.client;

import com.createoptimizedtrains.diagnostics.DebugLog;
import com.createoptimizedtrains.rendering.RenderDeferFlags;
import com.createoptimizedtrains.rendering.RenderOptimizer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flush de entidades buffered pelo Oculus/Iris ANTES do hook do Flywheel (priority 500).
 * A porta Copycats+ (translucentMovingBlock) NÃO é flushed aqui:
 *  - Com vidro (OIT activo): flushed pelo OitFramebufferMixin @HEAD (antes de composite(),
 *    com depthMask=true por defeito → depth correcto no item entity FBO → layering correcto).
 *  - Sem vidro (sem OIT): flushed pelo LevelRendererPostFlywheelMixin (priority 1500).
 */
@Mixin(value = LevelRenderer.class, priority = 500)
public class LevelRendererFlushMixin {

    private static final Logger LOGGER = LogManager.getLogger("COT/FlushMixin");
    private static volatile long lastFlushLog = 0;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void onRenderLevelStart(CallbackInfo ci) {
        RenderOptimizer.onFrameStart();
    }

    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE_STRING",
            target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
            args = "ldc=blockentities"
        )
    )
    private void flushEntityBuffersBeforeFlywheel(CallbackInfo ci) {
        long now = System.currentTimeMillis();
        if (DebugLog.ENABLED && now - lastFlushLog > 5000) {
            lastFlushLog = now;
            LOGGER.info("[COT] LevelRendererFlushMixin: flush de entidades antes do Flywheel OIT");
        }
        // Entidades Iris/Oculus — flush com depth write activo para ordenação correcta.
        // deferTranslucentMovingBlock=true evita que o flush Iris interaja com a porta.
        RenderDeferFlags.deferTranslucentMovingBlock = true;
        try {
            Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
        } finally {
            RenderDeferFlags.deferTranslucentMovingBlock = false;
        }
        // A porta (translucentMovingBlock) é tratada por OitFramebufferMixin e
        // LevelRendererPostFlywheelMixin — NÃO flushed aqui.
    }
}


