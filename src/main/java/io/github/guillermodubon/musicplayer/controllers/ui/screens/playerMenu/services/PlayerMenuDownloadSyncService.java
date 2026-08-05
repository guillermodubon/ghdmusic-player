package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services;

import javafx.application.Platform;
import javafx.scene.control.ListView;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane.QueueController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext;
import io.github.guillermodubon.musicplayer.models.DeezerApiMetaData;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import io.github.guillermodubon.musicplayer.services.downloads.services.IntegratedDownloadResult;
import io.github.guillermodubon.musicplayer.services.downloads.services.PlayerMenuDownloadBridge;

public class PlayerMenuDownloadSyncService implements PlayerMenuDownloadBridge {

    private final PlayerMenuContext context;
    private final PlaybackManager playbackManager;
    private final BooleanSupplier activeView;
    private final PlayerMenuDownloadViewCoordinator viewCoordinator;
    private final PlayerMenuDownloadPlaybackCoordinator playbackCoordinator;

    private StartUpService startUpService;
    private ListView<Song> songsToPlayView;
    private ListView<Song> songListView;
    private ListView<Song> recList;

    private Runnable refreshSongListView = () -> {};
    private Runnable refreshRecList = () -> {};
    private Runnable refreshPlaybackContext = () -> {};
    private Runnable refreshQueue = () -> {};
    private Runnable updateDownloadAllButtonState = () -> {};
    private Runnable forceFinalSongCellsRefresh = () -> {};

    private BiConsumer<Song, File> downloadedSongCellReplacement =
            (song, file) -> {};



    private BiConsumer<DeezerApiMetaData, File> downloadListener;

    public PlayerMenuDownloadSyncService(PlayerMenuContext context,
                                         PlaybackManager playbackManager,
                                         BooleanSupplier activeView) {
        this.context = Objects.requireNonNull(context, "context");
        this.playbackManager = playbackManager == null ? PlaybackManager.getInstance() : playbackManager;
        this.activeView = activeView == null ? () -> true : activeView;
        this.viewCoordinator = new PlayerMenuDownloadViewCoordinator(context);
        this.playbackCoordinator = new PlayerMenuDownloadPlaybackCoordinator(
                context,
                this.playbackManager,
                viewCoordinator
        );
    }

    public void bindDownloadedSongCellReplacement(
            BiConsumer<Song, File> replacement
    ) {
        if (replacement != null) {
            this.downloadedSongCellReplacement = replacement;
        }
    }

    public void bindUi(ListView<Song> songsToPlayView,
                       ListView<Song> songListView,
                       ListView<Song> recList) {
        this.songsToPlayView = songsToPlayView;
        this.songListView = songListView;
        this.recList = recList;
        viewCoordinator.bindUi(songsToPlayView, songListView, recList);
    }



    public void bindFinalSongCellsRefresh(Runnable forceFinalSongCellsRefresh) {
        if (forceFinalSongCellsRefresh != null) {
            this.forceFinalSongCellsRefresh = forceFinalSongCellsRefresh;
        }
    }

    public synchronized void attachToStartUpService(
            StartUpService service
    ) {
        detach();

        startUpService = service;
        viewCoordinator.bindStartUpService(service);

        if (startUpService == null) {
            return;
        }


        downloadListener = this::onSongDownloaded;

        startUpService.addDownloadListener(
                downloadListener
        );


        startUpService.registerPlayerMenuDownloadBridge(
                this
        );
    }
    public synchronized void detach() {
        if (startUpService != null) {
            if (downloadListener != null) {
                try {
                    startUpService.removeDownloadListener(
                            downloadListener
                    );
                } catch (Exception ignored) {
                }
            }

            try {
                startUpService.unregisterPlayerMenuDownloadBridge(
                        this
                );
            } catch (Exception ignored) {
            }
        }

        downloadListener = null;
        startUpService = null;
        viewCoordinator.bindStartUpService(null);
    }

    /**
     * Preferred integration path.
     *
     * This receives the exact remote Song used to render SongItemVisual and
     * the canonical local Song produced after metadata/DB/cache/manifest integration.
     */
    @Override
    public void publishIntegratedDownload(
            IntegratedDownloadResult result
    ) {
        if (result == null
                || result.localSong() == null) {
            return;
        }

        runOnFxThread(() ->
                applyIntegratedDownloads(
                        List.of(result)
                )
        );
    }

    /**
     * Batch-aware integration path.
     *
     * Applies all model mutations first and refreshes ListView/playback/queue once.
     * This avoids the bug where only some downloaded songs become PlayableSongItem.
     */
    @Override
    public void publishIntegratedDownloads(List<IntegratedDownloadResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }

        runOnFxThread(() -> applyIntegratedDownloads(results));
    }

    private void applyIntegratedDownloads(
            List<IntegratedDownloadResult> results
    ) {
        if (results == null || results.isEmpty()) {
            return;
        }

        if (!isActiveView()) {
            /*
             * The durable model has already been updated.
             *
             * If this PlayerMenuController is not visible, no UI is modified
             * and no unrelated screen is refreshed.
             */
            return;
        }

        /*
         * Only songs that actually belong to the album,
         * playlist, or single displayed by this PlayerMenuController are processed.
         */
        List<IntegratedDownloadResult> matchingResults =
                results.stream()
                        .filter(viewCoordinator::resultBelongsToCurrentView)
                        .toList();

        if (matchingResults.isEmpty()) {
            return;
        }

        boolean collectionChanged = false;
        boolean visibleListChanged = false;
        boolean recommendationsChanged = false;
        boolean playbackChanged = false;
        boolean processedDownload = false;


        List<DownloadedCellUpdate> downloadedCellUpdates =
                new ArrayList<>();


        List<IntegratedDownloadResult> validResults =
                new ArrayList<>();

        Long currentSourceId = playbackCoordinator.resolveCurrentSourceId();

        for (IntegratedDownloadResult result : matchingResults) {
            if (result == null
                    || result.localSong() == null
                    || !isUsableDownloadedFile(result.downloadedFile())) {
                continue;
            }

            Song remoteSong = result.remoteSong();

            Song localSong = PlayerMenuDownloadSongMatcher.makePlayableLocalSong(
                    result.localSong(),
                    result.downloadedFile()
            );

            if (!PlayerMenuDownloadSongMatcher.isPlayableLocalSong(localSong)) {
                continue;
            }

            processedDownload = true;
            validResults.add(result);


            downloadedCellUpdates.add(
                    new DownloadedCellUpdate(
                            localSong,
                            result.downloadedFile()
                    )
            );

            /*
             * Conserva orden, Ã¡lbum, artistas, portada y demÃ¡s metadata
             * especÃ­fica del Song utilizado originalmente por la vista.
             */
            /*
             * Immediately updates the corresponding Song within
             * masterSongList.
             *
             * In addition to replacing references, it must set:
             *
             * - isLocal = true
             * - a valid filePath
             *
             * before the cells are re-evaluated.
             */
            collectionChanged |= viewCoordinator.forceLocalSongIntoCurrentView(
                    remoteSong,
                    localSong,
                    result.downloadedFile()
            );

            /*
             * The PlayerMenu ListViews may be backed by a
             * FilteredList, which does not allow direct set(...) operations.
             *
             * If an UnsupportedOperationException occurs, the source model
             * has already been updated, so the ListViews are simply
             * refreshed to reflect the change.
             */
            try {
                visibleListChanged |= viewCoordinator.replaceInVisibleLists(
                        remoteSong,
                        localSong
                );
            } catch (UnsupportedOperationException ignored) {
                refreshList(songsToPlayView);

                if (songListView != null
                        && songListView != songsToPlayView) {
                    refreshList(songListView);
                }
            }

            recommendationsChanged |= viewCoordinator.replaceInRecommendations(
                    remoteSong,
                    localSong
            );


            playbackChanged |= playbackCoordinator.integrateIntoPlaybackFlow(
                    remoteSong,
                    localSong,
                    currentSourceId
            );
        }

        if (!processedDownload) {
            return;
        }


        boolean playableListChanged =
                viewCoordinator.rebuildCurrentPlayableListFromMaster();


        boolean playbackSourceChanged =
                playbackCoordinator.syncPlaybackSourceForCompletedDownloads(
                        validResults
                );

        playbackChanged |=
                playableListChanged
                        || playbackSourceChanged;

        /*
         * Actualiza los componentes generales de PlayerMenuController.
         */
        refreshUiAfterDownloadIntegration(
                collectionChanged || playableListChanged,
                visibleListChanged,
                recommendationsChanged,
                playbackChanged
        );


        for (DownloadedCellUpdate update : downloadedCellUpdates) {
            if (update == null
                    || update.song() == null
                    || update.file() == null) {
                continue;
            }

            try {
                downloadedSongCellReplacement.accept(
                        update.song(),
                        update.file()
                );
            } catch (Exception error) {
                error.printStackTrace();
            }
        }


        refreshList(songsToPlayView);

        if (songListView != null
                && songListView != songsToPlayView) {
            refreshList(songListView);
        }

        safeRun(refreshSongListView);

        /*
         * Final fallback:
         *
         * forces the VirtualFlow to rebuild and re-executes
         * updateItem(...) to automatically replace:
         *
         * SongItemVisual â†’ PlayableSongItem
         */
        safeRun(forceFinalSongCellsRefresh);


        QueueController.notifyPlaybackFlowChanged();
    }

    /**
     * Legacy path used by StartUpService download listeners.
     *
     * This path only receives metadata + file, so it updates matching Song models
     * in place by marking them local and setting FilePath.
     */
    public void onSongDownloaded(DeezerApiMetaData metadata, File finalFile) {
        if ((metadata == null && finalFile == null)
                || !isUsableDownloadedFile(finalFile)) {
            return;
        }

        /*
         * ID=0 downloads are already published through the exact
         * PlayerMenuDownloadBridge path. The legacy listener is also notified
         * for compatibility, but running both UI paths can recreate the same
         * PlayableSongItemController twice. Persistence has already completed
         * before either publication reaches this service.
         */
        if (metadata != null && metadata.getTrackId() <= 0) {
            return;
        }


        if (!isActiveView()) {
            return;
        }

        runOnFxThread(() -> {


            if (!isActiveView()) {
                return;
            }

            if (!isUsableDownloadedFile(finalFile)) {
                return;
            }


            if (!viewCoordinator.metadataBelongsToCurrentView(metadata)) {
                return;
            }

            try {
                boolean collectionChanged = viewCoordinator.updateCollectionState(metadata, finalFile);
                boolean visibleListChanged = viewCoordinator.updateVisibleLists(metadata, finalFile);
                boolean recommendationsChanged = viewCoordinator.updateRecommendations(metadata, finalFile);
                boolean playbackChanged = false;

                Song playableSong = viewCoordinator.resolvePlayableSong(metadata, finalFile);
                if (playableSong != null) {
                    playableSong = PlayerMenuDownloadSongMatcher.makePlayableLocalSong(playableSong, finalFile);
                    collectionChanged |= viewCoordinator.replaceInCollectionState(playableSong, playableSong);
                    visibleListChanged |= viewCoordinator.replaceInVisibleLists(playableSong, playableSong);
                    recommendationsChanged |= viewCoordinator.replaceInRecommendations(playableSong, playableSong);


                    playbackChanged = playbackCoordinator.integrateIntoPlaybackFlow(
                            playableSong,
                            playableSong,
                            playbackCoordinator.resolveCurrentSourceId()
                    );
                }


                boolean playableListChanged = viewCoordinator.rebuildCurrentPlayableListFromMaster();

                List<IntegratedDownloadResult> completedDownloads =
                        playableSong == null
                                ? List.of()
                                : List.of(new IntegratedDownloadResult(
                                playableSong,
                                playableSong,
                                finalFile
                        ));

                boolean playbackSourceChanged =
                        playbackCoordinator.syncPlaybackSourceIfCurrentView(completedDownloads);

                playbackChanged |= playableListChanged || playbackSourceChanged;

                refreshUiAfterDownloadIntegration(
                        collectionChanged || playableListChanged,
                        visibleListChanged,
                        recommendationsChanged,
                        playbackChanged
                );
                playbackCoordinator.reloadCurrentMediaIfDownloaded(metadata == null ? 0L : metadata.getTrackId());
                safeRun(forceFinalSongCellsRefresh);

            } catch (Exception error) {

                error.printStackTrace();
                refreshList(songsToPlayView);
                if (songListView != songsToPlayView) {
                    refreshList(songListView);
                }
                safeRun(forceFinalSongCellsRefresh);
            }
        });
    }


    public void handleDownloadedInView(DeezerApiMetaData metadata, File file) {
        onSongDownloaded(metadata, file);
    }

    private void refreshUiAfterDownloadIntegration(
            boolean collectionChanged,
            boolean visibleListChanged,
            boolean recommendationsChanged,
            boolean playbackChanged
    ) {
        if (visibleListChanged || collectionChanged) {
            refreshList(songsToPlayView);

            if (songListView != null
                    && songListView != songsToPlayView) {
                refreshList(songListView);
            }

            safeRun(refreshSongListView);
        }

        if (recommendationsChanged) {
            refreshList(recList);
            safeRun(refreshRecList);
        }

        if (collectionChanged
                || visibleListChanged
                || recommendationsChanged
                || playbackChanged) {
            safeRun(refreshPlaybackContext);
            safeRun(refreshQueue);
            safeRun(updateDownloadAllButtonState);
        }


        QueueController.notifyPlaybackFlowChanged();
    }



    private boolean isActiveView() {
        try {
            return activeView.getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
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

    private void refreshList(ListView<Song> listView) {
        try {
            if (listView != null) {
                listView.refresh();
            }
        } catch (Exception ignored) {
        }
    }

    private void safeRun(Runnable action) {
        try {
            if (action != null) {
                action.run();
            }
        } catch (Exception ignored) {
        }
    }

    private void runOnFxThread(Runnable action) {
        if (action == null) {
            return;
        }
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    private record DownloadedCellUpdate(
            Song song,
            File file
    ) {
    }

}
