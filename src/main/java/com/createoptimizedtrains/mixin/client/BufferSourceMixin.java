package com.createoptimizedtrains.mixin.client;

import com.createoptimizedtrains.diagnostics.DebugLog;
import com.createoptimizedtrains.rendering.ContraptionRenderTypes;
import com.createoptimizedtrains.rendering.RenderDeferFlags;
import net.minecraft.client.renderer.RenderType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicLong;

@Mixin(targets = "net.minecraft.client.renderer.MultiBufferSource$BufferSource", priority = 2000)
public class BufferSourceMixin {

    private static final Logger LOGGER = LogManager.getLogger("COT/BufferSourceMixin");
    private static volatile long lastLogTime = 0;
    private static final AtomicLong canceledCount = new AtomicLong(0);
    private static final AtomicLong allowedCount = new AtomicLong(0);

    @Inject(
        method = {"endBatch(Lnet/minecraft/client/renderer/RenderType;)V", "m_109912_"},
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void deferMovingBlockFlush(RenderType renderType, CallbackInfo ci) {
        boolean isOurType = renderType == RenderType.translucentMovingBlock()
                         || renderType == ContraptionRenderTypes.TRANSLUCENT_MOVING_BLOCK_NO_DEPTH;
        if (!isOurType) return;

        long now = System.currentTimeMillis();
        boolean shouldLog = DebugLog.ENABLED && (now - lastLogTime) > 1000;
        if (shouldLog) lastLogTime = now;

        if (RenderDeferFlags.deferTranslucentMovingBlock) {
            long c = canceledCount.incrementAndGet();
            if (shouldLog) LOGGER.info("[COT] endBatch({}) CANCELADO (janela defer activa)", renderType);
            if (shouldLog) LOGGER.info("[COT] BufferSourceMixin counters: canceled={}, allowed={}", c, allowedCount.get());
            ci.cancel();
        } else {
            long a = allowedCount.incrementAndGet();
            if (shouldLog) LOGGER.info("[COT] endBatch({}) PERMITIDO (defer=false, caller a flush)", renderType);
            if (shouldLog) LOGGER.info("[COT] BufferSourceMixin counters: canceled={}, allowed={}", canceledCount.get(), a);
        }
    }
}
