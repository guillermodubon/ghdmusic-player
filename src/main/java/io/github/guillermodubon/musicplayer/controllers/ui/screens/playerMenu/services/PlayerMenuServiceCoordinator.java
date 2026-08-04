package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services;

import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.PlayerMenuController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.managers.PlayerMenuArtistResolver;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.managers.PlayerMenuPlaybackBridge;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.header.PlayerMenuPlaylistHeaderActionsService;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.view.PlayerMenuUiBindings;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.ArtistCardActionManager;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.repository.dao.playlist.PlaylistDao;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Owns the PlayerMenu service graph and all service-to-view bindings.
 *
 * The controller remains the public integration point for navigation and
 * playback, while this coordinator handles service construction and wiring.
 */
public final class PlayerMenuServiceCoordinator {

    public record Callbacks(
            Supplier<PlayerMenuController> controllerSupplier,
            BooleanSupplier isCurrentCenterViewVisible,
            Consumer<Song> onSongClicked,
            Consumer<Song> onSongEnqueued,
            Consumer<Song> persistPlaybackOrigin,
            Runnable refreshCurrentViewMinimal,
            Runnable refreshQueue,
            Runnable refreshPlaybackContext,
            Runnable refreshHeaderAndFooter,
            Runnable refreshActionState
    ) {
    }

    private final PlayerMenuContext context;
    private final PlaybackManager playbackManager;
    private final Callbacks callbacks;
    private final AtomicBoolean servicesReady = new AtomicBoolean(false);
    private final AtomicBoolean uiBindingsInstalled = new AtomicBoolean(false);

    private StartUpService startupService;
    private BorderPane parentRoot;
    private PlaylistDao playlistDao;
    private MusicCardActionManager musicCardActionManager;
    private ArtistCardActionManager artistCardActionManager;
    private Image defaultCover;
    private PlayerMenuUiBindings ui;

    private PlayerMenuArtistResolver artistResolver;
    private PlayerMenuPlaybackBridge playbackBridge;
    private PlayerMenuSongListService songListService;
    private PlayerMenuHeaderFooterService headerFooterService;
    private PlayerMenuRecommendationsService recommendationsService;
    private PlayerMenuDownloadSyncService downloadSyncService;
    private PlayerMenuPlaylistHeaderActionsService playlistActionsService;

    public PlayerMenuServiceCoordinator(PlayerMenuContext context,
                                        PlaybackManager playbackManager,
                                        Callbacks callbacks) {
        this.context = Objects.requireNonNull(context, "context");
        this.playbackManager = playbackManager == null
                ? PlaybackManager.getInstance()
                : playbackManager;
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
    }

    public void bindUi(PlayerMenuUiBindings ui) {
        this.ui = ui;
        bindIfReady();
    }

    public void setDefaultCover(Image defaultCover) {
        this.defaultCover = defaultCover;
    }

    public void setStartupService(StartUpService service) {
        this.startupService = service;
        this.playlistDao = service == null ? null : service.getPlaylistDao();
        ensureServicesReady();
        bindIfReady();
    }

    public void setParentRoot(BorderPane parentRoot) {
        this.parentRoot = parentRoot;
        bindIfReady();
    }

    public void setMusicCardActionManager(MusicCardActionManager musicCardActionManager) {
        this.musicCardActionManager = musicCardActionManager;
        ensureServicesReady();
        bindIfReady();
    }

    public void setArtistCardActionManager(ArtistCardActionManager artistCardActionManager) {
        this.artistCardActionManager = artistCardActionManager;
        bindIfReady();
    }

    public void ensureServicesReady() {
        if (servicesReady.get() || startupService == null || musicCardActionManager == null) {
            return;
        }

        artistResolver = new PlayerMenuArtistResolver(startupService);
        playbackBridge = new PlayerMenuPlaybackBridge(
                context,
                startupService,
                playbackManager,
                callbacks.controllerSupplier(),
                callbacks.persistPlaybackOrigin()
        );
        songListService = new PlayerMenuSongListService(
                context,
                playbackManager,
                artistResolver
        );
        headerFooterService = new PlayerMenuHeaderFooterService(
                context,
                startupService,
                musicCardActionManager,
                artistResolver
        );
        recommendationsService = new PlayerMenuRecommendationsService(
                context,
                startupService,
                playbackManager,
                artistResolver,
                musicCardActionManager,
                playbackBridge,
                defaultCover
        );
        downloadSyncService = new PlayerMenuDownloadSyncService(
                context,
                playbackManager,
                callbacks.isCurrentCenterViewVisible()
        );
        playlistActionsService = new PlayerMenuPlaylistHeaderActionsService(
                context,
                playbackManager
        );

        servicesReady.set(true);
    }

    public void bindIfReady() {
        if (!servicesReady.get() || ui == null) return;

        if (!uiBindingsInstalled.compareAndSet(false, true)) {
            // Dependency setters can arrive after the FXML graph is bound.
            // Refresh only the collaborators that carry those references;
            // rebuilding the full graph would add duplicate text/listeners.
            if (songListService != null) {
                songListService.bindServices(
                        startupService,
                        musicCardActionManager,
                        callbacks.onSongClicked(),
                        callbacks.onSongEnqueued()
                );
            }
            if (playlistActionsService != null) {
                playlistActionsService.bindServices(
                        startupService,
                        playlistDao,
                        parentRoot,
                        musicCardActionManager,
                        artistCardActionManager
                );
            }
            return;
        }

        songListService.bindUi(
                ui.songSearchField(),
                ui.songSearchBox(),
                ui.resolvedSongsView(),
                ui.songListView(),
                ui.songCountLabel(),
                ui.playerMenuScroll(),
                ui.songListVirtualShell(),
                ui.playlistSortMenuButton()
        );
        songListService.bindServices(
                startupService,
                musicCardActionManager,
                callbacks.onSongClicked(),
                callbacks.onSongEnqueued()
        );

        headerFooterService.bindUi(
                ui.resolvedHeaderCover(),
                ui.playerMenuHeader(),
                ui.playerMenuHeaderFade(),
                ui.searchSongRow(),
                ui.songListVirtualShell(),
                ui.recordTypeLabel(),
                ui.headerTitle(),
                ui.creatorContainer(),
                ui.playlistDescLabel(),
                ui.dateLabel(),
                ui.moreByArtistsContainer(),
                ui.footerPane(),
                ui.playerMenuScroll(),
                ui.menuOptions(),
                ui.miEdit(),
                ui.songCountLabel(),
                ui.remoteSaveCheckBox(),
                ui.recContainer(),
                ui.recList(),
                ui.recommendationSearchField(),
                ui.remoteSuggestionBox()
        );

        recommendationsService.bindUi(
                ui.recContainer(),
                ui.recTitleLabel(),
                ui.addAllRecommendationsButton(),
                ui.recList(),
                ui.recommendationSearchField(),
                ui.btnRefreshRec(),
                ui.footerPane(),
                ui.remoteSuggestionBox()
        );
        recommendationsService.bindCallbacks(
                callbacks.refreshCurrentViewMinimal(),
                callbacks.refreshQueue(),
                callbacks.refreshPlaybackContext()
        );

        bindDownloadSyncService();

        playlistActionsService.bindUi(
                ui.menuOptions(),
                ui.miEdit(),
                ui.miDelete(),
                ui.remoteSaveCheckBox(),
                ui.resolvedHeaderCover(),
                ui.playerMenuActionButtons(),
                ui.headerOptionsSlot()
        );
        playlistActionsService.bindCallbacks(
                callbacks.refreshHeaderAndFooter(),
                callbacks.refreshPlaybackContext(),
                callbacks.refreshQueue(),
                callbacks.refreshCurrentViewMinimal()
        );
        playlistActionsService.bindServices(
                startupService,
                playlistDao,
                parentRoot,
                musicCardActionManager,
                artistCardActionManager
        );

        Playlist currentPlaylist = context.getCurrentPlaylistModel();
        PlayerMenuContext.ContentType currentType = context.getCurrentContentTypeInView();
        if (currentPlaylist != null && currentType != null) {
            playlistActionsService.onPlaylistModelChanged(currentPlaylist, currentType);
        }
    }

    private void bindDownloadSyncService() {
        downloadSyncService.bindUi(
                ui.resolvedSongsView(),
                ui.songListView(),
                ui.recList()
        );
        downloadSyncService.bindDownloadedSongCellReplacement((downloadedSong, finalFile) -> {
            if (songListService != null && downloadedSong != null && finalFile != null) {
                songListService.integrateDownloadedSongAndRefresh(downloadedSong, finalFile);
            }
            callbacks.refreshActionState().run();
        });
        downloadSyncService.bindFinalSongCellsRefresh(() -> {
            if (songListService != null) songListService.forceFinalDownloadCellRefresh();
            callbacks.refreshActionState().run();
        });
        if (startupService != null) downloadSyncService.attachToStartUpService(startupService);
    }

    public void detach() {
        if (songListService != null) songListService.onDetached();
        if (downloadSyncService != null) downloadSyncService.detach();
    }

    public PlayerMenuPlaybackBridge playbackBridge() {
        return playbackBridge;
    }

    public PlayerMenuSongListService songListService() {
        return songListService;
    }

    public PlayerMenuHeaderFooterService headerFooterService() {
        return headerFooterService;
    }

    public PlayerMenuRecommendationsService recommendationsService() {
        return recommendationsService;
    }

    public PlayerMenuDownloadSyncService downloadSyncService() {
        return downloadSyncService;
    }

    public PlayerMenuPlaylistHeaderActionsService playlistActionsService() {
        return playlistActionsService;
    }
}
