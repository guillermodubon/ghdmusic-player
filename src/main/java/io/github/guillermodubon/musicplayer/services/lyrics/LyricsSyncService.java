package io.github.guillermodubon.musicplayer.services.lyrics;

import io.github.guillermodubon.musicplayer.models.DeezerApiMetaData;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.models.lyrics.LyricsLookupCandidate;
import io.github.guillermodubon.musicplayer.models.lyrics.LyricsStatus;
import io.github.guillermodubon.musicplayer.models.lyrics.SongLyrics;
import io.github.guillermodubon.musicplayer.repository.DbConnectionManager;
import io.github.guillermodubon.musicplayer.repository.dao.lyrics.LyricsDaoImpl;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import javafx.util.Pair;

import java.io.File;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One durable, rate-limited lane for lyrics. Download requests are placed in
 * front of incremental library work, so a large library never delays a newly
 * completed download behind thousands of lookups.
 */
public final class LyricsSyncService {

    private final StartUpService owner;
    private final LrcLibApiClient apiClient;
    private final LinkedBlockingDeque<Work> workQueue = new LinkedBlockingDeque<>();
    private final ConcurrentLinkedDeque<LyricsLookupCandidate> unmatchedCandidates = new ConcurrentLinkedDeque<>();
    private final AtomicBoolean backgroundScheduled = new AtomicBoolean(false);
    private final AtomicLong nextWakeAtMillis = new AtomicLong(0L);
    private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "lyrics-retry-scheduler");
        thread.setDaemon(true);
        return thread;
    });
    private final Thread worker;

    public LyricsSyncService(StartUpService owner) {
        this(owner, new LrcLibApiClient());
    }

    LyricsSyncService(StartUpService owner, LrcLibApiClient apiClient) {
        this.owner = owner;
        this.apiClient = apiClient;
        this.worker = new Thread(this::runWorker, "lyrics-sync-io");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    public void scheduleLibraryBackfill(Collection<Pair<String, String>> unmatchedLocalSongs) {
        if (unmatchedLocalSongs != null) {
            for (Pair<String, String> song : unmatchedLocalSongs) {
                LyricsLookupCandidate candidate = LyricsCandidateFactory.fromUnmatchedLocalSong(song);
                if (!candidate.sourceKey().isBlank()) unmatchedCandidates.offerLast(candidate);
            }
        }
        scheduleBackgroundPass();
    }

    public CompletableFuture<SongLyrics> syncDownloaded(DeezerApiMetaData metadata, File file) {
        CompletableFuture<SongLyrics> completion = new CompletableFuture<>();
        workQueue.offerFirst(new DownloadWork(metadata, file, completion));
        return completion;
    }

    private void scheduleBackgroundPass() {
        if (backgroundScheduled.compareAndSet(false, true)) {
            workQueue.offerLast(new BackgroundWork());
        }
    }

    private void runWorker() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                workQueue.takeFirst().run();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Throwable ignored) {
                // A malformed record must not stop remaining queued lyrics work.
            }
        }
    }

    private void processDownload(DeezerApiMetaData metadata, File file, CompletableFuture<SongLyrics> completion) {
        try {
            long persistedSongId = resolvePersistedSongId(metadata, file);
            LyricsLookupCandidate candidate = LyricsCandidateFactory.fromDownloadedMetadata(metadata, file, persistedSongId);
            completion.complete(syncCandidate(candidate));
        } catch (Throwable ignored) {
            completion.complete(SongLyrics.empty());
        }
    }

    private void processOneBackgroundCandidate() {
        LyricsLookupCandidate candidate = null;
        SongLyrics result = SongLyrics.empty();
        try {
            candidate = unmatchedCandidates.pollFirst();
            if (candidate == null) {
                candidate = nextPersistedCandidate();
            }
            if (candidate != null) result = syncCandidate(candidate);
        } finally {
            backgroundScheduled.set(false);
            if (candidate != null || !unmatchedCandidates.isEmpty()) {
                scheduleBackgroundPass();
            }
            scheduleRetryIfNeeded(result);
        }
    }

    private LyricsLookupCandidate nextPersistedCandidate() {
        try {
            return DbConnectionManager.getInstance().runWithRetries(connection -> {
                try {
                    return new LyricsDaoImpl(connection)
                            .findPendingCandidates(1, System.currentTimeMillis())
                            .stream().findFirst().orElse(null);
                } catch (SQLException error) {
                    throw new RuntimeException(error);
                }
            });
        } catch (SQLException | RuntimeException ignored) {
            return null;
        }
    }

    private long resolvePersistedSongId(DeezerApiMetaData metadata, File file) {
        if (metadata != null && metadata.getTrackId() > 0) return metadata.getTrackId();
        if (file == null) return 0L;
        try {
            return DbConnectionManager.getInstance().runWithRetries(connection -> {
                try {
                    return new LyricsDaoImpl(connection).findSongIdByFilePath(file.getAbsolutePath()).orElse(0L);
                } catch (SQLException error) {
                    throw new RuntimeException(error);
                }
            });
        } catch (SQLException | RuntimeException ignored) {
            return 0L;
        }
    }

    private SongLyrics syncCandidate(LyricsLookupCandidate candidate) {
        if (candidate == null || candidate.sourceKey().isBlank()) return SongLyrics.empty();
        long now = System.currentTimeMillis();
        SongLyrics existing = findExisting(candidate);
        if (!isDue(existing, now)) {
            // Associate the reused result with this album edition as well.
            // This is a local write only; no second LRCLIB request is made.
            persist(candidate, existing);
            updateModel(candidate, existing);
            return existing;
        }

        SongLyrics lyrics = apiClient.lookup(candidate).lyrics();
        persist(candidate, lyrics);
        updateModel(candidate, lyrics);
        return lyrics;
    }

    private SongLyrics findExisting(LyricsLookupCandidate candidate) {
        try {
            return DbConnectionManager.getInstance().runWithRetries(connection -> {
                try {
                    LyricsDaoImpl dao = new LyricsDaoImpl(connection);
                    Optional<SongLyrics> sourceLyrics = candidate.sourceKey().isBlank()
                            ? Optional.empty()
                            : dao.findBySourceKey(candidate.sourceKey());
                    Optional<SongLyrics> sharedLyrics = candidate.hasLookupIdentity()
                            ? dao.findByTrackIdentity(candidate)
                            : Optional.empty();

                    return SongLyrics.prefer(
                            sourceLyrics.orElse(SongLyrics.empty()),
                            sharedLyrics.orElse(SongLyrics.empty())
                    );
                } catch (SQLException error) {
                    throw new RuntimeException(error);
                }
            });
        } catch (SQLException | RuntimeException ignored) {
            return SongLyrics.empty();
        }
    }

    private static boolean isDue(SongLyrics lyrics, long now) {
        if (lyrics == null || lyrics.status() == LyricsStatus.PENDING) return true;
        if (lyrics.status() == LyricsStatus.RETRYABLE_ERROR || lyrics.status() == LyricsStatus.NOT_FOUND) {
            return lyrics.nextRetryAtMillis() <= now;
        }
        return false;
    }

    private void scheduleRetryIfNeeded(SongLyrics lyrics) {
        if (lyrics == null || lyrics.nextRetryAtMillis() <= System.currentTimeMillis()) return;
        if (lyrics.status() != LyricsStatus.RETRYABLE_ERROR && lyrics.status() != LyricsStatus.NOT_FOUND) return;

        long requestedWakeAt = lyrics.nextRetryAtMillis();
        while (true) {
            long currentWakeAt = nextWakeAtMillis.get();
            if (currentWakeAt > 0L && currentWakeAt <= requestedWakeAt) return;
            if (!nextWakeAtMillis.compareAndSet(currentWakeAt, requestedWakeAt)) continue;

            long delay = Math.max(1L, requestedWakeAt - System.currentTimeMillis());
            retryScheduler.schedule(() -> {
                nextWakeAtMillis.compareAndSet(requestedWakeAt, 0L);
                scheduleBackgroundPass();
            }, delay, TimeUnit.MILLISECONDS);
            return;
        }
    }

    private void persist(LyricsLookupCandidate candidate, SongLyrics lyrics) {
        try {
            DbConnectionManager.getInstance().runInTransaction(connection -> {
                try {
                    new LyricsDaoImpl(connection).upsert(candidate, lyrics);
                } catch (SQLException error) {
                    throw new RuntimeException(error);
                }
                return null;
            });
        } catch (SQLException | RuntimeException ignored) {
            // Keep the lookup outcome in memory; the next queued pass can retry persistence.
        }
    }

    private void updateModel(LyricsLookupCandidate candidate, SongLyrics lyrics) {
        if (candidate == null || lyrics == null) return;
        String trackKey = candidate.trackKey();
        synchronized (owner.getSongs()) {
            for (Song song : owner.getSongs()) {
                if (song == null) continue;
                boolean sameSource = candidate.songId() > 0 && song.getSongID() == candidate.songId();
                boolean sameTrack = !trackKey.isBlank()
                        && LyricsCandidateFactory.fromSong(song).trackKey().equals(trackKey);
                if (sameSource || sameTrack) song.setLyrics(lyrics);
            }
        }
    }

    private interface Work {
        void run();
    }

    private final class DownloadWork implements Work {
        private final DeezerApiMetaData metadata;
        private final File file;
        private final CompletableFuture<SongLyrics> completion;

        private DownloadWork(DeezerApiMetaData metadata, File file, CompletableFuture<SongLyrics> completion) {
            this.metadata = metadata;
            this.file = file;
            this.completion = completion;
        }

        @Override
        public void run() {
            processDownload(metadata, file, completion);
        }
    }

    private final class BackgroundWork implements Work {
        @Override
        public void run() {
            processOneBackgroundCandidate();
        }
    }
}
