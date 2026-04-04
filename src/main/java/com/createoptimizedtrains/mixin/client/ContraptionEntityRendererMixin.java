package com.createoptimizedtrains.mixin.client;

import com.createoptimizedtrains.rendering.ContraptionRenderTypes;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.render.ContraptionEntityRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin em ContraptionEntityRenderer para corrigir o bug de entidades invisíveis
 * através de vidros em contraptions (comboios, carroças, elevadores, etc.).
 *
 * === Fix 1: Non-Flywheel path (Redirect) ===
 * Quando Flywheel NÃO está activo, ContraptionEntityRenderer renderiza blocos
 * estruturais manualmente. Para RenderType.translucent() (vidros), substitui
 * por TRANSLUCENT_NO_DEPTH para não escrever no depth buffer.
 *
 * === Fix 2: Flywheel path (Buffer Flush) — Issue #8727 ===
 * Quando Flywheel está activo, block entities da contraption (arcas, placas, etc.)
 * são renderizados por ContraptionEntityRenderer durante a fase de entities, usando
 * Sheet render types (Sheets.chestSheet, Sheets.signSheet, etc.).
 *
 * Flywheel hook "beforeBlockEntities" chama afterEntities() → OIT pipeline ANTES
 * dos Sheet buffers serem flushed. O oit_composite escreve gl_FragDepth (profundidade
 * do vidro) com GL_ALWAYS. Quando os Sheet buffers finalmente são flushed (depois
 * do Flywheel), os block entities atrás do vidro falham o depth test → INVISÍVEIS.
 *
 * Fix: Forçar flush de TODOS os Sheet render type buffers no final de render(),
 * ANTES que Flywheel's afterEntities() execute. Isto garante que block entities
 * da contraption já estão no framebuffer quando o OIT composite blenda a tintagem.
 *
 * Nota: No momento do flush, apenas vértices de block entities de contraptions
 * estão nos Sheet buffers (world block entities ainda não foram processados).
 */
@Mixin(value = ContraptionEntityRenderer.class)
public abstract class ContraptionEntityRendererMixin {

    /**
     * Redirect (non-Flywheel path): substitui translucent por TRANSLUCENT_NO_DEPTH.
     */
    @Redirect(
        method = {"render", "m_7392_"},
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/MultiBufferSource;getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
            remap = true
        ),
        remap = false
    )
    private VertexConsumer useNoDepthForTranslucent(MultiBufferSource source, RenderType type) {
        if (type == RenderType.translucent()) {
            return source.getBuffer(ContraptionRenderTypes.TRANSLUCENT_NO_DEPTH);
        }
        return source.getBuffer(type);
    }

    /**
     * Flush dos Sheet render type buffers após renderização da contraption.
     * Garante que block entities ficam no framebuffer antes do OIT do Flywheel.
     *
     * Usa o global BufferSource do Minecraft — é o mesmo subjacente ao
     * OutlineBufferSource passado pela entity render loop.
     */
    @Inject(
        method = {"render", "m_7392_"},
        at = @At("RETURN"),
        remap = false
    )
    private void flushContraptionBlockEntityBuffers(AbstractContraptionEntity entity, float yaw, float partialTicks, PoseStack poseStack, MultiBufferSource bufferSource, int light, CallbackInfo ci) {
        MultiBufferSource.BufferSource bs = Minecraft.getInstance().renderBuffers().bufferSource();
        bs.endBatch(Sheets.solidBlockSheet());
        bs.endBatch(Sheets.cutoutBlockSheet());
        bs.endBatch(Sheets.bedSheet());
        bs.endBatch(Sheets.shulkerBoxSheet());
        bs.endBatch(Sheets.signSheet());
        bs.endBatch(Sheets.hangingSignSheet());
        bs.endBatch(Sheets.chestSheet());
        bs.endBatch(Sheets.bannerSheet());
    }
}
