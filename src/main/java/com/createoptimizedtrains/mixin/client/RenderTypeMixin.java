package com.createoptimizedtrains.mixin.client;

import net.minecraft.client.renderer.RenderType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Substitui RenderType.translucentMovingBlock() por TRANSLUCENT_MOVING_BLOCK_NO_DEPTH.
 *
 * === Por que aqui e não no BufferSource.getBuffer() ===
 *
 * O @Inject em MultiBufferSource$BufferSource.getBuffer() só intercepta chamadas
 * na instância CONCRETA BufferSource. Se Create usar um wrapper ou um BufferSource
 * diferente para renderizar block entities da contraption (como CopycatSlidingDoorRenderer),
 * a intercepção falha.
 *
 * Ao substituir a FONTE (o método de fábrica translucentMovingBlock()), qualquer código
 * que chame RenderType.translucentMovingBlock() — directamente ou via cache estático —
 * recebe automaticamente TRANSLUCENT_MOVING_BLOCK_NO_DEPTH.
 *
 * Nesta versão, a substituição é ACTIVADA apenas durante ContraptionEntityRenderer.render().
 *
 * === Efeito ===
 *
 * TRANSLUCENT_MOVING_BLOCK_NO_DEPTH é idêntico a translucentMovingBlock() mas com
 * WriteMaskState = COLOR_WRITE (sem escrita de depth). Isso garante que múltiplos
 * painéis de uma porta folding/sliding não se occludam mutuamente no depth buffer,
 * tornando todos visíveis independentemente da ordem de renderização.
 *
 * === Impacto noutros usos ===
 *
 * O único outro uso vanilla de translucentMovingBlock é MovingBlockEntityRenderer
 * (blocos movidos por pistões). Para blocos opacos (maioria dos casos), a ausência
 * de depth write tem impacto visual negligenciável. Para pistões a mover vidro
 * (edge case raro), pode haver z-fighting mínimo durante a animação.
 */
@Mixin(RenderType.class)
public class RenderTypeMixin {

    private static final Logger LOGGER = LogManager.getLogger("COT/RenderTypeMixin");
    private static final AtomicBoolean LOGGED = new AtomicBoolean(false);

    @Inject(
        method = {"translucentMovingBlock", "m_110469_"},
        at = @At("RETURN"),
        cancellable = true
    )
    private static void useNoDepthMovingBlock(CallbackInfoReturnable<RenderType> cir) {
        // Inactivo: mantido apenas para referência histórica.
    }
}
