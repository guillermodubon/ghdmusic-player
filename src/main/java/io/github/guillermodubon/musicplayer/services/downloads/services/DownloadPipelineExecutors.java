package io.github.guillermodubon.musicplayer.services.downloads.services;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class DownloadPipelineExecutors {

    private static final int METADATA_WORKERS = Math.max(
            2,
            Math.min(4, Runtime.getRuntime().availableProcessors() / 2)
    );

    private static final ExecutorService METADATA = Executors.newFixedThreadPool(
            METADATA_WORKERS,
            daemonThreadFactory("download-metadata")
    );

    private static final ExecutorService COMPLETION = Executors.newFixedThreadPool(
            2,
            daemonThreadFactory("download-completion")
    );

    /*
     * SQLite writes and model hydration share mutable application state. A
     * single durable lane avoids lock contention and out-of-order cache
     * updates while yt-dlp and metadata lookups remain parallel.
     */
    private static final ExecutorService PERSISTENCE = Executors.newSingleThreadExecutor(
            daemonThreadFactory("download-persistence")
    );

    private DownloadPipelineExecutors() {
    }

    public static Executor metadata() {
        return METADATA;
    }

    public static Executor completion() {
        return COMPLETION;
    }

    public static Executor persistence() {
        return PERSISTENCE;
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
