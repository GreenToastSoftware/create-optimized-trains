package com.createoptimizedtrains.mixin;

import com.createoptimizedtrains.chunks.DeferredChunkTracker;
import com.createoptimizedtrains.chunks.DirectionalChunkShaper;
import com.createoptimizedtrains.config.ModConfig;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.WeakHashMap;

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
 *
 * Chunks bloqueadas ficam registadas por jogador e são reenviadas por
 * {@link DeferredChunkTracker#cot$resendDeferredChunks} quando voltam a ser permitidas.
 * Sem isto ficavam buracos: o vanilla só tenta enviar uma chunk quando ela entra no alcance.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin implements DeferredChunkTracker {

    @Shadow
    @Final
    ServerLevel level;

    @Shadow
    int viewDistance;

    @Shadow
    protected abstract void updateChunkTracking(ServerPlayer player, ChunkPos pos,
                                                MutableObject<ClientboundLevelChunkWithLightPacket> packet,
                                                boolean wasInRange, boolean isInRange);

    // Proteção de arranque: não filtrar durante os primeiros ticks após o mundo carregar.
    // Garante que as chunks iniciais (spawn) chegam ao cliente sem interferência.
    // Por instância: cada mundo/dimensão tem o seu ChunkMap, logo a graça repete-se ao reentrar.
    @Unique
    private long cot$firstCallTime = 0;
    @Unique
    private static final long COT$STARTUP_GRACE_MS = 10_000; // 10 segundos de graça

    // Chunks bloqueadas pelo filtro, por jogador. WeakHashMap: a instância de ServerPlayer
    // muda ao morrer/mudar de dimensão, e as entradas antigas desaparecem sozinhas.
    // Só acedido no main thread do servidor.
    @Unique
    private final Map<ServerPlayer, LongSet> cot$deferredChunks = new WeakHashMap<>();

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
        try {
            // Só filtrar quando vanilla quer CARREGAR uma chunk nova
            if (wasInRange || !isInRange) return;

            if (cot$shouldFilter(player, pos)) {
                ci.cancel(); // Não carregar esta chunk lateral distante (por agora)
                cot$deferredChunks.computeIfAbsent(player, p -> new LongOpenHashSet()).add(pos.toLong());
            }
        } catch (Exception e) {
            // NUNCA bloquear chunk loading — qualquer exceção deixa o vanilla correr
        }
    }

    @Unique
    private boolean cot$shouldFilter(ServerPlayer player, ChunkPos pos) {
        // Proteção de arranque: deixar TODAS as chunks passar nos primeiros segundos
        if (cot$firstCallTime == 0) cot$firstCallTime = System.currentTimeMillis();
        if (System.currentTimeMillis() - cot$firstCallTime < COT$STARTUP_GRACE_MS) return false;

        // Verificar se a feature está ativa
        if (!ModConfig.DIRECTIONAL_CHUNK_LOADING.get()) return false;

        // Verificar se o jogador está num comboio com direção conhecida
        DirectionalChunkShaper.PlayerDirection dir = DirectionalChunkShaper.getDirection(player);
        if (dir == null) return false;

        // Posição do jogador em chunk coordinates
        SectionPos section = SectionPos.of(player);
        int pcx = section.x();
        int pcz = section.z();

        // Raio mínimo: chunks dentro de 5 chunks do jogador NUNCA são filtradas.
        int dx = pos.x - pcx;
        int dz = pos.z - pcz;
        int distSq = dx * dx + dz * dz;
        if (distSq <= 25) return false; // 5*5 = 25 — raio de 5 chunks sempre carrega

        // Verificar se a chunk está dentro da área direcional
        int forward = ModConfig.DIRECTIONAL_FORWARD_CHUNKS.get();
        int backward = ModConfig.DIRECTIONAL_BACKWARD_CHUNKS.get();
        int side = ModConfig.DIRECTIONAL_SIDE_CHUNKS.get();

        return !dir.isInRange(pos.x, pos.z, pcx, pcz, forward, backward, side);
    }

    /**
     * Reenviar chunks bloqueadas que voltaram a ser permitidas. Chunks que entretanto
     * saíram do alcance vanilla do jogador são simplesmente esquecidas.
     */
    @Override
    public void cot$resendDeferredChunks(ServerPlayer player) {
        LongSet deferred = cot$deferredChunks.get(player);
        if (deferred == null || deferred.isEmpty()) return;
        if (player.level() != this.level) {
            cot$deferredChunks.remove(player);
            return;
        }

        // Mesmo centro que o vanilla usa para o tracking (ChunkMap.move)
        SectionPos center = player.getLastSectionPos();
        LongIterator it = deferred.iterator();
        while (it.hasNext()) {
            ChunkPos pos = new ChunkPos(it.nextLong());
            if (!ChunkMap.isChunkInRange(pos.x, pos.z, center.x(), center.z(), this.viewDistance)) {
                it.remove();
                continue;
            }
            try {
                if (cot$shouldFilter(player, pos)) continue;
            } catch (Exception e) {
                // Em caso de erro, enviar (mesma política do filtro)
            }
            it.remove();
            // Re-entra no filtro, que agora deixa passar
            updateChunkTracking(player, pos, new MutableObject<>(), false, true);
        }
    }
}
