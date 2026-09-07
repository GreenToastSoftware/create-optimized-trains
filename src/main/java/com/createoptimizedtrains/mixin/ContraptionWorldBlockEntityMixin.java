package com.createoptimizedtrains.mixin;

import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.ContraptionWorld;
import com.createoptimizedtrains.diagnostics.DebugLog;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Injecta em Level (onde getBlockEntity é definido) com instanceof ContraptionWorld.
 * Targeting ContraptionWorld directamente falha silenciosamente (método herdado +
 * defaultRequire:0 no mixin config).
 */
@Mixin(value = Level.class, priority = 900)
public abstract class ContraptionWorldBlockEntityMixin {

    @Unique
    private static final Logger COT_BE_LOGGER = LogManager.getLogger("COT/ContraptionWorldBE");
    @Unique
    private static final AtomicLong cot$callCount = new AtomicLong(0);
    @Unique
    private static final AtomicLong cot$nullInfoCount = new AtomicLong(0);
    @Unique
    private static volatile long cot$lastLog = 0;

    @Unique
    private static volatile Field cot$contraptionField = null;
    @Unique
    private static volatile boolean cot$fieldLookupDone = false;

    @Unique
    private Map<BlockPos, Optional<BlockEntity>> cot$beCache;

    @Unique
    private static Contraption cot$getContraption(ContraptionWorld cw) {
        if (!cot$fieldLookupDone) {
            try {
                Field f = ContraptionWorld.class.getDeclaredField("contraption");
                f.setAccessible(true);
                cot$contraptionField = f;
            } catch (NoSuchFieldException e) {
                COT_BE_LOGGER.error("[COT] ContraptionWorldBE: campo 'contraption' não encontrado!", e);
            }
            cot$fieldLookupDone = true;
        }
        if (cot$contraptionField == null) return null;
        try {
            return (Contraption) cot$contraptionField.get(cw);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    @Inject(
        method = {"getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;", "m_7702_"},
        at = @At("HEAD"),
        cancellable = true
    )
    private void cot$localBlockEntityLookup(BlockPos pos,
                                              CallbackInfoReturnable<BlockEntity> cir) {
        // Filtro rápido: só actua em ContraptionWorld
        if (!(Object.class.cast(this) instanceof ContraptionWorld cw)) return;

        long calls = cot$callCount.incrementAndGet();
        long now = System.currentTimeMillis();
        if (DebugLog.ENABLED && now - cot$lastLog > 10_000) {
            cot$lastLog = now;
            long nulls = cot$nullInfoCount.getAndSet(0);
            COT_BE_LOGGER.info("[COT] ContraptionWorldBE mixin ATIVO: {} chamadas/10s, {} sem info local",
                calls, nulls);
            cot$callCount.set(0);
        }

        Contraption contraption = cot$getContraption(cw);
        if (contraption == null) return;

        // Lazy: reconstrói on-demand com cache por-instância. O precompute eager em
        // readNBT foi removido — disparava dezenas de tarefas assíncronas em paralelo
        // durante o load do mundo (uma por contraption), esgotando o heap quase
        // instantaneamente com mapas grandes carregados todos ao mesmo tempo.
        StructureTemplate.StructureBlockInfo info = contraption.getBlocks().get(pos);
        if (info == null) {
            cot$nullInfoCount.incrementAndGet();
            cir.setReturnValue(null);
            return;
        }

        BlockState state = info.state();
        if (!state.hasBlockEntity()) {
            cir.setReturnValue(null);
            return;
        }

        if (cot$beCache == null) {
            cot$beCache = new HashMap<>();
        }

        Optional<BlockEntity> cached = cot$beCache.get(pos);
        if (cached != null) {
            cir.setReturnValue(cached.orElse(null));
            return;
        }

        CompoundTag nbt = info.nbt();
        if (nbt == null) {
            cot$beCache.put(pos, Optional.empty());
            cir.setReturnValue(null);
            return;
        }

        BlockEntity be = BlockEntity.loadStatic(pos, state, nbt);
        cot$beCache.put(pos, Optional.ofNullable(be));
        cir.setReturnValue(be);
    }
}
