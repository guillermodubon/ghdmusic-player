package io.github.guillermodubon.musicplayer.services.downloads.helpers.YTDLPApiHelpers;

import io.github.guillermodubon.musicplayer.services.downloads.helpers.DownloadFileNameHelper;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.control.ProgressIndicator;
import io.github.guillermodubon.musicplayer.services.downloads.dependencies.BundledMediaTools;
import io.github.guillermodubon.musicplayer.services.downloads.helpers.cache.DownloadUiCache;
import io.github.guillermodubon.musicplayer.services.downloads.logging.DownloadLog;
import io.github.guillermodubon.musicplayer.services.downloads.provider.ProviderOperation;
import io.github.guillermodubon.musicplayer.services.downloads.provider.YouTubeRequestCoordinator;
import io.github.guillermodubon.musicplayer.services.downloads.provider.YtDlpFailureClassifier;
import io.github.guillermodubon.musicplayer.services.downloads.provider.YtDlpFailureKind;
import io.github.guillermodubon.musicplayer.services.downloads.provider.YtDlpProcessGuard;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class YTDLPHelper {

    private static final YouTubeRequestCoordinator COORDINATOR = YouTubeRequestCoordinator.getInstance();
    private static final YtDlpFailureClassifier FAILURE_CLASSIFIER = new YtDlpFailureClassifier();

    private static final ThreadPoolExecutor LOOKUP_EXECUTOR = createLookupExecutor();

    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    public static void shutdown() {
        LOOKUP_EXECUTOR.shutdownNow();
    }

    

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

        submitLookup(() -> {
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
        }, () -> {
            if (query.equals(view.getUserData())) {
                if (spinner != null) spinner.setVisible(false);
                if (onNotFound != null) onNotFound.run();
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

        submitLookup(() -> {
            Process proc = null;
            YtDlpProcessGuard processGuard = null;
            YouTubeRequestCoordinator.Lease lease = null;
            StringBuilder output = new StringBuilder();
            Thread lookupThread = Thread.currentThread();
            try {
                DownloadLog.info("YTDLPHelper", "Fetching video title for query=\"" + query + "\"");
                YouTubeRequestCoordinator.Acquisition acquisition =
                        COORDINATOR.acquire(ProviderOperation.TITLE_LOOKUP, () -> false);
                if (!acquisition.granted()) {
                    Platform.runLater(() -> onTitle.accept("..."));
                    return;
                }
                lease = acquisition.lease();
                proc = BundledMediaTools.ytDlpProcessBuilder(List.of(
                        "--print", "%(title)s",
                        "ytsearch1:" + query
                ), null).redirectErrorStream(true).start();
                processGuard = YtDlpProcessGuard.watch(proc, lookupThread::isInterrupted);

                String realTitle = null;
                try (BufferedReader rd = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = rd.readLine()) != null) {
                        appendBounded(output, line);
                        YtDlpFailureKind lineFailure = FAILURE_CLASSIFIER.classifyLine(line);
                        if (lineFailure.isProviderRejection()) lease.reportFailure(lineFailure);
                        if (realTitle == null && !line.isBlank()) realTitle = line;
                    }
                }
                int exitCode = proc.waitFor();
                YtDlpFailureKind failure = FAILURE_CLASSIFIER.classify(output.toString(), exitCode, null);
                if (failure.isProviderRejection()) lease.reportFailure(failure);
                if (lease.cooldownState() != null) {
                    FAILURE_CLASSIFIER.retryAfter(output.toString()).ifPresent(lease::reportRetryAfter);
                }
                lease.complete(exitCode == 0 && failure == YtDlpFailureKind.NONE
                        ? YtDlpFailureKind.NONE : failure == YtDlpFailureKind.NONE
                        ? YtDlpFailureKind.UNKNOWN : failure);

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
                if (lease != null) lease.complete(FAILURE_CLASSIFIER.classify(output.toString(), -1, e));
                DownloadLog.error("YTDLPHelper", "Title lookup failed for query=\"" + query + "\"", e);
                Platform.runLater(() -> onTitle.accept("..."));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                if (lease != null) lease.complete(YtDlpFailureKind.UNKNOWN);
            } finally {
                if (proc != null && proc.isAlive()) proc.destroy();
                if (processGuard != null) processGuard.close();
                if (lease != null) lease.close();
            }
        }, () -> onTitle.accept("..."));
    }

    private static ThreadPoolExecutor createLookupExecutor() {
        var policy = COORDINATOR.policy();
        int parallelism = policy.maximumConcurrentAuxiliaryOperations();
        return new ThreadPoolExecutor(
                parallelism,
                parallelism,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(policy.maximumQueuedAuxiliaryLookups()),
                task -> {
                    Thread thread = new Thread(task, "yt-dlp-lookup");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private static void submitLookup(Runnable lookup, Runnable rejectedAction) {
        try {
            LOOKUP_EXECUTOR.execute(lookup);
        } catch (RejectedExecutionException rejected) {
            DownloadLog.warn("YTDLPHelper", "Skipped an auxiliary lookup because its queue is full");
            if (rejectedAction != null) {
                try {
                    Platform.runLater(rejectedAction);
                } catch (IllegalStateException ignored) {
                }
            }
        }
    }

    private static String probeVideoId(String query) throws IOException {
        Process proc = null;
        YtDlpProcessGuard processGuard = null;
        YouTubeRequestCoordinator.Lease lease = null;
        StringBuilder output = new StringBuilder();
        String videoId = null;
        Thread lookupThread = Thread.currentThread();
        try {
            DownloadLog.info("YTDLPHelper", "Probing video id for query=\"" + query + "\"");
            YouTubeRequestCoordinator.Acquisition acquisition =
                    COORDINATOR.acquire(ProviderOperation.THUMBNAIL_LOOKUP, () -> false);
            if (!acquisition.granted()) return null;
            lease = acquisition.lease();
            proc = BundledMediaTools.ytDlpProcessBuilder(List.of(
                    "--print", "%(id)s",
                    "ytsearch1:" + query
            ), null).redirectErrorStream(true).start();
            processGuard = YtDlpProcessGuard.watch(proc, lookupThread::isInterrupted);

            try (BufferedReader rd = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = rd.readLine()) != null) {
                    line = line.trim();
                    appendBounded(output, line);
                    YtDlpFailureKind lineFailure = FAILURE_CLASSIFIER.classifyLine(line);
                    if (lineFailure.isProviderRejection()) lease.reportFailure(lineFailure);
                    if (videoId == null && VIDEO_ID_PATTERN.matcher(line).matches()) videoId = line;
                }
            }
            int exitCode = proc.waitFor();
            YtDlpFailureKind failure = FAILURE_CLASSIFIER.classify(output.toString(), exitCode, null);
            if (failure.isProviderRejection()) lease.reportFailure(failure);
            if (lease.cooldownState() != null) {
                FAILURE_CLASSIFIER.retryAfter(output.toString()).ifPresent(lease::reportRetryAfter);
            }
            lease.complete(exitCode == 0 && failure == YtDlpFailureKind.NONE
                    ? YtDlpFailureKind.NONE : failure == YtDlpFailureKind.NONE
                    ? YtDlpFailureKind.UNKNOWN : failure);
            if (videoId != null && exitCode == 0) {
                DownloadLog.info("YTDLPHelper", "Resolved video id=" + videoId);
                return videoId;
            }
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (lease != null) lease.complete(YtDlpFailureKind.UNKNOWN);
            return null;
        } catch (IOException error) {
            if (lease != null) lease.complete(FAILURE_CLASSIFIER.classify(output.toString(), -1, error));
            throw error;
        } finally {
            if (proc != null && proc.isAlive()) proc.destroy();
            if (processGuard != null) processGuard.close();
            if (lease != null) lease.close();
        }
    }

    private static void appendBounded(StringBuilder output, String line) {
        if (line == null) return;
        if (output.length() > 0) output.append('\n');
        output.append(line);
        if (output.length() > 8_192) output.delete(0, output.length() - 8_192);
    }

}
