package com.createoptimizedtrains.chunks;

import com.createoptimizedtrains.config.ModConfig;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Route Chunk Preloader — pré-carrega chunks ao longo da rota ferroviária
 * em memória do servidor SEM force-loading (entity-ticking).
 *
 * Diferença chave vs ChunkLoadManager:
 * - ChunkLoadManager: tickets forçados nos chunks imediatos (entity-ticking)
 * - RouteChunkPreloader: tickets de pré-aquecimento nos chunks da rota (FULL, só memória)
 *
 * Isto garante que os chunks da rota estão em memória quando o comboio chega,
 * sem consumir CPU do server com entity-ticking de dezenas de chunks extra.
 */
public class RouteChunkPreloader {

    // Chunks já pedidas (para evitar re-pedir a mesma chunk várias vezes)
    private final Map<UUID, Set<ChunkPos>> lastRequestedChunks = new ConcurrentHashMap<>();

    // Tempo máximo para olhar à frente na rota (em blocos de track)
    // Adaptativo: base 256 blocos, aumenta com velocidade (até 512 a >30 bl/s)
    private static final double BASE_ROUTE_LOOKAHEAD_BLOCKS = 256.0;
    private static final double MAX_ROUTE_LOOKAHEAD_BLOCKS = 512.0;

    // Máximo de chunks a pré-carregar por comboio
    private static final int MAX_PRELOAD_PER_TRAIN = 24;

    /**
     * Calcular chunks de rota e pré-aquecê-las (FULL, sem entity-ticking) via
     * {@link ChunkPrewarmer}. Chamado do main thread.
     */
    public void preloadRouteChunks(Train train, ServerLevel level) {
        if (!ModConfig.SMART_CHUNK_LOADING.get()) return;
        if (train.graph == null || train.carriages.isEmpty()) return;
        if (Math.abs(train.speed) < 0.001) return;

        UUID trainId = train.id;

        List<double[]> routePoints = traceRouteFromGraph(train);
        if (routePoints.isEmpty()) return;

        // Converter pontos em chunks
        Set<ChunkPos> routeChunks = new LinkedHashSet<>();
        for (double[] point : routePoints) {
            int chunkX = ((int) Math.floor(point[0])) >> 4;
            int chunkZ = ((int) Math.floor(point[1])) >> 4;
            routeChunks.add(new ChunkPos(chunkX, chunkZ));
            if (routeChunks.size() >= MAX_PRELOAD_PER_TRAIN) break;
        }

        // Pedir todas as chunks da rota em cada chamada: o ChunkPrewarmer ignora as já
        // carregadas e renova os tickets das nossas antes de expirarem.
        //
        // Antes usava-se getChunkFuture(..., FULL, true), que cria um ticket UNKNOWN com
        // timeout de 1 tick — chunks ainda não geradas eram descarregadas a meio do
        // carregamento e o pré-carregamento praticamente não tinha efeito.
        lastRequestedChunks.put(trainId, routeChunks);
        for (ChunkPos pos : routeChunks) {
            if (!ChunkPrewarmer.request(level, pos.x, pos.z)) {
                break; // orçamento do tick esgotado
            }
        }
    }

    /**
     * Traçar a rota do comboio através do grafo ferroviário.
     */
    private List<double[]> traceRouteFromGraph(Train train) {
        List<double[]> points = new ArrayList<>();
        TrackGraph graph = train.graph;
        if (graph == null) return points;

        boolean forward = train.speed > 0;

        // Lookahead adaptativo baseado na velocidade
        double speedBps = Math.abs(train.speed) * 20.0;
        double routeLookahead = BASE_ROUTE_LOOKAHEAD_BLOCKS;
        if (speedBps > 15.0) {
            // Escalar linearmente: 15 b/s -> 256, 40 b/s -> 512
            double t = Math.min(1.0, (speedBps - 15.0) / 25.0);
            routeLookahead = BASE_ROUTE_LOOKAHEAD_BLOCKS + t * (MAX_ROUTE_LOOKAHEAD_BLOCKS - BASE_ROUTE_LOOKAHEAD_BLOCKS);
        }

        Carriage frontCarriage = forward ? train.carriages.get(0)
                : train.carriages.get(train.carriages.size() - 1);
        CarriageBogey leadingBogey = frontCarriage.leadingBogey();
        if (leadingBogey == null) return points;

        TravellingPoint point = leadingBogey.leading();
        if (point == null || point.node1 == null || point.node2 == null) return points;

        TrackNodeLocation loc1 = point.node1.getLocation();
        points.add(new double[]{loc1.getX(), loc1.getZ()});

        double distanceTraced = 0;
        TrackNode currentNode = forward ? point.node2 : point.node1;
        TrackNode prevNode = forward ? point.node1 : point.node2;
        Set<TrackNode> visited = new HashSet<>();
        visited.add(prevNode);

        while (distanceTraced < routeLookahead && points.size() < MAX_PRELOAD_PER_TRAIN * 2) {
            if (currentNode == null || visited.contains(currentNode)) break;
            visited.add(currentNode);

            TrackNodeLocation nodeLoc = currentNode.getLocation();
            points.add(new double[]{nodeLoc.getX(), nodeLoc.getZ()});

            TrackNodeLocation prevLoc = prevNode.getLocation();
            double dx = nodeLoc.getX() - prevLoc.getX();
            double dz = nodeLoc.getZ() - prevLoc.getZ();
            distanceTraced += Math.sqrt(dx * dx + dz * dz);

            Map<TrackNode, TrackEdge> connections = graph.getConnectionsFrom(currentNode);
            if (connections == null || connections.isEmpty()) break;

            TrackNode nextNode = null;
            for (Map.Entry<TrackNode, TrackEdge> entry : connections.entrySet()) {
                if (!entry.getKey().equals(prevNode) && !visited.contains(entry.getKey())) {
                    nextNode = entry.getKey();
                    break;
                }
            }

            if (nextNode == null) break;
            prevNode = currentNode;
            currentNode = nextNode;
        }

        return points;
    }

    public void releaseTrain(UUID trainId) {
        lastRequestedChunks.remove(trainId);
    }

    public void shutdown() {
        lastRequestedChunks.clear();
    }

    public int getPreloadedCount() {
        return lastRequestedChunks.values().stream().mapToInt(Set::size).sum();
    }

    public int getPreloadedCount(UUID trainId) {
        Set<ChunkPos> chunks = lastRequestedChunks.get(trainId);
        return chunks != null ? chunks.size() : 0;
    }
}
