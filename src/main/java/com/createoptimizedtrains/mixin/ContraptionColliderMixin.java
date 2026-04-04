package com.createoptimizedtrains.mixin;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ContraptionCollider;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Throttle de ContraptionCollider.collideEntities() para reduzir micro-stutters
 * quando o jogador anda perto de contraptions PARADAS.
 *
 * ContraptionHandler.tick() chama collideEntities() para CADA contraption carregada
 * a CADA tick. collideEntities() é muito pesado:
 *   1. getEntitiesOfClass() com AABB enorme (inflate(2) + expandTowards(0,32,0))
 *   2. Para cada entidade próxima: rotação matricial, transform world→local, cria OrientedBB
 *   3. Para cada collision shape da contraption: teste OBB contínuo (SAT com sweep)
 *
 * Só throttleamos contraptions PARADAS (estações, elevadores em repouso, etc.).
 * Contraptions em MOVIMENTO (comboios a andar) precisam de colisão a cada tick
 * para manter o jogador de pé dentro da carruagem — o displacement é calculado
 * em collideEntities() e sem ele o jogador escorrega/cai.
 */
@Mixin(value = ContraptionCollider.class, remap = false)
public class ContraptionColliderMixin {

    private static final double MOTION_THRESHOLD_SQ = 1.0E-6;

    /**
     * Cancela collideEntities() em ticks alternados APENAS para contraptions paradas.
     * Se a contraption está em movimento (deltaMovement > threshold), NUNCA salta.
     */
    @Inject(method = "collideEntities", at = @At("HEAD"), cancellable = true)
    private static void throttleCollideEntities(AbstractContraptionEntity entity, CallbackInfo ci) {
        Vec3 motion = entity.getDeltaMovement();
        if (motion.lengthSqr() > MOTION_THRESHOLD_SQ) {
            return; // Em movimento — não throttlear
        }

        if ((entity.tickCount + entity.getId()) % 2 != 0) {
            ci.cancel();
        }
    }
}
