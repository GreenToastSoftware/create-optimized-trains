package com.createoptimizedtrains.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Força o flush de TODOS os buffers de entidades ANTES do hook do Flywheel.
 *
 * Problema:
 *   Flywheel injeta em profiler.popPush("blockentities") e executa o pipeline OIT.
 *   O composite() tinta tudo o que JÁ estiver no framebuffer. Porém, no MC 1.20.1,
 *   os vértices de entidades (players, mobs, items) ainda estão buffered no
 *   MultiBufferSource — só serão flushed para o framebuffer mais tarde.
 *
 *   Resultado: blocos opacos da contraption (submitSolid) são tintados ✓
 *   mas entidades são desenhadas DEPOIS do composite → visíveis mas sem tintagem ✗
 *
 * Fix:
 *   Injectar no MESMO ponto que o Flywheel (popPush("blockentities")), mas com
 *   priority 500 (menor que o default 1000 do Flywheel) → executa PRIMEIRO.
 *   Chama endBatch() para forçar TODOS os vértices pendentes para o framebuffer.
 *   Quando o Flywheel correr composite() logo a seguir, as entidades já estão
 *   no framebuffer e recebem a tintagem do vidro.
 */
@Mixin(value = LevelRenderer.class, priority = 500)
public class LevelRendererFlushMixin {

    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE_STRING",
            target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
            args = "ldc=blockentities"
        )
    )
    private void flushEntityBuffersBeforeFlywheel(CallbackInfo ci) {
        Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
    }
}
