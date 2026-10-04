package io.github.guillermodubon.musicplayer.services.downloads.bulk;

import javafx.beans.value.ChangeListener;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Parent;
import io.github.guillermodubon.musicplayer.services.downloads.DownloadManager;
import io.github.guillermodubon.musicplayer.services.downloads.DownloadTask;
import io.github.guillermodubon.musicplayer.services.downloads.context.DownloadTaskContext;
import io.github.guillermodubon.musicplayer.services.downloads.logging.DownloadLog;
import io.github.guillermodubon.musicplayer.services.downloads.provider.ProviderCooldownState;
import io.github.guillermodubon.musicplayer.services.downloads.provider.YouTubeRequestCoordinator;
import io.github.guillermodubon.musicplayer.services.downloads.services.SongDownloadTaskFactory;
import io.github.guillermodubon.musicplayer.models.Song;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class BulkDownloadManager {

    private static final BulkDownloadManager INSTANCE = new BulkDownloadManager();
    private final Map<String, BulkDownloadSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> providerResumeCallbacks = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, DownloadTask>> deferredTaskRows = new ConcurrentHashMap<>();
    private final DownloadManager downloadManager = DownloadManager.getInstance();
    private final YouTubeRequestCoordinator coordinator = YouTubeRequestCoordinator.getInstance();
    private final ScheduledExecutorService cooldownScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "bulk-download-cooldown");
        thread.setDaemon(true);
        return thread;
    });

    private BulkDownloadManager() {
    }

    public static BulkDownloadManager getInstance() {
        return INSTANCE;
    }

    public BulkDownloadSession getSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        return sessions.get(sessionId);
    }


    public BulkDownloadSession startSession(String collectionTitle,
                                            List<Song> sourceSongs,
                                            Parent ownerRoot,
                                            long sourceId,
                                            BulkDownloadSession.SourceType sourceType) {
        List<Song> songs = normalizeRemoteSongs(sourceSongs);
        if (songs.isEmpty()) return null;

        String sessionId = UUID.randomUUID().toString();

        File targetDir = SongDownloadTaskFactory.resolveTargetDir();

        BulkDownloadSession session = new BulkDownloadSession(
                sessionId,
                collectionTitle,
                songs,
                targetDir,
                downloadManager.getWorkerCount(),
                sourceId,
                sourceType
        );

        sessions.put(sessionId, session);

        if (!downloadManager.hasExclusiveSession()) {
            downloadManager.beginExclusiveSession(sessionId);
        }

        downloadManager.showSidebar(ownerRoot);

        DownloadLog.info(
                "BulkDownloadManager",
                "Started bulk session " + sessionId
                        + " with " + songs.size()
                        + " songs, sourceId=" + sourceId
                        + ", sourceType=" + sourceType
                        + ", workerCapacity=" + downloadManager.getWorkerCount()
        );

        scheduleMore(session);

        return session;
    }

    public void clearSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;

        cancelProviderResume(sessionId);
        sessions.remove(sessionId);
        deferredTaskRows.remove(sessionId);
        downloadManager.getTasks().removeIf(task -> belongsToSession(task, sessionId));
    }

    public void resumeProviderSession(String sessionId) {
        BulkDownloadSession session = getSession(sessionId);
        if (session == null || session.isCancellationRequested() || session.isClosed()
                || session.getStatus() != BulkDownloadSession.Status.PAUSED_PROVIDER) return;
        cancelProviderResume(sessionId);
        coordinator.resumeManually();
        session.setProviderRecovering();
        scheduleMore(session);
    }

    public void shutdown() {
        for (String sessionId : new ArrayList<>(providerResumeCallbacks.keySet())) {
            cancelProviderResume(sessionId);
        }
        cooldownScheduler.shutdownNow();
    }

    public BulkDownloadSession retrySession(String sessionId, Parent ownerRoot) {
        BulkDownloadSession session = getSession(sessionId);
        if (session == null) return null;

        List<Song> retrySongs = session.retrySongs();
        String title = session.getTitle();
        long sourceId = session.getSourceId();
        BulkDownloadSession.SourceType sourceType = session.getSourceType();

        clearSession(sessionId);

        return startSession(title, retrySongs, ownerRoot, sourceId, sourceType);
    }

    public void cancelSession(String sessionId) {
        BulkDownloadSession session = getSession(sessionId);
        if (session == null) return;

        DownloadLog.warn("BulkDownloadManager", "Cancelling bulk session " + sessionId);

        session.requestCancel();
        cancelProviderResume(sessionId);

        for (DownloadTask task : downloadManager.getTasks()) {
            if (!belongsToSession(task, sessionId)) continue;

            if (!task.isDone() && !task.isCancelled()) {
                task.cancelAndAwaitCleanup();
            }
        }

        finishIfComplete(session);
    }

    /**
     * Requests cancellation for every bulk session currently known by the
     * manager.  Requesting the session cancellation first prevents completion
     * callbacks from scheduling new songs while the application is closing.
     */
    public void cancelAllSessions() {
        for (String sessionId : new ArrayList<>(sessions.keySet())) {
            cancelSession(sessionId);
        }
        for (String sessionId : new ArrayList<>(providerResumeCallbacks.keySet())) {
            cancelProviderResume(sessionId);
        }
    }

    private void scheduleMore(BulkDownloadSession session) {
        if (session == null || session.isCancellationRequested() || session.isClosed()) {
            finishIfComplete(session);
            return;
        }
        if (session.isFailureStopped()) {
            finishIfComplete(session);
            return;
        }
        if (!isSchedulable(session.getStatus())) return;

        synchronizeProviderState(session);
        if (!isSchedulable(session.getStatus())) return;

        while (!session.isDoneScheduling() && !session.isCancellationRequested()) {
            BulkDownloadSession.ScheduledSong scheduledSong = session.reserveNextScheduledSong(
                    coordinator.recommendedParallelism()
            );

            if (scheduledSong == null || scheduledSong.song() == null) {
                break;
            }

            Song song = scheduledSong.song();

            DownloadTask deferredSource = takeDeferredTask(session.getId(), scheduledSong.index());
            DownloadTask task = deferredSource == null
                    ? createTaskForBulkSong(session, song)
                    : DownloadTask.copyOf(deferredSource);

            if (task == null) {
                session.markProviderPhaseFinished();
                session.markDownloadFinished();
                recordTaskIntegration(
                        session,
                        null,
                        scheduledSong.index(),
                        new IllegalStateException("Could not create download task")
                );
                continue;
            }

            DownloadTaskContext context = task.getContext();

            context.setBulkSessionId(session.getId());
            context.setBulkSessionTitle(session.getTitle());
            context.setBulkSongIndex(scheduledSong.index());
            context.setBulkTotalSongs(session.getTotalSongs());

            context.setSourceCollectionId(session.getSourceId() > 0 ? session.getSourceId() : null);
            context.setSourceCollectionTitle(session.getTitle());
            context.setSourceCollectionType(toSourceContextType(session.getSourceType()));

            attachCompletionListener(session, task, scheduledSong.index());

            boolean accepted = deferredSource == null
                    ? downloadManager.enqueueTask(task)
                    : downloadManager.replaceDeferredTask(deferredSource, task);

            if (!accepted) {
                DownloadLog.warn(
                        "BulkDownloadManager",
                        "DownloadManager rejected task for session="
                                + session.getId()
                                + ", songIndex=" + scheduledSong.index()
                );

                task.cancelAndAwaitCleanup();
                task.completeProviderPhase();
                if (task.markBulkSessionTaskFinished()) {
                    session.markDownloadFinished();
                }
                recordTaskIntegration(
                        session,
                        task,
                        scheduledSong.index(),
                        new IllegalStateException("Duplicate download task")
                );
            }
        }

        finishIfComplete(session);
    }

    private DownloadTask createTaskForBulkSong(BulkDownloadSession session, Song song) {
        if (session == null || song == null) return null;

        long sourceId = session.getSourceId();
        BulkDownloadSession.SourceType sourceType = session.getSourceType();
        File targetDir = session.getTargetDirectory();

        if (sourceType == BulkDownloadSession.SourceType.PLAYLIST) {
            return SongDownloadTaskFactory.createForPlaylist(
                    song,
                    targetDir,
                    sourceId > 0 ? sourceId : null,
                    session.getTitle()
            );
        }

        if (sourceType == BulkDownloadSession.SourceType.ALBUM) {
            return SongDownloadTaskFactory.createForAlbum(
                    song,
                    targetDir,
                    sourceId > 0 ? sourceId : null,
                    session.getTitle()
            );
        }

        if (sourceType == BulkDownloadSession.SourceType.SINGLE) {
            return SongDownloadTaskFactory.createSingle(song, targetDir);
        }

        return SongDownloadTaskFactory.create(
                song,
                targetDir,
                sourceId > 0 ? sourceId : null,
                session.getTitle(),
                toSourceContextType(sourceType)
        );
    }

    private void attachCompletionListener(BulkDownloadSession session,
                                          DownloadTask task,
                                          int songIndex) {
        task.setProviderPhaseCompletionListener(failureKind -> {
            session.markProviderPhaseFinished();
            Platform.runLater(() -> {
                if (!session.isCancellationRequested()
                        && !session.isClosed()
                        && isSchedulable(session.getStatus())) {
                    scheduleMore(session);
                }
            });
        });

        task.getExecutionCompletion().whenComplete((ignored, executionError) -> {
            if (task.markBulkSessionTaskFinished()) {
                session.markDownloadFinished();
            }
            if (task.getState() == Worker.State.CANCELLED) {
                Platform.runLater(() -> afterTaskTerminal(session));
            }
        });

        AtomicReference<ChangeListener<Worker.State>> listenerRef = new AtomicReference<>();

        ChangeListener<Worker.State> listener = (obs, oldState, newState) -> {
            if (!isTerminal(newState)) return;

            task.stateProperty().removeListener(listenerRef.get());

            /*
             * DownloadTask now waits for the complete integration pipeline before
             * succeeding, so reaching SUCCEEDED means the song is ready to play.
             */
            if (task.isDeferredByProvider()) {
                handleProviderDeferred(session, task, songIndex);
                afterTaskTerminal(session);
                return;
            }

            if (task.getTerminalPresentation() == DownloadTask.TerminalPresentation.MEDIA_TOOLS_ERROR) {
                recordTaskIntegration(session, task, songIndex, null);
                session.stopForMediaToolFailure();
                afterTaskTerminal(session);
                return;
            }

            CompletableFuture<Void> integration = task.getPostProcessingFuture();

            if (integration == null) {
                recordTaskIntegration(session, task, songIndex, null);
                afterTaskTerminal(session);
                return;
            }

            integration.whenComplete((ignored, integrationError) -> {
                recordTaskIntegration(session, task, songIndex, integrationError);
                afterTaskTerminal(session);
            });
        };

        listenerRef.set(listener);
        task.stateProperty().addListener(listener);
    }

    private void afterTaskTerminal(BulkDownloadSession session) {
        if (session == null) return;

        if (!session.isCancellationRequested()
                && isSchedulable(session.getStatus())) {
            scheduleMore(session);
        }

        releaseExclusiveDownloadPhaseIfComplete(session);
        finishIfComplete(session);
    }

    private void recordTaskIntegration(BulkDownloadSession session,
                                       DownloadTask task,
                                       int songIndex,
                                       Throwable integrationError) {
        session.markTaskIntegrated(task, songIndex, integrationError);
        if (task == null || integrationError != null || task.isDeferredByProvider()) return;
        DownloadTask.ResultStatus result = task.getResultStatus();
        if (result == DownloadTask.ResultStatus.COMPLETED
                || result == DownloadTask.ResultStatus.WARNING) {
            downloadManager.trimCompletedBulkSuccessRows(
                    session.getId(),
                    coordinator.policy().completedBulkSuccessHistoryLimit()
            );
        }
    }

    private void handleProviderDeferred(BulkDownloadSession session,
                                        DownloadTask task,
                                        int songIndex) {
        if (session == null || task == null || session.isCancellationRequested()) return;
        if (session.isFailureStopped()) {
            recordTaskIntegration(session, null, songIndex,
                    new IllegalStateException("Media tools stopped this download batch"));
            return;
        }
        deferredTaskRows.computeIfAbsent(session.getId(), ignored -> new ConcurrentHashMap<>())
                .put(songIndex, task);
        session.deferSong(songIndex);

        ProviderCooldownState state = coordinator.cooldownState();
        if (state.manualResumeRequired()) {
            session.setProviderPaused();
            cancelProviderResume(session.getId());
            return;
        }
        if (!state.isActive(System.currentTimeMillis())) {
            synchronizeProviderState(session);
            return;
        }

        session.setProviderWaiting(state.resumeAtMillis(), state.generation());
        scheduleProviderResume(session, state);
    }

    private void scheduleProviderResume(BulkDownloadSession session,
                                       ProviderCooldownState state) {
        if (session == null || state == null || state.manualResumeRequired()
                || session.isCancellationRequested() || session.isClosed()) return;

        cancelProviderResume(session.getId());
        long delay = Math.max(1L, state.remainingMillis(System.currentTimeMillis()));
        long expectedGeneration = state.generation();
        ScheduledFuture<?> callback = cooldownScheduler.schedule(() -> {
            ProviderCooldownState current = coordinator.cooldownState();
            if (sessions.get(session.getId()) != session
                    || session.isCancellationRequested()
                    || session.isClosed()) return;
            if (session.getProviderCooldownGeneration() != expectedGeneration) return;
            if (current.manualResumeRequired()) {
                Platform.runLater(session::setProviderPaused);
                return;
            }
            if (current.generation() != expectedGeneration
                    || current.isActive(System.currentTimeMillis())) {
                session.setProviderWaiting(current.resumeAtMillis(), current.generation());
                scheduleProviderResume(session, current);
                return;
            }
            Platform.runLater(() -> {
                if (sessions.get(session.getId()) != session
                        || session.isCancellationRequested()
                        || session.isClosed()) return;
                synchronizeProviderState(session);
                scheduleMore(session);
            });
        }, delay, TimeUnit.MILLISECONDS);
        providerResumeCallbacks.put(session.getId(), callback);
    }

    private void cancelProviderResume(String sessionId) {
        ScheduledFuture<?> callback = providerResumeCallbacks.remove(sessionId);
        if (callback != null) callback.cancel(false);
    }

    private DownloadTask takeDeferredTask(String sessionId, int songIndex) {
        Map<Integer, DownloadTask> deferred = deferredTaskRows.get(sessionId);
        if (deferred == null) return null;
        DownloadTask task = deferred.remove(songIndex);
        if (deferred.isEmpty()) deferredTaskRows.remove(sessionId, deferred);
        return task;
    }

    private void finishIfComplete(BulkDownloadSession session) {
        if (session == null) return;

        releaseExclusiveDownloadPhaseIfComplete(session);

        if (!session.isCancellationRequested() && !session.isComplete()) {
            return;
        }

        if (session.isCancellationRequested() && !session.isDownloadPhaseComplete()) {
            return;
        }

        if (session.close()) {
            DownloadLog.info(
                    "BulkDownloadManager",
                    "Finished bulk session " + session.getId()
                            + " status=" + session.getStatus()
                            + ", completed=" + session.getCompletedCount()
                            + ", errors=" + session.getErrorCount()
                            + ", cancelled=" + session.getCancelledCount()
            );
        }
    }

    private void releaseExclusiveDownloadPhaseIfComplete(BulkDownloadSession session) {
        if (session == null || !session.isDownloadPhaseComplete()) return;

        if (session.markExclusiveDownloadPhaseReleased()) {
            downloadManager.endExclusiveSession(session.getId());

            DownloadLog.info(
                    "BulkDownloadManager",
                    "Released download queue after integrated phase for session " + session.getId()
            );
        }
    }

    private boolean isTerminal(Worker.State state) {
        return state == Worker.State.SUCCEEDED
                || state == Worker.State.FAILED
                || state == Worker.State.CANCELLED;
    }

    private boolean isSchedulable(BulkDownloadSession.Status status) {
        return status == BulkDownloadSession.Status.RUNNING
                || status == BulkDownloadSession.Status.RECOVERING_PROVIDER;
    }

    private void synchronizeProviderState(BulkDownloadSession session) {
        if (session == null || session.isClosed() || session.isCancellationRequested()) return;

        switch (coordinator.mode()) {
            case HEALTHY -> {
                if (session.getStatus() == BulkDownloadSession.Status.RECOVERING_PROVIDER) {
                    session.resumeProvider();
                }
            }
            case RECOVERING -> session.setProviderRecovering();
            case COOLDOWN -> {
                ProviderCooldownState state = coordinator.cooldownState();
                session.setProviderWaiting(state.resumeAtMillis(), state.generation());
                scheduleProviderResume(session, state);
            }
            case MANUAL_PAUSE -> {
                cancelProviderResume(session.getId());
                session.setProviderPaused();
            }
            case SHUTDOWN -> {
                cancelProviderResume(session.getId());
            }
        }
    }

    private boolean belongsToSession(DownloadTask task, String sessionId) {
        return task != null
                && task.getContext() != null
                && Objects.equals(sessionId, task.getContext().getBulkSessionId());
    }

    private List<Song> normalizeRemoteSongs(List<Song> sourceSongs) {
        if (sourceSongs == null || sourceSongs.isEmpty()) return List.of();

        List<Song> normalized = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (Song song : sourceSongs) {
            if (song == null || song.isLocal()) continue;

            String key = songKey(song);

            if (!seen.add(key)) continue;

            normalized.add(song);
        }

        return normalized;
    }

    private String songKey(Song song) {
        if (song == null) return "";

        if (song.getSongID() > 0) {
            return "id:" + song.getSongID();
        }

        String title = song.getTitle() == null
                ? ""
                : song.getTitle().trim().toLowerCase();

        String album = song.getAlbum() == null || song.getAlbum().getName() == null
                ? ""
                : song.getAlbum().getName().trim().toLowerCase();

        return "name:" + title + ":" + album;
    }

    private String toSourceContextType(BulkDownloadSession.SourceType sourceType) {
        if (sourceType == null) {
            return SongDownloadTaskFactory.SOURCE_TYPE_UNKNOWN;
        }

        return switch (sourceType) {
            case ALBUM -> SongDownloadTaskFactory.SOURCE_TYPE_ALBUM;
            case PLAYLIST -> SongDownloadTaskFactory.SOURCE_TYPE_PLAYLIST;
            case SINGLE -> SongDownloadTaskFactory.SOURCE_TYPE_SINGLE;
            case UNKNOWN -> SongDownloadTaskFactory.SOURCE_TYPE_UNKNOWN;
        };
    }
}
