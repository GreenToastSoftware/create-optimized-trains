package com.createoptimizedtrains.diagnostics;

import com.createoptimizedtrains.CreateOptimizedTrains;
import com.createoptimizedtrains.util.CarriageUtils;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Logger de diagnóstico para comboios do Create.
 * Regista no log do servidor informações detalhadas sobre cada comboio,
 * ajudando a identificar porque certos comboios se movem suavemente e outros não.
 *
 * === Anomalias detectadas ===
 *
 *  CHUNK_WAIT_ID=N  → Create tentou parar o comboio à espera de chunk (carruagem ID N)
 *                     Corrigido pela nossa redirect neverWaitForChunks, mas indica que
 *                     os chunks ainda não estão entity-ticking ao ser interceptado.
 *
 *  IMMUNE=Nt        → Imunidade contra derailing falso activa por N ticks.
 *                     Ocorre quando uma carruagem ficou sem entidade e foi recriada.
 *                     Durante este período suprimimos stress falso (bogeySpacing - 0 > 4).
 *
 *  SEM_ENTIDADE=N   → N carruagens sem CarriageContraptionEntity disponível.
 *                     A entidade foi removida (grace period expirou sem chunk carregar)
 *                     e ainda não foi recriada. Causa principal de engasgos.
 *
 *  FORA_CHUNK=N     → N carruagens com entidade em secção não-entity-ticking.
 *                     leftTickingChunks=true: grace period ativo, entidade temporariamente
 *                     fora do seu chunk. Chunks sendo carregados (normal, passageiro).
 *
 *  PARADO_SINAL=N   → N carruagens com stalled=true (sinal vermelho / sched. stop).
 *                     O comboio está a parar intencionalmente por sinal ou horário.
 *                     Não é um bug, mas pode parecer engasgo se o sinal for breve.
 *
 *  BLOQUEADO=N      → N carruagens com blocked=true (fim de via / track incompatível).
 *                     O Create quer parar o comboio porque não há via a seguir.
 *                     Verificar se a via está corretamente ligada.
 *
 * === Formato no log ===
 *   [COT] STUTTER   Train abc12345 vel=18.4b/s | carruagens=4 | SEM_ENTIDADE=1 | FORA_CHUNK=1
 *   [COT] SMOOTH    Train abc12345 vel=18.4b/s | carruagens=4
 *
 * STUTTER = comboio em movimento com anomalia detectada (nível WARN)
 * SMOOTH  = comboio a funcionar normalmente (nível INFO, menos frequente)
 */
public class TrainDiagnosticLogger {

    // Intervalos de log (em ticks)
    // Anomalia: log imediato na primeira ocorrência, depois a cada 20 ticks (1 segundo)
    // Normal: log a cada N ticks configurável (padrão 200 = 10 segundos)
    // Loading (zona distante): log apenas uma vez cada 2000 ticks (~100 segundos)
    private static final int ANOMALY_REPEAT_INTERVAL = 20;
    private static final int LOADING_LOG_INTERVAL    = 2000;

    // Estado persistente por comboio (limpado quando comboio é descarregado)
    private static final Map<UUID, Integer> normalTickCount  = new HashMap<>();
    private static final Map<UUID, Integer> anomalyTickCount = new HashMap<>();
    private static final Map<UUID, Boolean> prevWasAnomalous = new HashMap<>();

    /**
     * Chamado a cada tick por TrainMixin para registar o estado do comboio.
     *
     * @param trainId          UUID do comboio
     * @param speed            Velocidade actual (em blocos/tick; ×20 = blocos/segundo)
     * @param carriages        Lista de carruagens do comboio
     * @param realChunkWaitId  Valor real de carriageWaitingForChunks antes da nossa redirect
     *                         (-1 = nenhuma carruagem à espera de chunk)
     * @param immuneTicks      Ticks de imunidade restantes contra derailing falso
     * @param enabled          Se o logging está activo (da config)
     * @param normalInterval   Ticks entre logs de estado normal (da config)
     */
    public static void tick(
            UUID trainId,
            double speed,
            List<Carriage> carriages,
            int realChunkWaitId,
            int immuneTicks,
            boolean enabled,
            int normalInterval
    ) {
        if (!enabled || !DebugLog.ENABLED) return;
        if (carriages == null || carriages.isEmpty()) return;

        boolean isMoving = Math.abs(speed) > 0.001;

        // Recolher estado de cada carruagem neste tick
        int missingEntity = 0;
        int leftTicking   = 0;
        int stalled       = 0;
        int blocked       = 0;

        for (Carriage c : carriages) {
            CarriageContraptionEntity entity = CarriageUtils.safeAnyAvailableEntity(c);
            if (entity == null) {
                missingEntity++;
            } else if (entity.leftTickingChunks) {
                leftTicking++;
            }
            if (c.stalled) stalled++;
            if (c.blocked) blocked++;
        }

        // Classificar o estado do comboio
        //
        // LOADING: TODAS as carruagens sem entidade E sem imunidade activa.
        //   → Comboio em zona distante, nunca teve entidades nesta sessão.
        //   → Não causa stutter visual (jogador não está perto para ver).
        //   → Imunidade == 0 confirma que não houve perda RECENTE (count estável).
        //
        // STUTTER: Em movimento com ALGUMA anomalia que pode causar stutter visível.
        //   → SEM_ENTIDADE parcial (chunk boundary), FORA_CHUNK, stalled, blocked, etc.
        //   → Inclui caso onde IMMUNE>0: entidade foi recém perdida numa zona próxima.
        //
        // SMOOTH: Comboio sem anomalias ou parado.
        boolean allMissing     = (missingEntity == carriages.size());
        boolean isLoadingTrain = allMissing && immuneTicks == 0;  // distante, count estável

        boolean hasAnomaly = isMoving && !isLoadingTrain && (
                realChunkWaitId != -1 ||
                immuneTicks     >   0 ||
                missingEntity   >   0 ||
                leftTicking     >   0 ||
                stalled         >   0 ||
                blocked         >   0
        );

        boolean prevAnomaly = prevWasAnomalous.getOrDefault(trainId, false);
        prevWasAnomalous.put(trainId, hasAnomaly);

        // Comboios em zona distante (LOADING): log muito raro para não poluir
        if (isLoadingTrain) {
            int nTick = normalTickCount.merge(trainId, 1, Integer::sum);
            if (nTick % LOADING_LOG_INTERVAL == 1) {
                String trainShort = trainId.toString().substring(0, 8);
                double speedBs    = speed * 20.0;
                CreateOptimizedTrains.LOGGER.info(
                        "[COT] LOADING   Train {}  vel={}b/s  carruagens={} | SEM_ENTIDADE={} [zona não carregada]",
                        trainShort, String.format("%.1f", speedBs), carriages.size(), missingEntity);
            }
            return;
        }

        // Decidir se é altura de escrever no log
        boolean shouldLog = false;

        if (hasAnomaly) {
            int aTick = anomalyTickCount.merge(trainId, 1, Integer::sum);
            // Log imediato na primeira anomalia; depois a cada ANOMALY_REPEAT_INTERVAL ticks
            if (!prevAnomaly || aTick % ANOMALY_REPEAT_INTERVAL == 0) {
                shouldLog = true;
            }
        } else {
            anomalyTickCount.remove(trainId);
            int nTick = normalTickCount.merge(trainId, 1, Integer::sum);
            // Log uma vez quando anomalia termina; depois ao ritmo configurado
            if (prevAnomaly || nTick % Math.max(1, normalInterval) == 0) {
                shouldLog = true;
            }
        }

        if (!shouldLog) return;

        // Construir linha de log legível
        String trainShort = trainId.toString().substring(0, 8);
        double speedBs    = speed * 20.0;

        StringBuilder sb = new StringBuilder();
        sb.append(hasAnomaly ? "STUTTER  " : "SMOOTH   ");
        sb.append("Train ").append(trainShort);
        sb.append("  vel=").append(String.format("%.1f", speedBs)).append("b/s");
        if (!isMoving) sb.append(" [parado]");
        sb.append("  carruagens=").append(carriages.size());

        // Anomalias (por ordem de impacto)
        if (realChunkWaitId != -1) sb.append(" | CHUNK_WAIT_ID=").append(realChunkWaitId);
        if (immuneTicks      >  0) sb.append(" | IMMUNE=").append(immuneTicks).append("t");
        if (missingEntity    >  0) sb.append(" | SEM_ENTIDADE=").append(missingEntity);
        if (leftTicking      >  0) sb.append(" | FORA_CHUNK=").append(leftTicking);
        if (stalled          >  0) sb.append(" | PARADO_SINAL=").append(stalled);
        if (blocked          >  0) sb.append(" | BLOQUEADO=").append(blocked);

        if (hasAnomaly) {
            CreateOptimizedTrains.LOGGER.warn("[COT] {}", sb);
        } else {
            CreateOptimizedTrains.LOGGER.info("[COT] {}", sb);
        }
    }

    /**
     * Remove o estado de diagnóstico de um comboio (chamado quando é descarregado).
     * Evita que a Map cresça indefinidamente em sessões longas.
     */
    public static void unregister(UUID trainId) {
        normalTickCount.remove(trainId);
        anomalyTickCount.remove(trainId);
        prevWasAnomalous.remove(trainId);
    }
}
