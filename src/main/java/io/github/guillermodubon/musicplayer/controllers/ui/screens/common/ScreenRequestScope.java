package io.github.guillermodubon.musicplayer.controllers.ui.screens.common;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class ScreenRequestScope implements AutoCloseable {
    private final Set<Future<?>> pending = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicLong generation = new AtomicLong(0L);

    public boolean isActive() {
        return active.get();
    }

    public void restart() {
        cancelPending();
        generation.incrementAndGet();
        active.set(true);
    }

    public <T> CompletableFuture<T> supplyAsync(Callable<T> loader, ExecutorService executor) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (loader == null || executor == null || !active.get()) {
            result.cancel(false);
            return result;
        }

        long requestGeneration = generation.get();
        Future<?> future = executor.submit(() -> {
            try {
                if (!isCurrent(requestGeneration) || Thread.currentThread().isInterrupted()) {
                    result.cancel(false);
                    return;
                }

                T value = loader.call();

                if (!isCurrent(requestGeneration) || Thread.currentThread().isInterrupted()) {
                    result.cancel(false);
                    return;
                }
                result.complete(value);
            } catch (Throwable throwable) {
                if (!result.isCancelled()) {
                    result.completeExceptionally(throwable);
                }
            }
        });

        pending.add(future);
        result.whenComplete((value, throwable) -> {
            pending.remove(future);
            if (result.isCancelled()) {
                future.cancel(true);
            }
        });
        return result;
    }

    public void cancelPending() {
        for (Future<?> future : pending) {
            try {
                future.cancel(true);
            } catch (Exception ignored) {
            }
        }
        pending.clear();
    }

    @Override
    public void close() {
        active.set(false);
        generation.incrementAndGet();
        cancelPending();
    }

    private boolean isCurrent(long requestGeneration) {
        return active.get() && generation.get() == requestGeneration;
    }
}
