package com.createoptimizedtrains.mixin.client;

import com.simibubi.create.content.contraptions.render.ContraptionVisual;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Placeholder — o pipeline OIT do Flywheel já gere corretamente o tinting
 * do vidro colorido em contraptions. Não é necessário alterar os materiais.
 */
@Mixin(value = ContraptionVisual.class, remap = false)
public class ContraptionVisualMaterialMixin {
}
