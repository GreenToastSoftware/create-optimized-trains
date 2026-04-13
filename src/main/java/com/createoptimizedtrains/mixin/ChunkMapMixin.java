package com.createoptimizedtrains.mixin;

import com.createoptimizedtrains.chunks.DirectionalChunkShaper;
import com.createoptimizedtrains.config.ModConfig;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin no ChunkMap do Minecraft para implementar carregamento direcional de chunks.
 *
 * Quando o jogador está num comboio Create em movimento, intercepta a decisão de
 * carregar novas chunks. Chunks que estão fora da área direcional (demasiado para
 * os lados do sentido do comboio) não são enviadas ao cliente.
 *
 * Injecta no método updateChunkTracking que decide enviar/remover chunks ao jogador.
 * Só filtra chunks NOVAS (wasInRange=false, isInRange=true). Chunks já carregadas
 * são descarregadas pelo sistema vanilla quando o jogador se afasta.
 *
 * Compatible com Distant Horizons — DH preenche o cenário distante independentemente.
 */
@Mixin(ChunkMap.class)
public class ChunkMapMixin {

    /**
     * Interceptar updateChunkTracking para filtrar chunks novas fora da área direcional.
     *
     * Só cancela quando:
     * - wasInRange=false (chunk não estava a ser tracked)
     * - isInRange=true (vanilla quer começar a track)
     * - directional check diz que está fora da área
     *
     * Isto impede que chunks laterais novas sejam carregadas, mas não remove
     * chunks já carregadas (essas saem naturalmente quando o jogador se afasta).
     */
    @Inject(method = "updateChunkTracking",
            at = @At("HEAD"),
            cancellable = true)
    private void directionalChunkFilter(ServerPlayer player, ChunkPos pos,
                                         MutableObject<ClientboundLevelChunkWithLightPacket> packet,
                                         boolean wasInRange, boolean isInRange,
                                         CallbackInfo ci) {
        // Só filtrar quando vanilla quer CARREGAR uma chunk nova
        if (wasInRange || !isInRange) return;

        // Verificar se a feature está ativa
        try {
            if (!ModConfig.DIRECTIONAL_CHUNK_LOADING.get()) return;
        } catch (Exception e) {
            return; // Config não carregada ainda
        }

        // Verificar se o jogador está num comboio com direção conhecida
        DirectionalChunkShaper.PlayerDirection dir = DirectionalChunkShaper.getDirection(player);
        if (dir == null) return;

        // Posição do jogador em chunk coordinates
        SectionPos section = SectionPos.of(player);
        int pcx = section.x();
        int pcz = section.z();

        // Verificar se a chunk está dentro da área direcional
        int forward = ModConfig.DIRECTIONAL_FORWARD_CHUNKS.get();
        int backward = ModConfig.DIRECTIONAL_BACKWARD_CHUNKS.get();
        int side = ModConfig.DIRECTIONAL_SIDE_CHUNKS.get();

        if (!dir.isInRange(pos.x, pos.z, pcx, pcz, forward, backward, side)) {
            ci.cancel(); // Não carregar esta chunk lateral
        }
    }
}
