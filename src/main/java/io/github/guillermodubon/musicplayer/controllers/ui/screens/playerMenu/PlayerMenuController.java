package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import io.github.guillermodubon.musicplayer.services.navigation.SceneStateFlowManager;
import io.github.guillermodubon.musicplayer.controllers.ui.components.inputs.SearchBarController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.PlayerMenuBarController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane.QueueController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.actions.PlayerMenuActionCoordinator;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.actions.PlayerMenuActionHost;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext.ContentType;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.managers.PlayerMenuPlaybackBridge;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.*;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.PlayerMenuServiceCoordinator.Callbacks;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.view.PlayerMenuUiBindings;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.view.PlayerMenuUiCoordinator;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.ArtistCardActionManager;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;
import io.github.guillermodubon.musicplayer.models.*;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public class PlayerMenuController implements PlayerMenuActionHost {

    private static final double HEADER_COVER_DECODE_SIZE = 640.0;

    @FXML private BorderPane playerMenuRoot;
    @FXML private VBox playerMenuSurface;
    @FXML private HBox playerMenuHeader;
    @FXML private Region playerMenuHeaderFade;
    @FXML private StackPane headerCoverShell;
    @FXML private StackPane headerOptionsSlot;
    @FXML private HBox searchSongRow;
    @FXML private StackPane songSearchBox;
    @FXML private SearchBarController songSearchBarController;
    @FXML private HBox playerMenuActionButtons;
    @FXML private Button playVisibleSongsButton;
    @FXML private ToggleButton randomVisibleSongsButton;
    @FXML private Button addVisibleSongsToPlaylistButton;
    @FXML private Button downloadAllButton;
    @FXML private ImageView headerCover;
    @FXML private Label recordTypeLabel;
    @FXML private Label headerTitle;
    @FXML private StackPane creatorViewport;
    @FXML private HBox creatorContainer;
    @FXML private Label playlistDescLabel;
    @FXML private Label dateLabel;
    @FXML private ListView<Song> SongsToPlayView;
    @FXML private ScrollPane playerMenuScroll;
    @FXML private Pane songListVirtualShell;

    @FXML private VBox moreByArtistsContainer;
    @FXML private VBox footerPane;

    @FXML private VBox recContainer;
    @FXML private Label recTitleLabel;
    @FXML private Button addAllRecommendationsButton;
    @FXML private ListView<Song> recList;
    @FXML private StackPane recommendationSearchBox;
    @FXML private SearchBarController recommendationSearchBarController;
    @FXML private Button btnRefreshRec;

    @FXML private MenuButton menuOptions;
    @FXML private MenuItem miEdit;
    @FXML private MenuItem miDelete;
    @FXML private Label songCountLabel;
    @FXML private CheckBox remoteSaveCheckBox;
    @FXML private MenuButton playlistSortMenuButton;
    @FXML private VBox remoteSuggestionBox;
    @FXML private ListView<Song> songListView;
    @FXML private ImageView headerCoverImage;

    private StartUpService svc;
    private BorderPane parentRoot;
    private final PlaybackManager pm = PlaybackManager.getInstance();
    private final PlayerMenuContext context = new PlayerMenuContext();

    private MusicCardActionManager musicCardActionManager;
    private ArtistCardActionManager artistCardActionManager;
    private Image defaultCover;

    private final PlayerMenuPlaybackHistoryService playbackHistoryService =
            new PlayerMenuPlaybackHistoryService();
    private final AtomicBoolean playbackListenerRegistered = new AtomicBoolean(false);
    private final AtomicBoolean playbackRefreshQueued = new AtomicBoolean(false);
    private final AtomicBoolean queueRefreshQueued = new AtomicBoolean(false);
    private Runnable playbackListener;
    private String playlistName;
    public ObservableList<Song> masterSongList = context.getMasterSongList();
    public List<Song> currentSongList = context.getCurrentSongList();
    private Playlist currentPlaylistModel;
    private ContentType currentContentTypeInView = null;
    private long currentPlaylistInViewId = -1L;
    private boolean barLoaded = false;
    private PlayerMenuBarController playerMenuBarController;
    private final PlayerMenuActionCoordinator actionCoordinator;
    private PlayerMenuUiCoordinator uiCoordinator;
    private final PlayerMenuServiceCoordinator serviceCoordinator;
    private ChangeListener<Number> deferredFooterScrollListener;
    private ChangeListener<Bounds> deferredFooterViewportListener;
    private ChangeListener<Bounds> deferredFooterBoundsListener;
    private final AtomicBoolean deferredFooterCheckQueued = new AtomicBoolean(false);
    private boolean deferredFooterLoaded;

    public PlayerMenuController() {
        // keep aliases bound to the context lists
        this.masterSongList = context.getMasterSongList();
        this.currentSongList = context.getCurrentSongList();
        this.actionCoordinator = new PlayerMenuActionCoordinator(this);
        this.serviceCoordinator = new PlayerMenuServiceCoordinator(
                context,
                pm,
                new Callbacks(
                        () -> this,
                        this::isCurrentCenterViewVisible,
                        this::onSongClicked,
                        this::enqueueSongFromView,
                        this::actionPersistPlaybackOrigin,
                        this::refreshCurrentViewMinimal,
                        this::refreshQueue,
                        this::refreshPlaybackContext,
                        this::refreshHeaderAndFooter,
                        actionCoordinator::updateActionState
                )
        );
    }

    @Override
    public PlayerMenuContext actionContext() {
        return context;
    }

    @Override
    public PlaybackManager actionPlaybackManager() {
        return pm;
    }

    @Override
    public StartUpService actionStartUpService() {
        return svc;
    }

    @Override
    public PlayerMenuPlaybackBridge actionPlaybackBridge() {
        return serviceCoordinator.playbackBridge();
    }

    @Override
    public ContentType actionContentType() {
        return currentContentTypeInView;
    }

    @Override
    public long actionPlaylistId() {
        return currentPlaylistInViewId;
    }

    @Override
    public String actionCollectionTitle() {
        return resolveCollectionTitle();
    }

    @Override
    public Parent actionDownloadSidebarOwner() {
        return resolveDownloadSidebarOwner();
    }

    @Override
    public BorderPane actionParentRoot() {
        return parentRoot;
    }

    @Override
    public BorderPane actionPlayerMenuRoot() {
        return playerMenuRoot;
    }

    @Override
    public MusicCardActionManager actionMusicCardManager() {
        return musicCardActionManager;
    }

    @Override
    public boolean actionIsPlaybackSource() {
        return isCurrentViewPlaybackSource();
    }

    @Override
    public boolean actionSongImmediatelyPlayable(Song song) {
        return isSongImmediatelyPlayable(song);
    }

    @Override
    public void actionPersistPlaybackOrigin(Song song) {
        playbackHistoryService.persist(
                song,
                currentPlaylistModel,
                currentContentTypeInView
        );
    }

    @Override
    public void actionRefreshPlaybackContext() {
        refreshPlaybackContext();
    }

    @Override
    public void actionRefreshQueue() {
        refreshQueue();
    }

    @Override
    public void actionRefreshState() {
        syncSongListUiState();
    }

    @FXML
    public void initialize() {
        ensureDefaultCover();
        serviceCoordinator.setDefaultCover(defaultCover);
        uiCoordinator = new PlayerMenuUiCoordinator(
                createUiBindings(),
                context,
                actionCoordinator
        );
        if (headerCover != null) headerCover.setImage(defaultCover);
        uiCoordinator.configureRemoteSaveCheckBoxInitialState();
        registerPlaybackListener();
        ensureLegacyItems();
        uiCoordinator.initialize(this::syncSongListUiState);
        serviceCoordinator.bindUi(uiCoordinator.bindings());
        serviceCoordinator.bindIfReady();
    }

    private PlayerMenuUiBindings createUiBindings() {
        return new PlayerMenuUiBindings(
                playerMenuRoot,
                playerMenuSurface,
                playerMenuHeader,
                playerMenuHeaderFade,
                headerCoverShell,
                headerOptionsSlot,
                searchSongRow,
                songSearchBox,
                songSearchBarController,
                playerMenuActionButtons,
                playVisibleSongsButton,
                randomVisibleSongsButton,
                addVisibleSongsToPlaylistButton,
                downloadAllButton,
                headerCover,
                recordTypeLabel,
                headerTitle,
                creatorViewport,
                creatorContainer,
                playlistDescLabel,
                dateLabel,
                SongsToPlayView,
                playerMenuScroll,
                songListVirtualShell,
                moreByArtistsContainer,
                footerPane,
                recContainer,
                recTitleLabel,
                addAllRecommendationsButton,
                recList,
                recommendationSearchBox,
                recommendationSearchBarController,
                btnRefreshRec,
                menuOptions,
                miEdit,
                miDelete,
                songCountLabel,
                remoteSaveCheckBox,
                playlistSortMenuButton,
                remoteSuggestionBox,
                songListView,
                headerCoverImage
        );
    }

    public void setSvc(StartUpService svc) {
        this.svc = svc;
        context.setSvc(svc);
        ensureDefaultCover();
        serviceCoordinator.setDefaultCover(defaultCover);
        serviceCoordinator.setStartupService(svc);

        if (serviceCoordinator.playlistActionsService() != null) {
            refreshPlaylistHeaderActionsState();
        }
    }

    public void setStartUpService(StartUpService svc) {
        setSvc(svc);
    }

    public void setParentRoot(BorderPane root) {
        this.parentRoot = root;
        context.setParentRoot(root);
        serviceCoordinator.setParentRoot(root);
        if (serviceCoordinator.playlistActionsService() != null) {
            refreshPlaylistHeaderActionsState();
        }
    }

    public void setMusicCardActionManager(MusicCardActionManager musicCardActionManager) {
        this.musicCardActionManager = musicCardActionManager;
        serviceCoordinator.setMusicCardActionManager(musicCardActionManager);
    }

    public void setArtistCardActionManager(ArtistCardActionManager artistCardActionManager) {
        this.artistCardActionManager = artistCardActionManager;
        serviceCoordinator.setArtistCardActionManager(artistCardActionManager);
    }


    public BorderPane getParentRoot() {
        return parentRoot;
    }

    public boolean isCurrentCenterViewVisible() {
        try {
            if (parentRoot == null || parentRoot.getScene() == null) return false;
            Node center = parentRoot.getCenter();
            if (!(center instanceof Parent parent)) return false;
            Object controller = parent.getProperties().get("controller");
            return controller == this;
        } catch (Exception ignored) {
            return false;
        }
    }

    public Playlist getCurrentPlaylistModel() {
        return currentPlaylistModel;
    }

    public ContentType getCurrentContentTypeInView() {
        return currentContentTypeInView;
    }

    public long getCurrentPlaylistInViewId() {
        return currentPlaylistInViewId;
    }

    public PlayerMenuBarController getPlayerMenuBarController() {
        PlayerMenuBarController contextBar = context.getPlayerMenuBarController();
        if (contextBar != null) return contextBar;

        try {
            if (svc != null && svc.getAppShellController() != null) {
                PlayerMenuBarController shellBar = svc.getAppShellController().getPlayerMenuBarController();
                if (shellBar != null) return shellBar;
            }
        } catch (Exception ignored) {
        }

        return playerMenuBarController;
    }

    public ListView<Song> getSongsToPlayView() {
        return uiCoordinator == null ? SongsToPlayView : uiCoordinator.songsView();
    }


    public void refreshCurrentViewMinimal() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::refreshCurrentViewMinimal);
            return;
        }

        try {
            if (currentPlaylistModel != null && serviceCoordinator.songListService() != null) {
                serviceCoordinator.songListService().applySongList(
                        buildInitialSongList(currentPlaylistModel),
                        currentContentTypeInView,
                        currentPlaylistModel
                );
            }

            if (serviceCoordinator.headerFooterService() != null) {
                serviceCoordinator.headerFooterService().refreshHeader();
            }
            refreshPlaylistHeaderActionsState();
            syncSongListUiState();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void refreshHeaderAndFooter() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::refreshHeaderAndFooter);
            return;
        }

        refreshHeaderAndScheduleDeferredContent(context.getViewRevision());
    }

    /** Releases viewport listeners when navigation detaches this screen. */
    public void onDetached() {
        clearDeferredFooterListeners();
        context.invalidateViewRevision();
        serviceCoordinator.detach();
        if (playbackListenerRegistered.compareAndSet(true, false)) {
            pm.removeTrackChangeListener(playbackListener);
            playbackListener = null;
        }
        playbackRefreshQueued.set(false);
        queueRefreshQueued.set(false);
    }

    public void refreshPlaybackContext() {
        if (serviceCoordinator.playbackBridge() != null) {
            serviceCoordinator.playbackBridge().refreshPlaybackContext();
        }
    }

    public void updateCurrentSong(Song song) {
        PlayerMenuBarController bar = getPlayerMenuBarController();
        if (bar != null) {
            bar.updateCurrentSong(song);
        }
    }

    public void onSongClicked(Song song) {
        if (song == null || serviceCoordinator.playbackBridge() == null) return;
        serviceCoordinator.playbackBridge().playSongFromView(song);
    }

    /**
     * Called by the playback layer after a local file disappears. Only refresh
     * the active view when it actually contains that song, avoiding mutations
     * of a different PlayerMenu while the user is navigating.
     */
    public void onLocalSongUnavailable(Song song) {
        if (song == null) return;

        Runnable refresh = () -> {
            boolean visibleInCurrentView = context.getMasterSongList().stream()
                    .anyMatch(candidate -> sameSong(candidate, song));

            if (visibleInCurrentView && serviceCoordinator.songListService() != null) {
                serviceCoordinator.songListService().refreshDownloadedSongState();
            }

            if (serviceCoordinator.recommendationsService() != null) {
                serviceCoordinator.recommendationsService().onLocalSongUnavailable(song);
            }

            updatePlayVisibleSongsButtonState();
            updateDownloadAllButtonState();
            updateActionTooltips();
        };

        if (Platform.isFxApplicationThread()) {
            refresh.run();
        } else {
            Platform.runLater(refresh);
        }
    }

    private boolean sameSong(Song left, Song right) {
        if (left == null || right == null) return false;
        if (left.getSongID() > 0 && right.getSongID() > 0) {
            return left.getSongID() == right.getSongID();
        }
        return Objects.equals(left.getTitle(), right.getTitle());
    }


    @FXML
    private void onAddVisibleSongsToPlaylist() {
        actionCoordinator.addVisibleSongsToPlaylist();
    }

    @Override
    public List<Song> actionPlayableSongs() {
        List<Song> source = context.getCurrentSongList();
        if (source == null || source.isEmpty()) {
            source = masterSongList == null ? List.of() : masterSongList;
        }
        return source.stream()
                .filter(Objects::nonNull)
                .filter(Song::isLocal)
                .toList();
    }

    @Override
    public List<Song> actionAllSongs() {
        if (currentPlaylistModel != null && currentPlaylistModel.getSongList() != null && !currentPlaylistModel.getSongList().isEmpty()) {
            return new ArrayList<>(currentPlaylistModel.getSongList());
        }
        if (masterSongList != null && !masterSongList.isEmpty()) {
            return new ArrayList<>(masterSongList);
        }
        List<Song> source = context.getMasterSongList();
        return source == null ? List.of() : new ArrayList<>(source);
    }

    @Override
    public List<Song> actionDownloadableSongs() {
        if (currentContentTypeInView == ContentType.SINGLE) return List.of();
        return actionAllSongs().stream()
                .filter(Objects::nonNull)
                // A database row can exist for a remote track without a
                // playable local file. Download All must use the same
                // effective local state as the list cells.
                .filter(song -> !isSongImmediatelyPlayable(song))
                .distinct()
                .toList();
    }

    private void onDownloadAllSongs() {
        actionCoordinator.downloadAllSongs();
    }

    private Parent resolveDownloadSidebarOwner() {
        if (parentRoot != null) return parentRoot;
        if (playerMenuRoot != null && playerMenuRoot.getScene() != null
                && playerMenuRoot.getScene().getRoot() instanceof Parent root) {
            return root;
        }
        return playerMenuRoot;
    }

    private String resolveCollectionTitle() {
        String title = currentPlaylistModel == null ? "" : Objects.toString(currentPlaylistModel.getTitle(), "").trim();
        if (!title.isBlank()) return title;
        if (playlistName != null && !playlistName.isBlank()) return playlistName.trim();
        return currentContentTypeInView == ContentType.ALBUM
                ? "this album"
                : currentContentTypeInView == ContentType.SINGLE ? "this single" : "this playlist";
    }

    public void updatePlaylistContent(Playlist newPl) {
        if (newPl == null) {
            return;
        }

        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> updatePlaylistContent(newPl));
            return;
        }

        serviceCoordinator.ensureServicesReady();
        clearTransientNavigationState();
        long viewRevision = syncViewState(newPl, currentContentTypeInView);

        if (serviceCoordinator.songListService() != null) {
            serviceCoordinator.songListService().applySongList(
                    buildInitialSongList(newPl),
                    currentContentTypeInView,
                    newPl
            );
        }

        syncSongListUiState();

        refreshHeaderAndScheduleDeferredContent(viewRevision);
    }

    private void clearTransientNavigationState() {
        try {
            if (parentRoot != null && parentRoot.getCenter() instanceof Parent current) {
                Object controller = current.getProperties().get("controller");
                if (controller == this) {
                    SceneStateFlowManager.markTransient(current, false);
                }
            }
        } catch (Exception ignored) {
        }
    }

    public void initPlaylist(ContentType type, Playlist playlist) {
        if (svc == null) {
            setSvc(StartUpService.getInstance());
        }

        serviceCoordinator.ensureServicesReady();
        long viewRevision = syncViewState(playlist, type);
        List<Song> initial = buildInitialSongList(playlist);

        if (serviceCoordinator.songListService() != null) {
            serviceCoordinator.songListService().applySongList(initial, type, playlist);
        }

        syncSongListUiState();

        refreshHeaderAndScheduleDeferredContent(viewRevision);

        if (serviceCoordinator.playlistActionsService() != null) {
            serviceCoordinator.playlistActionsService().wireAfterPlaylistLoad(playlist, type);
            refreshPlaylistHeaderActionsState();
        } else {
            uiCoordinator.configureRemoteSaveCheckBoxInitialState();
        }
    }

    public void adjustListHeight(ListView<?> lv) {
        if (lv == null) return;
        if (serviceCoordinator.songListService() != null) {
            serviceCoordinator.songListService().adjustListHeight(lv);
            return;
        }
        uiCoordinator.adjustListHeightFallback(lv);
    }

    private void ensureDefaultCover() {
        if (defaultCover != null) return;

        defaultCover = MediaImageResolver.defaultCover(
                HEADER_COVER_DECODE_SIZE,
                HEADER_COVER_DECODE_SIZE
        );
    }

    private void ensureLegacyItems() {
        if (masterSongList == null) masterSongList = context.getMasterSongList();
        if (currentSongList == null) currentSongList = context.getCurrentSongList();
    }

    private void updateDownloadAllButtonState() {
        actionCoordinator.updateActionState();
    }

    private boolean isCurrentUserPlaylistEmpty() {
        return currentContentTypeInView == ContentType.PLAYLIST
                && currentPlaylistModel != null
                && context.getMasterSongList().isEmpty()
                && isUserCreatedPlaylist(currentPlaylistModel);
    }

    private boolean isUserCreatedPlaylist(Playlist playlist) {
        if (playlist == null) return false;
        String author = Objects.toString(playlist.getAuthorName(), "").trim();
        if (author.equalsIgnoreCase("User") || author.equalsIgnoreCase("By you")) return true;
        if (svc == null || svc.getPlaylists() == null) return false;
        return svc.getPlaylists().stream()
                .filter(Objects::nonNull)
                .filter(p -> p.getId() == playlist.getId())
                .map(p -> Objects.toString(p.getAuthorName(), "").trim())
                .anyMatch(a -> a.equalsIgnoreCase("User") || a.equalsIgnoreCase("By you"));
    }

    private void updateSongSearchPrompt(Playlist playlist, ContentType type) {
        if (uiCoordinator != null) uiCoordinator.updateSongSearchPrompt(playlist, type);
    }

    private void registerPlaybackListener() {
        if (playbackListenerRegistered.getAndSet(true)) return;

        playbackListener = () -> {
            if (!playbackRefreshQueued.compareAndSet(false, true)) return;
            Platform.runLater(() -> {
                try {
                    updateCurrentSong(pm.getCurrentSong());
                    if (serviceCoordinator.songListService() != null) {
                        serviceCoordinator.songListService().refreshPlaybackIndicators();
                    }
                    if (serviceCoordinator.recommendationsService() != null) {
                        serviceCoordinator.recommendationsService().refreshPlaybackIndicators();
                    }
                    actionCoordinator.updateActionState();
                    refreshPlaybackContext();
                    refreshQueue();
                } finally {
                    playbackRefreshQueued.set(false);
                }
            });
        };
        pm.addTrackChangeListener(playbackListener);
    }

    private void refreshQueue() {
        if (!queueRefreshQueued.compareAndSet(false, true)) return;
        Platform.runLater(() -> {
            try {
                QueueController qc = QueueController.getInstance();
                if (qc != null) qc.refreshAll();
            } finally {
                queueRefreshQueued.set(false);
            }
        });
    }

    private void enqueueSongFromView(Song song) {
        if (song == null) return;
        pm.enqueue(song);
        refreshQueue();
    }

    private long syncViewState(Playlist playlist, ContentType type) {
        this.currentPlaylistModel = playlist;
        this.currentContentTypeInView = type;
        this.currentPlaylistInViewId = playlist == null ? -1L : playlist.getId();
        this.playlistName = playlist == null ? null : playlist.getTitle();

        pm.setCurrentPlaylistInViewId(currentPlaylistInViewId);

        context.syncPlaylistFromView(playlist, type);
        context.setPlaylistName(playlistName);

        updateSongSearchPrompt(playlist, type);
        syncLocalRandomModeForCurrentView();

        /*
         * The playlist may change while the screen remains open.
         * Update the checkbox on the next JavaFX pulse.
         */
        if (Platform.isFxApplicationThread()) {
            refreshPlaylistHeaderActionsState();
        } else {
            Platform.runLater(this::refreshPlaylistHeaderActionsState);
        }

        return context.getViewRevision();
    }

    private void syncLocalRandomModeForCurrentView() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::syncLocalRandomModeForCurrentView);
            return;
        }
        if (randomVisibleSongsButton == null) return;

        boolean samePlaybackSource = isCurrentViewPlaybackSource();
        boolean viewShuffleEnabled = samePlaybackSource && pm.isRandomMode();
        actionCoordinator.syncRandomMode(viewShuffleEnabled);
    }

    private boolean isCurrentViewPlaybackSource() {
        if (currentContentTypeInView == null) return false;
        if (pm.getCurrentContentTypePlaying() != currentContentTypeInView) return false;
        return pm.getCurrentPlaylistPlayingId() == currentPlaylistInViewId;
    }

    private List<Song> buildInitialSongList(Playlist playlist) {
        if (playlist == null || playlist.getSongList() == null) {
            return List.of();
        }

        List<Song> songs = playlist.getSongList();
        if (currentContentTypeInView != ContentType.ALBUM) {
            return songs;
        }
        return PlayerMenuAlbumTrackOrder.order(songs);
    }

    /**
     * Keeps first paint focused on the header and virtualized song list. Footers
     * may need network or card construction, so they are started only after the
     * current JavaFX pulse and only while this playlist revision is still active.
     */
    private void refreshHeaderAndScheduleDeferredContent(long viewRevision) {
        if (serviceCoordinator.headerFooterService() != null) {
            serviceCoordinator.headerFooterService().refreshHeader();
        }
        refreshPlaylistHeaderActionsState();
        scheduleFooterWhenVisible(viewRevision);
        if (uiCoordinator != null) {
            uiCoordinator.settleResponsiveLayout();
        }
    }

    private void scheduleFooterWhenVisible(long viewRevision) {
        clearDeferredFooterListeners();
        deferredFooterLoaded = false;

        if (serviceCoordinator.headerFooterService() != null) {
            serviceCoordinator.headerFooterService().prepareFooterForDeferredLoad();
        }
        if (serviceCoordinator.recommendationsService() != null) {
            serviceCoordinator.recommendationsService().prepareFooterForDeferredLoad();
        }

        if (isLocalSongWithoutMetadataView()) {
            deferredFooterLoaded = true;
            return;
        }

        if (footerPane == null || playerMenuScroll == null) {
            loadDeferredFooter(viewRevision);
            return;
        }

        deferredFooterScrollListener = (obs, oldValue, newValue) ->
                queueDeferredFooterCheck(viewRevision);
        deferredFooterViewportListener = (obs, oldValue, newValue) ->
                queueDeferredFooterCheck(viewRevision);
        deferredFooterBoundsListener = (obs, oldValue, newValue) ->
                queueDeferredFooterCheck(viewRevision);

        playerMenuScroll.vvalueProperty().addListener(deferredFooterScrollListener);
        playerMenuScroll.viewportBoundsProperty().addListener(deferredFooterViewportListener);
        footerPane.boundsInParentProperty().addListener(deferredFooterBoundsListener);
        queueDeferredFooterCheck(viewRevision);
    }

    private boolean isLocalSongWithoutMetadataView() {
        if (currentContentTypeInView != ContentType.SINGLE
                || currentPlaylistModel == null
                || currentPlaylistModel.getSongList() == null
                || currentPlaylistModel.getSongList().isEmpty()) {
            return false;
        }

        Song song = currentPlaylistModel.getSongList().get(0);
        return song != null && song.isLocal() && song.getSongID() == 0L;
    }

    private void queueDeferredFooterCheck(long viewRevision) {
        if (!context.isViewRevisionCurrent(viewRevision)
                || deferredFooterLoaded
                || !deferredFooterCheckQueued.compareAndSet(false, true)) {
            return;
        }

        Platform.runLater(() -> {
            try {
                evaluateDeferredFooterVisibility(viewRevision);
            } finally {
                deferredFooterCheckQueued.set(false);
            }
        });
    }

    private void evaluateDeferredFooterVisibility(long viewRevision) {
        if (!context.isViewRevisionCurrent(viewRevision) || deferredFooterLoaded
                || footerPane == null || playerMenuScroll == null) {
            return;
        }

        if (footerPane.localToScene(footerPane.getBoundsInLocal()) == null
                || playerMenuScroll.localToScene(playerMenuScroll.getBoundsInLocal()) == null) {
            queueDeferredFooterCheck(viewRevision);
            return;
        }

        Bounds viewport = playerMenuScroll.localToScene(
                playerMenuScroll.getViewportBounds()
        );
        Bounds footer = footerPane.localToScene(footerPane.getBoundsInLocal());
        double preloadMargin = 420.0;
        boolean nearViewport = footer.getMaxY() >= viewport.getMinY() - preloadMargin
                && footer.getMinY() <= viewport.getMaxY() + preloadMargin;
        if (nearViewport) {
            loadDeferredFooter(viewRevision);
        }
    }

    private void loadDeferredFooter(long viewRevision) {
        if (deferredFooterLoaded || !context.isViewRevisionCurrent(viewRevision)) return;
        deferredFooterLoaded = true;
        clearDeferredFooterListeners();

        if (currentContentTypeInView == ContentType.PLAYLIST) {
            if (serviceCoordinator.headerFooterService() != null) {
                serviceCoordinator.headerFooterService().refreshFooter();
            }
            if (serviceCoordinator.recommendationsService() != null) {
                serviceCoordinator.recommendationsService().refreshForView(
                        currentPlaylistModel,
                        currentContentTypeInView
                );
            }
            return;
        }

        if (serviceCoordinator.recommendationsService() != null) {
            serviceCoordinator.recommendationsService().refreshForView(
                    currentPlaylistModel,
                    currentContentTypeInView
            );
        }
        if (serviceCoordinator.headerFooterService() != null) {
            serviceCoordinator.headerFooterService().refreshFooter();
        }
    }

    private void clearDeferredFooterListeners() {
        if (playerMenuScroll != null && deferredFooterScrollListener != null) {
            playerMenuScroll.vvalueProperty().removeListener(deferredFooterScrollListener);
        }
        if (playerMenuScroll != null && deferredFooterViewportListener != null) {
            playerMenuScroll.viewportBoundsProperty().removeListener(deferredFooterViewportListener);
        }
        if (footerPane != null && deferredFooterBoundsListener != null) {
            footerPane.boundsInParentProperty().removeListener(deferredFooterBoundsListener);
        }
        deferredFooterScrollListener = null;
        deferredFooterViewportListener = null;
        deferredFooterBoundsListener = null;
    }


    private void configureRemoteSaveCheckBoxInitialState() {
        if (uiCoordinator != null) uiCoordinator.configureRemoteSaveCheckBoxInitialState();
    }

    private void refreshPlaylistHeaderActionsState() {
        if (serviceCoordinator.playlistActionsService() == null) {
            configureRemoteSaveCheckBoxInitialState();
            return;
        }

        serviceCoordinator.playlistActionsService().onPlaylistModelChanged(
                currentPlaylistModel,
                currentContentTypeInView
        );
    }

    private boolean isSongImmediatelyPlayable(Song song) {
        if (song == null || !song.isLocal()) {
            return false;
        }

        String filePath = song.getFilePath();

        if (filePath == null || filePath.isBlank()) {
            return false;
        }

        try {
            File file = new File(filePath);

            return file.exists()
                    && file.isFile()
                    && file.canRead()
                    && file.length() > 0L;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void updatePlayVisibleSongsButtonState() {
        actionCoordinator.updateActionState();
    }

    private void updateActionTooltips() {
        actionCoordinator.updateTooltips();
    }

    private void syncSongListUiState() {
        boolean emptyUserPlaylist = isCurrentUserPlaylistEmpty();
        if (uiCoordinator != null) {
            uiCoordinator.syncSongListUiState(
                    currentContentTypeInView,
                    emptyUserPlaylist,
                    serviceCoordinator.songListService(),
                    this::refreshPlaylistHeaderActionsState
            );
        }
    }

}
