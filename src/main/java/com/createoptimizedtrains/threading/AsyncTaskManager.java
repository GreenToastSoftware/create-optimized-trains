package com.createoptimizedtrains.threading;

import com.createoptimizedtrains.CreateOptimizedTrains;
import com.createoptimizedtrains.config.ModConfig;

import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class AsyncTaskManager {

    private final ExecutorService executor;
    private final ConcurrentLinkedQueue<Runnable> mainThreadQueue = new ConcurrentLinkedQueue<>();

    public AsyncTaskManager() {
        int poolSize = ModConfig.THREAD_POOL_SIZE.get();
        this.executor = new ThreadPoolExecutor(
                poolSize,
                poolSize,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(256),
                r -> {
                    Thread t = new Thread(r, "CreateOptTrains-Worker");
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                },
                new ThreadPoolExecutor.DiscardOldestPolicy()
        );
    }

    /**
     * Submeter tarefa para thread secundária.
     * O resultado é processado no main thread via callback.
     *
     * SEGURO para: cálculo de rotas, previsão de colisões,
     * otimização de horários, gestão de prioridades, decisão de LOD, simulação preditiva.
     *
     * NÃO usar para: movimento de entidades, física do Create,
     * renderização, interação com blocos.
     */
    public <T> void submitAsync(Supplier<T> task, Consumer<T> onComplete) {
        executor.submit(() -> {
            try {
                T result = task.get();
                // O resultado é enfileirado para processamento no main thread
                mainThreadQueue.add(() -> onComplete.accept(result));
            } catch (Exception e) {
                CreateOptimizedTrains.LOGGER.error("Erro em tarefa async: ", e);
            }
        });
    }

    /**
     * Submeter tarefa fire-and-forget para thread secundária.
     */
    public void submitAsync(Runnable task) {
        executor.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                CreateOptimizedTrains.LOGGER.error("Erro em tarefa async: ", e);
            }
        });
    }

    /**
     * Submeter resultado para ser processado no main thread.
     * Chamar isto de dentro de uma thread secundária.
     */
    public void runOnMainThread(Runnable task) {
        mainThreadQueue.add(task);
    }

    /**
     * Processar todas as tarefas pendentes no main thread.
     * Deve ser chamado a cada tick do servidor.
     */
    public void processMainThreadQueue() {
        int maxPerTick = 50; // Limitar para não bloquear o tick
        int processed = 0;

        Runnable task;
        while (processed < maxPerTick && (task = mainThreadQueue.poll()) != null) {
            try {
                task.run();
            } catch (Exception e) {
                CreateOptimizedTrains.LOGGER.error("Erro ao processar tarefa no main thread: ", e);
            }
            processed++;
        }
    }

    /**
     * Submeter tarefa com timeout.
     */
    public <T> CompletableFuture<T> submitWithTimeout(Supplier<T> task, long timeoutMs) {
        CompletableFuture<T> future = new CompletableFuture<>();

        executor.submit(() -> {
            try {
                T result = task.get();
                future.complete(result);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        // Timeout handling
        CompletableFuture.delayedExecutor(timeoutMs, TimeUnit.MILLISECONDS)
                .execute(() -> {
                    if (!future.isDone()) {
                        future.cancel(true);
                    }
                });

        return future;
    }

    public int getPendingMainThreadTasks() {
        return mainThreadQueue.size();
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
}
