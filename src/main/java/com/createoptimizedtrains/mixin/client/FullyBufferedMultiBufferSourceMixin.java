package com.createoptimizedtrains.mixin.client;

import com.createoptimizedtrains.diagnostics.DebugLog;
import com.createoptimizedtrains.rendering.BufferSourceResolver;
import com.createoptimizedtrains.rendering.RenderDeferFlags;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = {
    "net.irisshaders.batchedentityrendering.impl.FullyBufferedMultiBufferSource",
    "net.coderbot.batchedentityrendering.impl.FullyBufferedMultiBufferSource"
}, remap = false)
public class FullyBufferedMultiBufferSourceMixin {

    private static final Logger LOGGER = LogManager.getLogger("COT/FullyBufferedMBSMixin");
    private static volatile long lastLogTime = 0;

    @Inject(
        method = {"getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;", "m_6299_"},
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void rerouteMovingBlockDuringContraptionRender(RenderType renderType, CallbackInfoReturnable<VertexConsumer> cir) {
        if (!RenderDeferFlags.insideContraptionRender) {
            return;
        }
        long now = System.currentTimeMillis();
        if (DebugLog.ENABLED && now - lastLogTime > 3000) {
            lastLogTime = now;
            LOGGER.info("[COT] FullyBufferedMBSMixin: getBuffer dentro de contraption render (type={}, class={})", renderType, renderType.getClass().getName());
        }
        if (renderType != RenderType.translucentMovingBlock()) {
            return;
        }

        now = System.currentTimeMillis();
        if (DebugLog.ENABLED && now - lastLogTime > 3000) {
            lastLogTime = now;
            LOGGER.info("[COT] FullyBufferedMBSMixin: reroute translucentMovingBlock -> raw RenderBuffers BufferSource");
        }

        MultiBufferSource.BufferSource rawSource = BufferSourceResolver.getRawMainBufferSource();
        cir.setReturnValue(rawSource.getBuffer(renderType));
    }

}