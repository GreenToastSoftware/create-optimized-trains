package com.createoptimizedtrains.mixin;

import com.createoptimizedtrains.config.ModConfig;
import com.createoptimizedtrains.util.PlayerTrainTracker;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ContraptionCollider;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Throttle de ContraptionCollider.collideEntities() para reduzir micro-stutters.
 *
 * ContraptionHandler.tick() chama collideEntities() para CADA contraption carregada
 * a CADA tick. collideEntities() é muito pesado:
 *   1. getEntitiesOfClass() com AABB enorme (inflate(2) + expandTowards(0,32,0))
 *   2. Para cada entidade próxima: rotação matricial, transform world→local, cria OrientedBB
 *   3. Para cada collision shape da contraption: teste OBB contínuo (SAT com sweep)
 *
 * Estratégia de throttle:
 * - Contraptions PARADAS sem jogador: skip em ticks alternados (50%)
 * - Contraptions de comboios onde o jogador NÃO está: skip 3 em cada 4 ticks (75%)
 *   quando o jogador está dentro de OUTRO comboio (cenário de estação com múltiplos comboios)
 * - Contraptions do comboio do jogador ou em MOVIMENTO com jogador perto: NUNCA skip
 *   (displacement é essencial para o jogador ficar de pé)
 */
@Mixin(value = ContraptionCollider.class, remap = false)
public class ContraptionColliderMixin {

    private static final double MOTION_THRESHOLD_SQ = 1.0E-6;
    private static final double NEAR_PLAYER_RADIUS = 96.0;

    /**
     * Throttle inteligente de collideEntities():
     * 1. Comboio do jogador → NUNCA skip (colisão mantém jogador de pé)
     * 2. Outro comboio, jogador está num comboio → skip 75% dos ticks
     * 3. Contraption parada qualquer → skip 50% dos ticks
     * 4. Contraption em movimento sem jogador → correr sempre
     */
    @Inject(method = "collideEntities", at = @At("HEAD"), cancellable = true)
    private static void throttleCollideEntities(AbstractContraptionEntity entity, CallbackInfo ci) {
        // Se é uma carruagem de comboio, usar lógica de comboio
        if (entity instanceof CarriageContraptionEntity cce) {
            var carriage = cce.getCarriage();
            if (carriage != null && carriage.train != null) {
                java.util.UUID trainId = carriage.train.id;

                // Comboio onde o jogador está → NUNCA throttlear
                if (PlayerTrainTracker.isOccupied(trainId)) {
                    return;
                }

                // Comboio próximo do jogador → física completa para evitar
                // sensação de "física simplificada" mesmo com chunks carregadas.
                if (PlayerTrainTracker.isPlayerNear(entity, NEAR_PLAYER_RADIUS)) {
                    return;
                }

                // Há algum jogador em QUALQUER comboio? Se sim, throttlear os outros
                // Config check
                try {
                    if (!ModConfig.PHYSICS_OPTIMIZATION_ENABLED.get()) return;
                    if (!ModConfig.AGGRESSIVE_OTHER_TRAINS_THROTTLE.get()) return;
                } catch (Exception e) {
                    return;
                }

                // Throttle agressivo: só processar 1 em cada 4 ticks
                if ((entity.tickCount + entity.getId()) % 4 != 0) {
                    ci.cancel();
                    return;
                }
                return;
            }
        }

        // Contraptions normais (não-comboio): throttle original para paradas
        Vec3 motion = entity.getDeltaMovement();
        if (motion.lengthSqr() > MOTION_THRESHOLD_SQ) {
            return; // Em movimento — não throttlear
        }

        if ((entity.tickCount + entity.getId()) % 2 != 0) {
            ci.cancel();
        }
    }
}
