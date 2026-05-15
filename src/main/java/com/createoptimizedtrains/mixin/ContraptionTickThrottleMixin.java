package com.createoptimizedtrains.mixin;

import com.createoptimizedtrains.config.ModConfig;
import com.createoptimizedtrains.util.PlayerTrainTracker;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Throttle de tickContraption() para carruagens de comboios PARADOS
 * onde o jogador NÃO está sentado.
 *
 * tickContraption() é uma das operações mais pesadas por carruagem:
 *   1. Tick de TODOS os block entities internos (fornalhas, deployers, redstone, etc.)
 *   2. Atualização do ContraptionWorld (boundary, collider shapes)
 *   3. Sincronização de dados da entidade (NBT, network)
 *   4. Lógica de stalling/unstalling
 *
 * No cenário de uma estação com 4 comboios × 11 carruagens = 44 instâncias
 * de tickContraption() por tick. Com este throttle, as 33 carruagens dos
 * comboios não-ocupados correm apenas 1 em cada 4 ticks = ~8 carruagens/tick
 * em vez de 33. Total: 11 (jogador) + 8 (outros) = 19 vs 44.
 *
 * NÃO afeta:
 * - O comboio onde o jogador está (sempre tick completo)
 * - Comboios em movimento (sempre tick completo)
 * - Train.tick() (posição/rota — sempre corre, é separado)
 *
 * NOTA: Dois mixins injetam em tickContraption() HEAD — este e CarriageEntityMixin.
 * São mutuamente exclusivos: CarriageEntityMixin só age quando carriage==null,
 * este só age quando carriage!=null + parado + não-ocupado.
 */
@Mixin(value = CarriageContraptionEntity.class)
public class ContraptionTickThrottleMixin {

    private static final double NEAR_PLAYER_RADIUS = 96.0;

    @Inject(method = "tickContraption", at = @At("HEAD"), remap = false, cancellable = true)
    private void throttleStationaryNonOccupied(CallbackInfo ci) {
        CarriageContraptionEntity self = (CarriageContraptionEntity) (Object) this;

        // NUNCA throttlear no cliente — tickContraption() no cliente faz
        // bindCarriage(), sync visual, e atualização de luz. Sem isto,
        // o comboio fica "preso" no estado visual de entrada.
        if (self.level().isClientSide) return;

        var carriage = self.getCarriage();
        if (carriage == null || carriage.train == null) return;

        try {
            if (!ModConfig.REDUCE_OTHER_TRAINS_PHYSICS.get()) return;
            if (!ModConfig.AGGRESSIVE_OTHER_TRAINS_THROTTLE.get()) return;
        } catch (Exception e) {
            return;
        }

        UUID trainId = carriage.train.id;

        // Nunca throttlear o comboio do jogador
        if (PlayerTrainTracker.isOccupied(trainId)) return;

        // Comboio próximo do jogador: manter tick completo para física natural.
        if (PlayerTrainTracker.isPlayerNear(self, NEAR_PLAYER_RADIUS)) return;

        // Só throttlear comboios parados (speed ≈ 0)
        if (Math.abs(carriage.train.speed) > 0.001) return;

        // Correr apenas 1 em cada 4 ticks (75% de redução)
        if (self.tickCount % 4 != 0) {
            ci.cancel();
        }
    }
}
