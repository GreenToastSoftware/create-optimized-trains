package com.createoptimizedtrains.mixin.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.Optional;

/**
 * Accessor mixin para MultiBufferSource$BufferSource.
 *
 * Estrutura relevante da classe:
 *  - fixedBuffers: Map<RenderType, BufferBuilder>  — pré-alocados (Sheets, glint, translucentMovingBlock...)
 *  - builder: BufferBuilder                         — builder partilhado para tipos dinâmicos
 *  - lastState: Optional<RenderType>                — tipo actualmente a usar o builder partilhado
 *
 * Usado em LevelRendererFlushMixin para flush selectivo: flush tudo excepto
 * translucentMovingBlock, preservando a porta da contraption para timing correcto.
 */
@Mixin(targets = "net.minecraft.client.renderer.MultiBufferSource$BufferSource")
public interface IBufferSourceAccessor {

    /** Buffers pré-alocados (fixos): translucentMovingBlock, Sheets, glint, water mask, etc. */
    @Accessor("fixedBuffers")
    Map<RenderType, BufferBuilder> getFixedBuffers();

    /**
     * Tipo actualmente a usar o builder partilhado (não-fixo).
     * Skins de jogadores (entityTranslucent(uuid)), entidades GeckoLib, etc.
     * Só o último tipo fica pendente — os anteriores são auto-flushed ao trocar de tipo.
     */
    @Accessor("lastState")
    Optional<RenderType> getLastState();
}

