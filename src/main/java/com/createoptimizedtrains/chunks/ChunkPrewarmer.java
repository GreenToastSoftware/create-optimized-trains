package com.createoptimizedtrains.chunks;

import com.createoptimizedtrains.CreateOptimizedTrains;
import com.createoptimizedtrains.config.ModConfig;
import com.createoptimizedtrains.monitor.PerformanceMonitor;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pré-aquecimento de chunks: carrega chunks até ao estado FULL (terreno gerado, luz,
 * pronto a enviar) SEM entity-ticking, antes de o jogador ou o comboio lá chegarem.
 *
 * Porquê: o vanilla só processa 4 tickets de jogador em simultâneo
 * (DistanceManager.ticketThrottler). A 40 bl/s com view distance 12 são precisas
 * ~60 chunks novas por segundo; com 4 em paralelo o terreno aparece atrasado.
 * Tickets de região não passam por esse throttle — quando o ticket do jogador chega,
 * a chunk já está FULL e a promoção a ticking é imediata.
 *
 * Os tickets têm timeout (não são persistidos) e são renovados enquanto a chunk
 * continuar a ser pedida. Um orçamento por tick limita quantos tickets NOVOS são criados,
 * partilhado por ordem de prioridade: campo de visão do jogador → lookahead dos
 * comboios → rota.
 */
public final class ChunkPrewarmer {

    // Timeout do ticket: se não for renovado, a chunk pode descarregar após 5 s
    private static final int TICKET_TIMEOUT_TICKS = 100;
    // Renovar tickets com mais de 2 s, para nunca chegarem ao timeout enquanto pedidos
    private static final long REFRESH_AFTER_TICKS = 40;
    // Distância 0 → nível 33 = FULL (carregada, não ticking)
    private static final int TICKET_DISTANCE = 0;
    // Velocidade mínima do comboio (blocos/tick) para pré-aquecer o campo de visão
    private static final double MIN_TRAIN_SPEED = 0.1;

    private static final TicketType<ChunkPos> PREWARM =
        TicketType.create("cot_prewarm", Comparator.comparingLong(ChunkPos::toLong), TICKET_TIMEOUT_TICKS);

    // Tick em que cada chunk recebeu/renovou o ticket, por dimensão. Só main thread.
    private static final Map<ResourceKey<Level>, Long2LongMap> issued = new HashMap<>();
    // Chunks pedidas que ainda não chegaram a FULL (a carregar do disco ou a gerar).
    private static final Map<ResourceKey<Level>, LongSet> pending = new HashMap<>();
    private static int pendingCount = 0;
    // Servidor atrasado (MSPT alto): não pedir chunks novas, só renovar as que já temos
    private static boolean paused = false;

    private static long tick = 0;
    private static int newTicketsThisTick = 0;

    private ChunkPrewarmer() {
    }

    /**
     * Chamar uma vez por tick do servidor, antes de qualquer {@link #request}.
     * Reinicia o orçamento e pré-aquece o campo de visão de jogadores em comboios.
     */
    public static void tick(MinecraftServer server, List<ServerPlayer> players) {
        tick++;
        newTicketsThisTick = 0;

        if (tick % TICKET_TIMEOUT_TICKS == 0) {
            pruneExpired();
        }
        updatePending(server);
        paused = isServerLagging();

        boolean enabled;
        try {
            enabled = ModConfig.PREWARM_ENABLED.get();
        } catch (Exception e) {
            enabled = false;
        }
        if (!enabled) return;

        int viewDistance = server.getPlayerList().getViewDistance();
        for (ServerPlayer player : players) {
            prewarmPlayerView(player, viewDistance);
        }
    }

    /**
     * Pedir que uma chunk fique carregada (FULL) durante os próximos segundos.
     *
     * @return false se o orçamento de tickets novos deste tick já se esgotou
     */
    public static boolean request(ServerLevel level, int chunkX, int chunkZ) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        Long2LongMap levelIssued = issued.computeIfAbsent(level.dimension(), k -> new Long2LongOpenHashMap());

        if (levelIssued.containsKey(key)) {
            // Já é nossa: renovar antes do timeout (addRegionTicket repõe o tick de criação)
            if (tick - levelIssued.get(key) >= REFRESH_AFTER_TICKS) {
                addTicket(level, chunkX, chunkZ);
                levelIssued.put(key, tick);
            }
            return true;
        }

        // Já carregada por outra razão (jogador, forceload, outro comboio): nada a fazer
        if (level.getChunkSource().getChunkNow(chunkX, chunkZ) != null) {
            return true;
        }

        // Limites para chunks NOVAS:
        //  - por tick (maxNewPerTick);
        //  - em voo (maxPending): nunca mais do que N chunks nossas à espera de gerar.
        //    Sem este limite a fila de worldgen enchia (centenas de chunks), e qualquer
        //    getChunk síncrono no tick do servidor esperava atrás delas — congelamentos
        //    de 60 s+ num servidor dedicado ao entrar um jogador perto de comboios.
        //  - pausa enquanto o servidor estiver atrasado.
        if (paused
                || newTicketsThisTick >= safeMaxNewTicketsPerTick()
                || pendingCount >= safeMaxPending()) {
            return false;
        }
        newTicketsThisTick++;
        addTicket(level, chunkX, chunkZ);
        levelIssued.put(key, tick);
        if (pending.computeIfAbsent(level.dimension(), k -> new LongOpenHashSet()).add(key)) {
            pendingCount++;
        }
        return true;
    }

    /** Retirar de "pendentes" as chunks que já carregaram ou cujo ticket expirou. */
    private static void updatePending(MinecraftServer server) {
        if (pendingCount == 0) return;
        int count = 0;
        var it = pending.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            ServerLevel level = server.getLevel(entry.getKey());
            Long2LongMap levelIssued = issued.get(entry.getKey());
            LongSet keys = entry.getValue();
            if (level == null || levelIssued == null) {
                it.remove();
                continue;
            }
            keys.removeIf((long key) -> !levelIssued.containsKey(key)
                || level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key)) != null);
            count += keys.size();
        }
        pendingCount = count;
    }

    private static boolean isServerLagging() {
        CreateOptimizedTrains mod = CreateOptimizedTrains.getInstance();
        PerformanceMonitor monitor = mod != null ? mod.getPerformanceMonitor() : null;
        return monitor != null && monitor.getState() != PerformanceMonitor.PerformanceState.NORMAL;
    }

    /** Número de chunks com ticket de pré-aquecimento ativo (todas as dimensões). */
    public static int getIssuedCount() {
        int total = 0;
        for (Long2LongMap levelIssued : issued.values()) {
            total += levelIssued.size();
        }
        return total;
    }

    /** Chunks pré-aquecidas ainda à espera de carregar. */
    public static int getPendingCount() {
        return pendingCount;
    }

    /** Esquecer todo o estado (servidor a parar). Os tickets expiram sozinhos. */
    public static void clear() {
        issued.clear();
        pending.clear();
        pendingCount = 0;
        paused = false;
        tick = 0;
        newTicketsThisTick = 0;
    }

    private static void addTicket(ServerLevel level, int chunkX, int chunkZ) {
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        level.getChunkSource().addRegionTicket(PREWARM, pos, TICKET_DISTANCE, pos);
    }

    private static void pruneExpired() {
        for (Long2LongMap levelIssued : issued.values()) {
            levelIssued.long2LongEntrySet().removeIf(e -> tick - e.getLongValue() >= TICKET_TIMEOUT_TICKS);
        }
    }

    /**
     * Pré-aquecer as chunks que vão entrar no campo de visão do jogador nos próximos
     * segundos: uma faixa à frente do comboio, desde a borda atual da view distance até
     * onde o jogador estará daqui a {@code prewarm.secondsAhead}. Ordenada por tempo
     * de chegada (mais próximas primeiro) e depois pelo centro da faixa.
     */
    private static void prewarmPlayerView(ServerPlayer player, int viewDistance) {
        double speed = getTrainSpeed(player);
        if (Math.abs(speed) < MIN_TRAIN_SPEED) return;

        DirectionalChunkShaper.PlayerDirection dir = DirectionalChunkShaper.getDirection(player);
        if (dir == null) return;

        double secondsAhead = ModConfig.PREWARM_SECONDS_AHEAD.get();
        int maxAhead = ModConfig.PREWARM_MAX_CHUNKS_AHEAD.get();
        double blocksPerSecond = Math.abs(speed) * 20.0;
        int ahead = Math.min(maxAhead, (int) Math.ceil(blocksPerSecond * secondsAhead / 16.0));
        if (ahead <= 0) return;

        // Largura: com o filtro direcional ativo, só as chunks que vão ser enviadas
        int halfWidth = viewDistance;
        if (ModConfig.DIRECTIONAL_CHUNK_LOADING.get()) {
            halfWidth = Math.min(viewDistance, Math.max(5, ModConfig.DIRECTIONAL_SIDE_CHUNKS.get()));
        }

        ServerLevel level = player.serverLevel();
        ChunkPos center = player.chunkPosition();
        double fx = dir.dirX();
        double fz = dir.dirZ();

        // Faixa à frente: da borda da view distance (as de dentro já têm ticket do
        // jogador) até viewDistance + ahead
        LongLinkedOpenHashSet ordered = new LongLinkedOpenHashSet();
        for (int f = Math.max(1, viewDistance - 1); f <= viewDistance + ahead; f++) {
            for (int s = 0; s <= halfWidth; s++) {
                addRotated(ordered, center, fx, fz, f, s);
                if (s != 0) addRotated(ordered, center, fx, fz, f, -s);
            }
        }

        for (long key : ordered) {
            if (!request(level, ChunkPos.getX(key), ChunkPos.getZ(key))) {
                return; // orçamento esgotado — continua no próximo tick
            }
        }
    }

    private static void addRotated(LongLinkedOpenHashSet out, ChunkPos center,
                                   double fx, double fz, int forward, int side) {
        // Vetor perpendicular à direção: (-fz, fx)
        int cx = center.x + (int) Math.round(fx * forward - fz * side);
        int cz = center.z + (int) Math.round(fz * forward + fx * side);
        out.add(ChunkPos.asLong(cx, cz));
    }

    private static double getTrainSpeed(ServerPlayer player) {
        Entity vehicle = player.getVehicle();
        int depth = 0;
        while (vehicle != null && depth < 5) {
            if (vehicle instanceof CarriageContraptionEntity cce) {
                var carriage = cce.getCarriage();
                return carriage != null && carriage.train != null ? carriage.train.speed : 0.0;
            }
            vehicle = vehicle.getVehicle();
            depth++;
        }
        return 0.0;
    }

    private static int safeMaxNewTicketsPerTick() {
        try {
            return ModConfig.PREWARM_MAX_NEW_PER_TICK.get();
        } catch (Exception e) {
            return 8;
        }
    }

    private static int safeMaxPending() {
        try {
            return ModConfig.PREWARM_MAX_PENDING.get();
        } catch (Exception e) {
            return 24;
        }
    }
}
