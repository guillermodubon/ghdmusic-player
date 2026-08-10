package io.github.guillermodubon.musicplayer.services.downloads;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import io.github.guillermodubon.musicplayer.services.downloads.bulk.BulkDownloadManager;
import io.github.guillermodubon.musicplayer.services.downloads.logging.DownloadLog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Coordinates the download part of the application shutdown lifecycle.
 *
 * <p>The coordinator does not change the normal download pipeline. It only
 * becomes active after the user confirms that the application should close,
 * then reuses each task's regular cancellation and token-scoped cleanup.</p>
 */
public final class DownloadShutdownCoordinator {

    private final DownloadManager downloadManager = DownloadManager.getInstance();
    private final AtomicReference<CompletableFuture<Void>> cancellationFuture =
            new AtomicReference<>();
    private final ReadOnlyDoubleWrapper cleanupProgress =
            new ReadOnlyDoubleWrapper(0.0);

    public boolean hasActiveDownloads() {
        return downloadManager.getTasks().stream()
                .anyMatch(task -> task != null && !task.isExecutionComplete());
    }

    public ReadOnlyDoubleProperty cleanupProgressProperty() {
        return cleanupProgress.getReadOnlyProperty();
    }

    /**
     * Stops new submissions, cancels bulk sessions before individual tasks and
     * completes only after every task has finished its worker-side cleanup.
     */
    public CompletableFuture<Void> cancelActiveDownloadsAndAwaitCleanup() {
        CompletableFuture<Void> existing = cancellationFuture.get();
        if (existing != null) return existing;

        downloadManager.stopAcceptingDownloads();

        Set<DownloadTask> activeTasks = new LinkedHashSet<>();
        for (DownloadTask task : downloadManager.getTasks()) {
            if (task != null && !task.isExecutionComplete()) {
                activeTasks.add(task);
            }
        }

        cleanupProgressOnFxThread(0.0);

        /*
         * A bulk session must receive the cancellation request first. Its
         * completion listener otherwise could schedule the next songs while
         * this shutdown operation is taking its snapshot.
         */
        BulkDownloadManager.getInstance().cancelAllSessions();

        /* A task can already be in CANCELLED state here, but its worker may
         * still be unwinding. Keep the original snapshot so its completion
         * future is still awaited. */
        for (DownloadTask task : downloadManager.getTasks()) {
            if (task != null && !task.isExecutionComplete()) {
                activeTasks.add(task);
            }
        }

        List<CompletableFuture<Void>> cleanupFutures = new ArrayList<>(activeTasks.size());
        AtomicInteger completedTasks = new AtomicInteger();
        int totalTasks = activeTasks.size();

        if (totalTasks == 0) {
            cleanupProgressOnFxThread(1.0);
        }

        for (DownloadTask task : activeTasks) {
            CompletableFuture<Void> cleanupFuture = task.cancelAndAwaitCleanup();
            cleanupFuture.whenComplete((ignored, error) -> {
                int completed = completedTasks.incrementAndGet();
                cleanupProgressOnFxThread((double) completed / Math.max(1, totalTasks));
            });
            cleanupFutures.add(cleanupFuture);
        }

        CompletableFuture<Void> combined = CompletableFuture.allOf(
                cleanupFutures.toArray(new CompletableFuture[0])
        ).handle((ignored, error) -> {
            cleanupProgressOnFxThread(1.0);
            if (error != null) {
                DownloadLog.error(
                        "DownloadShutdownCoordinator",
                        "Download cleanup finished with an error",
                        error
                );
            }
            return null;
        });

        if (cancellationFuture.compareAndSet(null, combined)) {
            return combined;
        }

        return cancellationFuture.get();
    }

    private void cleanupProgressOnFxThread(double progress) {
        double safeProgress = Math.max(0.0, Math.min(1.0, progress));
        Runnable update = () -> cleanupProgress.set(safeProgress);

        if (Platform.isFxApplicationThread()) {
            update.run();
        } else {
            Platform.runLater(update);
        }
    }
}
