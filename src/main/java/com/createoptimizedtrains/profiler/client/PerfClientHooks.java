package com.createoptimizedtrains.profiler.client;

import com.createoptimizedtrains.CreateOptimizedTrains;
import com.createoptimizedtrains.profiler.PerfRecorder;
import com.createoptimizedtrains.profiler.PerfReportWriter;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.GlUtil;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;

/**
 * Parte cliente do Analisador de Desempenho: amostras de FPS/frame time/memória,
 * indicador "REC" no ecrã, botão no menu de pausa, paragem automática e gravação
 * do relatório.
 */
@OnlyIn(Dist.CLIENT)
public final class PerfClientHooks {

    private static final long SAMPLE_INTERVAL_MS = 1000;
    private static final int CHUNK_STATS_EVERY_N_SAMPLES = 5;

    // Último relatório gravado (mostrado no ecrã do analisador)
    private static PerfReportWriter.Report lastReport;
    private static String lastError;

    // Janela atual (render/client thread)
    private static final LongArrayList frameNanos = new LongArrayList();
    private static long lastFrameNanos;
    private static long windowStart;
    private static long lastGcMillis;
    private static double distanceMoved;
    private static double lastX, lastZ;
    private static boolean hasLastPos;
    private static int sampleCount;

    private PerfClientHooks() {
    }

    public static PerfReportWriter.Report getLastReport() {
        return lastReport;
    }

    public static String getLastError() {
        return lastError;
    }

    public static boolean canRecord() {
        return Minecraft.getInstance().level != null;
    }

    public static void startRecording() {
        if (PerfRecorder.isRecording() || !canRecord()) return;
        lastReport = null;
        lastError = null;
        frameNanos.clear();
        lastFrameNanos = 0;
        windowStart = System.currentTimeMillis();
        lastGcMillis = totalGcMillis();
        distanceMoved = 0;
        hasLastPos = false;
        sampleCount = 0;
        PerfRecorder.start();
    }

    /** Parar e gravar. Devolve o relatório, ou null se falhou (ver getLastError). */
    public static PerfReportWriter.Report stopAndSave() {
        if (!PerfRecorder.isRecording()) return lastReport;
        PerfRecorder.RecordingData data = PerfRecorder.stop();
        try {
            lastReport = PerfReportWriter.write(data, clientEnvironment());
            lastError = null;
        } catch (IOException | RuntimeException e) {
            lastReport = null;
            lastError = e.getMessage();
            CreateOptimizedTrains.LOGGER.error("COT: falha ao gravar o relatório do Performance Analyzer", e);
        }
        return lastReport;
    }

    /** Descartar a gravação sem gravar ficheiro. */
    public static void cancelRecording() {
        if (PerfRecorder.isRecording()) {
            PerfRecorder.stop();
        }
    }

    // ======================== Eventos (FORGE bus) ========================

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !PerfRecorder.isRecording()) return;
        long now = System.nanoTime();
        if (lastFrameNanos > 0) {
            frameNanos.add(now - lastFrameNanos);
        }
        lastFrameNanos = now;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();

        // Tecla de atalho (sem tecla atribuída por padrão) → abrir o analisador
        while (PerfClientSetup.OPEN_ANALYZER.consumeClick()) {
            if (mc.screen == null) {
                mc.setScreen(new PerfAnalyzerScreen(null));
            }
        }

        if (!PerfRecorder.isRecording()) return;

        if (mc.player != null) {
            double x = mc.player.getX();
            double z = mc.player.getZ();
            if (hasLastPos) {
                distanceMoved += Math.sqrt((x - lastX) * (x - lastX) + (z - lastZ) * (z - lastZ));
            }
            lastX = x;
            lastZ = z;
            hasLastPos = true;
        }

        if (PerfRecorder.elapsedMillis() >= PerfRecorder.MAX_DURATION_MS) {
            PerfReportWriter.Report report = stopAndSave();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal(report != null
                    ? "[Create Optimized Trains] Recording stopped (5 min limit). Report saved: "
                        + report.file().getFileName() + " — open the Performance Analyzer to report it."
                    : "[Create Optimized Trains] Recording stopped, but the report could not be saved.")
                    .withStyle(ChatFormatting.YELLOW), false);
            }
            return;
        }

        long now = System.currentTimeMillis();
        if (now - windowStart >= SAMPLE_INTERVAL_MS) {
            flushClientWindow(mc, now);
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        if (PerfRecorder.isRecording()) {
            stopAndSave();
        }
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof PauseScreen pause)) return;
        String label = PerfRecorder.isRecording() ? "● Performance Analyzer" : "Performance Analyzer";
        event.addListener(Button.builder(Component.literal(label),
                b -> Minecraft.getInstance().setScreen(new PerfAnalyzerScreen(pause)))
            .bounds(4, 4, 130, 20)
            .build());
    }

    // ======================== Overlay (MOD bus) ========================

    public static void registerOverlay(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("cot_perf_recording", PerfClientHooks::renderOverlay);
    }

    private static void renderOverlay(ForgeGui gui, GuiGraphics g, float partialTick, int width, int height) {
        if (!PerfRecorder.isRecording()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;

        long elapsed = PerfRecorder.elapsedMillis() / 1000;
        long max = PerfRecorder.MAX_DURATION_MS / 1000;
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        String header = (blink ? "● " : "  ") + String.format("REC %02d:%02d / %02d:%02d",
            elapsed / 60, elapsed % 60, max / 60, max % 60);

        JsonObject client = PerfRecorder.getLatestClient();
        JsonObject server = PerfRecorder.getLatestServer();
        String fpsLine = client != null
            ? String.format("FPS %.0f  |  frame p95 %.1f ms", num(client, "fps"), num(client, "frameP95Ms"))
            : "FPS …";
        String serverLine = server != null
            ? String.format("MSPT %.1f (max %.1f)  |  mod %.2f ms",
                num(server, "msptAvg"), num(server, "msptMax"), num(server, "modMsAvg"))
            : "Server: no data yet";
        String trainLine = server != null
            ? String.format("Trains %d active / %d ghost  |  chunks %d forced, %d prewarm",
                (int) num(server, "trainsActive"), (int) num(server, "trainsGhost"),
                (int) num(server, "forcedChunks"), (int) num(server, "prewarmChunks"))
            : "";

        String[] lines = {header, fpsLine, serverLine, trainLine};
        int boxWidth = 0;
        for (String l : lines) boxWidth = Math.max(boxWidth, mc.font.width(l));
        int x = width - boxWidth - 8;
        int y = 4;
        int lineHeight = mc.font.lineHeight + 2;
        g.fill(x - 4, y - 2, width - 4, y + lineHeight * lines.length, 0x90000000);
        g.drawString(mc.font, lines[0], x, y, 0xFF5555);
        for (int i = 1; i < lines.length; i++) {
            g.drawString(mc.font, lines[i], x, y + lineHeight * i, 0xDDDDDD);
        }
    }

    // ======================== Amostras ========================

    private static void flushClientWindow(Minecraft mc, long now) {
        double seconds = (now - windowStart) / 1000.0;
        JsonObject s = new JsonObject();

        long[] frames = frameNanos.toLongArray();
        java.util.Arrays.sort(frames);
        s.addProperty("fps", round(frames.length / Math.max(0.001, seconds)));
        s.addProperty("frameP50Ms", round(percentileMs(frames, 0.50)));
        s.addProperty("frameP95Ms", round(percentileMs(frames, 0.95)));
        s.addProperty("frameMaxMs", round(frames.length > 0 ? frames[frames.length - 1] / 1_000_000.0 : 0));

        Runtime rt = Runtime.getRuntime();
        s.addProperty("memUsedMB", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        long gc = totalGcMillis();
        s.addProperty("gcMs", gc - lastGcMillis);
        lastGcMillis = gc;

        s.addProperty("onTrain", isOnTrain(mc));
        s.addProperty("speedBlocksPerSec", round(distanceMoved / Math.max(0.001, seconds)));
        if (mc.level != null) {
            s.addProperty("dimension", mc.level.dimension().location().toString());
        }
        if (sampleCount % CHUNK_STATS_EVERY_N_SAMPLES == 0 && mc.levelRenderer != null) {
            s.addProperty("chunkStats", mc.levelRenderer.getChunkStatistics());
        }

        PerfRecorder.addClientSample(s);
        sampleCount++;
        frameNanos.clear();
        distanceMoved = 0;
        windowStart = now;
    }

    private static JsonObject clientEnvironment() {
        Minecraft mc = Minecraft.getInstance();
        JsonObject env = new JsonObject();
        try {
            env.addProperty("gpu", GlUtil.getRenderer());
            env.addProperty("cpu", GlUtil.getCpuInfo());
        } catch (Exception ignored) {
            // Sem contexto GL disponível
        }
        env.addProperty("renderDistance", mc.options.renderDistance().get());
        env.addProperty("simulationDistance", mc.options.simulationDistance().get());
        env.addProperty("graphics", mc.options.graphicsMode().get().name());
        env.addProperty("framerateLimit", mc.options.framerateLimit().get());
        env.addProperty("window", mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight());
        env.addProperty("singleplayer", mc.getSingleplayerServer() != null);
        return env;
    }

    private static boolean isOnTrain(Minecraft mc) {
        if (mc.player == null) return false;
        Entity vehicle = mc.player.getVehicle();
        int depth = 0;
        while (vehicle != null && depth < 5) {
            if (vehicle instanceof CarriageContraptionEntity) return true;
            vehicle = vehicle.getVehicle();
            depth++;
        }
        return false;
    }

    private static double percentileMs(long[] sorted, double p) {
        if (sorted.length == 0) return 0;
        int idx = (int) Math.min(sorted.length - 1, Math.floor(p * sorted.length));
        return sorted[idx] / 1_000_000.0;
    }

    private static long totalGcMillis() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long t = gc.getCollectionTime();
            if (t > 0) total += t;
        }
        return total;
    }

    private static double num(JsonObject o, String key) {
        var e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsDouble() : 0;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
