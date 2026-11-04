package com.createoptimizedtrains.profiler;

import com.createoptimizedtrains.CreateOptimizedTrains;
import com.createoptimizedtrains.chunks.ChunkLoadManager;
import com.createoptimizedtrains.chunks.ChunkPrewarmer;
import com.createoptimizedtrains.monitor.WorldLoadPhaseManager;
import com.createoptimizedtrains.util.CarriageUtils;
import com.createoptimizedtrains.util.PlayerTrainTracker;
import com.google.gson.JsonObject;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Coletor do Analisador de Desempenho.
 *
 * Só faz trabalho enquanto uma gravação está ativa — fora disso cada hook é um único
 * teste a um boolean volatile. Recolhe amostras de 1 em 1 segundo:
 *   - servidor (server thread): MSPT, ticks lentos, tempo do mod, comboios, chunks;
 *   - cliente (render thread, ver PerfClientHooks): FPS, frame times, memória.
 * Em singleplayer as duas partes correm no mesmo processo e vão para o mesmo relatório.
 */
public final class PerfRecorder {

    /** Duração máxima de uma gravação; depois disso pára e grava sozinha. */
    public static final long MAX_DURATION_MS = 5 * 60 * 1000L;

    private static final long SAMPLE_INTERVAL_MS = 1000;
    private static final double SLOW_TICK_EVENT_MS = 100.0;
    private static final int MAX_EVENTS = 2000;

    private static final Object LOCK = new Object();
    private static volatile boolean recording = false;
    private static volatile long startMillis = 0;

    private static final List<JsonObject> serverSamples = new ArrayList<>();
    private static final List<JsonObject> clientSamples = new ArrayList<>();
    private static final List<JsonObject> events = new ArrayList<>();
    private static final Map<UUID, Integer> stutterTicksByTrain = new HashMap<>();

    // Amostras mais recentes, para o indicador no ecrã
    private static volatile JsonObject latestServer;
    private static volatile JsonObject latestClient;

    // Janela atual do servidor (só server thread)
    private static long windowStart;
    private static int windowTicks;
    private static double windowMsptSum;
    private static double windowMsptMax;
    private static int windowSlowTicks;
    private static long windowModNanos;
    private static long lastTickModNanos;
    private static int windowTrainTicks;
    private static int windowStutterTicks;
    private static int windowOutsideChunkTicks;
    private static int windowMissingEntityTicks;
    private static int windowImmuneTicks;
    private static int windowChunkWaitTicks;

    private PerfRecorder() {
    }

    public static boolean isRecording() {
        return recording;
    }

    public static long elapsedMillis() {
        return recording ? System.currentTimeMillis() - startMillis : 0;
    }

    public static JsonObject getLatestServer() {
        return latestServer;
    }

    public static JsonObject getLatestClient() {
        return latestClient;
    }

    public static void start() {
        synchronized (LOCK) {
            serverSamples.clear();
            clientSamples.clear();
            events.clear();
            stutterTicksByTrain.clear();
            latestServer = null;
            latestClient = null;
            startMillis = System.currentTimeMillis();
            windowStart = startMillis;
            resetServerWindow();
            recording = true;
        }
        CreateOptimizedTrains.LOGGER.info("COT: Performance Analyzer — gravação iniciada.");
    }

    /** Parar a gravação e devolver tudo o que foi recolhido. */
    public static RecordingData stop() {
        synchronized (LOCK) {
            recording = false;
            RecordingData data = new RecordingData(
                startMillis, System.currentTimeMillis(),
                new ArrayList<>(serverSamples), new ArrayList<>(clientSamples),
                new ArrayList<>(events), new HashMap<>(stutterTicksByTrain));
            CreateOptimizedTrains.LOGGER.info("COT: Performance Analyzer — gravação parada ({} s).",
                (data.endMillis() - data.startMillis()) / 1000);
            return data;
        }
    }

    public record RecordingData(long startMillis, long endMillis,
                                List<JsonObject> serverSamples, List<JsonObject> clientSamples,
                                List<JsonObject> events, Map<UUID, Integer> stutterTicksByTrain) {
    }

    // ======================== Servidor ========================

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (!recording) return;
        MinecraftServer server = event.getServer();
        if (server == null) return;

        if (event.phase == TickEvent.Phase.START) {
            // Antes do incremento de tickCount: tickTimes[tickCount % 100] é o tick anterior
            long nanos = server.tickTimes[server.getTickCount() % 100];
            if (nanos <= 0) return;
            double mspt = nanos / 1_000_000.0;
            windowTicks++;
            windowMsptSum += mspt;
            windowMsptMax = Math.max(windowMsptMax, mspt);
            if (mspt > 50.0) windowSlowTicks++;
            if (mspt >= SLOW_TICK_EVENT_MS) {
                JsonObject e = new JsonObject();
                e.addProperty("type", "slow_tick");
                e.addProperty("mspt", round(mspt));
                e.addProperty("modMs", round(lastTickModNanos / 1_000_000.0));
                addEvent(e);
            }
            return;
        }

        long now = System.currentTimeMillis();
        if (now - windowStart < SAMPLE_INTERVAL_MS) return;
        flushServerWindow(server, now);
    }

    /** Tempo gasto pelo TrainEventHandler neste tick (nanos). */
    public static void addModNanos(long nanos) {
        if (!recording) return;
        windowModNanos += nanos;
        lastTickModNanos = nanos;
    }

    /** Chamado por cada comboio, a cada tick, pelo TrainDiagnosticLogger. */
    public static void recordTrainTick(UUID trainId, boolean stutter, int outsideChunk,
                                       int missingEntity, int immuneTicks, boolean chunkWait) {
        if (!recording) return;
        windowTrainTicks++;
        if (!stutter) return;
        windowStutterTicks++;
        if (outsideChunk > 0) windowOutsideChunkTicks++;
        if (missingEntity > 0) windowMissingEntityTicks++;
        if (immuneTicks > 0) windowImmuneTicks++;
        if (chunkWait) windowChunkWaitTicks++;
        synchronized (LOCK) {
            stutterTicksByTrain.merge(trainId, 1, Integer::sum);
        }
    }

    private static void flushServerWindow(MinecraftServer server, long now) {
        JsonObject s = new JsonObject();
        s.addProperty("t", now - startMillis);
        s.addProperty("ticks", windowTicks);
        s.addProperty("msptAvg", windowTicks > 0 ? round(windowMsptSum / windowTicks) : 0);
        s.addProperty("msptMax", round(windowMsptMax));
        s.addProperty("slowTicks", windowSlowTicks);
        s.addProperty("modMsAvg", windowTicks > 0 ? round(windowModNanos / 1_000_000.0 / windowTicks) : 0);

        addTrainStats(s);

        CreateOptimizedTrains mod = CreateOptimizedTrains.getInstance();
        ChunkLoadManager chunks = mod != null ? mod.getChunkLoadManager() : null;
        s.addProperty("forcedChunks", chunks != null ? chunks.getLoadedChunkCount() : 0);
        s.addProperty("prewarmChunks", ChunkPrewarmer.getIssuedCount());
        s.addProperty("prewarmPending", ChunkPrewarmer.getPendingCount());
        s.addProperty("loadPhase", WorldLoadPhaseManager.getPhase().name());
        s.addProperty("viewDistance", server.getPlayerList().getViewDistance());

        s.addProperty("trainTicks", windowTrainTicks);
        s.addProperty("stutterTicks", windowStutterTicks);
        s.addProperty("outsideChunkTicks", windowOutsideChunkTicks);
        s.addProperty("missingEntityTicks", windowMissingEntityTicks);
        s.addProperty("immuneTicks", windowImmuneTicks);
        s.addProperty("chunkWaitTicks", windowChunkWaitTicks);

        synchronized (LOCK) {
            if (recording) serverSamples.add(s);
        }
        latestServer = s;
        windowStart = now;
        resetServerWindow();
    }

    private static void addTrainStats(JsonObject s) {
        int total = 0, active = 0, ghost = 0, moving = 0, occupied = 0, carriages = 0;
        try {
            var railways = com.simibubi.create.Create.RAILWAYS;
            if (railways != null && railways.trains != null) {
                for (Train train : railways.trains.values()) {
                    total++;
                    carriages += train.carriages.size();
                    if (Math.abs(train.speed) > 0.001) moving++;
                    if (PlayerTrainTracker.isOccupied(train.id)) occupied++;
                    boolean anyEntity = false;
                    for (Carriage c : train.carriages) {
                        if (CarriageUtils.safeAnyAvailableEntity(c) != null) {
                            anyEntity = true;
                            break;
                        }
                    }
                    if (anyEntity) active++;
                    else ghost++;
                }
            }
        } catch (Exception ignored) {
            // API do Create indisponível — amostra sem dados de comboios
        }
        s.addProperty("trains", total);
        s.addProperty("trainsActive", active);
        s.addProperty("trainsGhost", ghost);
        s.addProperty("trainsMoving", moving);
        s.addProperty("trainsOccupied", occupied);
        s.addProperty("carriages", carriages);
    }

    private static void resetServerWindow() {
        windowTicks = 0;
        windowMsptSum = 0;
        windowMsptMax = 0;
        windowSlowTicks = 0;
        windowModNanos = 0;
        windowTrainTicks = 0;
        windowStutterTicks = 0;
        windowOutsideChunkTicks = 0;
        windowMissingEntityTicks = 0;
        windowImmuneTicks = 0;
        windowChunkWaitTicks = 0;
    }

    // ======================== Cliente ========================

    public static void addClientSample(JsonObject sample) {
        synchronized (LOCK) {
            if (!recording) return;
            sample.addProperty("t", System.currentTimeMillis() - startMillis);
            clientSamples.add(sample);
        }
        latestClient = sample;
    }

    // ======================== Comum ========================

    public static void addEvent(JsonObject event) {
        synchronized (LOCK) {
            if (!recording || events.size() >= MAX_EVENTS) return;
            event.addProperty("t", System.currentTimeMillis() - startMillis);
            events.add(event);
        }
    }

    static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
