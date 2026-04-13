package com.createoptimizedtrains.mixin;

import com.createoptimizedtrains.CreateOptimizedTrains;
import com.createoptimizedtrains.monitor.PerformanceMonitor;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.world.level.Level;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Mixin no Train do Create para:
 * 1. Throttle adaptativo de collideWithOtherTrains() baseado no estado de performance.
 * 2. IMPEDIR que carriageWaitingForChunks pare o comboio (speed=0).
 *
 * === Fix: carriageWaitingForChunks ===
 * Em Train.tick(), Create lê o campo carriageWaitingForChunks. Se != -1,
 * define speed local = 0.0, parando o comboio até o chunk carregar.
 * Isto causa stutter de 0.5-1.5 segundos em mapas com muitos chunks.
 *
 * A nossa correção redireciona TODAS as leituras do campo dentro de tick()
 * para sempre devolver -1, impedindo a lógica de parar o comboio.
 * O comboio pode flutuar no vazio momentaneamente (chunks visuais não carregadas),
 * mas o movimento é contínuo e sem lag.
 *
 * Os chunk systems (ChunkLoadManager + RouteChunkPreloader) continuam a
 * pré-carregar chunks — esta correção é a segurança para quando o pre-loading
 * não chega a tempo.
 */
@Mixin(value = Train.class, remap = false)
public abstract class TrainMixin {

    @Shadow
    public UUID id;

    @Shadow
    public double speed;

    @Shadow
    public int carriageWaitingForChunks;

    @Unique
    private int collisionTickCounter = -1;

    @Unique
    private static final int COLLISION_CHECK_NORMAL = 4;
    @Unique
    private static final int COLLISION_CHECK_DEGRADED = 8;
    @Unique
    private static final int COLLISION_CHECK_CRITICAL = 12;

    /**
     * Redirect da leitura do campo carriageWaitingForChunks em tick().
     * O bytecode original:
     *   130: aload_0
     *   131: getfield #165  (carriageWaitingForChunks)
     *   134: iconst_m1
     *   135: if_icmpeq 140  (se == -1, pular)
     *   138: dconst_0       (senão, speed = 0)
     *   139: dstore_3
     *
     * Ao devolver sempre -1, o if_icmpeq salta sempre → speed nunca é zerada.
     * O comboio continua a mover-se mesmo que o chunk à frente não tenha
     * status entity-ticking. Pode "flutuar no vazio" visualmente, mas sem lag.
     */
    @Redirect(method = "tick",
        at = @At(value = "FIELD",
            target = "Lcom/simibubi/create/content/trains/entity/Train;carriageWaitingForChunks:I",
            opcode = Opcodes.GETFIELD))
    private int neverWaitForChunks(Train train) {
        // Sempre devolver -1 = "não há chunk waiting" → speed nunca é zerada
        // Se o campo estava set, limpá-lo para não acumular estado stale
        if (train.carriageWaitingForChunks != -1) {
            train.carriageWaitingForChunks = -1;
        }
        return -1;
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTickHead(Level level, CallbackInfo ci) {
        if (collisionTickCounter == -1) {
            collisionTickCounter = Math.abs(id.hashCode()) % COLLISION_CHECK_NORMAL;
        }
        collisionTickCounter++;
    }

    @Inject(method = "collideWithOtherTrains", at = @At("HEAD"), cancellable = true)
    private void throttleCollisionCheck(Level level, Carriage carriage, CallbackInfo ci) {
        int interval = getAdaptiveInterval();

        if (collisionTickCounter % interval != 0) {
            ci.cancel();
        }
    }

    @Unique
    private int getAdaptiveInterval() {
        CreateOptimizedTrains mod = CreateOptimizedTrains.getInstance();
        if (mod == null) return COLLISION_CHECK_NORMAL;

        PerformanceMonitor monitor = mod.getPerformanceMonitor();
        if (monitor == null) return COLLISION_CHECK_NORMAL;

        return switch (monitor.getState()) {
            case NORMAL -> COLLISION_CHECK_NORMAL;
            case DEGRADED -> COLLISION_CHECK_DEGRADED;
            case CRITICAL -> COLLISION_CHECK_CRITICAL;
        };
    }
}
