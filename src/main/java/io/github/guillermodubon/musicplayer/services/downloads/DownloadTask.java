package io.github.guillermodubon.musicplayer.services.downloads;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.concurrent.Task;
import javafx.scene.image.Image;
import io.github.guillermodubon.musicplayer.services.downloads.context.DownloadTaskContext;
import io.github.guillermodubon.musicplayer.services.downloads.managers.DownloadFileFinalizer;
import io.github.guillermodubon.musicplayer.services.downloads.managers.DownloadRetryPolicy;
import io.github.guillermodubon.musicplayer.services.downloads.managers.YtDlpRunner;
import io.github.guillermodubon.musicplayer.services.downloads.helpers.DownloadFileNameHelper;
import io.github.guillermodubon.musicplayer.services.downloads.logging.DownloadLog;
import io.github.guillermodubon.musicplayer.models.DeezerApiMetaData;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class DownloadTask extends Task<Void> {

    private static final String ICON_ROOT = "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/";
    private static final String ICON_SUCCESS =
            ICON_ROOT + "check_circle_27dp_0077B6_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_ERROR =
            ICON_ROOT + "error_24dp_D32F2F_FILL0_wght400_GRAD0_opsz24.svg";

    public static final String MESSAGE_COMPLETED = "Download completed successfully";
    public static final String MESSAGE_YT_DLP_ERROR = "An error has occurred, please try again";
    public static final String MESSAGE_CONNECTION_ERROR =
            "An error has occurred. Please check your internet connection and try again.";
    public static final String MESSAGE_ALREADY_EXISTS =
            "This song is already downloaded and saved in the selected folder.";
    public static final String MESSAGE_CANCELLED = "The download has been cancelled";

    /*
     * Progress distribution:
     * 0%  - 75%   yt-dlp / ffmpeg download and conversion
     * 75% - 82%   final file resolution/finalization
     * 82% - 97%   metadata, DB/cache/manifest persistence
     * 97% - 100%  final readiness; UI publication is queued at 100%
     */
    private static final double PROGRESS_DOWNLOAD_MAX = 75.0;
    private static final double PROGRESS_FILE_FINALIZED = 82.0;
    private static final double PROGRESS_POST_PROCESSING_STARTED = 92.0;
    private static final double PROGRESS_POST_PROCESSING_DONE = 97.0;
    private static final double PROGRESS_FULLY_READY = 100.0;

    public enum ResultStatus {
        RUNNING,
        COMPLETED,
        WARNING,
        ERROR,
        CANCELLED
    }

    public enum TerminalPresentation {
        COMPLETED(ResultStatus.COMPLETED, MESSAGE_COMPLETED, "#0077B6", ICON_SUCCESS),
        ALREADY_EXISTS(ResultStatus.WARNING, MESSAGE_ALREADY_EXISTS, "#FFB300", ICON_ERROR),
        CANCELLED(ResultStatus.CANCELLED, MESSAGE_CANCELLED, "#FFB300", ICON_ERROR),
        YT_DLP_ERROR(ResultStatus.ERROR, MESSAGE_YT_DLP_ERROR, "#D32F2F", ICON_ERROR),
        CONNECTION_ERROR(ResultStatus.ERROR, MESSAGE_CONNECTION_ERROR, "#D32F2F", ICON_ERROR);

        private final ResultStatus status;
        private final String message;
        private final String color;
        private final String iconPath;

        TerminalPresentation(ResultStatus status, String message, String color, String iconPath) {
            this.status = status;
            this.message = message;
            this.color = color;
            this.iconPath = iconPath;
        }

        public ResultStatus getStatus() {
            return status;
        }

        public String getMessage() {
            return message;
        }

        public String getColor() {
            return color;
        }

        public String getIconPath() {
            return iconPath;
        }
    }

    private final DownloadTaskContext context;

    private final SimpleStringProperty fetchedTitle = new SimpleStringProperty("");
    private final ObjectProperty<File> completedFile = new SimpleObjectProperty<>();
    private final ObjectProperty<ResultStatus> resultStatus = new SimpleObjectProperty<>(ResultStatus.RUNNING);
    private final StringProperty resultMessage = new SimpleStringProperty("");
    private final ObjectProperty<TerminalPresentation> terminalPresentation = new SimpleObjectProperty<>();
    private final BooleanProperty deferredByExclusiveSession = new SimpleBooleanProperty(false);

    private volatile CompletableFuture<Void> postProcessingFuture = CompletableFuture.completedFuture(null);
    private volatile boolean networkFailure;

    /*
     * Files owned by this task while it is in progress. They are kept
     * separately from completedFile because completedFile is only published
     * after metadata and library integration have succeeded.
     */
    private volatile File activeTemporaryFile;
    private volatile File activeFinalFile;
    private volatile boolean preserveFinalFileOnFailure;

    private final YtDlpRunner ytDlpRunner;
    private final DownloadFileFinalizer fileFinalizer;
    private final DownloadRetryPolicy retryPolicy;

    public DownloadTask(String query, File targetDir, String cleanSongName) {
        this(new DownloadTaskContext(query, targetDir, cleanSongName));
    }

    public DownloadTask(DownloadTaskContext context) {
        this(context, new YtDlpRunner(), new DownloadFileFinalizer(), new DownloadRetryPolicy());
    }

    public DownloadTask(DownloadTaskContext context,
                        YtDlpRunner ytDlpRunner,
                        DownloadFileFinalizer fileFinalizer,
                        DownloadRetryPolicy retryPolicy) {
        this.context = context;
        this.ytDlpRunner = ytDlpRunner;
        this.fileFinalizer = fileFinalizer;
        this.retryPolicy = retryPolicy;

        updateTitle(context == null ? "" : context.getQuery());
        updateMessage("Queued");
        updateProgressSafely(0);
    }

    public DownloadTaskContext getContext() {
        return context;
    }

    public static DownloadTask copyOf(DownloadTask source) {
        DownloadTask copy = new DownloadTask(source.getContext().copy());
        copy.setArtistForFile(source.getArtistForFile());
        copy.setFetchedTitle(source.getFetchedTitle());
        copy.setCoverImage(source.getCoverImage());
        copy.setSourceIsSongItem(source.isSourceIsSongItem());
        copy.setMaxAttempts(source.getMaxAttempts());
        copy.setDeferredByExclusiveSession(source.isDeferredByExclusiveSession());
        return copy;
    }

    public String getQuery() {
        return context.getQuery();
    }

    public File getTargetDir() {
        return context.getTargetDir();
    }

    public String getCleanSongName() {
        return context.getCleanSongName();
    }

    public void setArtistForFile(String a) {
        context.setArtistForFile(a);
    }

    public String getArtistForFile() {
        return context.getArtistForFile();
    }

    public void setMaxAttempts(int attempts) {
        context.setMaxAttempts(attempts);
    }

    public int getMaxAttempts() {
        return context.getMaxAttempts();
    }

    public void setCoverImage(Image image) {
        context.setCoverImage(image);
    }

    public Image getCoverImage() {
        return context.getCoverImage();
    }

    public void setSourceIsSongItem(boolean sourceIsSongItem) {
        context.setSourceIsSongItem(sourceIsSongItem);
    }

    public boolean isSourceIsSongItem() {
        return context.isSourceIsSongItem();
    }

    public void setFetchedTitle(String title) {
        fetchedTitle.set(title == null ? "" : title);
        context.setFetchedTitle(fetchedTitle.get());
        updateTitle(fetchedTitle.get());
    }

    public String getFetchedTitle() {
        return fetchedTitle.get();
    }

    public File getCompletedFile() {
        return completedFile.get();
    }

    public ReadOnlyObjectProperty<File> completedFileProperty() {
        return completedFile;
    }

    public ResultStatus getResultStatus() {
        return resultStatus.get();
    }

    public ReadOnlyObjectProperty<ResultStatus> resultStatusProperty() {
        return resultStatus;
    }

    public TerminalPresentation getTerminalPresentation() {
        return terminalPresentation.get();
    }

    public ReadOnlyObjectProperty<TerminalPresentation> terminalPresentationProperty() {
        return terminalPresentation;
    }

    public boolean isDeferredByExclusiveSession() {
        return deferredByExclusiveSession.get();
    }

    public ReadOnlyBooleanProperty deferredByExclusiveSessionProperty() {
        return deferredByExclusiveSession;
    }

    public void setDeferredByExclusiveSession(boolean deferred) {
        deferredByExclusiveSession.set(deferred);
    }

    public CompletableFuture<Void> getPostProcessingFuture() {
        return postProcessingFuture;
    }

    @Override
    protected Void call() throws Exception {
        updateMessage("Starting");
        updateProgressSafely(0);
        DownloadLog.info("DownloadTask", "Starting " + DownloadLog.taskLabel(context));

        String desiredBase = DownloadFileNameHelper.computeDesiredBaseName(
                context.getArtistForFile(),
                context.getFetchedTitle(),
                context.getCleanSongName()
        );

        try {
            DownloadLog.info("DownloadTask", "Resolved output base name: \"" + desiredBase + "\"");

        if (fileFinalizer.alreadyExists(context, desiredBase)) {
            DownloadLog.info("DownloadTask", "Final file already exists; skipping download");
            File existingFile = fileFinalizer.resolveFinalTarget(context, desiredBase);
            activeFinalFile = existingFile;
            // This file belongs to a previous download and must never be
            // removed if metadata or library integration fails now.
            preserveFinalFileOnFailure = true;

            publishPlayableAndCompleteIntegration(
                    desiredBase,
                    existingFile,
                    TerminalPresentation.ALREADY_EXISTS
            );

            return null;
        }

        boolean finishedSuccessfully = false;
        IOException lastIoEx = null;
        List<String> searchQueries = context.getSearchQueries();
        int attemptsPerQuery = context.getMaxAttempts();
        int totalAttempts = Math.max(1, searchQueries.size() * attemptsPerQuery);

        for (int attempt = 1; attempt <= totalAttempts && !isCancelled(); attempt++) {
            int queryIndex = Math.min(searchQueries.size() - 1, (attempt - 1) / attemptsPerQuery);
            int candidateIndex = ((attempt - 1) % attemptsPerQuery) + 1;
            String searchQuery = searchQueries.get(queryIndex);

            DownloadLog.info(
                    "DownloadTask",
                    "Starting attempt " + attempt + "/" + totalAttempts + " for "
                            + DownloadLog.taskLabel(context)
                            + " using search variant " + (queryIndex + 1) + "/" + searchQueries.size()
            );

            YtDlpRunner.AttemptResult result;

            try {
                result = ytDlpRunner.executeAttempt(
                        context,
                        searchQuery,
                        candidateIndex,
                        this::isCancelled,
                        pct -> {
                            double mapped = mapDownloadToolProgress(pct);
                            updateProgressSafely(mapped);
                            DownloadLog.progress(context, pct);
                        },
                        msg -> {
                            if (msg != null && !msg.isBlank()) {
                                updateMessage(msg);
                            }
                        }
                );
            } catch (IOException io) {
                lastIoEx = io;
                networkFailure |= isNetworkFailure(io);
                DownloadLog.error("DownloadTask", "Attempt " + attempt + " failed with IO error", io);
                updateMessage("Error: " + (io.getMessage() == null ? "IO" : io.getMessage()));
                result = null;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }

            if (isCancelled()) {
                DownloadLog.warn("DownloadTask", "Task cancelled: " + DownloadLog.taskLabel(context));
                setTerminalResult(TerminalPresentation.CANCELLED);
                return null;
            }

            if (result == null) {
                DownloadLog.warn("DownloadTask", "Attempt returned no result");
                cleanupIncompleteArtifacts();

                if (candidateIndex < attemptsPerQuery) {
                    long sleepMs = retryPolicy.computeDelayMillis(candidateIndex);
                    DownloadLog.info("DownloadTask", "Retry scheduled in " + sleepMs + " ms after empty result");
                    updateMessage(retryPolicy.buildRetryMessage(candidateIndex, attemptsPerQuery, sleepMs));

                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                } else if (networkFailure) {
                    break;
                } else if (queryIndex < searchQueries.size() - 1) {
                    DownloadLog.info("DownloadTask", "Trying fallback artist query after primary search candidates failed");
                    updateMessage("Trying another artist");
                }

                continue;
            }

            DownloadLog.info(
                    "DownloadTask",
                    "Attempt result: exitCode=" + result.getExitCode()
                            + ", exists=" + result.isExists()
                            + ", fatal=" + result.isFatal()
                            + ", candidate=" + DownloadLog.pathOf(result.getCreatedTmp())
            );

            activeTemporaryFile = result.getCreatedTmp();

            if (result.getIoException() != null) {
                lastIoEx = result.getIoException();
            }

            networkFailure |= result.isNetworkFailure();

            if (result.isExists()) {
                DownloadLog.info("DownloadTask", "yt-dlp reported that the file already exists");

                activeFinalFile = fileFinalizer.resolveFinalTarget(context, desiredBase);
                preserveFinalFileOnFailure = true;
                cleanupIncompleteArtifacts();

                publishPlayableAndCompleteIntegration(
                        desiredBase,
                        fileFinalizer.resolveFinalTarget(context, desiredBase),
                        TerminalPresentation.ALREADY_EXISTS
                );

                finishedSuccessfully = terminalPresentation.get() == TerminalPresentation.ALREADY_EXISTS;

                break;
            }

            if (result.isSuccessfulCandidate()) {
                updateMessage("Finalizing file");
                updateProgressSafely(PROGRESS_FILE_FINALIZED - 2);

                File finalFile = fileFinalizer.finalizeDownloadedFile(
                        context,
                        desiredBase,
                        result.getCreatedTmp()
                );

                if (finalFile != null) {
                    DownloadLog.info("DownloadTask", "Download finalized at " + DownloadLog.pathOf(finalFile));
                    activeFinalFile = finalFile;

                    publishPlayableAndCompleteIntegration(
                            desiredBase,
                            finalFile,
                            TerminalPresentation.COMPLETED
                    );

                    preserveFinalFileOnFailure = true;
                    finishedSuccessfully = true;

                    break;
                } else {
                    DownloadLog.warn("DownloadTask", "Could not finalize downloaded temporary file");
                    updateMessage("Error renaming file");
                    cleanupIncompleteArtifacts();
                }
            } else {
                updateMessage("Attempt failed (" + attempt + ")");
                cleanupIncompleteArtifacts();
            }

            if (candidateIndex < attemptsPerQuery) {
                boolean transientFailure = result.isNetworkFailure()
                        || result.getIoException() != null;
                long sleepMs = transientFailure ? retryPolicy.computeDelayMillis(candidateIndex) : 0L;

                if (sleepMs > 0) {
                    DownloadLog.info("DownloadTask", "Retry scheduled in " + sleepMs + " ms");
                    updateMessage(retryPolicy.buildRetryMessage(candidateIndex, attemptsPerQuery, sleepMs));

                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                } else {
                    DownloadLog.info("DownloadTask", "Trying next search candidate without delay");
                    updateMessage("Trying another result (" + (candidateIndex + 1) + "/" + attemptsPerQuery + ")");
                }
            } else if (result.isNetworkFailure()) {
                break;
            } else if (queryIndex < searchQueries.size() - 1) {
                DownloadLog.info("DownloadTask", "Trying fallback artist query after primary search candidates failed");
                updateMessage("Trying another artist");
            }
        }

        if (isCancelled()) {
            DownloadLog.warn("DownloadTask", "Task cancelled after attempts: " + DownloadLog.taskLabel(context));
            setTerminalResult(TerminalPresentation.CANCELLED);
            return null;
        }

        if (finishedSuccessfully) {
            DownloadLog.info("DownloadTask", "Task completed successfully: " + DownloadLog.taskLabel(context));
            return null;
        }

        setTerminalResult(networkFailure
                ? TerminalPresentation.CONNECTION_ERROR
                : TerminalPresentation.YT_DLP_ERROR);

        String message = "yt-dlp failed after retries";

        if (lastIoEx != null) {
            message += " -> " + lastIoEx.getMessage();
        }

        DownloadLog.error("DownloadTask", message, lastIoEx);
        throw new Exception(message);
        } finally {
            if (!preserveFinalFileOnFailure && context != null) {
                context.revokeDownloadPublication();
            }
            cleanupIncompleteArtifacts();
        }
    }

    /**
     * Full completion pipeline.
     *
     * Important:
     * This method does NOT mark the task as COMPLETED until:
     * 1. Final file exists.
     * 2. Metadata/DB/cache/manifest post-processing has finished.
     * 3. The final progress reaches 100%.
     * 4. The final playable state is published to UI/playback.
     */
    private void publishPlayableAndCompleteIntegration(String desiredBase,
                                                       File finalFile,
                                                       TerminalPresentation presentation) {
        if (finalFile == null || !finalFile.exists()) {
            setTerminalResult(TerminalPresentation.YT_DLP_ERROR);
            return;
        }

        updateMessage("Finalizing file");
        updateProgressSafely(PROGRESS_FILE_FINALIZED);

        updateMessage("Saving metadata and updating library");
        updateProgressSafely(PROGRESS_POST_PROCESSING_STARTED);

        CompletableFuture<DeezerApiMetaData> preparationFuture =
                fileFinalizer.prepareMetadataAsync(context, desiredBase, finalFile);

        postProcessingFuture = preparationFuture.thenCompose(metadata -> {
            if (!isPublicationAllowed()) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Download publication was cancelled")
                );
            }

            // Everything required by playback is durable at this point. Queue
            // the 100% update before scheduling JavaFX's final cell refresh.
            updateMessage("Refreshing library and playback");
            updateProgressSafely(PROGRESS_POST_PROCESSING_DONE);
            updateProgressSafely(PROGRESS_FULLY_READY);
            DownloadLog.progress(context, 100);

            return fileFinalizer.publishPreparedDownloadAsync(
                    context,
                    metadata,
                    finalFile
            );
        }).thenApply(ignored -> {
            if (!isPublicationAllowed()) {
                throw new IllegalStateException("Download publication was cancelled");
            }

            completedFile.set(finalFile);
            return null;
        });

        awaitPostProcessing(postProcessingFuture);

        setTerminalResult(presentation);
    }

    private void awaitPostProcessing(CompletableFuture<Void> future) {
        if (future == null) return;

        try {
            future.join();
        } catch (CompletionException error) {
            throw new IllegalStateException("Downloaded song post-processing failed", unwrapCompletionException(error));
        } catch (Exception error) {
            throw new IllegalStateException("Downloaded song post-processing failed", error);
        }
    }

    /**
     * Removes artifacts produced by this task when it did not reach a valid
     * completed state. This method is intentionally idempotent because it can
     * be called by the task lifecycle and by the bulk-session coordinator at
     * the same time.
     */
    public void cleanupIncompleteArtifacts() {
        if (context == null || context.getTargetDir() == null) return;

        File targetDir = context.getTargetDir();
        String token = context.getDownloadToken();

        for (File temporary : DownloadFileNameHelper.findTmpFiles(targetDir, token)) {
            deleteOwnedArtifact(temporary, targetDir, token, "temporary");
        }

        deleteOwnedArtifact(activeTemporaryFile, targetDir, token, "temporary");

        if (!preserveFinalFileOnFailure && activeFinalFile != null
                && isDirectChild(activeFinalFile, targetDir)) {
            deleteArtifact(activeFinalFile, "final");
        }
    }

    private void deleteOwnedArtifact(File file,
                                     File targetDir,
                                     String token,
                                     String kind) {
        if (file == null || !isDirectChild(file, targetDir)) return;

        String prefix = "dl_tmp_" + DownloadFileNameHelper.sanitizeToken(token) + "_";
        if (file.getName().startsWith(prefix)) {
            deleteArtifact(file, kind);
        }
    }

    private boolean isDirectChild(File file, File targetDir) {
        if (file == null || targetDir == null) return false;

        try {
            File canonicalFile = file.getCanonicalFile();
            File canonicalDir = targetDir.getCanonicalFile();
            return canonicalDir.equals(canonicalFile.getParentFile());
        } catch (IOException error) {
            DownloadLog.warn(
                    "DownloadTask",
                    "Could not validate cleanup path: " + DownloadLog.pathOf(file)
            );
            return false;
        }
    }

    private void deleteArtifact(File file, String kind) {
        if (file == null || !file.exists()) return;

        try {
            if (file.delete()) {
                DownloadLog.info(
                        "DownloadTask",
                        "Deleted incomplete " + kind + " artifact: " + DownloadLog.pathOf(file)
                );
            } else if (file.exists()) {
                DownloadLog.warn(
                        "DownloadTask",
                        "Could not delete incomplete " + kind + " artifact: " + DownloadLog.pathOf(file)
                );
            }
        } catch (Exception error) {
            DownloadLog.error(
                    "DownloadTask",
                    "Could not delete incomplete " + kind + " artifact: " + DownloadLog.pathOf(file),
                    error
            );
        }
    }

    private boolean isPublicationAllowed() {
        return !isCancelled()
                && context != null
                && context.isDownloadPublicationAllowed();
    }

    private Throwable unwrapCompletionException(CompletionException error) {
        return error.getCause() == null ? error : error.getCause();
    }

    private double mapDownloadToolProgress(double rawPercent) {
        double safe = Math.max(0.0, Math.min(100.0, rawPercent));
        return (safe / 100.0) * PROGRESS_DOWNLOAD_MAX;
    }

    private void updateProgressSafely(double percent) {
        double safe = Math.max(0.0, Math.min(100.0, percent));
        updateProgress(safe, 100.0);
    }

    @Override
    protected void cancelled() {
        if (context != null) {
            context.revokeDownloadPublication();
        }
        cleanupIncompleteArtifacts();
        setTerminalResult(TerminalPresentation.CANCELLED);
    }

    @Override
    protected void failed() {
        if (context != null) {
            context.revokeDownloadPublication();
        }
        cleanupIncompleteArtifacts();
        networkFailure |= isNetworkFailure(getException());

        if (getTerminalPresentation() == null || resultStatus.get() != ResultStatus.ERROR) {
            setTerminalResult(networkFailure
                    ? TerminalPresentation.CONNECTION_ERROR
                    : TerminalPresentation.YT_DLP_ERROR);
        }
    }

    private void setTerminalResult(TerminalPresentation presentation) {
        if (presentation == null) return;

        terminalPresentation.set(presentation);
        resultMessage.set(presentation.getMessage());
        updateMessage(presentation.getMessage());
        resultStatus.set(presentation.getStatus());
    }

    private boolean isNetworkFailure(Throwable error) {
        Throwable current = error;

        while (current != null) {
            String message = current.getMessage();

            if (message != null) {
                String normalized = message.toLowerCase();

                if (normalized.contains("network is unreachable")
                        || normalized.contains("no route to host")
                        || normalized.contains("connection refused")
                        || normalized.contains("connection reset")
                        || normalized.contains("connection aborted")
                        || normalized.contains("temporary failure in name resolution")
                        || normalized.contains("name or service not known")
                        || normalized.contains("getaddrinfo failed")
                        || normalized.contains("timed out")
                        || normalized.contains("timeout")
                        || normalized.contains("dns")) {
                    return true;
                }
            }

            current = current.getCause();
        }

        return false;
    }

    public static String cleanTitle(String title) {
        return DownloadFileNameHelper.cleanTitle(title);
    }

}
