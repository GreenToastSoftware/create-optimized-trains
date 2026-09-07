package com.createoptimizedtrains.mixin.client;

import com.createoptimizedtrains.diagnostics.DebugLog;
import com.createoptimizedtrains.rendering.BufferSourceResolver;
import com.createoptimizedtrains.rendering.RenderDeferFlags;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Flush de entidades e porta imediatamente ANTES de OitFramebuffer.prepare() no pipeline OIT do Flywheel.
 * A porta é desenhada com depthMask(false) para contribuir para a tintagem sem corromper o depth.
 *
 * === Timing ===
 *   1. submitSolid()   ← Z_solid já escrito no depth buffer principal
 *   2. hasOitDraws()   ← se não há OIT (vidro), salta para o fim (prepare nunca chamado)
 *   3. [nosso inject]  ← endBatch() aqui: entidades + porta no main FBO ✓
 *   4. prepare()       ← copia depth do main para OIT FBO
 *   5. OIT passes...
 *   6. composite()     ← blenda vidro OIT sobre main FBO → entidades + porta tintadas ✓
 *
 * === Porque funciona ===
 * - Entidades precisam de estar no framebuffer ANTES do OIT para receber a tintagem.
 * - A porta não pode escrever depth antes do OIT, senão os blocos sólidos desaparecem.
 * - A porta precisa de entrar antes do prepare(), senão não participa no tint do vidro.
 */
@Mixin(targets = "dev.engine_room.flywheel.backend.engine.indirect.IndirectDrawManager", remap = false)
public class IndirectDrawManagerMixin {

    private static final Logger LOGGER = LogManager.getLogger("COT/IndirectDrawManagerMixin");
    private static final AtomicBoolean LOGGED = new AtomicBoolean(false);

    @Inject(
        method = "render",
        at = @At(value = "INVOKE",
                target = "Ldev/engine_room/flywheel/backend/engine/indirect/OitFramebuffer;prepare()V",
                ordinal = 0)
    )
    private void flushBuffersBeforeOitPrepare(CallbackInfo ci) {
        if (DebugLog.ENABLED && LOGGED.compareAndSet(false, true)) {
            LOGGER.info("[COT] IndirectDrawManagerMixin: flush de entidades+porta antes de OitFramebuffer.prepare()");
        }
        int savedVao = GL30.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        // Entidades e porta são agora flushed no LevelRendererFlushMixin (priority 500),
        // que corre antes deste inject (Flywheel priority 1000). Nada a fazer aqui.
        GL30.glBindVertexArray(savedVao);
    }
}
