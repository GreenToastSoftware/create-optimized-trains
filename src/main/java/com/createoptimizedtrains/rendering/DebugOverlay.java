package com.createoptimizedtrains.rendering;

import com.createoptimizedtrains.CreateOptimizedTrains;
import com.createoptimizedtrains.chunks.ChunkLoadManager;
import com.createoptimizedtrains.chunks.RouteChunkPreloader;
import com.createoptimizedtrains.config.ModConfig;
import com.createoptimizedtrains.monitor.PerformanceMonitor;
import com.createoptimizedtrains.threading.AsyncTaskManager;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.lang.management.ManagementFactory;
import java.util.List;

public class DebugOverlay {

    private static final String PREFIX = "\u00A7e[COT]\u00A7r ";

    // Cache de thread count — atualizar a cada 60 frames (~1s) para evitar flickering
    private int cachedJvmThreadCount = 0;
    private int threadCountFrameCounter = 0;
    private static final int THREAD_COUNT_UPDATE_INTERVAL = 60;

    @SubscribeEvent
    public void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        if (!ModConfig.DEBUG_OVERLAY_ENABLED.get()) return;

        CreateOptimizedTrains mod = CreateOptimizedTrains.getInstance();
        if (mod == null) return;

        List<String> right = event.getRight();
        right.add("");
        right.add(PREFIX + "\u00A7bCreate Optimized Trains");

        addMemInfo(right);
        addThreadInfo(right, mod);
        addServerInfo(right, mod);
        addChunkInfo(right, mod);
    }

    private void addMemInfo(List<String> lines) {
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        long totalMem = Runtime.getRuntime().totalMemory() / (1024 * 1024);
        long freeMem = Runtime.getRuntime().freeMemory() / (1024 * 1024);
        long usedMem = totalMem - freeMem;
        long maxMem = Runtime.getRuntime().maxMemory() / (1024 * 1024);

        lines.add(PREFIX + String.format("Mem: \u00A7a%dMB\u00A7r / %dMB (max %dMB) | %d cores", usedMem, totalMem, maxMem, availableProcessors));
    }

    private void addThreadInfo(List<String> lines, CreateOptimizedTrains mod) {
        AsyncTaskManager async = mod.getAsyncTaskManager();
        if (async == null) return;

        int poolSize = async.getPoolSize();
        int active = async.getActiveTasks();
        int queued = async.getQueuedSubmissions();
        int pending = async.getPendingMainThreadTasks();

        lines.add(PREFIX + String.format("Threads: \u00A7a%d\u00A7r pool, \u00A7e%d\u00A7r active, \u00A7e%d\u00A7r queued", poolSize, active, queued));
        if (pending > 0) {
            lines.add(PREFIX + String.format("  Main-thread queue: \u00A7c%d\u00A7r pending", pending));
        }

        // Threads Java totais do processo (cacheado para evitar flickering)
        if (++threadCountFrameCounter >= THREAD_COUNT_UPDATE_INTERVAL || cachedJvmThreadCount == 0) {
            threadCountFrameCounter = 0;
            cachedJvmThreadCount = ManagementFactory.getThreadMXBean().getThreadCount();
        }
        lines.add(PREFIX + String.format("JVM Threads: \u00A7a%d\u00A7r total", cachedJvmThreadCount));
    }

    private void addServerInfo(List<String> lines, CreateOptimizedTrains mod) {
        PerformanceMonitor monitor = mod.getPerformanceMonitor();
        if (monitor == null) return;

        String stateColor = switch (monitor.getState()) {
            case NORMAL -> "\u00A7a";
            case DEGRADED -> "\u00A7e";
            case CRITICAL -> "\u00A7c";
        };

        lines.add(PREFIX + String.format("TPS: %s%.1f\u00A7r MSPT: %s%.1f\u00A7rms Peak: %s%.1f\u00A7rms",
                stateColor, monitor.getCurrentTPS(),
                stateColor, monitor.getAverageMSPT(),
                stateColor, monitor.getPeakMSPT()));
        lines.add(PREFIX + String.format("State: %s%s\u00A7r Factor: \u00A7a%.2f",
                stateColor, monitor.getState(), monitor.getPerformanceFactor()));

        double clientFps = RenderOptimizer.getClientFPS();
        if (clientFps > 0) {
            lines.add(PREFIX + String.format("Client FPS: \u00A7a%.0f", clientFps));
        }
    }

    private void addChunkInfo(List<String> lines, CreateOptimizedTrains mod) {
        ChunkLoadManager chunks = mod.getChunkLoadManager();
        RouteChunkPreloader preloader = mod.getRouteChunkPreloader();

        int forced = chunks != null ? chunks.getLoadedChunkCount() : 0;
        int preloaded = preloader != null ? preloader.getPreloadedCount() : 0;

        lines.add(PREFIX + String.format("Chunks: \u00A7e%d\u00A7r forced, \u00A7b%d\u00A7r route-cached", forced, preloaded));
    }
}
