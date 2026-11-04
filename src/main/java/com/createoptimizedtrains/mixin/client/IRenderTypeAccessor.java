package com.createoptimizedtrains.mixin.client;

import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor para RenderType.sortOnUpload — true nos tipos translúcidos (os quads são
 * ordenados por profundidade antes de desenhar). Usado para não fazer flush antecipado
 * de geometria translúcida antes de o Flywheel desenhar as contraptions.
 */
@Mixin(RenderType.class)
public interface IRenderTypeAccessor {

    @Accessor("sortOnUpload")
    boolean cot$isSortOnUpload();
}
