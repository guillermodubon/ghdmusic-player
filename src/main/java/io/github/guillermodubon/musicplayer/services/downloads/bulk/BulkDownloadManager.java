package io.github.guillermodubon.musicplayer.services.downloads.bulk;

import javafx.beans.value.ChangeListener;
import javafx.concurrent.Worker;
import javafx.scene.Parent;
import io.github.guillermodubon.musicplayer.services.downloads.DownloadManager;
import io.github.guillermodubon.musicplayer.services.downloads.DownloadTask;
import io.github.guillermodubon.musicplayer.services.downloads.context.DownloadTaskContext;
import io.github.guillermodubon.musicplayer.services.downloads.logging.DownloadLog;
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

public final class BulkDownloadManager {

    private static final BulkDownloadManager INSTANCE = new BulkDownloadManager();
    private static final int MAX_PARALLEL_DOWNLOADS = 4;

    private final Map<String, BulkDownloadSession> sessions = new ConcurrentHashMap<>();
    private final DownloadManager downloadManager = DownloadManager.getInstance();

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

        int parallelLimit = Math.max(
                1,
                Math.min(MAX_PARALLEL_DOWNLOADS, downloadManager.getWorkerCount())
        );

        File targetDir = SongDownloadTaskFactory.resolveTargetDir();

        BulkDownloadSession session = new BulkDownloadSession(
                sessionId,
                collectionTitle,
                songs,
                targetDir,
                parallelLimit,
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
                        + ", parallelWorkers=" + parallelLimit
        );

        scheduleMore(session);

        return session;
    }

    public void clearSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;

        sessions.remove(sessionId);
        downloadManager.getTasks().removeIf(task -> belongsToSession(task, sessionId));
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

        for (DownloadTask task : downloadManager.getTasks()) {
            if (!belongsToSession(task, sessionId)) continue;

            if (!task.isDone() && !task.isCancelled()) {
                task.cancel();
                cleanupIncompleteTask(task);
            }
        }

        finishIfComplete(session);
    }

    private void scheduleMore(BulkDownloadSession session) {
        if (session == null || session.isCancellationRequested() || session.isClosed()) {
            finishIfComplete(session);
            return;
        }

        while (session.hasCapacity()
                && !session.isDoneScheduling()
                && !session.isCancellationRequested()) {

            BulkDownloadSession.ScheduledSong scheduledSong = session.pollNextScheduledSong();

            if (scheduledSong == null || scheduledSong.song() == null) {
                break;
            }

            Song song = scheduledSong.song();

            DownloadTask task = createTaskForBulkSong(session, song);

            if (task == null) {
                session.markTaskStarted();
                session.markDownloadFinished();
                session.markTaskIntegrated(
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

            session.markTaskStarted();
            attachCompletionListener(session, task, scheduledSong.index());

            boolean accepted = downloadManager.enqueueTask(task);

            if (!accepted) {
                DownloadLog.warn(
                        "BulkDownloadManager",
                        "DownloadManager rejected task for session="
                                + session.getId()
                                + ", songIndex=" + scheduledSong.index()
                );

                task.cancel();
                cleanupIncompleteTask(task);

                session.markDownloadFinished();
                session.markTaskIntegrated(
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
        ChangeListener<Worker.State>[] ref = new ChangeListener[1];

        ref[0] = (obs, oldState, newState) -> {
            if (!isTerminal(newState)) return;

            task.stateProperty().removeListener(ref[0]);

            if (newState == Worker.State.CANCELLED || newState == Worker.State.FAILED) {
                cleanupIncompleteTask(task);
            }

            /*
             * DownloadTask now waits for the complete integration pipeline before
             * succeeding, so reaching SUCCEEDED means the song is ready to play.
             */
            session.markDownloadFinished();

            CompletableFuture<Void> integration = task.getPostProcessingFuture();

            if (integration == null) {
                session.markTaskIntegrated(task, songIndex, null);
                afterTaskTerminal(session);
                return;
            }

            integration.whenComplete((ignored, integrationError) -> {
                session.markTaskIntegrated(task, songIndex, integrationError);
                afterTaskTerminal(session);
            });
        };

        task.stateProperty().addListener(ref[0]);
    }

    private void afterTaskTerminal(BulkDownloadSession session) {
        if (session == null) return;

        if (!session.isCancellationRequested()) {
            scheduleMore(session);
        }

        releaseExclusiveDownloadPhaseIfComplete(session);
        finishIfComplete(session);
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

    private boolean belongsToSession(DownloadTask task, String sessionId) {
        return task != null
                && task.getContext() != null
                && Objects.equals(sessionId, task.getContext().getBulkSessionId());
    }

    private void cleanupIncompleteTask(DownloadTask task) {
        if (task == null) return;

        // DownloadTask owns the token-scoped cleanup and also knows whether a
        // final MP3 was created by this task or existed before it started.
        task.cleanupIncompleteArtifacts();
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
