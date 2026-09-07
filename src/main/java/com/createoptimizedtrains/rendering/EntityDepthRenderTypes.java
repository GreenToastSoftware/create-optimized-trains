package com.createoptimizedtrains.rendering;

import com.createoptimizedtrains.mixin.client.ICompositeStateAccessor;
import com.createoptimizedtrains.mixin.client.IRenderTypeCompositeAccessor;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class EntityDepthRenderTypes extends RenderType {

    private static final Map<RenderType, RenderType> CACHE = new ConcurrentHashMap<>();

    private EntityDepthRenderTypes() {
        super("dummy", DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS, 256, false, false, () -> {}, () -> {});
    }

    public static RenderType forOriginal(RenderType original) {
        return CACHE.computeIfAbsent(original, EntityDepthRenderTypes::createDepthReplayType);
    }

    private static RenderType createDepthReplayType(RenderType original) {
        if (!(original instanceof IRenderTypeCompositeAccessor compositeAccessor)) {
            return original;
        }

        var state = compositeAccessor.invokeState();
        var stateAccessor = (ICompositeStateAccessor) (Object) state;

        return create(
            "create_optimized_trains:entity_depth_replay/" + Integer.toHexString(System.identityHashCode(original)),
            original.format(),
            original.mode(),
            original.bufferSize(),
            original.affectsCrumbling(),
            false,
            CompositeState.builder()
                .setTextureState(stateAccessor.getTextureState())
                .setShaderState(stateAccessor.getShaderState())
                .setTransparencyState(stateAccessor.getTransparencyState())
                .setDepthTestState(stateAccessor.getDepthTestState())
                .setCullState(stateAccessor.getCullState())
                .setLightmapState(stateAccessor.getLightmapState())
                .setOverlayState(stateAccessor.getOverlayState())
                .setLayeringState(stateAccessor.getLayeringState())
                .setOutputState(MAIN_TARGET)
                .setTexturingState(stateAccessor.getTexturingState())
                .setWriteMaskState(COLOR_DEPTH_WRITE)
                .createCompositeState(original.outline().isPresent())
        );
    }
}
