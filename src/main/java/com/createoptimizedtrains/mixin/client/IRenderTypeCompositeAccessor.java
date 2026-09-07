package com.createoptimizedtrains.mixin.client;

import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "net.minecraft.client.renderer.RenderType$CompositeRenderType")
public interface IRenderTypeCompositeAccessor {

    @Invoker("state")
    RenderType.CompositeState invokeState();
}
