package com.createoptimizedtrains.mixin.client;

import com.createoptimizedtrains.diagnostics.DebugLog;
import com.createoptimizedtrains.rendering.ContraptionBufferSourceWrapper;
import com.createoptimizedtrains.rendering.BufferSourceResolver;
import com.createoptimizedtrains.rendering.RenderDeferFlags;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.render.ContraptionEntityRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.lang.reflect.Field;
import java.util.Objects;

/**
 * Mixin em ContraptionEntityRenderer para corrigir o bug de entidades invisíveis
 * através de vidros em contraptions (comboios, carroças, elevadores, etc.).
 *
 * === Fix 1: Non-Flywheel path (buffer wrapper) ===
 * Quando Flywheel NÃO está activo, ContraptionEntityRenderer renderiza blocos
 * estruturais manualmente com RenderType.translucent() (que escreve no depth buffer).
 * Substitui por TRANSLUCENT_NO_DEPTH para não escrever no depth buffer, evitando
 * que entidades atrás do vidro fiquem invisíveis.
 *
 * === Fix 2: Flywheel path (Buffer Flush) — Issue #8727 ===
 * Quando Flywheel está activo, block entities da contraption (arcas, placas, etc.)
 * são renderizados durante a fase de entities usando Sheet render types.
 * Flywheel's afterEntities() → OIT composite corre ANTES dos Sheet buffers serem
 * flushed → block entities ficam invisíveis através do vidro.
 * Fix: Flush dos Sheet buffers no RETURN de render().
 *
 * === Fix 3: Flywheel path — Copycats+ depth bug ===
 * Portas animadas do Copycats+ usam RenderType.translucentMovingBlock().
 * O fix está em LevelRendererFlushMixin + BufferSourceMixin:
 * o flush desse buffer é deferido para DEPOIS do Flywheel submeter os blocos sólidos.
 */
@Mixin(value = ContraptionEntityRenderer.class)
public abstract class ContraptionEntityRendererMixin {

    private static final Logger LOGGER = LogManager.getLogger("COT/ContraptionEntityRendererMixin");
    private static volatile long lastLogTime = 0;
    private static volatile Field unflushableWrappedField;
    private static volatile boolean lookedUpUnflushableWrappedField = false;

    @Inject(
        method = {"render", "m_7392_"},
        at = @At("HEAD"),
        remap = false
    )
    private void markContraptionRenderStart(AbstractContraptionEntity entity, float yaw, float partialTicks, PoseStack poseStack, MultiBufferSource bufferSource, int light, CallbackInfo ci) {
        RenderDeferFlags.insideContraptionRender = true;
    }

    /**
     * Wrapper do MultiBufferSource para unificar os reroutes de translucent e
     * translucentMovingBlock independentemente do tipo concreto do buffer.
     */
    @ModifyVariable(
        method = {"render", "m_7392_"},
        at = @At("HEAD"),
        argsOnly = true,
        ordinal = 0,
        remap = false
    )
    private MultiBufferSource wrapContraptionBufferSource(MultiBufferSource original) {
        if (original instanceof ContraptionBufferSourceWrapper) {
            return original;
        }
        return new ContraptionBufferSourceWrapper(original);
    }

    /**
     * Flush dos Sheet render type buffers após renderização da contraption.
     * Garante que block entities ficam no framebuffer antes do OIT do Flywheel.
     */
    @Inject(
        method = {"render", "m_7392_"},
        at = @At("RETURN"),
        remap = false
    )
    private void flushContraptionBlockEntityBuffers(AbstractContraptionEntity entity, float yaw, float partialTicks, PoseStack poseStack, MultiBufferSource bufferSource, int light, CallbackInfo ci) {
        MultiBufferSource effectiveSource = bufferSource;
        if (bufferSource instanceof ContraptionBufferSourceWrapper wrapped) {
            effectiveSource = wrapped.getDelegate();
        }

        MultiBufferSource.BufferSource bs;
        MultiBufferSource.BufferSource unwrappedSource = unwrapUnflushableWrapper(effectiveSource);
        if (unwrappedSource != null) {
            bs = unwrappedSource;
        } else if (effectiveSource instanceof MultiBufferSource.BufferSource casted) {
            bs = casted;
        } else {
            bs = Minecraft.getInstance().renderBuffers().bufferSource();
            long now = System.currentTimeMillis();
            if (DebugLog.ENABLED && now - lastLogTime > 3000) {
                lastLogTime = now;
                LOGGER.warn("[COT] ContraptionEntityRendererMixin: source efetivo nao e BufferSource ({}), a usar global fallback", effectiveSource.getClass().getName());
            }
        }

        long startNs = System.nanoTime();
        bs.endBatch(Objects.requireNonNull(Sheets.solidBlockSheet()));
        bs.endBatch(Objects.requireNonNull(Sheets.cutoutBlockSheet()));
        bs.endBatch(Objects.requireNonNull(Sheets.bedSheet()));
        bs.endBatch(Objects.requireNonNull(Sheets.shulkerBoxSheet()));
        bs.endBatch(Objects.requireNonNull(Sheets.signSheet()));
        bs.endBatch(Objects.requireNonNull(Sheets.hangingSignSheet()));
        bs.endBatch(Objects.requireNonNull(Sheets.chestSheet()));
        bs.endBatch(Objects.requireNonNull(Sheets.bannerSheet()));

        // A porta (translucentMovingBlock) é agora flushed no LevelRendererFlushMixin,
        // DEPOIS do flush Iris, para que depth das entidades já esteja no buffer.

        long now = System.currentTimeMillis();
        if (DebugLog.ENABLED && now - lastLogTime > 3000) {
            lastLogTime = now;
            long tookUs = (System.nanoTime() - startNs) / 1000L;
            LOGGER.info("[COT] ContraptionEntityRendererMixin: flush sheets+door concluido em {} us (source={})", tookUs, bs.getClass().getName());
        }

        RenderDeferFlags.insideContraptionRender = false;
    }

    private static MultiBufferSource.BufferSource unwrapUnflushableWrapper(MultiBufferSource source) {
        String className = source.getClass().getName();
        if (!className.endsWith("FullyBufferedMultiBufferSource$UnflushableWrapper")) {
            return null;
        }

        try {
            Field field = unflushableWrappedField;
            if (!lookedUpUnflushableWrappedField) {
                field = source.getClass().getDeclaredField("wrapped");
                field.setAccessible(true);
                unflushableWrappedField = field;
                lookedUpUnflushableWrappedField = true;
            }

            Object wrapped = field.get(source);
            if (wrapped instanceof MultiBufferSource.BufferSource casted) {
                return casted;
            }
        } catch (ReflectiveOperationException e) {
            long now = System.currentTimeMillis();
            if (DebugLog.ENABLED && now - lastLogTime > 3000) {
                lastLogTime = now;
                LOGGER.warn("[COT] ContraptionEntityRendererMixin: falha a desembrulhar UnflushableWrapper ({})", source.getClass().getName(), e);
            }
        }

        return null;
    }
}
