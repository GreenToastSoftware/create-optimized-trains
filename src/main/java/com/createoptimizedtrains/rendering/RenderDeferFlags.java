package com.createoptimizedtrains.rendering;

/**
 * Flags estáticos partilhados entre mixins de rendering.
 */
public final class RenderDeferFlags {

    private RenderDeferFlags() {}

    /**
     * Quando true, qualquer chamada a endBatch(RenderType.translucentMovingBlock())
     * é interceptada e cancelada por BufferSourceMixin.
     * Activado durante o flush pré-Flywheel para impedir que vanilla ou o pipeline
     * OIT flush a porta fora do timing controlado.
     */
    public static volatile boolean deferTranslucentMovingBlock = false;

    /**
     * Quando true, estamos dentro de ContraptionEntityRenderer.render().
     * Usado para reroute da porta em renderizadores buffered de Iris/Embeddium.
     */
    public static volatile boolean insideContraptionRender = false;
}
