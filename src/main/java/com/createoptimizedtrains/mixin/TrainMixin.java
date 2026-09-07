package com.createoptimizedtrains.mixin;

import com.createoptimizedtrains.CreateOptimizedTrains;
import com.createoptimizedtrains.config.ModConfig;
import com.createoptimizedtrains.diagnostics.TrainDiagnosticLogger;
import com.createoptimizedtrains.monitor.PerformanceMonitor;
import com.createoptimizedtrains.util.PlayerTrainTracker;
import com.createoptimizedtrains.util.CarriageUtils;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageBogey;
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

import java.util.List;
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

    @Shadow
    public List<Carriage> carriages;

    /**
     * Valor real de carriageWaitingForChunks capturado no redirect antes de ser
     * limpo para -1. Usado pelo logger de diagnóstico para saber se o Create
     * tentou parar o comboio por chunk não carregado naquele tick.
     * O valor reflecte o estado do tick ANTERIOR (capturado quando o redirect
     * intercepta o GETFIELD; o HEAD inject lê este valor no tick seguinte).
     */
    @Unique
    private int lastRealWaitingForChunks = -1;

    @Unique
    private int collisionTickCounter = -1;

    /**
     * Contador de imunidade contra derailing falso.
     * Quando uma carruagem PERDE a sua entidade numa fronteira de chunk,
     * getAnchorDiff() pode retornar 0 por 1-5 ticks enquanto a entidade é recriada:
     *   stress = bogeySpacing - 0 = bogeySpacing (tipicamente >4)
     *   → speed=0, derailed=true (falso positivo transitório)
     * Durante 5 ticks de imunidade, suprimimos a contribuição de stress falso.
     *
     * IMPORTANTE: A imunidade só é activada quando o count de entidades ausentes
     * AUMENTA (nova perda de entidade). Comboios em zonas distantes que SEMPRE
     * tiveram todas as carruagens sem entidade NÃO ativam imunidade (o count
     * é estável) — isto evita imunidade permanente que mascarava o estado real.
     */
    @Unique
    private int missingEntityImmuneTicks = 0;

    /**
     * Número de carruagens sem entidade no tick anterior.
     * Usado para detetar NOVA perda de entidade (count aumentou) vs estado estável.
     * -1 no primeiro tick (valor inicial desconhecido).
     */
    @Unique
    private int prevMissingEntityCount = -1;

    @Unique
    private static final int COLLISION_CHECK_NORMAL = 2;
    @Unique
    private static final int COLLISION_CHECK_DEGRADED = 4;
    @Unique
    private static final int COLLISION_CHECK_CRITICAL = 8;
    @Unique
    private static final double NEAR_PLAYER_RADIUS = 96.0;

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
        // Capturar valor real ANTES de limpar, para o logger de diagnóstico.
        // O logger lê lastRealWaitingForChunks no próximo tick (1 tick de desfasamento
        // aceitável para fins de diagnóstico).
        lastRealWaitingForChunks = train.carriageWaitingForChunks;

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

        // Detetar carruagens sem entidade e atualizar imunidade contra derailing falso.
        //
        // LÓGICA: Imunidade ativa APENAS quando o count de carruagens sem entidade
        // AUMENTA (nova perda). Comboios distantes com TODAS as carruagens sempre
        // ausentes têm count estável → imunidade fica em 0 → sem supressão de stress
        // permanente. O DCE mantém posições cacheadas correctas mesmo sem entidade
        // viva, por isso getAnchorDiff() funciona bem para carruagens distantes.
        //
        // Fronteira de chunk (entidade removida repentinamente):
        //   prevCount=0 → currentCount=1 → 1>0 → immune=5 ✓
        // Comboio distante (sempre sem entidade, count estável):
        //   prevCount=11 → currentCount=11 → 11>11=false → immune não resetado ✓
        // Arranque do mundo (primeiro tick, count desconhecido → prevCount=-1):
        //   prevCount=-1 < 0 → immune não ativado ✓
        int currentMissingCount = 0;
        if (carriages != null) {
            for (Carriage carriage : carriages) {
                if (CarriageUtils.safeAnyAvailableEntity(carriage) == null) {
                    currentMissingCount++;
                }
            }
        }

        // Imunidade contra derailing falso:
        //   • Comboio em movimento com qualquer entidade ausente → imune (fronteira de chunk)
        //     A imunidade mantém-se enquanto o comboio se mover E tiver entidades em falta.
        //     getAnchorDiff() devolve 0 ao perder entidade → stress falso → train para.
        //     Com imunidade ativa, suppressFalseAnchorStress devolve 1000 → sem stress falso.
        //   • Comboio parado com nova perda de entidade → 5 ticks de imunidade (graça de recuperação)
        //   • Comboio parado com count estável → imunidade 0 → comportamento normal do Create
        boolean isMoving = Math.abs(speed) > 0.01;
        if (isMoving && currentMissingCount > 0) {
            // Comboio em movimento com entidades ausentes: manter imunidade ativa
            if (missingEntityImmuneTicks < 5) missingEntityImmuneTicks = 5;
        } else if (prevMissingEntityCount >= 0 && currentMissingCount > prevMissingEntityCount) {
            // Nova perda de entidade (comboio parado ou em movimento): 5 ticks de graça
            missingEntityImmuneTicks = 5;
        } else if (missingEntityImmuneTicks > 0) {
            missingEntityImmuneTicks--;
        }
        prevMissingEntityCount = currentMissingCount;

        // Logging de diagnóstico: regista o estado do comboio para ajudar a perceber
        // porque certos comboios têm engasgos e outros não.
        // lastRealWaitingForChunks é o valor capturado pelo redirect no tick anterior.
        try {
            boolean diagEnabled = ModConfig.DIAGNOSTIC_LOGGING.get();
            int diagInterval = ModConfig.DIAGNOSTIC_LOG_INTERVAL.get();
            TrainDiagnosticLogger.tick(
                    id, speed, carriages,
                    lastRealWaitingForChunks, missingEntityImmuneTicks,
                    diagEnabled, diagInterval
            );
        } catch (Exception ignored) {
            // Config ainda não inicializada (fase de carregamento do mod)
        }
    }

    @Inject(method = "collideWithOtherTrains", at = @At("HEAD"), cancellable = true)
    private void throttleCollisionCheck(Level level, Carriage carriage, CallbackInfo ci) {
        // Perfil estável (1.2): usar intervalo adaptativo normal.
        // O modo agressivo 1.3 (dobrar intervalo para comboios não-ocupados)
        // só é aplicado quando explicitamente ativado na config.
        int interval;
        boolean aggressiveMode;
        try {
            aggressiveMode = ModConfig.AGGRESSIVE_OTHER_TRAINS_THROTTLE.get();
        } catch (Exception e) {
            aggressiveMode = false;
        }

        if (aggressiveMode && !PlayerTrainTracker.isOccupied(this.id) && !isNearAnyPlayer(carriage)) {
            // Comboio sem jogador: colisão muito menos frequente
            interval = getAdaptiveInterval() * 2; // Dobro do intervalo normal
        } else {
            interval = getAdaptiveInterval();
        }

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

    /**
     * Suprime a contribuição de anchorDiff ao stress do comboio durante o período
     * de imunidade (entidade ausente/a ser recriada).
     *
     * Cálculo original: stress = max(prevStress, bogeySpacing - getAnchorDiff())
     * Se getAnchorDiff() == 0 (entidade ausente): stress = bogeySpacing (tipicamente >4)
     * → Train.tick() faz speed=0 e derailed=true erroneamente por 1 tick.
     *
     * Correcção: devolver 1000.0 → bogeySpacing - 1000 = valor negativo
     * → Math.max(prevStress, negativo) = prevStress (sem nova contribuição de stress)
     * O comboio não fica com speed=0 por este motivo durante a imunidade.
     */
    @Redirect(method = "tick",
        at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/content/trains/entity/Carriage;getAnchorDiff()D"))
    private double suppressFalseAnchorStress(Carriage carriage) {
        if (missingEntityImmuneTicks > 0) {
            // bogeySpacing - 1000 < 0 → Math.max não aumenta o stress acumulado
            return 1000.0;
        }
        return carriage.getAnchorDiff();
    }

    /**
     * Suprime a contribuição de bogey stress durante o período de imunidade.
     * Complementa suppressFalseAnchorStress para garantir que o stress total
     * permanece abaixo de 4.0 enquanto entidades estão a ser recriadas.
     */
    @Redirect(method = "tick",
        at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/content/trains/entity/CarriageBogey;getStress()D"))
    private double suppressFalseBogeyStress(CarriageBogey bogey) {
        if (missingEntityImmuneTicks > 0) {
            return 0.0;
        }
        return bogey.getStress();
    }

    @Unique
    private boolean isNearAnyPlayer(Carriage carriage) {
        if (carriage == null) return false;
        var entity = CarriageUtils.safeAnyAvailableEntity(carriage);
        if (entity == null) return false;
        return PlayerTrainTracker.isPlayerNear(entity, NEAR_PLAYER_RADIUS);
    }
}
