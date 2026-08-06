package io.github.guillermodubon.musicplayer.services.downloads.services;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.services.downloads.DownloadTask;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.downloads.context.DownloadTaskContext;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;



public final class DownloadCompletionIntegrationService {

    private final StartUpService startUpService;
    private final PlayerMenuDownloadBridge playerMenuBridge;
    private final ExecutorService metadataExecutor;
    private final ExecutorService persistenceExecutor;

    public DownloadCompletionIntegrationService(
            StartUpService startUpService,
            PlayerMenuDownloadBridge playerMenuBridge,
            ExecutorService metadataExecutor,
            ExecutorService persistenceExecutor
    ) {
        this.startUpService = Objects.requireNonNull(startUpService, "startUpService");
        this.playerMenuBridge = Objects.requireNonNull(playerMenuBridge, "playerMenuBridge");
        this.metadataExecutor = Objects.requireNonNull(metadataExecutor, "metadataExecutor");
        this.persistenceExecutor = Objects.requireNonNull(persistenceExecutor, "persistenceExecutor");
    }

    public CompletableFuture<IntegratedDownloadResult> integrateAsync(
            DownloadTask task,
            Song remoteSong,
            File downloadedFile
    ) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(remoteSong, "remoteSong");
        Objects.requireNonNull(downloadedFile, "downloadedFile");

        /*
         * Important:
         * Do not call DownloadTask.updateProgress(...) here.
         * JavaFX Task.updateProgress(...) is protected.
         *
         * The DownloadTask itself is responsible for progress updates:
         * - yt-dlp progress
         * - file finalization
         * - playable publication
         * - metadata/post-processing
         * - completion
         */
        return CompletableFuture
                .supplyAsync(() -> startUpService.resolveDownloadMetadata(remoteSong, downloadedFile), metadataExecutor)

                .thenApplyAsync(meta -> {
                    Song canonicalSong =
                            startUpService.persistDownloadedSongAndReturnCanonical(
                                    meta,
                                    downloadedFile
                            );

                    validateIntegratedSong(
                            canonicalSong,
                            downloadedFile
                    );

                    /*
                     * Para la UI se conserva el Song original que ya tiene la metadata
                     * completa del PlayerMenuController: artistas, álbum, portada y orden.
                     *
                     * Solo se le incorpora el estado local producido por la persistencia.
                     */
                    Song sourceSnapshot = task.getContext() == null
                            ? null
                            : task.getContext().getSourceSong();
                    Song uiReadySong = prepareSourceSongForUi(
                            sourceSnapshot == null ? remoteSong : sourceSnapshot,
                            canonicalSong,
                            downloadedFile
                    );

                    /*
                     * Se actualiza el modelo exacto almacenado en DownloadTaskContext.
                     *
                     * Esto se realiza aunque el usuario esté en Home, Discover, búsqueda
                     * u otra pantalla. No refresca ninguna interfaz.
                     */
                    updateStoredSourceModel(
                            task,
                            remoteSong,
                            uiReadySong
                    );

                    return new IntegratedDownloadResult(
                            remoteSong,
                            uiReadySong,
                            downloadedFile
                    );
                }, persistenceExecutor)

                .thenCompose(result -> publishIntegrationOnFxThread(task, result));
    }

    private CompletableFuture<IntegratedDownloadResult> publishIntegrationOnFxThread(
            DownloadTask task,
            IntegratedDownloadResult result
    ) {
        CompletableFuture<IntegratedDownloadResult> future = new CompletableFuture<>();

        Runnable action = () -> {
            try {
                if (task == null
                        || task.isCancelled()
                        || task.getState() == Worker.State.CANCELLED
                        || task.getState() == Worker.State.FAILED
                        || result == null
                        || result.downloadedFile() == null
                        || !result.downloadedFile().exists()
                        || !result.downloadedFile().isFile()
                        || result.downloadedFile().length() <= 0L) {
                    future.complete(result);
                    return;
                }

                /*
                 * PlayerMenuDownloadSyncService implements this bridge.
                 * That service is responsible for:
                 * - replacing SongItemVisual model with local Song
                 * - refreshing ListView
                 * - refreshing queue
                 * - refreshing playback context
                 */
                playerMenuBridge.publishIntegratedDownload(result);

                /*
                 * Safety net:
                 * If the bridge is not attached to the active PlayerMenu,
                 * this still updates PlaybackManager references.
                 */
                PlaybackManager.getInstance().integrateDownloadedSongIntoCurrentFlow(
                        result.remoteSong(),
                        result.localSong(),
                        resolveSourceCollectionId(task)
                );

                future.complete(result);
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        };

        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }

        return future;
    }

    private Long resolveSourceCollectionId(DownloadTask task) {
        try {
            if (task == null || task.getContext() == null) {
                return null;
            }

            Long sourceId = task.getContext().getSourceCollectionId();

            return sourceId != null && sourceId > 0 ? sourceId : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void validateIntegratedSong(Song localSong, File file) {
        if (localSong == null) {
            throw new IllegalStateException("La canción integrada no puede ser null.");
        }

        if (!localSong.isLocal()) {
            localSong.setLocal(true);
        }

        if (file == null || !file.exists() || !file.isFile()) {
            throw new IllegalStateException("El archivo descargado no existe.");
        }

        String filePath = file.getAbsolutePath();

        if (localSong.getFilePath() == null || localSong.getFilePath().isBlank()) {
            localSong.setFilePath(filePath);
        }

        if (localSong.getFilePath() == null || localSong.getFilePath().isBlank()) {
            throw new IllegalStateException("La canción integrada no tiene FilePath.");
        }
    }

    private Song prepareSourceSongForUi(
            Song sourceSong,
            Song canonicalSong,
            File downloadedFile
    ) {
        /*
         * Se mantiene preferentemente el objeto original de SongItemVisual,
         * porque es el que posee la metadata completa de la vista remota.
         */
        Song target = sourceSong != null
                ? sourceSong
                : canonicalSong;

        if (target == null) {
            throw new IllegalStateException(
                    "No existe una canción para integrar en la UI."
            );
        }

        mergeCanonicalMetadata(
                target,
                canonicalSong
        );

        target.setLocal(true);
        target.setFilePath(
                downloadedFile.getAbsolutePath()
        );

        return target;
    }

    private void mergeCanonicalMetadata(
            Song target,
            Song canonical
    ) {
        if (target == null || canonical == null) {
            return;
        }

        /*
         * El canonical puede contener IDs persistidos que el Song remoto todavía
         * no tenía, pero no debe borrar metadata visual correcta.
         */
        if (target.getSongID() <= 0
                && canonical.getSongID() > 0) {
            target.setSongID(
                    canonical.getSongID()
            );
        }

        if ((target.getTitle() == null
                || target.getTitle().isBlank())
                && canonical.getTitle() != null) {
            target.setTitle(
                    canonical.getTitle()
            );
        }

        if (target.getTrackOrder() <= 0
                && canonical.getTrackOrder() > 0) {
            target.setTrackOrder(
                    canonical.getTrackOrder()
            );
        }

        if (!hasUsableArtists(target.getArtist())
                && hasUsableArtists(canonical.getArtist())) {
            target.setArtist(
                    new ArrayList<>(
                            canonical.getArtist()
                    )
            );
        }

        Album targetAlbum = target.getAlbum();
        Album canonicalAlbum = canonical.getAlbum();

        if (targetAlbum == null && canonicalAlbum != null) {
            target.setAlbum(canonicalAlbum);
            return;
        }

        if (targetAlbum != null && canonicalAlbum != null) {
            enrichAlbum(
                    targetAlbum,
                    canonicalAlbum
            );
        }
    }

    private void enrichAlbum(
            Album target,
            Album canonical
    ) {
        if (target == null || canonical == null) {
            return;
        }

        if (target.getAlbumID() <= 0
                && canonical.getAlbumID() > 0) {
            target.setAlbumID(
                    canonical.getAlbumID()
            );
        }

        if ((target.getName() == null
                || target.getName().isBlank())
                && canonical.getName() != null) {
            target.setName(
                    canonical.getName()
            );
        }

        if (!hasUsableArtists(target.getArtist())
                && hasUsableArtists(canonical.getArtist())) {
            target.setArtist(
                    new ArrayList<>(
                            canonical.getArtist()
                    )
            );
        }

        if ((target.getCoverUrl() == null
                || target.getCoverUrl().isBlank())
                && canonical.getCoverUrl() != null) {
            target.setCoverUrl(
                    canonical.getCoverUrl()
            );
        }
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

            String name = artist.getName()
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

    private void updateStoredSourceModel(
            DownloadTask task,
            Song remoteSong,
            Song localSong
    ) {
        if (task == null
                || task.getContext() == null
                || localSong == null) {
            return;
        }

        DownloadTaskContext taskContext =
                task.getContext();

        Playlist sourceModel =
                taskContext.getSourcePlaylistModel();

        if (sourceModel == null
                || sourceModel.getSongList() == null) {
            return;
        }

        List<Song> sourceSongs =
                sourceModel.getSongList();

        for (int index = 0;
             index < sourceSongs.size();
             index++) {

            Song existing = sourceSongs.get(index);

            if (!sameSong(
                    existing,
                    remoteSong
            ) && !sameSong(
                    existing,
                    localSong
            )) {
                continue;
            }

            /*
             * Se conserva cualquier metadata rica que tuviera específicamente
             * la canción almacenada dentro de este álbum o playlist.
             */
            mergeSourceMetadata(
                    existing,
                    localSong
            );

            sourceSongs.set(
                    index,
                    localSong
            );

            return;
        }
    }

    private void mergeSourceMetadata(
            Song source,
            Song target
    ) {
        if (source == null || target == null) {
            return;
        }

        if (source.getTrackOrder() > 0
                && target.getTrackOrder() <= 0) {
            target.setTrackOrder(
                    source.getTrackOrder()
            );
        }

        if (source.getTitle() != null
                && !source.getTitle().isBlank()
                && (target.getTitle() == null
                || target.getTitle().isBlank())) {
            target.setTitle(
                    source.getTitle()
            );
        }

        if (hasUsableArtists(source.getArtist())
                && !hasUsableArtists(target.getArtist())) {
            target.setArtist(
                    new ArrayList<>(
                            source.getArtist()
                    )
            );
        }

        Album sourceAlbum = source.getAlbum();
        Album targetAlbum = target.getAlbum();

        /*
         * La instancia remota normalmente contiene portada, artistas, género,
         * fecha y demás metadata necesaria para renderizar correctamente.
         */
        if (sourceAlbum != null
                && (targetAlbum == null
                || targetAlbum.getAlbumID() <= 0
                || !hasUsableArtists(targetAlbum.getArtist()))) {
            target.setAlbum(sourceAlbum);
        }
    }

    private boolean sameSong(
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

        String firstTitle = normalizeTitle(
                first.getTitle()
        );

        String secondTitle = normalizeTitle(
                second.getTitle()
        );

        if (firstTitle.isBlank()
                || !firstTitle.equals(secondTitle)) {
            return false;
        }

        long firstAlbumId =
                first.getAlbum() == null
                        ? 0
                        : first.getAlbum().getAlbumID();

        long secondAlbumId =
                second.getAlbum() == null
                        ? 0
                        : second.getAlbum().getAlbumID();

        return firstAlbumId <= 0
                || secondAlbumId <= 0
                || firstAlbumId == secondAlbumId;
    }

    private String normalizeTitle(
            String title
    ) {
        return title == null
                ? ""
                : title.trim()
                  .toLowerCase(Locale.ROOT)
                  .replaceAll("\\s+", " ");
    }
}
