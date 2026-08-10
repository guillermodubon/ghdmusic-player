
package io.github.guillermodubon.musicplayer.services.startup;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import io.github.guillermodubon.musicplayer.repository.DataBaseConfig;
import io.github.guillermodubon.musicplayer.repository.DbConnectionManager;
import io.github.guillermodubon.musicplayer.repository.dao.album.AlbumDao;
import io.github.guillermodubon.musicplayer.repository.dao.album.AlbumDaoImpl;
import io.github.guillermodubon.musicplayer.repository.dao.artist.ArtistDao;
import io.github.guillermodubon.musicplayer.repository.dao.artist.ArtistDaoImpl;
import io.github.guillermodubon.musicplayer.repository.dao.genre.GenreDao;
import io.github.guillermodubon.musicplayer.repository.dao.genre.GenreDaoImpl;
import io.github.guillermodubon.musicplayer.repository.dao.playlist.PlaylistDao;
import io.github.guillermodubon.musicplayer.repository.dao.playlist.PlaylistDaoImpl;
import io.github.guillermodubon.musicplayer.repository.dao.song.SongDao;
import io.github.guillermodubon.musicplayer.repository.dao.song.SongDaoImpl;
import io.github.guillermodubon.musicplayer.controllers.layout.AppShellController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.SideBarNavigationMenu;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.HomePageController;
import io.github.guillermodubon.musicplayer.services.downloads.context.DownloadTaskContext;
import io.github.guillermodubon.musicplayer.services.downloads.logging.DownloadLog;
import io.github.guillermodubon.musicplayer.services.downloads.services.IntegratedDownloadResult;
import io.github.guillermodubon.musicplayer.services.downloads.services.PlayerMenuDownloadBridge;
import io.github.guillermodubon.musicplayer.utils.SongDataHelper;
import io.github.guillermodubon.musicplayer.utils.SongAudioIdentity;
import io.github.guillermodubon.musicplayer.services.manifest.ManifestService;
import io.github.guillermodubon.musicplayer.models.*;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;
import io.github.guillermodubon.musicplayer.services.api.DeezerHttpClient;
import io.github.guillermodubon.musicplayer.services.api.WikipediaApiService;
import io.github.guillermodubon.musicplayer.services.manifest.ManifestSyncService;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;
import io.github.guillermodubon.musicplayer.services.scanning.ScannedAudioFile;
import io.github.guillermodubon.musicplayer.services.scanning.SongScannerService;
import io.github.guillermodubon.musicplayer.services.startup.artist.ArtistBiographyService;
import io.github.guillermodubon.musicplayer.services.startup.downloads.DownloadedSongStateService;
import io.github.guillermodubon.musicplayer.services.startup.downloads.DownloadLifecycleService;
import io.github.guillermodubon.musicplayer.services.startup.hydration.ModelHydrationService;
import io.github.guillermodubon.musicplayer.services.startup.library.IncrementalLibrarySyncService;
import io.github.guillermodubon.musicplayer.services.startup.library.InitialLibraryImportService;
import io.github.guillermodubon.musicplayer.services.startup.locality.LocalAudioPathRecoveryService;
import io.github.guillermodubon.musicplayer.services.startup.locality.SongLocalityService;
import io.github.guillermodubon.musicplayer.services.startup.locality.SongPathResolver;
import io.github.guillermodubon.musicplayer.services.startup.orchestration.LibraryStartupCoordinator;
import io.github.guillermodubon.musicplayer.services.startup.persistence.RemoteAlbumPromotionService;
import io.github.guillermodubon.musicplayer.services.startup.persistence.DownloadedMediaPersistenceService;
import io.github.guillermodubon.musicplayer.services.lyrics.LyricsSyncService;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

public class StartUpService {

    private static StartUpService INSTANCE;

    private final SongScannerService scannerService = new SongScannerService();
    private final DeezerApiService deezerService = new DeezerApiService();
    private final WikipediaApiService wikipediaService = new WikipediaApiService();
    private final ArtistBiographyService artistBiographyService = new ArtistBiographyService(wikipediaService);

    private final ManifestSyncService manifestService = new ManifestSyncService();
    private final ModelHydrationService modelHydrationService = new ModelHydrationService(this);
    private final SongLocalityService songLocalityService = new SongLocalityService(this, manifestService);
    private final RemoteAlbumPromotionService remoteAlbumPromotionService =
            new RemoteAlbumPromotionService(this, modelHydrationService, songLocalityService);

    private final DownloadedMediaPersistenceService downloadedMediaPersistenceService =
            new DownloadedMediaPersistenceService(this, remoteAlbumPromotionService, modelHydrationService);

    private final DownloadLifecycleService downloadLifecycleService =
            new DownloadLifecycleService(this, manifestService, downloadedMediaPersistenceService);

    private final LyricsSyncService lyricsSyncService = new LyricsSyncService(this);

    private final InitialLibraryImportService initialLibraryImportService =
            new InitialLibraryImportService(deezerService, artistBiographyService, manifestService);

    private final IncrementalLibrarySyncService incrementalLibrarySyncService =
            new IncrementalLibrarySyncService(deezerService, manifestService, modelHydrationService, artistBiographyService);

    private final SongPathResolver songPathResolver = new SongPathResolver();
    private final LocalAudioPathRecoveryService localAudioPathRecoveryService =
            new LocalAudioPathRecoveryService(this);
    private final DownloadedSongStateService downloadedSongStateService;
    private final LibraryStartupCoordinator startupCoordinator;

    private final List<Genre> genres = new ArrayList<>();
    private final List<Album> albums = Collections.synchronizedList(new ArrayList<>());
    private final List<Song> songs = Collections.synchronizedList(new ArrayList<>());
    private final List<Artist> artists = Collections.synchronizedList(new ArrayList<>());
    private final ObservableList<Playlist> playlists = FXCollections.observableArrayList();
    private final Map<String, String> titleToPath = new ConcurrentHashMap<>();
    private final Map<String, String> audioIdentityToPath = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Runnable> genreChangeListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Runnable> audioIdentityChangeListeners = new CopyOnWriteArrayList<>();
    private final Object DB_LOCK = new Object();
    private volatile Consumer<String> startupStatusListener = ignored -> { };
    private volatile DoubleConsumer startupProgressListener = ignored -> { };

    public final List<javafx.util.Pair<String, String>> noMetadataSongs =
            Collections.synchronizedList(new ArrayList<>());

    private SideBarNavigationMenu leftMenuController;
    private HomePageController homePageController;

    private AlbumDao albumDao;
    private PlaylistDao playlistDao;

    private AppShellController appShellController;

    private volatile PlayerMenuDownloadBridge activePlayerMenuDownloadBridge;

    public StartUpService() {
        INSTANCE = this;
        downloadedSongStateService = new DownloadedSongStateService(this, deezerService);
        startupCoordinator = new LibraryStartupCoordinator(this);
        System.out.println(">>> StartUpService.<init>() new INSTANCE");
    }

    /**
     * Reports human-readable startup stages without coupling the service to a
     * specific UI. The splash screen subscribes while it is visible.
     */
    public void setStartupStatusListener(Consumer<String> listener) {
        startupStatusListener = listener == null ? ignored -> { } : listener;
    }

    public void setStartupProgressListener(DoubleConsumer listener) {
        startupProgressListener = listener == null ? ignored -> { } : listener;
    }

    public void reportStartupStatus(String message) {
        try {
            startupStatusListener.accept(message == null ? "" : message);
        } catch (Exception ignored) {
        }
    }

    public void reportStartupProgress(double progress) {
        try {
            startupProgressListener.accept(Math.max(0, Math.min(1, progress)));
        } catch (Exception ignored) {
        }
    }

    public static StartUpService getInstance() {
        return INSTANCE;
    }

    public ManifestSyncService getManifestService() {
        return manifestService;
    }

    public ModelHydrationService getModelHydrationService() {
        return modelHydrationService;
    }

    public ArtistBiographyService getArtistBiographyService() {
        return artistBiographyService;
    }

    public Object getDbLock() {
        return DB_LOCK;
    }

    public List<Genre> getGenres() {
        return genres;
    }

    /** Registers a lightweight listener notified after the Genre table is hydrated. */
    public void addGenreChangeListener(Runnable listener) {
        if (listener != null && !genreChangeListeners.contains(listener)) {
            genreChangeListeners.add(listener);
        }
    }

    public void removeGenreChangeListener(Runnable listener) {
        if (listener != null) genreChangeListeners.remove(listener);
    }

    /** Publishes a completed Genre-table refresh without touching JavaFX from the DB thread. */
    public void notifyGenreChangeListeners() {
        for (Runnable listener : genreChangeListeners) {
            try {
                listener.run();
            } catch (Throwable ignored) {
                // One screen must not prevent the remaining listeners from updating.
            }
        }
    }

    public List<Album> getAlbums() {
        return albums;
    }

    public List<Song> getSongs() {
        return songs;
    }

    public List<Artist> getArtists() {
        return artists;
    }

    public ObservableList<Playlist> getPlaylists() {
        return playlists;
    }

    public PlaylistDao getPlaylistDao() {
        return playlistDao;
    }

    public HomePageController getMainMenuController() {
        return homePageController;
    }

    public void setMainMenuController(HomePageController homePageController) {
        this.homePageController = homePageController;
    }

    public SideBarNavigationMenu getLeftMenuController() {
        return leftMenuController;
    }

    public void setLeftMenuController(SideBarNavigationMenu ctrl) {
        this.leftMenuController = ctrl;
    }

    public AppShellController getAppShellController() {
        return appShellController;
    }

    public void setAppShellController(AppShellController appShellController) {
        this.appShellController = appShellController;
    }

    public synchronized void putTitleToPath(String title, String path) {
        if (title == null || title.isBlank() || path == null || path.isBlank()) return;

        String k1 = title.trim();
        String k2 = k1.toLowerCase(Locale.ROOT);
        String k3;

        try {
            k3 = SongDataHelper.sanitizeForFileKey(k1);
        } catch (Throwable t) {
            k3 = null;
        }

        titleToPath.put(k1, path);
        titleToPath.put(k2, path);

        if (k3 != null && !k3.isBlank()) {
            titleToPath.put(k3, path);
        }

        try {
            String fallback = SongDataHelper.fallbackKey(k1);
            if (fallback != null && !fallback.isBlank()) {
                titleToPath.put(fallback, path);
            }
        } catch (Throwable ignored) {
        }

        System.out.println("putTitleToPath: '" + k1 + "' -> " + path);
    }

    public synchronized Map<String, String> getTitleToPathSnapshot() {
        return new HashMap<>(titleToPath);
    }

    /**
     * Registers a local file under the strict recording identity used for
     * cross-edition reuse: normalized title plus every credited artist.
     */
    public void putSongToPath(Song song, String path) {
        if (song == null) {
            return;
        }
        SongAudioIdentity.keyFor(song).ifPresent(key -> putAudioIdentityPath(key, path));
    }

    /** Registers downloaded metadata without conflating same-titled tracks. */
    public void putSongToPath(DeezerApiMetaData metadata, String path) {
        if (metadata == null) {
            return;
        }
        SongAudioIdentity.keyFor(metadata).ifPresent(key -> putAudioIdentityPath(key, path));
    }

    public synchronized Map<String, String> getSongIdentityPathSnapshot() {
        return new HashMap<>(audioIdentityToPath);
    }

    public void clearSongIdentityPathIndex() {
        audioIdentityToPath.clear();
    }

    /** Registers a lightweight listener for strict local-audio identity updates. */
    public void addAudioIdentityChangeListener(Runnable listener) {
        if (listener != null && !audioIdentityChangeListeners.contains(listener)) {
            audioIdentityChangeListeners.add(listener);
        }
    }

    public void removeAudioIdentityChangeListener(Runnable listener) {
        if (listener != null) {
            audioIdentityChangeListeners.remove(listener);
        }
    }

    /** Removes every strict identity associated with a deleted local file. */
    public void removeSongPath(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        audioIdentityToPath.entrySet().removeIf(entry -> path.equals(entry.getValue()));
    }

    public Map<String, String> titleToPathIndex() {
        return titleToPath;
    }

    public SongScannerService scannerService() {
        return scannerService;
    }

    public DeezerApiService deezerService() {
        return deezerService;
    }

    public LyricsSyncService lyricsSyncService() {
        return lyricsSyncService;
    }

    public InitialLibraryImportService initialLibraryImportService() {
        return initialLibraryImportService;
    }

    public IncrementalLibrarySyncService incrementalLibrarySyncService() {
        return incrementalLibrarySyncService;
    }

    public void setDataAccessObjects(AlbumDao albumDao, PlaylistDao playlistDao) {
        this.albumDao = albumDao;
        this.playlistDao = playlistDao;
    }


    public synchronized void addDownloadListener(BiConsumer<DeezerApiMetaData, File> l) {
        downloadLifecycleService.addDownloadListener(l);
    }

    public synchronized void removeDownloadListener(BiConsumer<DeezerApiMetaData, File> l) {
        downloadLifecycleService.removeDownloadListener(l);
    }

    public List<Artist> getCachedTrackArtists(long trackId) {
        return downloadLifecycleService.getCachedTrackArtists(trackId);
    }

    public void ensureTrackArtistsLoadedAsync(long trackId, Song targetSong, Runnable onComplete) {
        downloadLifecycleService.ensureTrackArtistsLoadedAsync(trackId, targetSong, onComplete);
    }

    public synchronized Optional<String> resolvePathForSong(Song s) {
        return songPathResolver.resolvePathForSong(s, audioIdentityToPath);
    }

    /**
     * Used by playback after the fast resolver has failed. Disk work stays on
     * the playback executor and is coalesced by the recovery service.
     */
    public Optional<String> recoverMovedAudioPath(Song song) {
        return localAudioPathRecoveryService.recoverMovedPath(song);
    }

    /** Shares the startup scan with the local-path repair workflow. */
    public void updateScannedAudioFiles(List<ScannedAudioFile> scannedFiles) {
        localAudioPathRecoveryService.replaceScanIndex(scannedFiles);
    }

    /** Repairs old database locations before in-memory models are hydrated. */
    public int reconcileMovedLocalAudioPaths(
            Connection connection,
            Map<String, ManifestEntry> manifest
    ) throws SQLException {
        return localAudioPathRecoveryService.reconcilePersistedLocalPaths(connection, manifest);
    }

    /**
     * Publishes a recovered path immediately to every in-memory view of the
     * same recording and persists only the canonical local row asynchronously.
     */
    public void applyRecoveredLocalPath(Song persistedSong, String recoveredPath) {
        if (persistedSong == null || recoveredPath == null || recoveredPath.isBlank()) return;

        updateRecoveredSongReference(persistedSong, persistedSong, recoveredPath);
        synchronized (songs) {
            for (Song candidate : songs) {
                updateRecoveredSongReference(candidate, persistedSong, recoveredPath);
            }
        }
        synchronized (albums) {
            for (Album album : albums) {
                if (album == null || album.getSongList() == null) continue;
                for (Song candidate : album.getSongList()) {
                    updateRecoveredSongReference(candidate, persistedSong, recoveredPath);
                }
            }
        }
        synchronized (playlists) {
            for (Playlist playlist : playlists) {
                if (playlist == null || playlist.getSongList() == null) continue;
                for (Song candidate : playlist.getSongList()) {
                    updateRecoveredSongReference(candidate, persistedSong, recoveredPath);
                }
            }
        }

        putTitleToPath(persistedSong.getTitle(), recoveredPath);
        putSongToPath(persistedSong, recoveredPath);
        songLocalityService.repairSongPathAsync(persistedSong, recoveredPath);
    }

    private void updateRecoveredSongReference(Song candidate, Song persistedSong, String recoveredPath) {
        if (candidate == null || persistedSong == null) return;
        boolean sameId = candidate.getSongID() > 0
                && candidate.getSongID() == persistedSong.getSongID();
        if (!sameId && !SongAudioIdentity.matches(candidate, persistedSong)) return;
        candidate.setLocal(true);
        candidate.setFilePath(recoveredPath);
    }

    private void putAudioIdentityPath(String identityKey, String path) {
        if (identityKey == null || identityKey.isBlank() || path == null || path.isBlank()) {
            return;
        }
        String previousPath = audioIdentityToPath.put(identityKey, path);
        if (Objects.equals(previousPath, path)) {
            return;
        }
        for (Runnable listener : audioIdentityChangeListeners) {
            try {
                listener.run();
            } catch (Throwable ignored) {
                // A UI listener must never interfere with download persistence.
            }
        }
    }


    /**
     * Invalidates a local reference when its backing file was deleted while the
     * application was running. The cache update is immediate; DB/manifest work
     * is delegated to SongLocalityService.
     */
    public void markSongAsUnavailable(Song song) {
        songLocalityService.markSongAsUnavailable(song);
    }

    public boolean promoteRemoteAlbumToLocalDynamic(DeezerApiMetaData meta, File file) {
        boolean promoted = remoteAlbumPromotionService.promoteRemoteAlbumToLocalDynamic(meta, file);
        refreshDownloadedSongCaches(meta, file);
        return promoted;
    }



    public CompletableFuture<Void> handleDownloadedSongAsync(DeezerApiMetaData meta, File finalFile) {
        return prepareDownloadedSongAsync(meta, finalFile)
                .thenCompose(ignored -> publishFullyIntegratedDownloadAsync(meta, finalFile));
    }

    /**
     * Completes durable download integration without changing visible song cells.
     */
    public CompletableFuture<Void> prepareDownloadedSongAsync(DeezerApiMetaData meta, File finalFile) {
        return downloadLifecycleService
                .handleDownloadedSongAsync(meta, finalFile)
                .thenRun(() -> refreshDownloadedSongCaches(meta, finalFile))
                .thenRun(() -> {
                    try {
                        PlaybackManager.getInstance().reloadCurrentMediaIfNeeded();
                    } catch (Exception ignored) {
                    }
                });
    }

    /**
     * Publishes the already prepared local song to JavaFX listeners.
     */
    public CompletableFuture<Void> publishFullyIntegratedDownloadAsync(
            DeezerApiMetaData meta,
            File finalFile
    ) {
        return publishFullyIntegratedDownloadAsync(
                null,
                meta,
                finalFile
        );
    }

    public CompletableFuture<Void> publishFullyIntegratedDownloadAsync(
            DownloadTaskContext taskContext,
            DeezerApiMetaData meta,
            File finalFile
    ) {
        if (taskContext != null
                && !taskContext.isDownloadPublicationAllowed()) {
            return CompletableFuture.completedFuture(null);
        }

        Song canonicalSong =
                findCanonicalDownloadedSong(
                        meta,
                        finalFile
                ).orElse(null);

        /*
         * Immediate fallback:
         * sourceSong is precisely the object used by SongItemVisual.
         * If the canonical version is not yet in the global cache,
         * that exact reference can be converted to a local one.
         */
        Song publishedLocalSong = canonicalSong;

        if (publishedLocalSong == null
                && taskContext != null
                && taskContext.getSourceSong() != null) {
            publishedLocalSong =
                    taskContext.getSourceSong();

            publishedLocalSong.setLocal(true);

            if (finalFile != null) {
                publishedLocalSong.setFilePath(
                        finalFile.getAbsolutePath()
                );
            }
        }

        Song finalPublishedLocalSong =
                publishedLocalSong;

        CompletableFuture<Void> bridgePublication =
                publishExactDownloadToPlayerMenu(
                        taskContext,
                        finalPublishedLocalSong,
                        finalFile
                ).exceptionally(error -> {
                    DownloadLog.error(
                            "StartUpService",
                            "Exact PlayerMenu download publication failed",
                            error
                    );

                    return null;
                });

        /*
         * The listener is retained as a fallback. It must execute even if the
         * bridge fails or does not exist.
         */
        CompletableFuture<Void> listenerPublication =
                downloadLifecycleService
                        .notifyFullyIntegratedDownloadAsync(
                                taskContext,
                                meta,
                                finalFile
                        )
                        .exceptionally(error -> {
                            DownloadLog.error(
                                    "StartUpService",
                                    "Legacy download listener publication failed",
                                    error
                            );

                            return null;
                        });

        return CompletableFuture.allOf(
                bridgePublication,
                listenerPublication
        );
    }

    /**
     * Used by the improved download pipeline when it needs the canonical local Song
     * after DB/cache/manifest integration.
     */
    public Song persistDownloadedSongAndReturnCanonical(DeezerApiMetaData meta, File finalFile) {
        return persistDownloadedSongAndReturnCanonicalAsync(meta, finalFile).join();
    }

    public CompletableFuture<Song> persistDownloadedSongAndReturnCanonicalAsync(DeezerApiMetaData meta, File finalFile) {
        return handleDownloadedSongAsync(meta, finalFile)
                .thenApply(ignored -> findCanonicalDownloadedSong(meta, finalFile).orElse(null));
    }

    /**
     * Resolves/fallbacks metadata for a downloaded song.
     *
     * This is useful for integration services that receive source Song + file
     * and need a DeezerApiMetaData object before calling persistence.
     */
    public DeezerApiMetaData resolveDownloadMetadata(Song sourceSong, File finalFile) {
        return downloadedSongStateService.resolveDownloadMetadata(sourceSong, finalFile);
    }


    private void refreshDownloadedSongCaches(DeezerApiMetaData meta, File finalFile) {
        downloadedSongStateService.refreshDownloadedSongCaches(meta, finalFile);
    }


    public void markSongUnavailableInMemory(Song target, String missingPath) {
        downloadedSongStateService.markSongUnavailableInMemory(target, missingPath);
    }

    public Optional<Song> findCanonicalDownloadedSong(DeezerApiMetaData meta, File finalFile) {
        return downloadedSongStateService.findCanonicalDownloadedSong(meta, finalFile);
    }
    public static com.google.gson.JsonObject fetchJsonObject(String urlStr) {
        return DeezerHttpClient.fetchJsonObjectStatic(urlStr);
    }

    public void runStartup() throws SQLException {
        startupCoordinator.runStartup();
    }

    public synchronized void registerPlayerMenuDownloadBridge(
            PlayerMenuDownloadBridge bridge
    ) {
        activePlayerMenuDownloadBridge = bridge;
    }

    public synchronized void unregisterPlayerMenuDownloadBridge(
            PlayerMenuDownloadBridge bridge
    ) {
        if (activePlayerMenuDownloadBridge == bridge) {
            activePlayerMenuDownloadBridge = null;
        }
    }

    private CompletableFuture<Void> publishExactDownloadToPlayerMenu(
            DownloadTaskContext taskContext,
            Song localSong,
            File finalFile
    ) {
        if (taskContext == null
                || finalFile == null
                || localSong == null) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> publication =
                new CompletableFuture<>();

        Runnable action = () -> {
            try {
                if (!taskContext.isDownloadPublicationAllowed()
                        || !isUsableDownloadedFile(finalFile)) {
                    publication.complete(null);
                    return;
                }

                /*
                 * This always executes, regardless of which screen
                 * is visible.
                 *
                 * It does not refresh any interface: it only updates the specific
                 * model from which the download originated.
                 */
                Song sourceReadySong =
                        updateStoredDownloadSourceModel(
                                taskContext,
                                localSong,
                                finalFile
                        );

                /*
                 * The PlayerMenu bridge is only responsible for the visible
                 * screen. Playback must be updated independently so a batch
                 * track joins the active album/playlist even after navigation
                 * has replaced or detached that screen.
                 */
                synchronizeActivePlaybackFlow(
                        taskContext,
                        sourceReadySong
                );

                if (!taskContext.isDownloadPublicationAllowed()) {
                    publication.complete(null);
                    return;
                }

                PlayerMenuDownloadBridge bridge =
                        activePlayerMenuDownloadBridge;


                if (bridge != null
                        && sourceReadySong != null) {

                    IntegratedDownloadResult result =
                            new IntegratedDownloadResult(
                                    taskContext.getSourceSong(),
                                    sourceReadySong,
                                    finalFile,
                                    taskContext.getSourceCollectionId(),
                                    taskContext.getSourceCollectionType()
                            );

                    bridge.publishIntegratedDownload(result);
                }

                publication.complete(null);

            } catch (Throwable error) {
                publication.completeExceptionally(error);
            }
        };

        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }

        return publication;
    }

    private boolean isUsableDownloadedFile(File file) {
        try {
            return file != null
                    && file.exists()
                    && file.isFile()
                    && file.canRead()
                    && file.length() > 0L;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void synchronizeActivePlaybackFlow(
            DownloadTaskContext taskContext,
            Song localSong
    ) {
        if (taskContext == null || localSong == null) {
            return;
        }

        Long sourceCollectionId = taskContext.getSourceCollectionId();

        if (sourceCollectionId == null || sourceCollectionId <= 0L) {
            return;
        }

        try {
            Song remoteSong = taskContext.getSourceSong();

            PlaybackManager.getInstance()
                    .integrateDownloadedSongIntoCurrentFlow(
                            remoteSong == null ? localSong : remoteSong,
                            localSong,
                            sourceCollectionId
                    );
        } catch (Exception error) {
            DownloadLog.error(
                    "StartUpService",
                    "Could not add a completed download to the active playback flow",
                    error
            );
        }
    }

    private Song updateStoredDownloadSourceModel(
            DownloadTaskContext taskContext,
            Song canonicalLocalSong,
            File finalFile
    ) {
        if (taskContext == null
                || canonicalLocalSong == null
                || finalFile == null) {
            return canonicalLocalSong;
        }

        Song originalSourceSong =
                taskContext.getSourceSong();

        /*
         * We prefer the original SongItemVisual object because it typically
         * contains the complete remote metadata: artists, album, cover art,
         * genre, and position within the collection.
         */
        Song sourceReadySong =
                originalSourceSong != null
                        ? originalSourceSong
                        : canonicalLocalSong;

        mergeDownloadedLocalState(
                sourceReadySong,
                canonicalLocalSong,
                finalFile
        );

        Playlist sourceModel =
                taskContext.getSourcePlaylistModel();

        if (sourceModel != null
                && sourceModel.getSongList() != null) {

            List<Song> sourceSongs =
                    sourceModel.getSongList();

            for (int index = 0;
                 index < sourceSongs.size();
                 index++) {

                Song existing =
                        sourceSongs.get(index);

                if (!sameDownloadedSong(
                        existing,
                        originalSourceSong
                ) && !sameDownloadedSong(
                        existing,
                        canonicalLocalSong
                )) {
                    continue;
                }


                mergeDownloadedLocalState(
                        existing,
                        canonicalLocalSong,
                        finalFile
                );

                sourceSongs.set(
                        index,
                        existing
                );

                sourceReadySong = existing;
                break;
            }
        }

        taskContext.setSourceSong(sourceReadySong);

        return sourceReadySong;
    }

    private void mergeDownloadedLocalState(
            Song target,
            Song canonicalLocalSong,
            File finalFile
    ) {
        if (target == null || finalFile == null) {
            return;
        }

        target.setLocal(true);
        target.setFilePath(
                finalFile.getAbsolutePath()
        );

        if (canonicalLocalSong == null
                || target == canonicalLocalSong) {
            return;
        }

        try {
            if (target.getSongID() <= 0
                    && canonicalLocalSong.getSongID() > 0) {
                target.setSongID(
                        canonicalLocalSong.getSongID()
                );
            }
        } catch (Exception ignored) {
        }

        try {
            if (target.getTrackOrder() <= 0
                    && canonicalLocalSong.getTrackOrder() > 0) {
                target.setTrackOrder(
                        canonicalLocalSong.getTrackOrder()
                );
            }
        } catch (Exception ignored) {
        }

        try {
            if (!hasUsableArtists(target.getArtist())
                    && hasUsableArtists(
                    canonicalLocalSong.getArtist()
            )) {
                target.setArtist(
                        new ArrayList<>(
                                canonicalLocalSong.getArtist()
                        )
                );
            }
        } catch (Exception ignored) {
        }

        try {
            Album targetAlbum = target.getAlbum();
            Album canonicalAlbum =
                    canonicalLocalSong.getAlbum();

            if (targetAlbum == null
                    && canonicalAlbum != null) {
                target.setAlbum(canonicalAlbum);
            } else if (targetAlbum != null
                    && canonicalAlbum != null
                    && !hasUsableArtists(
                    targetAlbum.getArtist()
            )
                    && hasUsableArtists(
                    canonicalAlbum.getArtist()
            )) {
                targetAlbum.setArtist(
                        new ArrayList<>(
                                canonicalAlbum.getArtist()
                        )
                );
            }
        } catch (Exception ignored) {
        }
    }

    private boolean sameDownloadedSong(
            Song first,
            Song second
    ) {
        if (first == null || second == null) {
            return false;
        }

        if (first == second) {
            return true;
        }

        if (first.getSongID() > 0
                && second.getSongID() > 0) {
            return first.getSongID()
                    == second.getSongID();
        }

        return SongAudioIdentity.matches(first, second);
    }

    private boolean hasUsableArtists(
            List<Artist> artists
    ) {
        if (artists == null || artists.isEmpty()) {
            return false;
        }

        for (Artist artist : artists) {
            if (artist == null
                    || artist.getName() == null) {
                continue;
            }

            String name =
                    artist.getName()
                            .trim()
                            .toLowerCase(Locale.ROOT);

            if (!name.isBlank()
                    && !name.equals("unknown")
                    && !name.equals("unknown artist")) {
                return true;
            }
        }

        return false;
    }

}
