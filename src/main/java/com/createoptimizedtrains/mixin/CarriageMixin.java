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
 * === Fix 2: Chunk Boundary Grace Period ===
 * Quando uma carruagem entra num chunk section não-ticked (fronteira de view distance),
 * CarriageEntityHandler marca leftTickingChunks=true imediatamente.
 * manageEntities() vê o flag e chama removeAndSaveEntity() → entidade destruída.
 * Quando o chunk carrega (1-2 ticks depois), createEntity() recria a entidade →
 * o cliente vê despawn+respawn = BLINK visual.
 *
 * Para o jogador a andar no comboio, isto manifesta-se como:
 * - Carruagem da frente desaparece e reaparece (piscar)
 * - Se a remoção/recriação é rápida demais, o jogador pode cair no void brevemente
 * - Chunks em redor podem não estar carregados quando o jogador é reposicionado
 *
 * Correcção: Interceptar a leitura de leftTickingChunks em manageEntities() via
 * @Redirect. Durante um período de graça (15 ticks = 750ms), retornar false
 * para evitar a remoção da entidade. Simultaneamente, parar o comboio
 * (via carriageWaitingForChunks) para dar tempo aos chunks de carregar.
 * Se após o período o chunk ainda não é activo, permitir remoção normal.
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

    /**
     * Cancelar updateConductors() quando a entidade existe mas está morta.
     * Preserva os presentConductors do tick anterior em vez de resetar.
     */
    @Inject(method = "updateConductors", at = @At("HEAD"), cancellable = true)
    private void preserveConductorsIfEntityUnavailable(CallbackInfo ci) {
        CarriageContraptionEntity entity = anyAvailableEntity();
        if (entity != null && !entity.isAlive()) {
            ci.cancel();
        }
    }

    // ======== Fix 2: Chunk Boundary Grace Period ========

    /**
     * Mapa de entity ID → ticks em estado leftTickingChunks.
     * Rastreia quanto tempo cada entidade de carruagem está numa secção não-ticked.
     */
    @Unique
    private final Map<Integer, Integer> chunkGraceMap = new HashMap<>();

    /**
     * Período de graça antes de permitir remoção de entidades que saíram de
     * chunks com entity-ticking activo. 15 ticks = 750ms.
     *
     * Porquê 15 ticks:
     * - O servidor envia chunks com ~2-4 ticks de delay entre cada
     * - Para chunks adjacentes à view distance, 750ms cobre a maioria dos cenários
     * - Suficientemente curto para não causar stall perceptível
     * - Suficientemente longo para cobrir loading de chunks a ~1.2 bl/tick
     */
    @Unique
    private static final int CHUNK_ENTITY_GRACE_TICKS = 15;

    /**
     * Redirect na leitura do campo leftTickingChunks dentro de manageEntities().
     *
     * No bytecode de Carriage.manageEntities(), o fluxo relevante é:
     *   offset 261: CarriageEntityHandler.validateCarriageEntity(entity)
     *              → pode marcar entity.leftTickingChunks = true
     *   offset 268: entity.isAlive()
     *   offset 276: entity.leftTickingChunks  ← ESTE GETFIELD é interceptado
     *   offset 287: dce.removeAndSaveEntity(entity, shouldDiscard)
     *
     * A condição original é: if (!isAlive || leftTickingChunks || shouldDiscard) → remove
     * O nosso redirect intercepta APENAS a leitura de leftTickingChunks (offset 276).
     * isAlive e shouldDiscard continuam a funcionar normalmente.
     *
     * @param entity a CarriageContraptionEntity cujo leftTickingChunks seria lido
     * @return false durante período de graça (previne remoção), true após expirar
     */
    @Redirect(method = "manageEntities",
        at = @At(value = "FIELD",
                 target = "Lcom/simibubi/create/content/trains/entity/CarriageContraptionEntity;leftTickingChunks:Z",
                 opcode = Opcodes.GETFIELD))
    private boolean delayChunkBoundaryRemoval(CarriageContraptionEntity entity) {
        if (!entity.leftTickingChunks) {
            // Entidade em chunk activo — limpar qualquer grace period anterior
            chunkGraceMap.remove(entity.getId());
            return false;
        }

        // Entidade saiu de chunk com entity-ticking
        int entityId = entity.getId();
        int ticks = chunkGraceMap.getOrDefault(entityId, 0) + 1;
        chunkGraceMap.put(entityId, ticks);

        if (ticks <= CHUNK_ENTITY_GRACE_TICKS) {
            // Período de graça: manter entidade viva + parar comboio
            // O comboio para para não avançar mais para território não carregado
            // e para dar tempo ao servidor de enviar chunks ao cliente
            train.carriageWaitingForChunks = id;
            return false;
        }

        // Período expirado: chunk não carregou em 750ms — permitir remoção normal
        chunkGraceMap.remove(entityId);
        // Limpar o nosso wait flag para não bloquear o comboio
        if (train.carriageWaitingForChunks == id) {
            train.carriageWaitingForChunks = -1;
        }
        return true;
    }
}
