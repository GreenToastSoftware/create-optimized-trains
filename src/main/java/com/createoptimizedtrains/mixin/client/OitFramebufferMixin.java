package com.createoptimizedtrains.mixin.client;

import com.createoptimizedtrains.rendering.BufferSourceResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Flush de RenderType.translucentMovingBlock() exactamente ANTES do OIT composite().
 *
 * === Pipeline Flywheel OIT (por ordem) ===
 *   1. submitSolid()                         ← Z_solid escrito no depth buffer
 *   2. depthRange()
 *   3. submitTransparent(DEPTH_RANGE)
 *   4. renderTransmittance()
 *   5. submitTransparent(GENERATE_COEFFICIENTS)
 *   6. renderDepthFromTransmittance()         ← Z_solid confirmado no depth principal
 *   7. accumulate() + submitTransparent(EVALUATE)
 *   8. composite()                            ← injectamos aqui (HEAD)
 *
 * Neste ponto (HEAD de composite()):
 *  - Z_solid já está no depth buffer principal → porta usa depth test correcto ✓
 *  - OIT acumulado mas ainda não blendado → porta fica no framebuffer ANTES do blend → tint ✓
 *
 * Para portas físicas (block entities): o buffer está vazio aqui (a porta ainda não renderizou,
 * renderiza durante o ciclo de block entities DEPOIS desta injec ção) → no-op → flushed
 * pelo vanilla endBatch(translucentMovingBlock()) após block entities. ✓
 */
@Mixin(targets = "dev.engine_room.flywheel.backend.engine.indirect.OitFramebuffer", remap = false)
public class OitFramebufferMixin {

    private static final Logger LOGGER = LogManager.getLogger("COT/OitFramebufferMixin");
    private static final AtomicBoolean LOGGED = new AtomicBoolean(false);

    @Inject(method = "composite", at = @At("HEAD"))
    private void flushMovingBlockBeforeOitComposite(CallbackInfo ci) {
        if (LOGGED.compareAndSet(false, true)) {
            LOGGER.info("[COT] OitFramebufferMixin: flush da porta antes de composite() com depth write normal");
        }
        Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
        RenderType doorType = Objects.requireNonNull(RenderType.translucentMovingBlock());
        BufferSourceResolver.getRawMainBufferSource().endBatch(doorType);
    }
}

