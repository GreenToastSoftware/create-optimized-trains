package com.createoptimizedtrains.rendering;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;

/**
 * RenderTypes customizados para corrigir o bug de visibilidade de entidades
 * através de vidros em contraptions (comboios, etc.).
 *
 * Estende RenderType para aceder aos campos protected static de RenderStateShard
 * (RENDERTYPE_TRANSLUCENT_SHADER, BLOCK_SHEET_MIPPED, etc.).
 *
 * Bug original:
 * ContraptionEntityRenderer renderiza blocos translúcidos (vidro) com
 * RenderType.translucent() que escreve no depth buffer. Quando entidades
 * (jogadores, mobs) são renderizadas depois, os seus fragmentos falham
 * o teste de profundidade contra o vidro — tornando-os invisíveis através
 * do vidro, mesmo que o vidro seja transparente.
 *
 * Correção:
 * TRANSLUCENT_NO_DEPTH é idêntico a RenderType.translucent() mas com
 * WriteMaskState = COLOR_WRITE (só cor, sem profundidade). Isto permite
 * que entidades atrás/à frente do vidro sejam visíveis enquanto o tint
 * de cor do vidro continua a ser renderizado normalmente.
 */
public class ContraptionRenderTypes extends RenderType {

    // Nunca instanciado — classe utilitária estática
    private ContraptionRenderTypes() {
        super("dummy", DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS,
              256, false, false, () -> {}, () -> {});
    }

    /**
     * RenderType translúcido SEM escrita de profundidade.
     * Idêntico a RenderType.translucent() excepto WriteMaskState = COLOR_WRITE.
     * Usado no path non-Flywheel para vidros estruturais da contraption.
     */
    public static final RenderType TRANSLUCENT_NO_DEPTH = create(
        "create_optimized_trains:contraption_translucent",
        DefaultVertexFormat.BLOCK,
        VertexFormat.Mode.QUADS,
        2097152,
        true,
        true,
        CompositeState.builder()
            .setShaderState(RENDERTYPE_TRANSLUCENT_SHADER)
            .setTextureState(BLOCK_SHEET_MIPPED)
            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
            .setOutputState(TRANSLUCENT_TARGET)
            .setWriteMaskState(COLOR_WRITE)
            .createCompositeState(true)
    );

    /**
     * RenderType para blocos em movimento SEM escrita de profundidade.
     *
     * Idêntico a RenderType.translucentMovingBlock() excepto WriteMaskState = COLOR_WRITE.
     *
     * Porquê necessário:
     *   CopycatSlidingDoorRenderer usa translucentMovingBlock() para renderizar as
     *   partes animadas das portas Copycats+. Este render type usa:
     *     - RENDERTYPE_TRANSLUCENT_MOVING_BLOCK_SHADER (shader correcto)
     *     - ITEM_ENTITY_TARGET (render target correcto)
     *   Sem esta substituição, a porta escreve no depth buffer ANTES dos blocos
     *   sólidos GPU-instanced do Flywheel serem submetidos, causando que esses
     *   blocos falhem o depth test e fiquem invisíveis.
     *
     *   TRANSLUCENT_NO_DEPTH não serve para este caso porque usa TRANSLUCENT_TARGET
     *   e RENDERTYPE_TRANSLUCENT_SHADER — shaders e targets diferentes fazem a
     *   porta aparecer preta e na "camada de fundo".
     *
     *   Esta versão usa o mesmo shader e target que translucentMovingBlock(),
     *   apenas sem escrita de depth — a porta renderiza correctamente sem
     *   corromper o depth buffer.
     */
    public static final RenderType TRANSLUCENT_MOVING_BLOCK_NO_DEPTH = create(
        "create_optimized_trains:contraption_translucent_moving_block",
        DefaultVertexFormat.BLOCK,
        VertexFormat.Mode.QUADS,
        786432,   // Mesmo tamanho que translucentMovingBlock (768KB)
        false,    // affectsOutline = false (igual a translucentMovingBlock)
        true,     // sortOnUpload
        CompositeState.builder()
            .setShaderState(RENDERTYPE_TRANSLUCENT_MOVING_BLOCK_SHADER) // shader correcto
            .setTextureState(BLOCK_SHEET_MIPPED)
            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
            .setOutputState(ITEM_ENTITY_TARGET)  // target correcto (não TRANSLUCENT_TARGET)
            .setWriteMaskState(COLOR_WRITE)      // ← só cor, sem depth write
            .createCompositeState(false)
    );
}
