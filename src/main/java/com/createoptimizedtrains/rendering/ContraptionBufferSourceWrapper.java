package com.createoptimizedtrains.rendering;

import com.createoptimizedtrains.diagnostics.DebugLog;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;

/**
 * Wrapper de MultiBufferSource usado durante render de contraption.
 *
 * Centraliza os dois ajustes que precisam coexistir:
 * 1) translucentMovingBlock -> buffer global (para alinhar com os flushes de timing).
 * 2) translucent -> TRANSLUCENT_NO_DEPTH (evita poluição de depth no path sem Flywheel).
 */
public final class ContraptionBufferSourceWrapper implements MultiBufferSource {

    private static final Logger LOGGER = LogManager.getLogger("COT/ContraptionBufferSourceWrapper");
    private static volatile long lastLogTime = 0;

    private final MultiBufferSource delegate;

    public ContraptionBufferSourceWrapper(MultiBufferSource delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    public MultiBufferSource getDelegate() {
        return delegate;
    }

    @Override
    public VertexConsumer getBuffer(RenderType type) {
        if (type == RenderType.translucentMovingBlock()) {
            logRateLimited("[COT] ContraptionBufferSourceWrapper: reroute translucentMovingBlock -> raw RenderBuffers BufferSource (orig={})", delegate.getClass().getName());
            MultiBufferSource.BufferSource rawSource = BufferSourceResolver.getRawMainBufferSource();
            return rawSource.getBuffer(Objects.requireNonNull(type));
        }

        if (type == RenderType.translucent()) {
            logRateLimited("[COT] ContraptionBufferSourceWrapper: reroute translucent -> TRANSLUCENT_NO_DEPTH (orig={})", delegate.getClass().getName());
            return delegate.getBuffer(Objects.requireNonNull(ContraptionRenderTypes.TRANSLUCENT_NO_DEPTH));
        }

        return delegate.getBuffer(Objects.requireNonNull(type));
    }

    private static void logRateLimited(String message, Object arg) {
        long now = System.currentTimeMillis();
        if (DebugLog.ENABLED && now - lastLogTime > 3000) {
            lastLogTime = now;
            LOGGER.info(message, arg);
        }
    }
}
