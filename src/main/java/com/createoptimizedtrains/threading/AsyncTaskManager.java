package com.createoptimizedtrains.threading;

import com.createoptimizedtrains.CreateOptimizedTrains;
import com.createoptimizedtrains.config.ModConfig;

import java.util.Collection;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class AsyncTaskManager {

    private final ForkJoinPool executor;
    private final ConcurrentLinkedQueue<MainThreadTask> mainThreadQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger activeTasks = new AtomicInteger(0);

    // Budget dinâmico: quantos ms podemos gastar no main thread por tick
    private static final double MAIN_THREAD_BUDGET_MS = 2.0;

    public AsyncTaskManager() {
        int poolSize = Math.max(2, Math.min(
                ModConfig.THREAD_POOL_SIZE.get(),
                Runtime.getRuntime().availableProcessors() - 1
        ));
        this.executor = new ForkJoinPool(
                poolSize,
                pool -> {
                    ForkJoinWorkerThread t = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
                    t.setName("CreateOptTrains-Worker-" + t.getPoolIndex());
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                },
                (t, e) -> CreateOptimizedTrains.LOGGER.error("Erro não tratado em {}: ", t.getName(), e),
                true // asyncMode = true para melhor throughput de tarefas independentes
        );
    }

    /**
     * Submeter tarefa para thread secundária.
     * O resultado é processado no main thread via callback.
     */
    public <T> void submitAsync(Supplier<T> task, Consumer<T> onComplete) {
        if (executor.isShutdown()) return;
        activeTasks.incrementAndGet();
        executor.execute(() -> {
            try {
                T result = task.get();
                mainThreadQueue.add(new MainThreadTask(() -> onComplete.accept(result), false));
            } catch (Exception e) {
                CreateOptimizedTrains.LOGGER.error("Erro em tarefa async: ", e);
            } finally {
                activeTasks.decrementAndGet();
            }
        });
    }

    /**
     * Submeter tarefa fire-and-forget para thread secundária.
     */
    public void submitAsync(Runnable task) {
        if (executor.isShutdown()) return;
        activeTasks.incrementAndGet();
        executor.execute(() -> {
            try {
                task.run();
            } catch (Exception e) {
                CreateOptimizedTrains.LOGGER.error("Erro em tarefa async: ", e);
            } finally {
                activeTasks.decrementAndGet();
            }
        });
    }

    /**
     * Processar uma coleção em paralelo usando o ForkJoinPool.
     * Cada item é processado numa thread secundária.
     * Os resultados são recolhidos e devolvidos no main thread.
     */
    public <T, R> CompletableFuture<List<R>> submitParallelBatch(
            Collection<T> items,
            Function<T, R> processor) {

        if (items.isEmpty() || executor.isShutdown()) {
            return CompletableFuture.completedFuture(List.of());
        }

        return CompletableFuture.supplyAsync(() -> {
            List<R> results = new ArrayList<>(items.size());
            List<ForkJoinTask<R>> tasks = new ArrayList<>(items.size());

            for (T item : items) {
                tasks.add(ForkJoinTask.adapt(() -> processor.apply(item)));
            }

            // Invocar todas as tarefas em paralelo no ForkJoinPool
            for (ForkJoinTask<R> task : tasks) {
                task.fork();
            }

            // Recolher resultados
            for (ForkJoinTask<R> task : tasks) {
                try {
                    R result = task.join();
                    if (result != null) {
                        results.add(result);
                    }
                } catch (Exception e) {
                    CreateOptimizedTrains.LOGGER.error("Erro em tarefa batch: ", e);
                }
            }

            return results;
        }, executor);
    }

    /**
     * Submeter resultado para ser processado no main thread.
     */
    public void runOnMainThread(Runnable task) {
        mainThreadQueue.add(new MainThreadTask(task, false));
    }

    /**
     * Submeter tarefa prioritária para o main thread (processada primeiro).
     */
    public void runOnMainThreadPriority(Runnable task) {
        mainThreadQueue.add(new MainThreadTask(task, true));
    }

    /**
     * Processar tarefas pendentes no main thread com budget de tempo.
     * Garante que não gastamos mais que MAIN_THREAD_BUDGET_MS por tick.
     */
    public void processMainThreadQueue() {
        long startNanos = System.nanoTime();
        long budgetNanos = (long) (MAIN_THREAD_BUDGET_MS * 1_000_000);
        int processed = 0;

        // Primeiro: processar todas as tarefas prioritárias (sem limite de budget)
        MainThreadTask task;
        while ((task = peekPriority()) != null) {
            mainThreadQueue.remove(task);
            try {
                task.runnable.run();
                processed++;
            } catch (Exception e) {
                CreateOptimizedTrains.LOGGER.error("Erro ao processar tarefa prioritária: ", e);
            }
        }

        // Depois: processar tarefas normais com budget de tempo
        while ((task = mainThreadQueue.poll()) != null) {
            try {
                task.runnable.run();
                processed++;
            } catch (Exception e) {
                CreateOptimizedTrains.LOGGER.error("Erro ao processar tarefa no main thread: ", e);
            }

            // Verificar budget a cada 8 tarefas para reduzir overhead de nanoTime()
            if (processed % 8 == 0 && (System.nanoTime() - startNanos) > budgetNanos) {
                break;
            }
        }
    }

    private MainThreadTask peekPriority() {
        for (MainThreadTask t : mainThreadQueue) {
            if (t.priority) return t;
        }
        return null;
    }

    /**
     * Submeter tarefa com timeout.
     */
    public <T> CompletableFuture<T> submitWithTimeout(Supplier<T> task, long timeoutMs) {
        if (executor.isShutdown()) return CompletableFuture.completedFuture(null);
        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> task.get(), executor);

        // Timeout handling
        return future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .exceptionally(e -> {
                    if (e instanceof TimeoutException) {
                        CreateOptimizedTrains.LOGGER.debug("Tarefa async expirou após {}ms", timeoutMs);
                    }
                    return null;
                });
    }

    public int getPendingMainThreadTasks() {
        return mainThreadQueue.size();
    }

    public int getActiveTasks() {
        return activeTasks.get();
    }

    public int getPoolSize() {
        return executor.getParallelism();
    }

    public int getQueuedSubmissions() {
        return executor.getQueuedSubmissionCount();
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private record MainThreadTask(Runnable runnable, boolean priority) {}
}
