
package io.github.guillermodubon.musicplayer.services.startup.downloads;

import javafx.application.Platform;
import io.github.guillermodubon.musicplayer.utils.SongDataHelper;
import io.github.guillermodubon.musicplayer.utils.FileNameUtils;
import io.github.guillermodubon.musicplayer.models.*;
import io.github.guillermodubon.musicplayer.services.manifest.ManifestSyncService;
import io.github.guillermodubon.musicplayer.services.downloads.services.DownloadPipelineExecutors;
import io.github.guillermodubon.musicplayer.services.downloads.context.DownloadTaskContext;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.services.startup.persistence.DownloadedMediaPersistenceService;

import java.io.File;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

public class DownloadLifecycleService {

    private final StartUpService owner;
    private final ManifestSyncService manifestSyncService;
    private final DownloadedMediaPersistenceService downloadedMediaPersistenceService;
    private final DownloadedTrackArtistService trackArtistService;

    private final List<BiConsumer<DeezerApiMetaData, File>> downloadListeners = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, CompletableFuture<Void>> processingDownloads = new ConcurrentHashMap<>();
    private final Set<String> recentlyNotified = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Long> albumsManifestExpanded = ConcurrentHashMap.newKeySet();

    private final ScheduledExecutorService NOTIFY_CLEANER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "notify-cleaner");
        t.setDaemon(true);
        return t;
    });

    public DownloadLifecycleService(
            StartUpService owner,
            ManifestSyncService manifestSyncService,
            DownloadedMediaPersistenceService downloadedMediaPersistenceService
    ) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.manifestSyncService = Objects.requireNonNull(manifestSyncService, "manifestSyncService");
        this.downloadedMediaPersistenceService = Objects.requireNonNull(
                downloadedMediaPersistenceService,
                "downloadedMediaPersistenceService"
        );
        this.trackArtistService = new DownloadedTrackArtistService(owner);
        this.NOTIFY_CLEANER.scheduleAtFixedRate(recentlyNotified::clear, 30, 30, TimeUnit.SECONDS);
    }

    public void addDownloadListener(BiConsumer<DeezerApiMetaData, File> l) {
        if (l == null) return;
        synchronized (downloadListeners) {
            if (downloadListeners.contains(l)) return;
            downloadListeners.add(l);
        }
        System.out.println("addDownloadListener -> total listeners: " + downloadListeners.size());
    }

    public void removeDownloadListener(BiConsumer<DeezerApiMetaData, File> l) {
        if (l == null) return;
        synchronized (downloadListeners) {
            downloadListeners.remove(l);
        }
        System.out.println("removeDownloadListener -> total listeners: " + downloadListeners.size());
    }

    public List<Artist> getCachedTrackArtists(long trackId) {
        return trackArtistService.getCachedTrackArtists(trackId);
    }

    public void ensureTrackArtistsLoadedAsync(long trackId, Song target, Runnable onComplete) {
        trackArtistService.ensureTrackArtistsLoadedAsync(trackId, target, onComplete);
    }

    /**
     * Publishes a song only after StartUpService has completed every durable
     * integration step, including its in-memory cache refresh.
     */
    public CompletableFuture<Void> notifyFullyIntegratedDownloadAsync(DeezerApiMetaData meta, File file) {
        return notifyFullyIntegratedDownloadAsync(null, meta, file);
    }

    public CompletableFuture<Void> notifyFullyIntegratedDownloadAsync(
            DownloadTaskContext taskContext,
            DeezerApiMetaData meta,
            File file
    ) {
        if (file == null) return CompletableFuture.completedFuture(null);

        if (taskContext != null
                && !taskContext.isDownloadPublicationAllowed()) {
            return CompletableFuture.completedFuture(null);
        }

        String notificationKey = buildDownloadKey(meta, file);
        if (!recentlyNotified.add(notificationKey)) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> publication = new CompletableFuture<>();
        Runnable notifyUi = () -> {
            try {
                if (taskContext != null
                        && !taskContext.isDownloadPublicationAllowed()) {
                    publication.complete(null);
                    return;
                }

                List<BiConsumer<DeezerApiMetaData, File>> snapshot;
                synchronized (downloadListeners) {
                    snapshot = new ArrayList<>(downloadListeners);
                }
                for (BiConsumer<DeezerApiMetaData, File> l : snapshot) {
                    try {
                        l.accept(meta, file);
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                }
                publication.complete(null);
            } catch (Throwable error) {
                publication.completeExceptionally(error);
            }
        };
        if (Platform.isFxApplicationThread()) notifyUi.run();
        else Platform.runLater(notifyUi);
        return publication;
    }

    private CompletableFuture<Void> finalizeDownloadStateAsync(DeezerApiMetaData meta, File finalFile) {
        if (finalFile == null || !finalFile.exists()) return CompletableFuture.completedFuture(null);

        long timestamp = finalFile.lastModified() > 0L
                ? finalFile.lastModified()
                : System.currentTimeMillis();

        return manifestSyncService
                .updateManifestEntryAsync(meta, finalFile, timestamp)
                .thenCompose(ignored -> {
                    registerDownloadPaths(meta, finalFile);
                    markDownloadedSongPlayableInMemory(meta, finalFile);
                    return CompletableFuture.completedFuture(null);
                });
    }

    public CompletableFuture<Void> postProcessDownloadedMetaAsync(DeezerApiMetaData meta, File file) {
        if (meta == null) return CompletableFuture.completedFuture(null);
        System.out.println("postProcessDownloadedMetaAsync SUBMIT: trackId=" + meta.getTrackId() + " albumId=" + meta.getAlbumId() + " songName=" + meta.getSongName());

        return CompletableFuture.runAsync(() -> {
            try {
                runWithRetry(
                        () -> downloadedMediaPersistenceService.persist(meta, file),
                        4,
                        150L
                );
                if (meta.getTrackId() > 0) {
                    owner.getArtistBiographyService().hydrateMetadataArtistBiographiesAsync(meta);
                }
            } catch (Throwable t) {
                System.out.println("postProcessDownloadedMetaAsync: UNCAUGHT -> " + Optional.ofNullable(t.getMessage()).orElse("null"));
                t.printStackTrace();
                throw new CompletionException(t);
            }
        }, DownloadPipelineExecutors.persistence());
    }


    public synchronized CompletableFuture<Void> handleDownloadedSongAsync(DeezerApiMetaData meta, File finalFile) {
        String key = buildDownloadKey(meta, finalFile);

        CompletableFuture<Void> existing = processingDownloads.get(key);
        if (existing != null) {
            System.out.println("handleDownloadedSong: already processing " + key + " -> joining existing work");
            return existing;
        }

        if (finalFile == null || meta == null) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> integration = postProcessDownloadedMetaAsync(meta, finalFile)
                .thenCompose(ignored -> finalizeDownloadStateAsync(meta, finalFile));

        processingDownloads.put(key, integration);
        integration.whenComplete((v, ex) -> {
            try {
                if (ex != null) {
                    System.out.println("handleDownloadedSong: postProcess completed with error");
                    ex.printStackTrace();
                }
            } finally {
                processingDownloads.remove(key, integration);
            }
        });

        System.out.println(">>> handleDownloadedSong END (full integration scheduled)");
        return integration;
    }

    private void registerDownloadPaths(DeezerApiMetaData meta, File finalFile) {
        String title = meta != null && meta.getSongName() != null && !meta.getSongName().isBlank()
                ? meta.getSongName().trim()
                : FileNameUtils.withoutExtension(finalFile.getName());
        owner.putTitleToPath(title, finalFile.getAbsolutePath());

        if (meta == null || meta.getAlbumArtistNames() == null) return;
        for (String artistName : meta.getAlbumArtistNames()) {
            if (artistName == null || artistName.isBlank()) continue;
            String combined = artistName + " " + title;
            owner.putTitleToPath(combined, finalFile.getAbsolutePath());
            owner.putTitleToPath(SongDataHelper.sanitizeForFileKey(combined), finalFile.getAbsolutePath());
        }
    }

    private void markDownloadedSongPlayableInMemory(DeezerApiMetaData meta, File finalFile) {
        if (meta == null || finalFile == null) return;

        long trackId = meta.getTrackId();
        String title = meta.getSongName();
        String absolutePath = finalFile.getAbsolutePath();

        synchronized (owner.getSongs()) {
            for (Song song : owner.getSongs()) {
                if (song == null) continue;
                boolean matches = trackId > 0 && song.getSongID() == trackId;
                if (!matches && title != null && song.getTitle() != null) {
                    matches = title.equalsIgnoreCase(song.getTitle());
                }
                if (!matches) continue;

                song.setLocal(true);
                song.setFilePath(absolutePath);
            }
        }
    }

    private String buildDownloadKey(DeezerApiMetaData meta, File file) {
        if (meta != null && meta.getTrackId() > 0) return "id:" + meta.getTrackId();
        if (file != null) return "path:" + file.getAbsolutePath().toLowerCase(Locale.ROOT);
        return "unknown:" + System.nanoTime();
    }

    private void runWithRetry(Runnable task, int attempts, long baseMillis) {
        int tries = 0;
        while (true) {
            tries++;
            try {
                task.run();
                return;
            } catch (RuntimeException re) {
                SQLException sqlError = findSqlException(re);
                if (tries < attempts && sqlError != null) {
                    String msg = Optional.ofNullable(sqlError.getMessage()).orElse("").toLowerCase(Locale.ROOT);
                    if (msg.contains("busy") || msg.contains("locked")) {
                        try { Thread.sleep(baseMillis * tries); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        continue;
                    }
                }
                throw re;
            }
        }
    }

    private SQLException findSqlException(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sqlException) return sqlException;
            current = current.getCause();
        }
        return null;
    }

    public void expandAlbumForDownloadedTrack(DeezerApiMetaData meta, File file, Long finalTs) {
        if (meta == null) return;
        long albumId = meta.getAlbumId();
        if (albumId <= 0) return;
        if (!albumsManifestExpanded.add(albumId)) return;

        DownloadPipelineExecutors.persistence().execute(() -> {
            try {
                if (meta.getAlbumId() > 0) {
                    owner.promoteRemoteAlbumToLocalDynamic(meta, file);
                }
            } catch (Throwable t) {
                System.out.println("expandAlbumForDownloadedTrack: UNCAUGHT -> " + Optional.ofNullable(t.getMessage()).orElse("null"));
                t.printStackTrace();
            }
        });
    }

}

