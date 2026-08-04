package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.footer;

import io.github.guillermodubon.musicplayer.models.ManifestEntry;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.services.PlaylistDialogService;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext.ContentType;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.managers.PlayerMenuPlaybackBridge;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.manifest.ManifestService;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.utils.LocalSongVerifier;
import io.github.guillermodubon.musicplayer.utils.MusicCardHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Owns local-library recommendations and playlist mutations. */
public final class PlayerMenuPlaylistLocalRecommendations {

    private static final double EMPTY_LIST_HEIGHT = 118.0;
    private static final String LOADING_REMOTE_PLAYLIST_AUTHOR = "__LOADING_REMOTE_PLAYLIST__";

    private final PlayerMenuContext context;
    private final StartUpService svc;
    private final PlaybackManager playbackManager;
    private final PlayerMenuPlaybackBridge playbackBridge;
    private final PlaylistDialogService playlistDialogService = new PlaylistDialogService();
    private final ExecutorService ioPool = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "player-menu-playlist-recommendations-io");
        thread.setDaemon(true);
        return thread;
    });

    private VBox recContainer;
    private Label recTitleLabel;
    private Button addAllButton;
    private ListView<Song> recList;
    private TextField searchField;
    private VBox footerPane;
    private Runnable refreshSongListView = () -> {};
    private Runnable refreshQueue = () -> {};
    private Runnable refreshPlaybackContext = () -> {};

    private final List<Song> baseList = new ArrayList<>();
    private final List<Song> pickedList = new ArrayList<>();
    private final ObservableList<Song> displayedList = FXCollections.observableArrayList();
    private boolean visibilityListenerInstalled;
    private final AtomicBoolean addAllInProgress = new AtomicBoolean(false);
    private ObservableList<Song> observedPlaylistSongs;
    private ListChangeListener<Song> observedPlaylistSongsListener;
    private boolean updatingPlaylistFromRecommendation;

    public PlayerMenuPlaylistLocalRecommendations(PlayerMenuContext context,
                                                  StartUpService svc,
                                                  PlaybackManager playbackManager,
                                                  PlayerMenuPlaybackBridge playbackBridge,
                                                  VBox footerPane) {
        this.context = context;
        this.svc = svc;
        this.playbackManager = playbackManager == null
                ? PlaybackManager.getInstance() : playbackManager;
        this.playbackBridge = playbackBridge;
        this.footerPane = footerPane;
    }

    public void bindUi(VBox recContainer,
                       Label recTitleLabel,
                       Button addAllButton,
                       ListView<Song> recList,
                       TextField searchField,
                       Button refreshButton,
                       VBox footerPane) {
        this.recContainer = recContainer;
        this.recTitleLabel = recTitleLabel;
        this.addAllButton = addAllButton;
        this.recList = recList;
        this.searchField = searchField;
        this.footerPane = footerPane;

        if (recList != null) {
            recList.setItems(displayedList);
            PlayerMenuPlaylistRecommendationRenderer.configureList(
                    recList,
                    svc,
                    this::handleRecommendationPlay,
                    this::handleRecommendationAdd
            );
        }
        if (refreshButton != null) {
            PlayerMenuPlaylistRecommendationRenderer.configureRefreshButton(refreshButton, () -> {
                populateRecommendations();
                if (this.searchField != null) this.searchField.clear();
            });
        }
        if (searchField != null) {
            searchField.textProperty().addListener((obs, oldValue, text) -> filterRecommendations(text));
        }
        if (addAllButton != null) {
            addAllButton.setOnAction(event -> handleAddAllRecommendations());
        }
        if (!visibilityListenerInstalled) {
            visibilityListenerInstalled = true;
            displayedList.addListener((ListChangeListener<Song>) change -> updateLocalRecommendationVisibility());
        }
        updateAddAllButtonState();
    }

    public void bindCallbacks(Runnable refreshSongListView,
                              Runnable refreshQueue,
                              Runnable refreshPlaybackContext) {
        if (refreshSongListView != null) this.refreshSongListView = refreshSongListView;
        if (refreshQueue != null) this.refreshQueue = refreshQueue;
        if (refreshPlaybackContext != null) this.refreshPlaybackContext = refreshPlaybackContext;
    }

    public ObservableList<Song> displayedRecommendations() {
        return displayedList;
    }

    public void refreshPlaybackIndicators() {
        Platform.runLater(() -> {
            if (recList != null) recList.refresh();
        });
    }

    public void onLocalSongUnavailable(Song song) {
        if (song == null) return;
        Runnable update = () -> {
            baseList.removeIf(candidate -> matchesSong(candidate, song));
            pickedList.removeIf(candidate -> matchesSong(candidate, song));
            displayedList.removeIf(candidate -> matchesSong(candidate, song));
            populateRecommendations();
        };
        if (Platform.isFxApplicationThread()) update.run();
        else Platform.runLater(update);
    }

    public void clear() {
        detachPlaylistSongsListener();
        baseList.clear();
        pickedList.clear();
        displayedList.clear();
        adjustListHeight(recList);
        if (recContainer != null) {
            recContainer.setVisible(false);
            recContainer.setManaged(false);
        }
        updateAddAllButtonState();
    }

    public void prepareLocalPlaylist(Playlist playlist) {
        if (playlist == null) {
            clear();
            return;
        }
        attachPlaylistSongsListener(playlist);
        updateRecommendationTitle(playlist);
        populateRecommendations();
    }

    public void populateRecommendations() {
        if (svc == null || context.getCurrentPlaylistModel() == null
                || context.getCurrentContentTypeInView() != ContentType.PLAYLIST
                || isRemotePlaylistView(context.getCurrentPlaylistModel())) {
            clear();
            if (footerPane != null && context.getCurrentContentTypeInView() == ContentType.PLAYLIST) {
                footerPane.setVisible(false);
                footerPane.setManaged(false);
            }
            return;
        }

        attachPlaylistSongsListener(context.getCurrentPlaylistModel());
        List<Song> alreadyIn = context.getCurrentPlaylistModel().getSongList() == null
                ? List.of() : context.getCurrentPlaylistModel().getSongList();
        List<Song> sourceSongs = svc.getSongs() == null ? List.of() : svc.getSongs();
        Map<String, ManifestEntry> manifest = LocalSongVerifier.loadManifest(svc);
        List<Song> candidates = LocalSongVerifier.verifiedPlayableLocalSongs(sourceSongs, svc, manifest)
                .stream()
                .filter(song -> !alreadyIn.contains(song))
                .collect(Collectors.toList());
        Collections.shuffle(candidates);

        baseList.clear();
        baseList.addAll(candidates);
        pickedList.clear();
        displayedList.clear();
        while (pickedList.size() < 10 && !baseList.isEmpty()) {
            Song next = baseList.remove(0);
            pickedList.add(next);
            displayedList.add(next);
        }
        adjustListHeight(recList);
        updateLocalRecommendationVisibility();
        updateAddAllButtonState();
    }

    public boolean isLocalUserPlaylistView() {
        Playlist playlist = context.getCurrentPlaylistModel();
        return context.getCurrentContentTypeInView() == ContentType.PLAYLIST
                && playlist != null
                && !isLoadingRemotePlaylist(playlist)
                && !isRemotePlaylistView(playlist);
    }

    public boolean hasLocalRecommendationCandidates() {
        return !pickedList.isEmpty() || !displayedList.isEmpty();
    }

    private void handleRecommendationPlay(Song song) {
        if (song == null) return;
        playbackManager.setOriginSource("Recommendations");
        if (playbackBridge != null) playbackBridge.ensurePlayerMenuBarLoaded();

        List<Song> recommendations = new ArrayList<>(displayedList);
        int index = indexOfSongById(recommendations, song);
        if (index < 0) return;

        List<Song> savedQueue = new ArrayList<>(playbackManager.getQueue());
        playbackManager.playSongs(recommendations, index, -1L, ContentType.PLAYLIST);
        savedQueue.forEach(playbackManager::enqueue);
        refreshQueue.run();
        refreshPlaybackContext.run();
    }

    private void handleRecommendationAdd(Song song) {
        if (song == null || context.getCurrentPlaylistModel() == null || svc == null) return;
        Playlist model = context.getCurrentPlaylistModel();
        if (model.getSongList() == null) model.setSongList(FXCollections.observableArrayList());

        if (!containsSong(model.getSongList(), song)) {
            song.setTrackOrder(model.getSongList().size());
            updatingPlaylistFromRecommendation = true;
            try {
                model.getSongList().add(song);
            } finally {
                updatingPlaylistFromRecommendation = false;
            }
            try {
                playlistDialogService.addSongToPlaylist(model.getId(), song);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        PlaybackManager manager = PlaybackManager.getInstance();
        boolean viewMatch = context.getCurrentContentTypeInView() == ContentType.PLAYLIST
                && context.getCurrentPlaylistInViewId() == model.getId();
        boolean playingMatch = manager.getCurrentContentTypePlaying() == ContentType.PLAYLIST
                && manager.getCurrentPlaylistPlayingId() == model.getId();

        if (viewMatch) {
            if (!containsSong(context.getMasterSongList(), song)) context.getMasterSongList().add(song);
            if (song.isLocal() && !containsSong(context.getCurrentSongList(), song)) {
                context.getCurrentSongList().add(song);
            }
        }
        if (playingMatch) {
            manager.addSongToCurrentPlaylist(song);
            List<Song> source = manager.getSourceSongList();
            if (!manager.isRandomMode()) {
                for (int i = 0; i < source.size(); i++) source.get(i).setTrackOrder(i);
            } else {
                Collections.shuffle(source);
                for (int i = 0; i < source.size(); i++) source.get(i).setTrackOrder(i);
            }
            manager.setCurrentSongList(new ArrayList<>(source));
        }

        populateRecommendations();
        refreshSongListView.run();
        refreshPlaybackContext.run();
        refreshQueue.run();
    }

    private void handleAddAllRecommendations() {
        if (!isLocalUserPlaylistView() || svc == null
                || !addAllInProgress.compareAndSet(false, true)) return;

        Playlist playlist = context.getCurrentPlaylistModel();
        if (playlist == null || playlist.getId() <= 0) {
            addAllInProgress.set(false);
            updateAddAllButtonState();
            return;
        }

        List<Song> librarySnapshot = MusicCardHelper.snapshot(svc.getSongs());
        List<Song> playlistSnapshot = playlist.getSongList() == null
                ? List.of() : new ArrayList<>(playlist.getSongList());
        updateAddAllButtonState();
        CompletableFuture
                .supplyAsync(() -> addAllLocalSongs(playlist, librarySnapshot, playlistSnapshot), ioPool)
                .thenAccept(result -> Platform.runLater(() -> finishAddAllRecommendations(result)));
    }

    private PlayerMenuPlaylistFooterModels.BulkAddResult addAllLocalSongs(
            Playlist playlist, List<Song> librarySnapshot, List<Song> playlistSnapshot) {
        try {
            Map<String, ManifestEntry> manifest = LocalSongVerifier.loadManifest(svc);
            List<Song> candidates = LocalSongVerifier.verifiedPlayableLocalSongs(
                            librarySnapshot, svc, manifest)
                    .stream()
                    .filter(song -> !containsSong(playlistSnapshot, song))
                    .collect(Collectors.toList());
            if (!candidates.isEmpty()) playlistDialogService.addSongsToPlaylist(playlist.getId(), candidates);
            return new PlayerMenuPlaylistFooterModels.BulkAddResult(playlist, candidates, null);
        } catch (Throwable error) {
            return new PlayerMenuPlaylistFooterModels.BulkAddResult(playlist, List.of(), error);
        }
    }

    private void finishAddAllRecommendations(PlayerMenuPlaylistFooterModels.BulkAddResult result) {
        addAllInProgress.set(false);
        if (result == null || result.error() != null) {
            if (result != null && result.error() != null) result.error().printStackTrace();
            updateAddAllButtonState();
            return;
        }

        Playlist playlist = result.playlist();
        if (playlist == null) {
            updateAddAllButtonState();
            return;
        }
        if (playlist.getSongList() == null) playlist.setSongList(FXCollections.observableArrayList());
        List<Song> added = result.songs().stream()
                .filter(song -> !containsSong(playlist.getSongList(), song))
                .collect(Collectors.toList());
        if (!added.isEmpty()) {
            updatingPlaylistFromRecommendation = true;
            try {
                playlist.getSongList().addAll(added);
            } finally {
                updatingPlaylistFromRecommendation = false;
            }
        }

        boolean current = context.getCurrentContentTypeInView() == ContentType.PLAYLIST
                && context.getCurrentPlaylistModel() == playlist
                && context.getCurrentPlaylistInViewId() == playlist.getId();
        if (current) {
            for (Song song : added) {
                if (!containsSong(context.getMasterSongList(), song)) context.getMasterSongList().add(song);
                if (!containsSong(context.getCurrentSongList(), song)) context.getCurrentSongList().add(song);
            }
            populateRecommendations();
            refreshSongListView.run();
            refreshPlaybackContext.run();
            refreshQueue.run();
        }
        updateAddAllButtonState();
    }

    private void updateRecommendationTitle(Playlist playlist) {
        if (recTitleLabel == null) return;
        String title = playlist == null || playlist.getTitle() == null
                ? "" : playlist.getTitle().trim();
        recTitleLabel.setText("Add songs from your library to "
                + (title.isBlank() ? "this playlist" : title));
    }

    private void filterRecommendations(String text) {
        if (svc == null || context.getCurrentPlaylistModel() == null) {
            displayedList.setAll(pickedList);
            adjustListHeight(recList);
            return;
        }
        if (text == null || text.isBlank()) {
            displayedList.setAll(pickedList);
        } else {
            String lower = text.toLowerCase(Locale.ROOT);
            List<Song> currentSongs = context.getCurrentPlaylistModel().getSongList() == null
                    ? List.of() : context.getCurrentPlaylistModel().getSongList();
            Set<Song> alreadyIn = new HashSet<>(currentSongs);
            Map<String, ManifestEntry> manifest = LocalSongVerifier.loadManifest(svc);
            List<Song> results = LocalSongVerifier.verifiedPlayableLocalSongs(
                            svc.getSongs() == null ? List.of() : svc.getSongs(), svc, manifest)
                    .stream()
                    .filter(song -> !alreadyIn.contains(song))
                    .filter(song -> songMatches(song, lower))
                    .distinct()
                    .limit(10)
                    .collect(Collectors.toList());
            displayedList.setAll(results);
        }
        adjustListHeight(recList);
        updateSearchResultsPlaceholder(text);
        updateLocalRecommendationVisibility();
    }

    private boolean songMatches(Song song, String query) {
        if (song.getTitle() != null && song.getTitle().toLowerCase(Locale.ROOT).contains(query)) return true;
        if (song.getAlbum() != null && song.getAlbum().getName() != null
                && song.getAlbum().getName().toLowerCase(Locale.ROOT).contains(query)) return true;
        if (song.getArtist() != null) {
            for (Artist artist : song.getArtist()) {
                if (artist != null && artist.getName() != null
                        && artist.getName().toLowerCase(Locale.ROOT).contains(query)) return true;
            }
        }
        return false;
    }

    private void updateSearchResultsPlaceholder(String query) {
        if (recList == null) return;
        String normalized = query == null ? "" : query.trim();
        if (normalized.isEmpty() || !displayedList.isEmpty()) {
            recList.setPlaceholder(null);
            return;
        }
        Label placeholder = new Label("No results found for \"" + normalized + "\" in your library");
        placeholder.getStyleClass().add("player-menu-empty-placeholder");
        placeholder.setWrapText(true);
        placeholder.setMaxWidth(Double.MAX_VALUE);
        placeholder.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        recList.setPlaceholder(placeholder);
    }

    public void adjustListHeight(ListView<?> listView) {
        if (listView == null) return;
        double cell = listView.getFixedCellSize() > 0 ? listView.getFixedCellSize() : 24;
        int count = listView.getItems() == null ? 0 : listView.getItems().size();
        double height = count * cell + 2;
        if (listView == recList && hasRecommendationSearchNoResults()) height = EMPTY_LIST_HEIGHT;
        listView.setMinHeight(height);
        listView.setPrefHeight(height);
        listView.setMaxHeight(height);
    }

    private boolean hasRecommendationSearchNoResults() {
        return searchField != null && searchField.getText() != null
                && !searchField.getText().trim().isEmpty() && displayedList.isEmpty();
    }

    private void updateLocalRecommendationVisibility() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::updateLocalRecommendationVisibility);
            return;
        }
        if (!isLocalUserPlaylistView()) return;
        boolean visible = hasLocalRecommendationCandidates();
        if (recContainer != null) {
            recContainer.setVisible(visible);
            recContainer.setManaged(visible);
        }
        if (footerPane != null) {
            footerPane.setVisible(visible);
            footerPane.setManaged(visible);
        }
        updateAddAllButtonState();
    }

    private void updateAddAllButtonState() {
        if (addAllButton == null) return;
        boolean hasCandidates = !pickedList.isEmpty() || !baseList.isEmpty();
        addAllButton.setDisable(addAllInProgress.get() || !isLocalUserPlaylistView() || !hasCandidates);
    }

    private void attachPlaylistSongsListener(Playlist playlist) {
        if (playlist == null || playlist.getSongList() == null) {
            detachPlaylistSongsListener();
            return;
        }
        ObservableList<Song> songs = playlist.getSongList();
        if (observedPlaylistSongs == songs) return;
        detachPlaylistSongsListener();
        observedPlaylistSongs = songs;
        observedPlaylistSongsListener = change -> {
            if (updatingPlaylistFromRecommendation || !isLocalUserPlaylistView()) return;
            Platform.runLater(this::populateRecommendations);
        };
        observedPlaylistSongs.addListener(observedPlaylistSongsListener);
    }

    private void detachPlaylistSongsListener() {
        if (observedPlaylistSongs != null && observedPlaylistSongsListener != null) {
            try {
                observedPlaylistSongs.removeListener(observedPlaylistSongsListener);
            } catch (Exception ignored) {
            }
        }
        observedPlaylistSongs = null;
        observedPlaylistSongsListener = null;
    }

    public boolean isRemotePlaylistView(Playlist playlist) {
        if (playlist == null || svc == null) return false;
        long id = playlist.getId();
        String author = Objects.toString(playlist.getAuthorName(), "");
        boolean originalRemote = svc.getPlaylists().stream().noneMatch(item -> item.getId() == id);
        boolean savedFromDeezer = svc.getPlaylists().stream().anyMatch(item -> item.getId() == id)
                && !author.equalsIgnoreCase("User");
        return originalRemote || savedFromDeezer;
    }

    public boolean isLoadingRemotePlaylist(Playlist playlist) {
        return playlist != null && LOADING_REMOTE_PLAYLIST_AUTHOR.equals(playlist.getAuthorName());
    }

    private boolean matchesSong(Song left, Song right) {
        if (left == null || right == null) return false;
        if (left.getSongID() > 0 && right.getSongID() > 0) return left.getSongID() == right.getSongID();
        return Objects.equals(left.getTitle(), right.getTitle());
    }

    private int indexOfSongById(List<Song> songs, Song target) {
        if (songs == null || target == null) return -1;
        long id = target.getSongID();
        for (int i = 0; i < songs.size(); i++) {
            Song song = songs.get(i);
            if (song != null && song.getSongID() == id) return i;
        }
        return songs.indexOf(target);
    }

    private boolean containsSong(List<Song> songs, Song target) {
        return indexOfSongById(songs, target) >= 0;
    }
}


