package com.createoptimizedtrains.mixin;

import com.createoptimizedtrains.CreateOptimizedTrains;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Mixin no Train do Create para otimizações de performance.
 * IMPORTANTE: NUNCA cancelar Train.tick() — o Create calcula movimento
 * nos trilhos a cada tick. Saltar ticks = comboio abranda/para.
 *
 * Otimização principal: throttle de collideWithOtherTrains().
 * No Create 6.0.8, findCollidingTrain() é chamado para CADA carruagem
 * a CADA tick, iterando TODOS os grafos × TODOS os comboios × TODAS
 * as carruagens = O(trains² × carriages²) por tick.
 * Throttlear para cada 4 ticks reduz 75% do overhead.
 */
@Mixin(value = Train.class, remap = false)
public abstract class TrainMixin {

    @Shadow
    public UUID id;

    @Shadow
    public double speed;

    /**
     * Contador interno usado para stagger o throttle entre comboios diferentes.
     * Cada comboio recebe um offset baseado no seu UUID para que nem todos
     * façam collision check no mesmo tick.
     */
    @Unique
    private int collisionTickCounter = -1;

    @Unique
    private static final int COLLISION_CHECK_INTERVAL = 4;

    /**
     * Observar tick do comboio para métricas — sem cancelar.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void onTickHead(Level level, CallbackInfo ci) {
        // Incrementar contador de ticks para throttle de colisões
        if (collisionTickCounter == -1) {
            // Inicializar com offset baseado no UUID do comboio
            // para distribuir collision checks ao longo dos ticks
            collisionTickCounter = Math.abs(id.hashCode()) % COLLISION_CHECK_INTERVAL;
        }
        collisionTickCounter++;
    }

    /**
     * Throttle de collideWithOtherTrains — o maior bottleneck de Train.tick().
     *
     * No Create 6.0.8, para CADA carruagem, CADA tick:
     *   1. Itera todos os TrackGraphs
     *   2. Para cada grafo, itera TODOS os comboios
     *   3. Para cada comboio, itera TODAS as carruagens
     *   4. Calcula posições via TravellingPoint.getPosition() (graph lookup)
     *   5. Faz math vetorial pesada (intersect, intersectSphere, distanceTo)
     *
     * Com 5 comboios de 3 carruagens = 180 operações/tick.
     * Throttleando para cada 4 ticks = 45 operações/tick (75% menos).
     *
     * Seguro porque:
     * - Mesmo a velocidade máxima (~1.2 blocks/tick), um comboio move ~5 blocos em 4 ticks
     * - O range de detecção de colisão é 128+ blocos (configurável)
     * - 200ms de "atraso" vs 50ms é negligível para evitar crashes
     */
    @Inject(method = "collideWithOtherTrains", at = @At("HEAD"), cancellable = true)
    private void throttleCollisionCheck(Level level, Carriage carriage, CallbackInfo ci) {
        if (collisionTickCounter % COLLISION_CHECK_INTERVAL != 0) {
            ci.cancel();
        }
    }
}
