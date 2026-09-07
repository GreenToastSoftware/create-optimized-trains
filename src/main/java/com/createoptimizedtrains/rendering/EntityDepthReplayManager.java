package com.createoptimizedtrains.rendering;

import com.createoptimizedtrains.diagnostics.DebugLog;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;

public final class EntityDepthReplayManager {

    private static final Logger LOGGER = LogManager.getLogger("COT/EntityDepthReplayManager");
    private static final MultiBufferSource.BufferSource REPLAY_BUFFER = MultiBufferSource.immediate(new BufferBuilder(1024));
    private static volatile long lastLogTime = 0;

    private EntityDepthReplayManager() {}

    public static VertexConsumer getReplayConsumer(RenderType renderType) {
        return REPLAY_BUFFER.getBuffer(EntityDepthRenderTypes.forOriginal(Objects.requireNonNull(renderType)));
    }

    public static void flushDepthOnly() {
        long startNs = System.nanoTime();
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.depthMask(true);
        try {
            REPLAY_BUFFER.endBatch();
        } finally {
            RenderSystem.colorMask(true, true, true, true);
        }

        long now = System.currentTimeMillis();
        if (DebugLog.ENABLED && now - lastLogTime > 5000) {
            lastLogTime = now;
            LOGGER.info("[COT] EntityDepthReplayManager: flush depth-only concluido em {} us", (System.nanoTime() - startNs) / 1000L);
        }
    }
}
