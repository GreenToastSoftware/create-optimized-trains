package com.createoptimizedtrains.mixin;

import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageEntityHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mixin on Carriage$DimensionalCarriageEntity to prevent trains from stopping
 * when passing chunk boundaries at high speed.
 *
 * Root cause: In alignEntity(), Create projects a point 16 blocks ahead of the
 * carriage entity and checks if that chunk is "active" (entity-ticking status).
 * If any passenger is a Player within 32 blocks and the projected chunk is NOT
 * entity-ticking, Create sets train.carriageWaitingForChunks = carriageId,
 * which makes Train.tick() set speed = 0 → the train stops for ~1 second.
 *
 * This happens even when the chunk IS force-loaded by our ChunkLoadManager,
 * because there's a pipeline delay (1-3 ticks) between setChunkForced() and the
 * chunk reaching entity-ticking status. At 40 blocks/second, the train moves
 * 2-6 blocks during this delay — enough to trigger the stop.
 *
 * Fix: Redirect the isActiveChunk call to also accept chunks that are loaded
 * in memory (hasChunkAt). For force-loaded chunks, this returns true as soon as
 * chunk data is in memory (before entity-ticking status is set). The train
 * continues moving and the chunk reaches entity-ticking within 1-3 ticks.
 */
@Mixin(value = Carriage.DimensionalCarriageEntity.class, remap = false)
public class DimensionalCarriageEntityMixin {

    /**
     * Redirect the isActiveChunk check in alignEntity to ALWAYS return true.
     *
     * Since we completely bypass carriageWaitingForChunks in TrainMixin (always -1),
     * the train never stops. But alignEntity still returns early when this check
     * fails, skipping setPos/yaw/pitch updates. By always returning true,
     * the alignment continues and the entity position stays correct even in
     * chunks that haven't reached entity-ticking status.
     *
     * The entity might be in a visually unloaded chunk (floating in void),
     * but its server-side position is always accurate.
     */
    @Redirect(method = "alignEntity",
        at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/content/trains/entity/CarriageEntityHandler;isActiveChunk(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean alwaysActiveForAlignment(Level level, BlockPos pos) {
        return true;
    }
}
