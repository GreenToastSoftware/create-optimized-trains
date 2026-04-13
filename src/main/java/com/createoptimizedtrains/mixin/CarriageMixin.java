package com.createoptimizedtrains.mixin;

import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageEntityHandler;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
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
        CarriageContraptionEntity entity = anyAvailableEntity();
        if (entity != null && !entity.isAlive()) {
            ci.cancel();
        }
    }

    // ======== Fix 2: Entity Creation in Loaded Chunks ========

    /**
     * Redirect isActiveChunk in manageEntities to also accept force-loaded/loaded chunks.
     * This allows entity creation earlier (when chunk data is in memory but not yet
     * entity-ticking), spreading the rendering initialization over more ticks instead
     * of all carriages spawning in the same tick.
     */
    @Redirect(method = "manageEntities",
        at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/content/trains/entity/CarriageEntityHandler;isActiveChunk(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean isActiveChunkOrLoadedInManage(Level level, BlockPos pos) {
        if (CarriageEntityHandler.isActiveChunk(level, pos)) {
            return true;
        }
        // Accept force-loaded chunks (our ChunkLoadManager pre-loads these)
        if (level instanceof ServerLevel serverLevel) {
            long packed = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
            if (serverLevel.getForcedChunks().contains(packed)) {
                return true;
            }
        }
        return false;
    }

    // ======== Fix 3: Chunk Boundary Grace Period ========

    @Unique
    private final Map<Integer, Integer> chunkGraceMap = new HashMap<>();

    /**
     * Período de graça antes de permitir remoção. 60 ticks = 3 segundos.
     * Muito mais longo que antes (6 ticks) porque o comboio agora NUNCA para
     * à espera de chunks (carriageWaitingForChunks está neutralizado).
     * O RouteChunkPreloader + ChunkLoadManager quase sempre carregam o chunk
     * antes de 3 segundos. Se não carregar, a remoção acontece mas o comboio
     * recria a entidade imediatamente a seguir (sem parar).
     *
     * NOTA: A entidade pode flutuar no vazio durante este período — é preferível
     * a parar o comboio.
     */
    @Unique
    private static final int CHUNK_ENTITY_GRACE_TICKS = 60;

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

        if (ticks <= CHUNK_ENTITY_GRACE_TICKS) {
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
