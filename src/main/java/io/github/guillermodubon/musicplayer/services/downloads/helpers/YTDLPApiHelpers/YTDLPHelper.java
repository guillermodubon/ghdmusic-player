package io.github.guillermodubon.musicplayer.services.downloads.helpers.YTDLPApiHelpers;

import io.github.guillermodubon.musicplayer.services.downloads.helpers.DownloadFileNameHelper;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.control.ProgressIndicator;
import io.github.guillermodubon.musicplayer.services.downloads.dependencies.BundledMediaTools;
import io.github.guillermodubon.musicplayer.services.downloads.helpers.cache.DownloadUiCache;
import io.github.guillermodubon.musicplayer.services.downloads.logging.DownloadLog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class YTDLPHelper {

    private static final ExecutorService LOOKUP_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "yt-dlp-lookup");
        t.setDaemon(true);
        return t;
    });

    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    

    public static void loadThumbnail(
            String query,
            ImageView view,
            ProgressIndicator spinner,
            Runnable onNotFound
    ) {
        if (query == null || query.isBlank() || view == null) return;

        Platform.runLater(() -> {
            view.setUserData(query);
            if (spinner != null) spinner.setVisible(true);
        });

        LOOKUP_EXECUTOR.submit(() -> {
            Process proc = null;
            try {
                DownloadLog.info("YTDLPHelper", "Looking up thumbnail for query=\"" + query + "\"");
                String videoId = probeVideoId(query);
                if (videoId == null) {
                    DownloadLog.warn("YTDLPHelper", "No video id found for thumbnail query=\"" + query + "\"");
                    Platform.runLater(() -> {
                        if (query.equals(view.getUserData())) {
                            if (spinner != null) spinner.setVisible(false);
                            if (onNotFound != null) onNotFound.run();
                        }
                    });
                    return;
                }

                String thumbUrl = "https://img.youtube.com/vi/" + videoId + "/hqdefault.jpg";
                DownloadLog.info("YTDLPHelper", "Loading thumbnail " + thumbUrl);
                Image img = new Image(thumbUrl, true);

                img.progressProperty().addListener((obs, oldV, newV) -> {
                    if (newV.doubleValue() >= 1.0) {
                        Platform.runLater(() -> {
                            if (query.equals(view.getUserData())) {
                                view.setImage(img);
                                if (spinner != null) spinner.setVisible(false);
                            }
                        });
                    }
                });

                img.errorProperty().addListener((obs, oldV, newV) -> {
                    if (Boolean.TRUE.equals(newV)) {
                        Platform.runLater(() -> {
                            if (query.equals(view.getUserData())) {
                                if (spinner != null) spinner.setVisible(false);
                                if (onNotFound != null) onNotFound.run();
                            }
                        });
                    }
                });

            } catch (IOException e) {
                DownloadLog.error("YTDLPHelper", "Thumbnail lookup failed for query=\"" + query + "\"", e);
                Platform.runLater(() -> {
                    if (query.equals(view.getUserData())) {
                        if (spinner != null) spinner.setVisible(false);
                        if (onNotFound != null) onNotFound.run();
                    }
                });
            } finally {
                if (proc != null && proc.isAlive()) proc.destroy();
            }
        });
    }

    public static void fetchVideoTitle(String query, Consumer<String> onTitle) {
        if (query == null || query.isBlank() || onTitle == null) return;

        if (DownloadUiCache.hasCleanTitle(query)) {
            String cached = DownloadUiCache.getCleanTitle(query);
            DownloadLog.info("YTDLPHelper", "Using cached title for query=\"" + query + "\" -> " + cached);
            Platform.runLater(() -> onTitle.accept(cached == null ? "..." : cached));
            return;
        }

        LOOKUP_EXECUTOR.submit(() -> {
            Process proc = null;
            try {
                DownloadLog.info("YTDLPHelper", "Fetching video title for query=\"" + query + "\"");
                proc = BundledMediaTools.ytDlpProcessBuilder(List.of(
                        "--print", "%(title)s",
                        "ytsearch1:" + query
                ), null).redirectErrorStream(true).start();

                String realTitle;
                try (BufferedReader rd = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    realTitle = rd.readLine();
                }

                if (realTitle == null || realTitle.isBlank()) {
                    realTitle = "...";
                }

                realTitle = realTitle.trim();
                String cleanedTitle = DownloadFileNameHelper.cleanTitle(realTitle);
                DownloadLog.info("YTDLPHelper", "Resolved title=\"" + cleanedTitle + "\"");

                DownloadUiCache.putFetchedTitle(query, realTitle);
                DownloadUiCache.putCleanTitle(query, cleanedTitle);

                Platform.runLater(() -> onTitle.accept(cleanedTitle));
            } catch (IOException e) {
                DownloadLog.error("YTDLPHelper", "Title lookup failed for query=\"" + query + "\"", e);
                Platform.runLater(() -> onTitle.accept("..."));
            } finally {
                if (proc != null && proc.isAlive()) proc.destroy();
            }
        });
    }

    private static String probeVideoId(String query) throws IOException {
        Process proc = null;
        try {
            DownloadLog.info("YTDLPHelper", "Probing video id for query=\"" + query + "\"");
            proc = BundledMediaTools.ytDlpProcessBuilder(List.of(
                    "--print", "%(id)s",
                    "ytsearch1:" + query
            ), null).redirectErrorStream(true).start();

            try (BufferedReader rd = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = rd.readLine()) != null) {
                    line = line.trim();
                    if (VIDEO_ID_PATTERN.matcher(line).matches()) {
                        DownloadLog.info("YTDLPHelper", "Resolved video id=" + line);
                        return line;
                    }
                }
            }
            return null;
        } finally {
            if (proc != null && proc.isAlive()) proc.destroy();
        }
    }

}
