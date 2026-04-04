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
     *
     * Idêntico a RenderType.translucent() excepto:
     * - WriteMaskState = COLOR_WRITE em vez de COLOR_DEPTH_WRITE
     *
     * Isto significa:
     * - Cor do vidro é renderizada normalmente (tint, transparência) ✓
     * - Blocos opacos da contraption ainda ocluem correctamente (usam solid/cutout) ✓
     * - Entidades atrás do vidro visíveis (sem oclusão de depth pelo vidro) ✓
     * - Entidades à frente do vidro visíveis (mesmo raciocínio) ✓
     */
    public static final RenderType TRANSLUCENT_NO_DEPTH = create(
        "create_optimized_trains:contraption_translucent",
        DefaultVertexFormat.BLOCK,
        VertexFormat.Mode.QUADS,
        2097152,  // Mesmo tamanho de buffer que translucent (2MB)
        true,     // affectsOutline
        true,     // sortOnUpload (necessário para blending correcto)
        CompositeState.builder()
            .setShaderState(RENDERTYPE_TRANSLUCENT_SHADER)
            .setTextureState(BLOCK_SHEET_MIPPED)
            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
            .setOutputState(TRANSLUCENT_TARGET)
            .setWriteMaskState(COLOR_WRITE) // ← A CORREÇÃO: só escreve cor, não depth
            .createCompositeState(true)
    );
}
