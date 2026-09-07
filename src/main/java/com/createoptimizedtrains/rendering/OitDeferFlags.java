package com.createoptimizedtrains.rendering;

/**
 * Flag partilhada entre OitFramebufferMixin e LevelRendererTranslucentFlushMixin
 * para diferir o composite() do OIT para depois da pass translúcida do mundo.
 *
 * Não pode ser campo estático do Mixin (restrição do Mixin framework).
 */
public final class OitDeferFlags {

    private OitDeferFlags() {}

    /**
     * Composite pendente: criado em OitFramebufferMixin.deferComposite() quando o
     * Flywheel chama composite() durante o pipeline OIT.
     * Executado em LevelRendererTranslucentFlushMixin.applyDeferredOitComposite()
     * no RETURN de renderLevel, depois de água/vidros/block entities terem sido
     * renderizados.
     */
    public static volatile Runnable pendingComposite = null;
}
