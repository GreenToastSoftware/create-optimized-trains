package com.createoptimizedtrains.mixin;

import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
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

import java.util.HashMap;
import java.util.Map;

/**
 * Mixin na Carriage do Create para:
 * 1. Corrigir detecção falsa de condutores ausentes (entidades mortas na WeakReference)
 * 2. Prevenir blinks visuais quando carruagens atravessam fronteiras de chunks
 *
 * === Fix 1: Conductor Detection ===
 * Carriage.updateConductors() usa anyAvailableEntity() que retorna entidades mortas
 * (isAlive=false) da WeakReference. Uma entidade morta passa a guarda null, reseta
 * presentConductors para {false, false}, mas depois é filtrada por isAlive() na
 * iteração interna → false positivo de "sem condutor".
 *
 * === Fix 2: Chunk Boundary Grace Period (SEM parar o comboio) ===
 * Quando uma carruagem entra num chunk section não-ticked (fronteira de view distance),
 * CarriageEntityHandler marca leftTickingChunks=true imediatamente.
 * manageEntities() vê o flag e chama removeAndSaveEntity() → entidade destruída.
 * Quando o chunk carrega (1-2 ticks depois), createEntity() recria a entidade →
 * o cliente vê despawn+respawn = BLINK visual.
 *
 * CORREÇÃO v1.1.1: NÃO usamos mais carriageWaitingForChunks pois isso para
 * o comboio (speed=0) e causa o stutter de ~1 segundo. Em vez disso:
 * - Retardamos a remoção da entidade por um período curto (6 ticks = 300ms)
 * - O comboio CONTINUA A ANDAR durante o grace period
 * - Se o chunk carregar nesse tempo, a entidade sobrevive sem blink
 * - Se não carregar, a remoção normal acontece (despawn+respawn, mas raro
 *   porque o ChunkLoadManager pré-carrega chunks à frente)
 */
@Mixin(value = Carriage.class, remap = false)
public abstract class CarriageMixin {

    @Shadow
    public Train train;

    @Shadow
    public int id;

    @Shadow
    public abstract CarriageContraptionEntity anyAvailableEntity();

    // ======== Fix 1: Conductor Detection ========

    @Inject(method = "updateConductors", at = @At("HEAD"), cancellable = true)
    private void preserveConductorsIfEntityUnavailable(CallbackInfo ci) {
        CarriageContraptionEntity entity;
        try {
            entity = anyAvailableEntity();
        } catch (java.util.ConcurrentModificationException ignored) {
            return; // mapa entities modificado concorrentemente; saltar esta verificação
        }
        if (entity != null && !entity.isAlive()) {
            ci.cancel();
        }
    }

    // ======== Fix 2: Chunk Boundary Grace Period ========
    //
    // NOTA IMPORTANTE: NÃO redirecionamos isActiveChunk em manageEntities para aceitar
    // chunks force-loaded (não entity-ticking). Fazer isso causaria o bug de camera shake:
    // entidades criadas em chunks não-entity-ticking não são tracked pelo EntityTracker,
    // portanto as suas posições não são sincronizadas ao cliente. O jogador sentado dentro
    // ficaria a ver a carruagem na posição antiga enquanto o servidor já a moveu → shake.
    // A combinação do grace period abaixo + ChunkLoadManager (priority loading) é suficiente.
    //
    // Fluxo correto:
    //   1. ChunkLoadManager force-load o chunk da carruagem (incluindo priority para
    //      carruagens com jogadores, sem rate limit)
    //   2. O chunk transita para entity-ticking em 1-3 ticks
    //   3. manageEntities() vê isActiveChunk=true → cria entidade em chunk entity-ticking
    //   4. EntityTracker sincroniza posição ao cliente corretamente
    //   5. Jogador sentado: posição atualizada, sem shake
    //
    // Durante os 1-3 ticks de transição: grace period impede remoção prematura,
    // e alignEntity() mantém a posição do servidor atualizada.

    @Unique
    private final Map<Integer, Integer> chunkGraceMap = new HashMap<>();

    /**
     * Período de graça antes de permitir remoção da entidade.
     *
     * MOVING (>0.001 blocks/tick): 10 ticks = 500ms
     *   O ChunkLoadManager pré-carrega chunks à frente com prioridade;
     *   chunks force-loaded ficam entity-ticking em 1-5 ticks normalmente.
     *   10 ticks cobre 99%+ dos casos sem entidade a ser removida/recriada.
     *   Reduzido de 20→5 na v1.3.1 causou mais engasgos porque 5 ticks é
     *   insuficiente em servidores carregados — entidades eram removidas e
     *   recriadas frequentemente, causando derailing falso por AnchorDiff=0.
     *   10 ticks é o equilíbrio: chunks carregam a tempo, JourneyMap drift
     *   máximo de 10 blocos (vs 20 antes, vs stutter constante com 5).
     *
     * STOPPED (speed≈0): 20 ticks = 1 segundo
     *   Comboios parados não causam lag de posição visível; o grace longo
     *   previne destroys/spawns desnecessários por variações de chunk ticking.
     */
    @Unique
    private static final int CHUNK_ENTITY_GRACE_TICKS_MOVING = 10;
    @Unique
    private static final int CHUNK_ENTITY_GRACE_TICKS_STOPPED = 20;

    /**
     * Redirect na leitura do campo leftTickingChunks dentro de manageEntities().
     *
     * A condição original é: if (!isAlive || leftTickingChunks || shouldDiscard) → remove
     * O nosso redirect intercepta APENAS a leitura de leftTickingChunks.
     *
     * Diferença da v1.0.0: NÃO definimos carriageWaitingForChunks — o comboio não para.
     */
    @Redirect(method = "manageEntities",
        at = @At(value = "FIELD",
                 target = "Lcom/simibubi/create/content/trains/entity/CarriageContraptionEntity;leftTickingChunks:Z",
                 opcode = Opcodes.GETFIELD))
    private boolean delayChunkBoundaryRemoval(CarriageContraptionEntity entity) {
        if (!entity.leftTickingChunks) {
            // Entidade em chunk activo — limpar qualquer grace period anterior
            int entityId = entity.getId();
            if (chunkGraceMap.remove(entityId) != null) {
                // Estava em grace period e o chunk carregou a tempo — sucesso!
                // Se NÓS tínhamos definido carriageWaitingForChunks, limpar
                if (train.carriageWaitingForChunks == id) {
                    train.carriageWaitingForChunks = -1;
                }
            }
            return false;
        }

        // Entidade saiu de chunk com entity-ticking
        int entityId = entity.getId();
        int ticks = chunkGraceMap.getOrDefault(entityId, 0) + 1;
        chunkGraceMap.put(entityId, ticks);

        // Grace period adaptativo: curto para comboios em movimento, longo para parados.
        // Para comboios em movimento, o cliente NÃO recebe atualizações de posição enquanto
        // a entidade está em secção não-entity-ticking → congelamento visível no JourneyMap.
        // 5 ticks são suficientes para o ChunkLoadManager tornar o chunk entity-ticking.
        boolean isMoving = train != null && Math.abs(train.speed) > 0.001;
        int graceTicks = isMoving ? CHUNK_ENTITY_GRACE_TICKS_MOVING : CHUNK_ENTITY_GRACE_TICKS_STOPPED;

        if (ticks <= graceTicks) {
            // Grace period ativo — manter entidade viva SEM parar o comboio
            // O comboio continua a andar normalmente enquanto esperamos que o
            // chunk carregue (o ChunkLoadManager pré-carrega chunks à frente)
            return false;
        }

        // Período expirado: chunk não carregou em 300ms — permitir remoção normal
        chunkGraceMap.remove(entityId);
        // Limpar wait flag se ainda estiver definido de versões anteriores
        if (train.carriageWaitingForChunks == id) {
            train.carriageWaitingForChunks = -1;
        }
        return true;
    }
}
