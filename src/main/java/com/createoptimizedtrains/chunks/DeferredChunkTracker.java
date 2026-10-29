package com.createoptimizedtrains.chunks;

import net.minecraft.server.level.ServerPlayer;

/**
 * Implementado pelo ChunkMapMixin. Reenvia ao jogador as chunks que o filtro
 * direcional bloqueou e que voltaram a ser permitidas (o jogador saiu do comboio
 * ou mudou de direção).
 *
 * Necessário porque o vanilla só chama updateChunkTracking quando uma chunk ENTRA
 * no alcance do jogador. Uma chunk bloqueada nesse momento nunca mais era enviada
 * enquanto continuasse no alcance — ficava um buraco no mundo.
 */
public interface DeferredChunkTracker {

    void cot$resendDeferredChunks(ServerPlayer player);
}
