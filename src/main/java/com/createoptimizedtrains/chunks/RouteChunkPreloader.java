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
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;

import java.util.*;
import java.util.concurrent.*;

/**
 * Route Chunk Preloader — pré-carrega chunks ao longo da rota ferroviária
 * em memória do servidor SEM force-loading (entity-ticking).
 *
 * Diferença chave vs ChunkLoadManager:
 * - ChunkLoadManager: setChunkForced() nos chunks imediatos (entity-ticking)
 * - RouteChunkPreloader: getChunkFuture(FULL) nos chunks da rota (só memória)
 *
 * Isto garante que os chunks da rota estão em memória quando o comboio chega,
 * sem consumir CPU do server com entity-ticking de dezenas de chunks extra.
 */
public class RouteChunkPreloader {

    private volatile ExecutorService preloadExecutor;

    // Chunks já pedidas (para evitar re-pedir a mesma chunk várias vezes)
    private final Map<UUID, Set<ChunkPos>> lastRequestedChunks = new ConcurrentHashMap<>();

    // Tempo máximo para olhar à frente na rota (em blocos de track)
    private static final double ROUTE_LOOKAHEAD_BLOCKS = 256.0; // ~6.5 segundos a 40bl/s

    // Máximo de chunks a pré-carregar por comboio
    private static final int MAX_PRELOAD_PER_TRAIN = 24;

    public RouteChunkPreloader() {
        // Lazy init — thread só é criada quando realmente precisa (após startup delay)
    }

    private ExecutorService getExecutor() {
        if (preloadExecutor == null) {
            synchronized (this) {
                if (preloadExecutor == null) {
                    preloadExecutor = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "COT-RouteChunkPreloader");
                        t.setDaemon(true);
                        t.setPriority(Thread.NORM_PRIORITY);
                        return t;
                    });
                }
            }
        }
        return preloadExecutor;
    }

    /**
     * Calcular chunks de rota e pré-carregar em memória (sem force-load).
     * Chamado do main thread, submete trabalho pesado para thread dedicada.
     */
    public void preloadRouteChunks(Train train, ServerLevel level) {
        if (!ModConfig.SMART_CHUNK_LOADING.get()) return;
        if (train.graph == null || train.carriages.isEmpty()) return;
        if (Math.abs(train.speed) < 0.001) return;

        UUID trainId = train.id;

        List<double[]> routePoints = traceRouteFromGraph(train);
        if (routePoints.isEmpty()) return;

        // Converter pontos em chunks (apenas no centro da rota, sem vizinhos)
        Set<ChunkPos> routeChunks = new LinkedHashSet<>();
        for (double[] point : routePoints) {
            int chunkX = ((int) Math.floor(point[0])) >> 4;
            int chunkZ = ((int) Math.floor(point[1])) >> 4;
            routeChunks.add(new ChunkPos(chunkX, chunkZ));
            if (routeChunks.size() >= MAX_PRELOAD_PER_TRAIN) break;
        }

        // Filtrar chunks já pedidas
        Set<ChunkPos> previous = lastRequestedChunks.getOrDefault(trainId, Collections.emptySet());
        Set<ChunkPos> newChunks = new LinkedHashSet<>(routeChunks);
        newChunks.removeAll(previous);

        if (newChunks.isEmpty()) return;
        lastRequestedChunks.put(trainId, routeChunks);

        // Pré-carregar em memória (NÃO force-load) no main thread
        level.getServer().execute(() -> {
            try {
                ServerChunkCache chunkSource = level.getChunkSource();
                for (ChunkPos pos : newChunks) {
                    // getChunkFuture: carrega o chunk para memória sem entity-ticking
                    // O chunk fica em cache do ChunkMap, pronto para quando o jogador chegar
                    chunkSource.getChunkFuture(pos.x, pos.z, ChunkStatus.FULL, true);
                }
            } catch (Exception e) {
                // Best-effort
            }
        });
    }

    /**
     * Traçar a rota do comboio através do grafo ferroviário.
     */
    private List<double[]> traceRouteFromGraph(Train train) {
        List<double[]> points = new ArrayList<>();
        TrackGraph graph = train.graph;
        if (graph == null) return points;

        boolean forward = train.speed > 0;

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

        while (distanceTraced < ROUTE_LOOKAHEAD_BLOCKS && points.size() < MAX_PRELOAD_PER_TRAIN * 2) {
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
        if (preloadExecutor != null) {
            preloadExecutor.shutdownNow();
        }
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
