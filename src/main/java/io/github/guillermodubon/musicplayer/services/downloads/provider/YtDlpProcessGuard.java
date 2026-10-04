package io.github.guillermodubon.musicplayer.services.downloads.provider;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Terminates a media process promptly when its owning task is cancelled. */
public final class YtDlpProcessGuard implements AutoCloseable {

    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread watcher;

    private YtDlpProcessGuard(Process process, BooleanSupplier cancelled) {
        watcher = new Thread(() -> {
            while (!closed.get() && process.isAlive()) {
                if (Thread.currentThread().isInterrupted()
                        || (cancelled != null && cancelled.getAsBoolean())) {
                    process.destroyForcibly();
                    return;
                }
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "yt-dlp-process-guard");
        watcher.setDaemon(true);
        watcher.start();
    }

    public static YtDlpProcessGuard watch(Process process, BooleanSupplier cancelled) {
        return process == null ? null : new YtDlpProcessGuard(process, cancelled);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        watcher.interrupt();
        try {
            watcher.join(250L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
