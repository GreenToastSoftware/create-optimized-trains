package com.createoptimizedtrains.mixin.client;

import com.createoptimizedtrains.rendering.OitDeferFlags;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Executa o composite() do Flywheel OIT diferido no RETURN de renderLevel.
 *
 * === Por que diferir? ===
 *
 * O Flywheel injeta em popPush("blockentities") com priority 1000.
 * Nessa altura, o framebuffer só contém blocos sólidos e entidades.
 * Se composite() corre aí, apenas esses elementos ficam tintados pelo vidro colorido.
 *
 * Ao diferir para o RETURN de renderLevel:
 *   ✅ Blocos sólidos: tintados
 *   ✅ Entidades (jogadores, mobs): tintados
 *   ✅ Block entities (cabeças, crânios, sinais): tintados
 *   ✅ Pass translúcida (água, lava, vidros do mundo): tintados
 *
 * === Como funciona ===
 *
 * OitFramebufferMixin.deferComposite() cancela o composite() original e armazena
 * um Runnable em OitDeferFlags.pendingComposite que chama composite() quando
 * executado (com flag cot_compositeExecuting=true para não re-deferir).
 *
 * Este mixin executa esse Runnable no RETURN de renderLevel.
 */
@Mixin(value = LevelRenderer.class)
public class LevelRendererTranslucentFlushMixin {

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void applyDeferredOitComposite(CallbackInfo ci) {
        Runnable composite = OitDeferFlags.pendingComposite;
        if (composite != null) {
            composite.run();
        }
    }
}
