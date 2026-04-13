package com.createoptimizedtrains.mixin;

import com.simibubi.create.content.trains.GlobalRailwayManager;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Proteção contra crashes de outros mods durante o tick de comboios.
 *
 * GlobalRailwayManager.tickTrains() itera sobre todos os comboios e chama
 * earlyTick() em cada um. Se um mod (ex: RailX) injectar código que crasha
 * dentro de earlyTick(), o server inteiro crasha e o mundo não carrega.
 *
 * Este mixin envolve a chamada earlyTick() num try-catch para que:
 * - O comboio problemático salta o tick
 * - Outros comboios continuam a funcionar
 * - O mundo carrega normalmente
 * - Erros repetidos são logados apenas algumas vezes para não spammar
 */
@Mixin(value = GlobalRailwayManager.class, remap = false)
public class SafeTrainTickMixin {

    @Unique
    private static final Logger cot$logger = LoggerFactory.getLogger("COT-SafeTick");

    @Unique
    private final Map<UUID, Integer> cot$failedTrains = new ConcurrentHashMap<>();

    @Unique
    private static final int COT$MAX_LOG_PER_TRAIN = 3;

    /**
     * Envolver train.earlyTick() num try-catch.
     * Se crashar, o comboio salta o tick mas o server continua.
     */
    @Redirect(method = "tickTrains",
        at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/content/trains/entity/Train;earlyTick(Lnet/minecraft/world/level/Level;)V"))
    private void cot$safeEarlyTick(Train train, Level level) {
        try {
            train.earlyTick(level);
            // Sucesso — limpar contador de falhas se existia
            if (!cot$failedTrains.isEmpty()) {
                cot$failedTrains.remove(train.id);
            }
        } catch (Exception e) {
            int failures = cot$failedTrains.merge(train.id, 1, Integer::sum);
            if (failures <= COT$MAX_LOG_PER_TRAIN) {
                cot$logger.warn("[COT] Train {} crashed during earlyTick (attempt {}), skipping: {}",
                    train.id, failures, e.getMessage());
                if (failures == COT$MAX_LOG_PER_TRAIN) {
                    cot$logger.warn("[COT] Train {} has failed {} times, suppressing further logs.",
                        train.id, failures);
                }
            }
        }
    }
}
