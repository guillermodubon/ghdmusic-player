package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.collections.transformation.FilteredList;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Rectangle;
import javafx.util.Callback;
import io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.services.ArtistLinksBuilder;
import io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.services.PreviewService;
import io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.services.SongCoverResolver;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.managers.PlayerMenuArtistResolver;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.libraryCatalogListScreens.common.LibraryCatalogFilterMenu;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Facade for the PlayerMenu song list.
 *
 * The public API remains compatible with PlayerMenuController. Layout/search
 * concerns stay here, while local state and cell rendering live in dedicated
 * collaborators.
 */
public class PlayerMenuSongListService {
    private static final String EMPTY_PLAYLIST_MIN_HEIGHT_KEY = "playerMenuEmptyPlaylistMinHeight";
    private static final int VIRTUALIZED_VISIBLE_ROWS = 12;
    private static final double LIST_MIN_HEIGHT = 180.0;
    private static final double LIST_MAX_HEIGHT = 820.0;
    private static final double SINGLE_ROW_VERTICAL_PADDING = 12.0;
    private static final double LIST_BOTTOM_GAP = 66.0;
    private static final String PLAYLIST_SORT_ICON =
            "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/list_24dp_E3E3E3_FILL0_wght400_GRAD0_opsz24.svg";
    /**
     * Reconciliation can inspect the complete local library and filesystem.
     * Keep that work off the JavaFX thread unless both the opened collection
     * and the local library are genuinely small.
     */
    private static final int FIRST_PAINT_RECONCILIATION_TRACK_LIMIT = 12;
    private static final int FIRST_PAINT_RECONCILIATION_LIBRARY_LIMIT = 120;
    private static final ExecutorService LOCAL_RECONCILIATION_IO = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "player-menu-local-reconciliation");
        thread.setDaemon(true);
        return thread;
    });

    private final PlayerMenuContext context;
    private final PlaybackManager playbackManager;
    private final PlayerMenuArtistResolver artistResolver;
    private final ArtistLinksBuilder artistLinksBuilder = new ArtistLinksBuilder();
    private final PreviewService previewService = new PreviewService();
    private final SongCoverResolver coverResolver = new SongCoverResolver();
    private final PlayerMenuSongLocalState localState;
    private final PlayerMenuSongCellFactory cellFactory;
    private final PlayerMenuPlaylistOrderPersistenceService playlistOrderPersistenceService =
            new PlayerMenuPlaylistOrderPersistenceService();
    private final PlayerMenuPlaylistReorderSupport playlistReorderSupport;
    private final PlayerMenuRemoteSongDetailsService remoteSongDetailsService =
            new PlayerMenuRemoteSongDetailsService();

    private StartUpService startUpService;
    private MusicCardActionManager musicActions;
    private Consumer<Song> onSongClicked = song -> {};
    private Consumer<Song> onAddToQueue = song -> {};

    private TextField searchSongField;
    private Node searchSongContainer;
    private ListView<Song> songsToPlayView;
    private ListView<Song> songListView;
    private Label songCountLabel;
    private ScrollPane screenScrollPane;
    private Pane songListVirtualShell;
    private MenuButton playlistSortMenuButton;
    private LibraryCatalogFilterMenu playlistSortMenu;
    private PlayerMenuContext.ContentType activeContentType;
    private Playlist activePlaylist;
    private PlayerMenuPlaylistSort selectedPlaylistSort = PlayerMenuPlaylistSort.RECENTLY_ADDED;
    private long sortPlaylistId = -1L;
    private List<Long> customOrderIds = List.of();
    private List<Long> recentlyAddedOrderIds = List.of();
    private final AtomicLong sortMetadataGeneration = new AtomicLong();

    private FilteredList<Song> filteredSongs;
    private boolean searchBindingInstalled;
    private boolean listHeightListenerInstalled;
    private final AtomicBoolean listRefreshQueued = new AtomicBoolean(false);
    private final AtomicBoolean listStateRefreshQueued = new AtomicBoolean(false);
    private final AtomicBoolean searchRefreshQueued = new AtomicBoolean(false);
    private final Set<Long> visibleHydrationRequested = ConcurrentHashMap.newKeySet();
    private final Map<Long, Song> pendingVisibleHydration = new LinkedHashMap<>();
    private final AtomicBoolean remoteHydrationFlushQueued = new AtomicBoolean(false);
    private Callback<ListView<Song>, ListCell<Song>> songCellFactory;
    private int virtualWindowStart = -1;

    public PlayerMenuSongListService(PlayerMenuContext context,
                                     PlaybackManager playbackManager,
                                     PlayerMenuArtistResolver artistResolver) {
        this.context = Objects.requireNonNull(context, "context");
        this.playbackManager = playbackManager;
        this.artistResolver = artistResolver;
        this.localState = new PlayerMenuSongLocalState(context);
        this.cellFactory = new PlayerMenuSongCellFactory(
                context,
                localState
        );
        this.playlistReorderSupport = new PlayerMenuPlaylistReorderSupport(
                 context,
                 playbackManager,
                 playlistOrderPersistenceService
         );
        this.playlistReorderSupport.setReorderListener(this::handlePlaylistReordered);
    }

    public void bindUi(TextField searchSongField,
                       Node searchSongContainer,
                       ListView<Song> songsToPlayView,
                       ListView<Song> songListView,
                       Label songCountLabel,
                       ScrollPane screenScrollPane,
                       Pane songListVirtualShell,
                       MenuButton playlistSortMenuButton) {
        this.searchSongField = searchSongField;
        this.searchSongContainer = searchSongContainer;
        this.songsToPlayView = songsToPlayView;
        this.songListView = songListView;
        this.songCountLabel = songCountLabel;
        this.screenScrollPane = screenScrollPane;
        this.songListVirtualShell = songListVirtualShell;
        this.playlistSortMenuButton = playlistSortMenuButton;
        playlistReorderSupport.bindAutoScroll(screenScrollPane, this::scrollOuterByPixels);
        configurePlaylistSortMenu();

        playlistReorderSupport.bind(songsToPlayView);
        playlistReorderSupport.bind(songListView);
        setupOuterScrollVirtualization();
        setupSearchBinding();
        setupSongsCellFactory();
        setupKeyboardNavigation();
    }

    public void bindServices(StartUpService startUpService,
                             MusicCardActionManager musicActions,
                             Consumer<Song> onSongClicked,
                             Consumer<Song> onAddToQueue) {
        this.startUpService = startUpService;
        this.musicActions = musicActions;
        this.onSongClicked = onSongClicked == null ? song -> {} : onSongClicked;
        this.onAddToQueue = onAddToQueue == null ? song -> {} : onAddToQueue;
        localState.bind(startUpService);
        cellFactory.bindServices(
                startUpService,
                this.musicActions,
                this.onSongClicked,
                this.onAddToQueue
        );
        cellFactory.setRemoteDetailsRequester(this::requestVisibleRemoteSongDetails);
        cellFactory.setDownloadCompleted(this::integrateDownloadedSongAndRefresh);
        remoteSongDetailsService.bind(startUpService);
        setupSongsCellFactory();
    }

    public void applySongList(List<Song> initial,
                              PlayerMenuContext.ContentType type,
                              Playlist playlist) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> applySongList(initial, type, playlist));
            return;
        }

        List<Song> sourceSongs = initial == null ? List.of() : new ArrayList<>(initial);
        boolean deferLocalReconciliation = shouldDeferLocalReconciliation(sourceSongs);
        List<Song> reconciledInitial = deferLocalReconciliation
                ? sourceSongs
                : localState.reconcileLocalSongsBeforeRender(sourceSongs);
        List<Song> safeInitial = orderAlbumSongs(reconciledInitial, type);
        localState.clearManifestCache();
        visibleHydrationRequested.clear();
        pendingVisibleHydration.clear();
        remoteHydrationFlushQueued.set(false);
        virtualWindowStart = -1;
        activeContentType = type;
        activePlaylist = playlist;
        activatePlaylistSort(type, playlist);
        cellFactory.bindSource(type, playlist);

        context.setMasterSongList(safeInitial);
        if (filteredSongs == null) {
            filteredSongs = new FilteredList<>(context.getMasterSongList(), song -> true);
        }
        installDynamicListHeightListener();

        if (songsToPlayView != null) songsToPlayView.setItems(filteredSongs);
        if (songListView != null && songListView != songsToPlayView) {
            songListView.setItems(filteredSongs);
        }

        List<Song> playable = deferLocalReconciliation
                ? resolveImmediatelyPlayableSongs(context.getMasterSongList())
                : resolvePlayableSongs(context.getMasterSongList());
        context.setCurrentSongList(playable);
        updateSongCountLabel();
        updateSearchVisibility(context.getMasterSongList().size() > 1);
        updateEmptyPlaylistPlaceholder(type, playlist);
        applySearchPredicate(searchSongField == null ? "" : searchSongField.getText());
        applyPlaylistSortInternal(selectedPlaylistSort, false);
        configurePlaylistReorder(type, playlist);
        updateSearchNoResultsPlaceholder(searchSongField == null ? "" : searchSongField.getText());
        setupSearchBinding();
        setupSongsCellFactory();
        adjustListHeight(getPrimarySongListView());

        if (deferLocalReconciliation) {
            scheduleDeferredLocalReconciliation(safeInitial, context.getViewRevision());
        }
        loadPlaylistOrderMetadata(type, playlist);
    }

    public void refreshListState() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::refreshListState);
            return;
        }
        updateSearchVisibility(context.getMasterSongList().size() > 1);
        updateEmptyPlaylistPlaceholder(activeContentType, activePlaylist);
        updateSearchNoResultsPlaceholder(searchSongField == null ? "" : searchSongField.getText());
        updatePlaylistReorderAvailability();
        updatePlaylistSortVisibility();
        adjustListHeight(getPrimarySongListView());
    }

    public void refreshCurrentViewMinimal() {
        scheduleListRefresh();
    }

    public void onDetached() {
        playlistReorderSupport.activate(null, null, false);
        sortMetadataGeneration.incrementAndGet();
        sortPlaylistId = -1L;
        customOrderIds = List.of();
        recentlyAddedOrderIds = List.of();
        if (playlistSortMenu != null) playlistSortMenu.setVisible(false);
    }

    public void adjustListHeight(ListView<?> listView) {
        if (listView == null) return;
        double cellHeight = listView.getFixedCellSize() > 0 ? listView.getFixedCellSize() : 24;
        int count = listView.getItems() == null ? 0 : listView.getItems().size();
        Object emptyMinHeight = listView.getProperties().get(EMPTY_PLAYLIST_MIN_HEIGHT_KEY);
        double emptyHeight = emptyMinHeight instanceof Number number
                ? Math.max(180, number.doubleValue())
                : LIST_MIN_HEIGHT;
        double height = count == 0 ? emptyHeight : boundedListHeight(count, cellHeight);

        if (songListVirtualShell == null || listView != getPrimarySongListView()) {
            setListHeight(listView, height);
            return;
        }

        double virtualHeight = count == 0 ? emptyHeight : virtualShellHeight(count, cellHeight);
        double listViewportHeight = count == 0
                ? emptyHeight
                : alignedListViewportHeight(count, cellHeight, virtualHeight);
        setRegionHeight(songListVirtualShell, virtualHeight);
        setListHeight(listView, listViewportHeight);
        updateVirtualWindow(listView, count, cellHeight, virtualHeight, listViewportHeight);
    }

    public void refreshDownloadedSongState() {
        Runnable refresh = () -> {
            localState.clearManifestCache();
            localState.rebuildCurrentPlayableListFromMaster();
            applySearchPredicate(searchSongField == null ? "" : searchSongField.getText());
            refreshLists();
            updateSongCountLabel();
            updateSearchVisibility(context.getMasterSongList().size() > 1);
            updateEmptyPlaylistPlaceholder(activeContentType, activePlaylist);
            updateSearchNoResultsPlaceholder(searchSongField == null ? "" : searchSongField.getText());
            adjustListHeight(getPrimarySongListView());
            Platform.runLater(() -> {
                refreshLists();
                adjustListHeight(getPrimarySongListView());
            });
        };
        if (Platform.isFxApplicationThread()) refresh.run(); else Platform.runLater(refresh);
    }

    public void refreshPlaybackIndicators() {
        Platform.runLater(() -> {
            cellFactory.refreshPlaybackIndicators(songsToPlayView);
            if (songListView != null && songListView != songsToPlayView) {
                cellFactory.refreshPlaybackIndicators(songListView);
            }
        });
    }

    public void forceFinalDownloadCellRefresh() {
        Runnable refresh = () -> {
            localState.clearManifestCache();
            hardRefreshSongListView(songsToPlayView);
            if (songListView != null && songListView != songsToPlayView) {
                hardRefreshSongListView(songListView);
            }
            updateSongCountLabel();
            updateSearchNoResultsPlaceholder(searchSongField == null ? "" : searchSongField.getText());
        };
        if (Platform.isFxApplicationThread()) refresh.run(); else Platform.runLater(refresh);
    }

    public void integrateDownloadedSongAndRefresh(Song downloadedSong, File finalFile) {
        if (downloadedSong == null || finalFile == null || !finalFile.exists() || !finalFile.isFile()) {
            return;
        }

        Runnable integration = () -> {
            ListView<Song> primaryView = getPrimarySongListView();
            if (primaryView == null
                    || primaryView.getScene() == null
                    || !primaryView.isVisible()
                    || !primaryView.isManaged()) {
                return;
            }

            Song replacement = replaceWithObservableLocalSong(
                    downloadedSong,
                    finalFile
            );
            if (replacement == null) return;

            replaceSongInActivePlaylist(
                    downloadedSong,
                    finalFile.getAbsolutePath()
            );
            localState.clearManifestCache();
            localState.rebuildCurrentPlayableListFromMaster();
            refreshVisibleCellForSong(songsToPlayView, replacement);
            if (songListView != null && songListView != songsToPlayView) {
                refreshVisibleCellForSong(songListView, replacement);
            }
            updateSongCountLabel();
            updateSearchNoResultsPlaceholder(searchSongField == null ? "" : searchSongField.getText());

            Platform.runLater(() -> {
                refreshVisibleCellForSong(songsToPlayView, replacement);
                if (songListView != null && songListView != songsToPlayView) {
                    refreshVisibleCellForSong(songListView, replacement);
                }
                refreshLists();
                forceFinalDownloadCellRefresh();
            });
        };
        if (Platform.isFxApplicationThread()) integration.run(); else Platform.runLater(integration);
    }

    private void setupSongsCellFactory() {
        if (songsToPlayView == null && songListView == null) return;
        if (songCellFactory == null) songCellFactory = cellFactory.callback();
        if (songsToPlayView != null && songsToPlayView.getCellFactory() != songCellFactory) {
            songsToPlayView.setCellFactory(songCellFactory);
        }
        if (songListView != null && songListView != songsToPlayView) {
            if (songListView.getCellFactory() != songCellFactory) {
                songListView.setCellFactory(songCellFactory);
            }
        }
    }

    private void setupSearchBinding() {
        if (searchSongField == null || filteredSongs == null || searchBindingInstalled) return;
        searchBindingInstalled = true;
        searchSongField.textProperty().addListener((obs, old, query) -> {
            queueSearchRefresh(query);
        });
    }

    private void setupOuterScrollVirtualization() {
        ListView<Song> target = getPrimarySongListView();
        if (target != null && songListVirtualShell != null) {
            if (!target.prefWidthProperty().isBound()) {
                target.prefWidthProperty().bind(songListVirtualShell.widthProperty());
            }
            target.setMaxWidth(Double.MAX_VALUE);
        }
        if (songListVirtualShell != null
                && !Boolean.TRUE.equals(songListVirtualShell.getProperties().get("playerMenuVirtualClipInstalled"))) {
            Rectangle clip = new Rectangle();
            clip.widthProperty().bind(songListVirtualShell.widthProperty());
            clip.heightProperty().bind(songListVirtualShell.heightProperty());
            songListVirtualShell.setClip(clip);
            songListVirtualShell.getProperties().put("playerMenuVirtualClipInstalled", Boolean.TRUE);
            songListVirtualShell.boundsInParentProperty().addListener((obs, old, value) -> adjustListHeight(target));
            songListVirtualShell.heightProperty().addListener((obs, old, value) -> adjustListHeight(target));
        }
        if (screenScrollPane != null
                && !Boolean.TRUE.equals(screenScrollPane.getProperties().get("playerMenuVirtualScrollInstalled"))) {
            screenScrollPane.viewportBoundsProperty().addListener((obs, old, value) -> adjustListHeight(target));
            screenScrollPane.getProperties().put("playerMenuVirtualScrollInstalled", Boolean.TRUE);
        }
        if (screenScrollPane != null
                && !Boolean.TRUE.equals(screenScrollPane.getProperties().get("playerMenuVirtualWindowScrollInstalled"))) {
            screenScrollPane.vvalueProperty().addListener((obs, old, value) -> updateVirtualWindow(target));
            screenScrollPane.getProperties().put("playerMenuVirtualWindowScrollInstalled", Boolean.TRUE);
        }
        if (target != null
                && !Boolean.TRUE.equals(target.getProperties().get("playerMenuRouteScrollInstalled"))) {
            target.addEventFilter(ScrollEvent.SCROLL, this::routeListScrollToOuterScroll);
            target.getProperties().put("playerMenuRouteScrollInstalled", Boolean.TRUE);
        }
    }

    private void setupKeyboardNavigation() {
        installKeyboardNavigation(songsToPlayView);
        if (songListView != null && songListView != songsToPlayView) {
            installKeyboardNavigation(songListView);
        }
    }

    private void installKeyboardNavigation(ListView<Song> listView) {
        if (listView == null
                || Boolean.TRUE.equals(listView.getProperties().get("playerMenuKeyboardNavigationInstalled"))) {
            return;
        }
        listView.setFocusTraversable(true);
        listView.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event == null || event.isConsumed()) return;
            if (event.getCode() == KeyCode.UP) {
                moveSelection(listView, -1);
                event.consume();
            } else if (event.getCode() == KeyCode.DOWN) {
                moveSelection(listView, 1);
                event.consume();
            } else if (event.getCode() == KeyCode.ENTER) {
                playSelectedSong(listView);
                event.consume();
            }
        });
        listView.getProperties().put("playerMenuKeyboardNavigationInstalled", Boolean.TRUE);
    }

    private void moveSelection(ListView<Song> listView, int delta) {
        if (listView.getItems() == null || listView.getItems().isEmpty()) return;
        int size = listView.getItems().size();
        int selected = listView.getSelectionModel().getSelectedIndex();
        int next = selected < 0
                ? (delta < 0 ? size - 1 : 0)
                : Math.max(0, Math.min(size - 1, selected + delta));
        listView.getSelectionModel().select(next);
        scrollOuterToSelection(listView, next);
    }

    private void playSelectedSong(ListView<Song> listView) {
        Song selected = listView == null ? null : listView.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        if (localState.shouldUsePlayableSongItem(selected)) {
            onSongClicked.accept(selected);
            return;
        }
        previewService.handlePreview(selected, resolvePreviewArtistsText(selected), listView, startUpService, coverResolver);
    }

    private String resolvePreviewArtistsText(Song song) {
        if (song == null) return "";
        List<Artist> artists = new ArrayList<>();
        if (song.getArtist() != null) artists.addAll(song.getArtist());
        if (artists.isEmpty() && song.getAlbum() != null && song.getAlbum().getArtist() != null) {
            artists.addAll(song.getAlbum().getArtist());
        }
        return artistLinksBuilder.formatArtists(artists);
    }

    private void applySearchPredicate(String query) {
        if (filteredSongs == null) return;
        String lower = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        filteredSongs.setPredicate(song -> {
            if (song == null) return false;
            if (lower.isBlank()) return true;
            if (song.getTitle() != null && song.getTitle().toLowerCase(Locale.ROOT).contains(lower)) return true;
            if (song.getAlbum() != null
                    && song.getAlbum().getName() != null
                    && song.getAlbum().getName().toLowerCase(Locale.ROOT).contains(lower)) return true;
            return song.getArtist() != null && song.getArtist().stream()
                    .filter(Objects::nonNull)
                    .anyMatch(artist -> artist.getName() != null
                            && artist.getName().toLowerCase(Locale.ROOT).contains(lower));
        });
    }

    private void updateSearchVisibility(boolean visible) {
        Node target = searchSongContainer != null ? searchSongContainer : searchSongField;
        if (target != null) {
            target.setVisible(visible);
            target.setManaged(visible);
        }
        if (!visible && searchSongField != null && !searchSongField.getText().isBlank()) {
            searchSongField.clear();
        }
    }

    private void updateSearchNoResultsPlaceholder(String query) {
        ListView<Song> target = getPrimarySongListView();
        if (target == null) return;
        String safeQuery = query == null ? "" : query.trim();
        boolean noResults = !safeQuery.isBlank()
                && context.getMasterSongList() != null
                && !context.getMasterSongList().isEmpty()
                && filteredSongs != null
                && filteredSongs.isEmpty();
        if (!noResults) {
            updateEmptyPlaylistPlaceholder(activeContentType, activePlaylist);
            return;
        }
        Label label = new Label("There are no results for \"" + safeQuery + "\" for " + activePlaylistDisplayName());
        label.getStyleClass().addAll("app-text-subtitle", "player-menu-empty-placeholder");
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        target.setPlaceholder(label);
        target.getProperties().put(EMPTY_PLAYLIST_MIN_HEIGHT_KEY, LIST_MIN_HEIGHT);
    }

    private void updateEmptyPlaylistPlaceholder(PlayerMenuContext.ContentType type, Playlist playlist) {
        ListView<Song> target = getPrimarySongListView();
        if (target == null) return;
        boolean emptyLocalPlaylist = type == PlayerMenuContext.ContentType.PLAYLIST
                && playlist != null
                && context.getMasterSongList().isEmpty()
                && isUserCreatedPlaylist(playlist);
        if (!emptyLocalPlaylist) {
            target.setPlaceholder(null);
            target.getProperties().remove(EMPTY_PLAYLIST_MIN_HEIGHT_KEY);
            return;
        }
        String title = Optional.ofNullable(playlist.getTitle()).orElse("This playlist").trim();
        if (title.isBlank()) title = "This playlist";
        Label label = new Label(title + " doesn't have any song yet");
        label.getStyleClass().addAll("app-text-subtitle", "player-menu-empty-placeholder");
        label.setWrapText(true);
        target.setPlaceholder(label);
        target.getProperties().put(EMPTY_PLAYLIST_MIN_HEIGHT_KEY, 240.0);
    }

    private void configurePlaylistReorder(PlayerMenuContext.ContentType type, Playlist playlist) {
        boolean allowed = type == PlayerMenuContext.ContentType.PLAYLIST
                && playlist != null
                && playlist.getSongList() != null
                && playlist.getSongList().size() >= 2
                && isUserCreatedPlaylist(playlist);
        playlistReorderSupport.activate(startUpService, playlist, allowed);
        updatePlaylistReorderAvailability();
    }

    private void updatePlaylistReorderAvailability() {
        String query = searchSongField == null ? "" : searchSongField.getText();
        playlistReorderSupport.setEnabled(query == null || query.isBlank());
    }

    private void configurePlaylistSortMenu() {
        if (playlistSortMenuButton == null) return;
        if (playlistSortMenu == null) {
            playlistSortMenu = new LibraryCatalogFilterMenu(
                    playlistSortMenuButton,
                    List.of(
                            new LibraryCatalogFilterMenu.Option(
                                    PlayerMenuPlaylistSort.TITLE.name(),
                                    PlayerMenuPlaylistSort.TITLE.label()),
                            new LibraryCatalogFilterMenu.Option(
                                    PlayerMenuPlaylistSort.ARTIST.name(),
                                    PlayerMenuPlaylistSort.ARTIST.label()),
                            new LibraryCatalogFilterMenu.Option(
                                    PlayerMenuPlaylistSort.ALBUM.name(),
                                    PlayerMenuPlaylistSort.ALBUM.label()),
                            new LibraryCatalogFilterMenu.Option(
                                    PlayerMenuPlaylistSort.RECENTLY_ADDED.name(),
                                    PlayerMenuPlaylistSort.RECENTLY_ADDED.label()),
                            new LibraryCatalogFilterMenu.Option(
                                    PlayerMenuPlaylistSort.CUSTOM.name(),
                                    PlayerMenuPlaylistSort.CUSTOM.label())
                    ),
                    selectedPlaylistSort.name(),
                    option -> applyPlaylistSort(PlayerMenuPlaylistSort.fromId(option.id())),
                    PLAYLIST_SORT_ICON,
                    "player-menu-sort-button",
                    "Sort playlist songs",
                    "Change sort",
                    16
            );
        }
        playlistSortMenu.setSelected(selectedPlaylistSort.name());
        updatePlaylistSortVisibility();
    }

    public void applyPlaylistSort(PlayerMenuPlaylistSort sort) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> applyPlaylistSort(sort));
            return;
        }
        if (!isPlaylistSortAllowed(activeContentType, activePlaylist) || sort == null) return;

        selectedPlaylistSort = sort;
        PlayerMenuPlaylistSortPreferences.save(activePlaylist.getId(), sort);
        if (playlistSortMenu != null) playlistSortMenu.setSelected(sort.name());
        applyPlaylistSortInternal(sort, true);
    }

    private void activatePlaylistSort(PlayerMenuContext.ContentType type, Playlist playlist) {
        boolean allowed = isPlaylistSortAllowed(type, playlist);
        if (!allowed) {
            sortMetadataGeneration.incrementAndGet();
            sortPlaylistId = -1L;
            selectedPlaylistSort = PlayerMenuPlaylistSort.RECENTLY_ADDED;
            customOrderIds = List.of();
            recentlyAddedOrderIds = List.of();
            if (playlistSortMenu != null) playlistSortMenu.setVisible(false);
            return;
        }

        if (sortPlaylistId != playlist.getId()) {
            sortPlaylistId = playlist.getId();
            selectedPlaylistSort = PlayerMenuPlaylistSortPreferences.load(sortPlaylistId);
            customOrderIds = List.of();
            recentlyAddedOrderIds = List.of();
        }

        if (playlistSortMenu != null) {
            playlistSortMenu.setSelected(selectedPlaylistSort.name());
            playlistSortMenu.setVisible(true);
        }
    }

    private void updatePlaylistSortVisibility() {
        if (playlistSortMenu == null) return;
        boolean visible = isPlaylistSortAllowed(activeContentType, activePlaylist);
        playlistSortMenu.setVisible(visible);
        if (visible) playlistSortMenu.setSelected(selectedPlaylistSort.name());
    }

    private void loadPlaylistOrderMetadata(PlayerMenuContext.ContentType type, Playlist playlist) {
        if (!isPlaylistSortAllowed(type, playlist) || startUpService == null) return;

        final long playlistId = playlist.getId();
        final long generation = sortMetadataGeneration.incrementAndGet();
        final StartUpService service = startUpService;
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        if (service.getPlaylistDao() == null) {
                            return new PlaylistOrderMetadata(List.of(), List.of());
                        }
                        return new PlaylistOrderMetadata(
                                service.getPlaylistDao().findSongIdsByCustomOrder(playlistId),
                                service.getPlaylistDao().findSongIdsByRecentlyAdded(playlistId)
                        );
                    } catch (Exception ignored) {
                        return new PlaylistOrderMetadata(List.of(), List.of());
                    }
                }, LOCAL_RECONCILIATION_IO)
                .thenAccept(metadata -> Platform.runLater(() -> {
                    if (generation != sortMetadataGeneration.get()
                            || sortPlaylistId != playlistId
                            || activePlaylist == null
                            || activePlaylist.getId() != playlistId) {
                        return;
                    }
                    customOrderIds = metadata.customOrderIds();
                    recentlyAddedOrderIds = metadata.recentlyAddedOrderIds();
                    applyPlaylistSortInternal(selectedPlaylistSort, true);
                }));
    }

    private void handlePlaylistReordered(List<Song> orderedSongs) {
        if (!isPlaylistSortAllowed(activeContentType, activePlaylist)) return;
        customOrderIds = toSongIds(orderedSongs);
        selectedPlaylistSort = PlayerMenuPlaylistSort.CUSTOM;
        PlayerMenuPlaylistSortPreferences.save(activePlaylist.getId(), selectedPlaylistSort);
        if (playlistSortMenu != null) playlistSortMenu.setSelected(selectedPlaylistSort.name());
    }

    private void applyPlaylistSortInternal(PlayerMenuPlaylistSort sort, boolean persist) {
        if (!isPlaylistSortAllowed(activeContentType, activePlaylist) || sort == null) return;

        List<Song> source = new ArrayList<>(context.getMasterSongList());
        List<Song> ordered = orderSongs(source, sort);
        boolean changed = !sameOrder(source, ordered);
        if (changed) {
            if (activePlaylist.getSongList() != null) activePlaylist.getSongList().setAll(ordered);
            context.setMasterSongList(ordered);
            reorderPlayableSongs(ordered);
            applySearchPredicate(searchSongField == null ? "" : searchSongField.getText());
            refreshLists();
            updateSongCountLabel();
            updateSearchNoResultsPlaceholder(searchSongField == null ? "" : searchSongField.getText());
            adjustListHeight(getPrimarySongListView());
            syncPlaybackFlowIfRequired(ordered);
        }

        if (persist && changed) {
            if (sort == PlayerMenuPlaylistSort.CUSTOM) {
                playlistOrderPersistenceService.requestCustom(startUpService, activePlaylist, ordered);
            } else {
                playlistOrderPersistenceService.request(startUpService, activePlaylist, ordered);
            }
        }
    }

    private List<Song> orderSongs(List<Song> source, PlayerMenuPlaylistSort sort) {
        List<Song> ordered = source == null ? new ArrayList<>() : new ArrayList<>(source);
        if (ordered.size() < 2) return ordered;

        switch (sort) {
            case TITLE -> ordered.sort(songComparator(this::songTitleSortName));
            case ARTIST -> ordered.sort(songComparator(this::songArtistSortName));
            case ALBUM -> ordered.sort(songComparator(this::songAlbumSortName)
                    .thenComparing(songComparator(this::songTitleSortName)));
            case RECENTLY_ADDED -> ordered = orderByPersistedIds(ordered, recentlyAddedOrderIds);
            case CUSTOM -> ordered = orderByPersistedIds(ordered, customOrderIds);
        }
        return ordered;
    }

    private Comparator<Song> songComparator(java.util.function.Function<Song, String> keyExtractor) {
        return Comparator.comparing(keyExtractor)
                .thenComparingLong(song -> song == null ? Long.MAX_VALUE : song.getSongID());
    }

    private String songTitleSortName(Song song) {
        return sortName(song == null ? null : song.getTitle());
    }

    private String songArtistSortName(Song song) {
        if (song != null && song.getArtist() != null) {
            for (Artist artist : song.getArtist()) {
                if (artist != null && artist.getName() != null && !artist.getName().isBlank()) {
                    return sortName(artist.getName());
                }
            }
        }
        return song == null || song.getAlbum() == null
                ? "\uFFFF"
                : firstArtistSortName(song.getAlbum().getArtist());
    }

    private String songAlbumSortName(Song song) {
        return sortName(song == null || song.getAlbum() == null
                ? null
                : song.getAlbum().getName());
    }

    private String firstArtistSortName(List<Artist> artists) {
        if (artists != null) {
            for (Artist artist : artists) {
                if (artist != null && artist.getName() != null && !artist.getName().isBlank()) {
                    return sortName(artist.getName());
                }
            }
        }
        return "\uFFFF";
    }

    private String sortName(String value) {
        return value == null || value.isBlank()
                ? "\uFFFF"
                : value.trim().toLowerCase(Locale.ROOT);
    }

    private List<Song> orderByPersistedIds(List<Song> source, List<Long> orderedIds) {
        if (orderedIds == null || orderedIds.isEmpty()) return source;

        Map<Long, Song> byId = new HashMap<>();
        for (Song song : source) {
            if (song != null && song.getSongID() > 0) byId.putIfAbsent(song.getSongID(), song);
        }

        List<Song> ordered = new ArrayList<>(source.size());
        Set<Long> used = new HashSet<>();
        for (Long id : orderedIds) {
            if (id == null || !used.add(id)) continue;
            Song song = byId.get(id);
            if (song != null) ordered.add(song);
        }
        for (Song song : source) {
            if (song == null || song.getSongID() <= 0 || used.add(song.getSongID())) {
                ordered.add(song);
            }
        }
        return ordered;
    }

    private void reorderPlayableSongs(List<Song> orderedSongs) {
        List<Song> playable = new ArrayList<>(context.getCurrentSongList());
        playable.sort(Comparator.comparingInt(song -> {
            int index = indexOfSong(orderedSongs, song);
            return index < 0 ? Integer.MAX_VALUE : index;
        }));
        context.setCurrentSongList(playable);
    }

    private void syncPlaybackFlowIfRequired(List<Song> orderedSongs) {
        if (playbackManager == null
                || activePlaylist == null
                || playbackManager.isRandomMode()
                || playbackManager.getCurrentContentTypePlaying()
                != PlayerMenuContext.ContentType.PLAYLIST
                || playbackManager.getCurrentPlaylistPlayingId() != activePlaylist.getId()) {
            return;
        }
        playbackManager.syncCurrentSourceSongs(orderedSongs);
    }

    private int indexOfSong(List<Song> songs, Song expected) {
        if (songs == null || expected == null) return -1;
        for (int index = 0; index < songs.size(); index++) {
            Song candidate = songs.get(index);
            if (candidate == expected) return index;
            if (candidate != null && candidate.getSongID() > 0
                    && candidate.getSongID() == expected.getSongID()) return index;
        }
        return -1;
    }

    private boolean sameOrder(List<Song> left, List<Song> right) {
        if (left == right) return true;
        if (left == null || right == null || left.size() != right.size()) return false;
        for (int index = 0; index < left.size(); index++) {
            Song leftSong = left.get(index);
            Song rightSong = right.get(index);
            if (leftSong == rightSong) continue;
            if (leftSong == null || rightSong == null
                    || leftSong.getSongID() <= 0
                    || rightSong.getSongID() <= 0
                    || leftSong.getSongID() != rightSong.getSongID()) return false;
        }
        return true;
    }

    private List<Long> toSongIds(List<Song> songs) {
        if (songs == null) return List.of();
        List<Long> ids = new ArrayList<>(songs.size());
        for (Song song : songs) {
            if (song != null && song.getSongID() > 0) ids.add(song.getSongID());
        }
        return List.copyOf(ids);
    }

    private boolean isPlaylistSortAllowed(PlayerMenuContext.ContentType type, Playlist playlist) {
        return type == PlayerMenuContext.ContentType.PLAYLIST
                && playlist != null
                && playlist.getId() > 0
                && isUserCreatedPlaylist(playlist);
    }

    private record PlaylistOrderMetadata(
            List<Long> customOrderIds,
            List<Long> recentlyAddedOrderIds
    ) {
    }

    private boolean isUserCreatedPlaylist(Playlist playlist) {
        if (playlist == null) return false;
        String author = Optional.ofNullable(playlist.getAuthorName()).orElse("").trim();
        if (author.equalsIgnoreCase("User") || author.equalsIgnoreCase("By you")) return true;
        if (startUpService == null || startUpService.getPlaylists() == null) return false;
        return startUpService.getPlaylists().stream()
                .filter(Objects::nonNull)
                .filter(value -> value.getId() == playlist.getId())
                .map(value -> Optional.ofNullable(value.getAuthorName()).orElse("").trim())
                .anyMatch(value -> value.equalsIgnoreCase("User") || value.equalsIgnoreCase("By you"));
    }

    private void installDynamicListHeightListener() {
        if (filteredSongs == null || listHeightListenerInstalled) return;
        listHeightListenerInstalled = true;
        filteredSongs.addListener((ListChangeListener<Song>) change -> queueListStateRefresh());
    }

    private void queueSearchRefresh(String query) {
        if (!searchRefreshQueued.compareAndSet(false, true)) return;
        Platform.runLater(() -> {
            try {
                String currentQuery = searchSongField == null ? query : searchSongField.getText();
                applySearchPredicate(currentQuery);
                updatePlaylistReorderAvailability();
                updateSearchNoResultsPlaceholder(currentQuery);
                adjustListHeight(getPrimarySongListView());
            } finally {
                searchRefreshQueued.set(false);
            }
        });
    }

    private void queueListStateRefresh() {
        if (!listStateRefreshQueued.compareAndSet(false, true)) return;
        Platform.runLater(() -> {
            try {
                updateSearchVisibility(context.getMasterSongList().size() > 1);
                updateEmptyPlaylistPlaceholder(activeContentType, activePlaylist);
                updateSearchNoResultsPlaceholder(searchSongField == null ? "" : searchSongField.getText());
                adjustListHeight(getPrimarySongListView());
            } finally {
                listStateRefreshQueued.set(false);
            }
        });
    }

    private void requestVisibleRemoteSongDetails(Song song) {
        if (song == null || song.isLocal() || song.getSongID() <= 0) return;
        if (!visibleHydrationRequested.add(song.getSongID())) return;

        pendingVisibleHydration.putIfAbsent(song.getSongID(), song);
        if (!remoteHydrationFlushQueued.compareAndSet(false, true)) return;

        Platform.runLater(this::flushVisibleRemoteSongDetails);
    }

    /**
     * A VirtualFlow commonly creates its first cells within the same pulse.
     * Coalescing those requests lets Deezer metadata share one lookup snapshot
     * and prevents a burst of tiny, redundant scheduling operations.
     */
    private void flushVisibleRemoteSongDetails() {
        remoteHydrationFlushQueued.set(false);
        if (pendingVisibleHydration.isEmpty()) return;

        long revision = context.getViewRevision();
        List<Song> songs = new ArrayList<>(pendingVisibleHydration.values());
        pendingVisibleHydration.clear();
        remoteSongDetailsService.prefetch(
                songs,
                () -> context.isViewRevisionCurrent(revision),
                this::scheduleListRefresh
        );
    }

    private boolean shouldDeferLocalReconciliation(List<Song> songs) {
        if (songs == null || songs.isEmpty() || startUpService == null) return false;
        if (songs.size() > FIRST_PAINT_RECONCILIATION_TRACK_LIMIT) return true;

        try {
            List<Song> librarySongs = startUpService.getSongs();
            return librarySongs != null
                    && librarySongs.size() > FIRST_PAINT_RECONCILIATION_LIBRARY_LIMIT;
        } catch (Exception ignored) {
            // A failed size check must not prevent the normal reconciliation.
            return false;
        }
    }

    private List<Song> resolvePlayableSongs(List<Song> songs) {
        List<Song> playable = new ArrayList<>();
        if (songs == null) return playable;
        for (Song song : songs) {
            if (localState.shouldUsePlayableSongItem(song)) playable.add(song);
        }
        return playable;
    }

    /**
     * First paint must not trigger manifest scans or library-wide path
     * resolution. Songs that already carry an exact local path stay playable
     * immediately; the deferred reconciliation promotes the remaining ones.
     */
    private List<Song> resolveImmediatelyPlayableSongs(List<Song> songs) {
        List<Song> playable = new ArrayList<>();
        if (songs == null) return playable;
        for (Song song : songs) {
            if (song != null
                    && song.isLocal()
                    && localState.hasUsableAudioFile(song.getFilePath())) {
                playable.add(song);
            }
        }
        return playable;
    }

    private void scheduleDeferredLocalReconciliation(List<Song> initialSongs, long viewRevision) {
        List<Song> snapshot = initialSongs == null ? List.of() : new ArrayList<>(initialSongs);
        CompletableFuture
                .supplyAsync(
                        () -> localState.reconcileLocalSongsBeforeRender(snapshot),
                        LOCAL_RECONCILIATION_IO
                )
                .thenAccept(reconciled -> Platform.runLater(() -> {
                    if (!context.isViewRevisionCurrent(viewRevision)
                            || reconciled == null
                            || context.getMasterSongList().isEmpty()) {
                        return;
                    }

                    List<Song> orderedReconciled = orderAlbumSongs(reconciled, activeContentType);
                    List<Song> current = new ArrayList<>(context.getMasterSongList());
                    if (current.size() == orderedReconciled.size()) {
                        context.setMasterSongList(orderedReconciled);
                    } else {
                        mergeReconciledLocalState(current, orderedReconciled);
                        context.setMasterSongList(orderAlbumSongs(current, activeContentType));
                    }

                    visibleHydrationRequested.clear();
                    localState.rebuildCurrentPlayableListFromMaster();
                    applySearchPredicate(searchSongField == null ? "" : searchSongField.getText());
                    refreshLists();
                    updateSongCountLabel();
                    adjustListHeight(getPrimarySongListView());
                }))
                .exceptionally(ignored -> null);
    }

    private List<Song> orderAlbumSongs(List<Song> songs, PlayerMenuContext.ContentType type) {
        return type == PlayerMenuContext.ContentType.ALBUM
                ? PlayerMenuAlbumTrackOrder.order(songs)
                : songs == null ? List.of() : songs;
    }

    private void mergeReconciledLocalState(List<Song> current, List<Song> reconciled) {
        if (current == null || reconciled == null) return;

        java.util.Map<Long, Song> byId = new java.util.HashMap<>();
        for (Song song : reconciled) {
            if (song != null && song.getSongID() > 0) {
                byId.putIfAbsent(song.getSongID(), song);
            }
        }

        for (int index = 0; index < current.size(); index++) {
            Song currentSong = current.get(index);
            Song resolved = currentSong != null && currentSong.getSongID() > 0
                    ? byId.get(currentSong.getSongID())
                    : index < reconciled.size() ? reconciled.get(index) : null;
            if (resolved != null && resolved.isLocal()
                    && localState.hasUsableAudioFile(resolved.getFilePath())) {
                current.set(index, resolved);
            }
        }
    }

    private void scheduleListRefresh() {
        if (!listRefreshQueued.compareAndSet(false, true)) return;
        Platform.runLater(() -> {
            try {
                refreshLists();
            } finally {
                listRefreshQueued.set(false);
            }
        });
    }

    private void hardRefreshSongListView(ListView<Song> listView) {
        if (listView == null) return;
        try {
            int selectedIndex = listView.getSelectionModel().getSelectedIndex();
            Song selectedItem = listView.getSelectionModel().getSelectedItem();
            listView.setCellFactory(null);
            listView.setCellFactory(songCellFactory == null ? cellFactory.callback() : songCellFactory);
            listView.refresh();
            listView.layout();
            if (selectedItem != null && listView.getItems().contains(selectedItem)) {
                listView.getSelectionModel().select(selectedItem);
            } else if (selectedIndex >= 0 && selectedIndex < listView.getItems().size()) {
                listView.getSelectionModel().select(selectedIndex);
            }
        } catch (Exception ignored) {
            try { listView.refresh(); } catch (Exception ignoredAgain) { }
        }
    }

    private Song replaceWithObservableLocalSong(Song downloadedSong, File finalFile) {
        if (downloadedSong == null
                || finalFile == null
                || context.getMasterSongList() == null
                || context.getMasterSongList().isEmpty()) return null;
        Song firstReplacement = null;
        for (int index = 0; index < context.getMasterSongList().size(); index++) {
            Song existing = context.getMasterSongList().get(index);
            if (!localState.sameSongForImmediateRefresh(existing, downloadedSong)) continue;
            Song replacement = createLocalViewSong(existing, downloadedSong, finalFile.getAbsolutePath());
            context.getMasterSongList().set(index, replacement);
            if (firstReplacement == null) {
                firstReplacement = replacement;
            }
        }
        return firstReplacement;
    }

    private Song createLocalViewSong(Song existing, Song downloadedSong, String localPath) {
        long songId = existing != null && existing.getSongID() > 0
                ? existing.getSongID() : downloadedSong.getSongID();
        String title = existing != null && existing.getTitle() != null && !existing.getTitle().isBlank()
                ? existing.getTitle() : downloadedSong.getTitle();
        List<Artist> artists;
        if (existing != null && hasRenderableArtists(existing.getArtist())) {
            artists = new ArrayList<>(existing.getArtist());
        } else if (hasRenderableArtists(downloadedSong.getArtist())) {
            artists = new ArrayList<>(downloadedSong.getArtist());
        } else {
            artists = new ArrayList<>();
        }
        Album album = existing != null && existing.getAlbum() != null
                ? existing.getAlbum() : downloadedSong.getAlbum();
        int trackOrder = existing != null && existing.getTrackOrder() > 0
                ? existing.getTrackOrder() : downloadedSong.getTrackOrder();
        return new Song(songId, title, artists, album, localPath, trackOrder, true);
    }

    private void replaceSongInActivePlaylist(Song downloadedSong, String localPath) {
        if (downloadedSong == null || localPath == null || localPath.isBlank()) return;
        Playlist activeModel = context.getCurrentPlaylistModel();
        if (activeModel == null || activeModel.getSongList() == null) return;
        List<Song> sourceSongs = activeModel.getSongList();
        for (int index = 0; index < sourceSongs.size(); index++) {
            Song existing = sourceSongs.get(index);
            if (localState.sameSongForImmediateRefresh(existing, downloadedSong)) {
                sourceSongs.set(index, createLocalViewSong(existing, downloadedSong, localPath));
            }
        }
    }

    private void refreshVisibleCellForSong(ListView<Song> listView, Song song) {
        cellFactory.refreshVisibleCellForSong(listView, song);
    }

    private void refreshLists() {
        if (songsToPlayView != null) songsToPlayView.refresh();
        if (songListView != null && songListView != songsToPlayView) songListView.refresh();
    }

    private void updateSongCountLabel() {
        if (songCountLabel == null) return;
        int total = context.getMasterSongList() == null ? 0 : context.getMasterSongList().size();
        songCountLabel.setText(total + (total == 1 ? " song" : " songs"));
    }

    private ListView<Song> getPrimarySongListView() {
        return songsToPlayView != null ? songsToPlayView : songListView;
    }

    private void setListHeight(ListView<?> listView, double height) {
        if (listView == null) return;
        setRegionHeight(listView, height);
    }

    private void setRegionHeight(javafx.scene.layout.Region region, double height) {
        if (region == null) return;
        if (Math.abs(region.getMinHeight() - height) > 0.01) region.setMinHeight(height);
        if (Math.abs(region.getPrefHeight() - height) > 0.01) region.setPrefHeight(height);
        if (Math.abs(region.getMaxHeight() - height) > 0.01) region.setMaxHeight(height);
    }

    private double boundedListHeight(int count, double cellHeight) {
        if (count <= 0) return LIST_MIN_HEIGHT;
        if (count == 1) return virtualShellHeight(count, cellHeight);
        return Math.min(
                LIST_MAX_HEIGHT,
                Math.max(LIST_MIN_HEIGHT, Math.min(count, VIRTUALIZED_VISIBLE_ROWS) * cellHeight + LIST_BOTTOM_GAP)
        );
    }

    private double virtualShellHeight(int count, double cellHeight) {
        if (count <= 0) return LIST_MIN_HEIGHT;
        if (count == 1) return Math.max(58.0, cellHeight + SINGLE_ROW_VERTICAL_PADDING);
        return Math.max(LIST_MIN_HEIGHT, count * cellHeight + LIST_BOTTOM_GAP);
    }

    private void updateVirtualWindow(ListView<?> listView) {
        if (listView == null || listView != getPrimarySongListView()) return;
        int count = listView.getItems() == null ? 0 : listView.getItems().size();
        double cellHeight = listView.getFixedCellSize() > 0 ? listView.getFixedCellSize() : 66.0;
        double virtualHeight = count == 0 ? LIST_MIN_HEIGHT : virtualShellHeight(count, cellHeight);
        double viewportHeight = count == 0
                ? LIST_MIN_HEIGHT
                : alignedListViewportHeight(count, cellHeight, virtualHeight);
        updateVirtualWindow(listView, count, cellHeight, virtualHeight, viewportHeight);
    }

    /**
     * Keeps the virtual ListView viewport aligned to complete fixed-size rows.
     * Without this, the outer scroll can stop with the final cell partially
     * clipped even though the virtual shell has reached its end.
     */
    private double alignedListViewportHeight(int count,
                                             double cellHeight,
                                             double virtualHeight) {
        if (count <= 1 || cellHeight <= 0) {
            return Math.min(virtualHeight, boundedListHeight(count, cellHeight));
        }

        double boundedHeight = boundedListHeight(count, cellHeight);
        double completeRowsHeight = Math.ceil(boundedHeight / cellHeight) * cellHeight;
        return Math.min(virtualHeight, completeRowsHeight);
    }

    private void updateVirtualWindow(ListView<?> listView,
                                     int count,
                                     double cellHeight,
                                     double virtualHeight,
                                     double viewportHeight) {
        if (listView == null || songListVirtualShell == null || count <= 0
                || screenScrollPane == null || screenScrollPane.getContent() == null) {
            if (listView != null) {
                listView.setTranslateY(0);
                if (virtualWindowStart != 0) {
                    virtualWindowStart = 0;
                    listView.scrollTo(0);
                }
            }
            return;
        }

        double shellTop = songListVirtualShell.getBoundsInParent().getMinY();
        double scrollTop = currentScrollTop();
        double maxOffset = Math.max(0, virtualHeight - viewportHeight);
        double offset = clamp(scrollTop - shellTop, 0, maxOffset);
        int maxStart = Math.max(0, count - Math.max(1, (int) Math.ceil(viewportHeight / cellHeight)));
        int firstRow = Math.min(maxStart, Math.max(0, (int) Math.floor(offset / cellHeight)));

        double translateY = firstRow * cellHeight;
        if (Math.abs(listView.getTranslateY() - translateY) > 0.01) {
            listView.setTranslateY(translateY);
        }
        if (virtualWindowStart != firstRow) {
            virtualWindowStart = firstRow;
            listView.scrollTo(firstRow);
        }
    }

    private void routeListScrollToOuterScroll(ScrollEvent event) {
        if (event == null || screenScrollPane == null || screenScrollPane.getContent() == null) return;
        Node content = screenScrollPane.getContent();
        double contentHeight = content.getBoundsInLocal().getHeight();
        double viewportHeight = screenScrollPane.getViewportBounds().getHeight();
        double maxScroll = Math.max(0, contentHeight - viewportHeight);
        if (maxScroll <= 0) return;
        double current = screenScrollPane.getVvalue() * maxScroll;
        double next = clamp(current - event.getDeltaY(), 0, maxScroll);
        screenScrollPane.setVvalue(next / maxScroll);
        event.consume();
    }

    private void scrollOuterByPixels(double delta) {
        if (screenScrollPane == null || screenScrollPane.getContent() == null || delta == 0.0) return;

        Node content = screenScrollPane.getContent();
        double contentHeight = content.getBoundsInLocal().getHeight();
        double viewportHeight = screenScrollPane.getViewportBounds().getHeight();
        double maxScroll = Math.max(0.0, contentHeight - viewportHeight);
        if (maxScroll <= 0.0) return;

        double current = screenScrollPane.getVvalue() * maxScroll;
        double next = clamp(current + delta, 0.0, maxScroll);
        if (Math.abs(next - current) < 0.01) return;

        screenScrollPane.setVvalue(next / maxScroll);
        adjustListHeight(getPrimarySongListView());
    }

    private void scrollOuterToSelection(ListView<Song> listView, int selectedIndex) {
        if (listView == null || selectedIndex < 0) return;
        if (screenScrollPane == null || screenScrollPane.getContent() == null || songListVirtualShell == null) {
            listView.scrollTo(selectedIndex);
            return;
        }
        double viewportHeight = screenScrollPane.getViewportBounds().getHeight();
        double contentHeight = screenScrollPane.getContent().getBoundsInLocal().getHeight();
        double maxScroll = Math.max(0, contentHeight - viewportHeight);
        if (maxScroll <= 0) {
            listView.scrollTo(selectedIndex);
            return;
        }
        double cellHeight = listView.getFixedCellSize() > 0 ? listView.getFixedCellSize() : 66;
        double itemTop = songListVirtualShell.getBoundsInParent().getMinY() + selectedIndex * cellHeight;
        double itemBottom = itemTop + cellHeight;
        double currentTop = currentScrollTop();
        double currentBottom = currentTop + viewportHeight;
        double padding = Math.min(56.0, Math.max(14.0, cellHeight * 0.40));
        double nextTop = currentTop;
        if (itemTop < currentTop + padding) nextTop = itemTop - padding;
        else if (itemBottom > currentBottom - padding) nextTop = itemBottom - viewportHeight + padding;
        screenScrollPane.setVvalue(clamp(nextTop, 0, maxScroll) / maxScroll);
        adjustListHeight(listView);
    }

    private double currentScrollTop() {
        if (screenScrollPane == null || screenScrollPane.getContent() == null) return 0;
        double contentHeight = screenScrollPane.getContent().getBoundsInLocal().getHeight();
        double viewportHeight = screenScrollPane.getViewportBounds().getHeight();
        double maxScroll = Math.max(0, contentHeight - viewportHeight);
        return screenScrollPane.getVvalue() * maxScroll;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String activePlaylistDisplayName() {
        String title = activePlaylist == null ? "" : Optional.ofNullable(activePlaylist.getTitle()).orElse("").trim();
        if (!title.isBlank()) return title;
        return switch (activeContentType == null ? PlayerMenuContext.ContentType.PLAYLIST : activeContentType) {
            case ALBUM, EPISODE -> "this album";
            case SINGLE -> "this single";
            case PLAYLIST -> "this playlist";
        };
    }

    private boolean hasRenderableArtists(List<Artist> artists) {
        if (artists == null || artists.isEmpty()) return false;
        return artists.stream()
                .filter(Objects::nonNull)
                .map(Artist::getName)
                .filter(Objects::nonNull)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .anyMatch(value -> !value.isBlank()
                        && !value.equals("unknown")
                        && !value.equals("unknown artist"));
    }
}
