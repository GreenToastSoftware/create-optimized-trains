package com.createoptimizedtrains.mixin.client;

import dev.engine_room.flywheel.backend.engine.indirect.OitFramebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Fix primário para vidro de contraptions (requer que mixin aplique a classe JiJ'd do Flywheel).
 *
 * O OIT pipeline do Flywheel chama renderDepthFromTransmittance() que escreve
 * depth com depthMask(true) + depthFunc(GL_ALWAYS). Isto faz com que tudo
 * que renderize depois (block entities, portas, partículas) fique oculto
 * atrás do vidro.
 *
 * Fix: No-op o renderDepthFromTransmittance(). O compositing OIT continua
 * a funcionar (tint de vidro colorido preservado), mas o vidro não escreve
 * depth. Blocos opacos CONTINUAM a escrever depth via submitSolid().
 */
@Mixin(value = OitFramebuffer.class, remap = false)
public class OitDepthMixin {

    private static final Logger LOGGER = LogManager.getLogger("CreateOptimizedTrains/OitDepth");
    private static boolean logged = false;

    @Inject(method = "renderDepthFromTransmittance", at = @At("HEAD"), cancellable = true)
    private void skipDepthFromTransmittance(CallbackInfo ci) {
        if (!logged) {
            LOGGER.info("[CreateOptimizedTrains] OitDepthMixin active - skipping renderDepthFromTransmittance");
            logged = true;
        }
        ci.cancel();
    }
}
