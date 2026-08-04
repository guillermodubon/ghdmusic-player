package io.github.guillermodubon.musicplayer.controllers.ui.components.downloadComponents;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DownloadLocationOpener {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "download-location-opener");
        thread.setDaemon(true);
        return thread;
    });

    private DownloadLocationOpener() {
    }

    public static void open(File downloadedFile, File fallbackDirectory) {
        File target = downloadedFile != null && downloadedFile.exists()
                ? downloadedFile
                : fallbackDirectory;
        if (target == null || !target.exists()) return;

        EXECUTOR.execute(() -> openNative(target));
    }

    private static void openNative(File target) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                if (target.isFile()) {
                    new ProcessBuilder("explorer.exe", "/select,", target.getAbsolutePath()).start();
                } else {
                    new ProcessBuilder("explorer.exe", target.getAbsolutePath()).start();
                }
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", target.getAbsolutePath()).start();
            } else {
                File directory = target.isDirectory() ? target : target.getParentFile();
                if (directory != null) {
                    new ProcessBuilder("xdg-open", directory.getAbsolutePath()).start();
                }
            }
        } catch (Exception ignored) {
            // Opening a location is a convenience action and must not affect the download pipeline.
        }
    }
}
