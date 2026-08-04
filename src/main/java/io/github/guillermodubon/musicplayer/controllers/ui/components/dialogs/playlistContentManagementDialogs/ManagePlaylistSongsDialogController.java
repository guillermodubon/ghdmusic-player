package io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistContentManagementDialogs;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Window;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.base.BaseDialogController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.base.DialogShellController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistContentManagementDialogs.common.ManagePlaylistSongsContentController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.icons.SvgIconFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.PlaylistMembershipItemController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane.QueueController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.PlayerMenuController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.services.PlaylistDialogService;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class ManagePlaylistSongsDialogController extends BaseDialogController {

    private static final String ICON_CREATE_PLAYLIST = "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/add_box_26dp_FAFAFA_FILL0_wght400_GRAD0_opsz24.svg";

    @FXML private DialogShellController shellController;

    private final PlaylistDialogService playlistDialogService = new PlaylistDialogService();

    private ManagePlaylistSongsContentController contentController;
    private ObservableList<Playlist> userPlaylists = FXCollections.observableArrayList();

    private Song song;
    private List<Song> bulkSongs = List.of();
    private String bulkCollectionTitle = "";
    private boolean bulkMode;
    private long activePlaylistId;
    private Button closeButton;
    private boolean suppressMembershipToggle;
    private Consumer<Consumer<Playlist>> createPlaylistLauncher = onCreated -> {};

    public void init(StartUpService svc, Song song, long activePlaylistId) {
        initBase(svc, null);
        this.song = Objects.requireNonNull(song, "song");
        this.bulkMode = false;
        this.bulkSongs = List.of();
        this.bulkCollectionTitle = "";
        this.activePlaylistId = activePlaylistId;

        shellController.setTitle("Manage the song list of your playlists");
        shellController.setSubtitle("");
        shellController.setSubtitleContent(createSubtitle());

        loadContent();
        loadUserPlaylists();
        bindSearch();

        closeButton = new Button("Close");
        closeButton.getStyleClass().addAll("dialog-button", "secondary-button");
        closeButton.setOnAction(e -> closePopoverOrDialog());
        shellController.setActions(closeButton);
    }

    public void initBulk(StartUpService svc, List<Song> songs, long activePlaylistId, String collectionTitle) {
        initBase(svc, null);
        this.song = null;
        this.bulkSongs = sanitizeBulkSongs(songs);
        this.bulkCollectionTitle = normalize(collectionTitle).isBlank() ? "this collection" : normalize(collectionTitle);
        this.activePlaylistId = activePlaylistId;
        this.bulkMode = true;

        shellController.setTitle("Manage the song list of your playlists");
        shellController.setSubtitle("");
        shellController.setSubtitleContent(createSubtitle());

        loadContent();
        loadUserPlaylists();
        bindSearch();

        closeButton = new Button("Close");
        closeButton.getStyleClass().addAll("dialog-button", "secondary-button");
        closeButton.setOnAction(e -> closePopoverOrDialog());
        shellController.setActions(closeButton);
    }

    public void setCreatePlaylistLauncher(Consumer<Consumer<Playlist>> createPlaylistLauncher) {
        this.createPlaylistLauncher = createPlaylistLauncher == null ? onCreated -> {} : createPlaylistLauncher;
    }

    private void loadContent() {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/io/github/guillermodubon/musicplayer/Views/components/dialogs/playlistContentManagementDialogs/common/ManagePlaylistSongsContent.fxml")
            );
            Node root = loader.load();
            contentController = loader.getController();
            shellController.setContent(root);
        } catch (IOException ex) {
            throw new RuntimeException("Could not load playlist management dialog content.", ex);
        }
    }

    private void loadUserPlaylists() {
        List<Playlist> filtered = svc.getPlaylists().stream()
                .filter(p -> "User".equalsIgnoreCase(Optional.ofNullable(p.getAuthorName()).orElse("")))
                .collect(Collectors.toList());

        userPlaylists = FXCollections.observableArrayList(filtered);
        renderList(userPlaylists);
    }

    private void bindSearch() {
        contentController.searchField.textProperty().addListener((obs, oldText, newText) -> {
            String text = normalize(newText).toLowerCase();
            if (text.isBlank()) {
                renderList(userPlaylists);
                return;
            }

            List<Playlist> match = userPlaylists.stream()
                    .filter(p -> normalize(p.getTitle()).toLowerCase().contains(text))
                    .collect(Collectors.toList());

            renderList(FXCollections.observableArrayList(match));
        });
    }

    private boolean playlistContainsSong(Playlist playlist, Song target) {
        if (playlist == null || playlist.getSongList() == null || target == null) return false;

        long targetId = target.getSongID();
        if (targetId > 0) {
            return playlist.getSongList().stream().anyMatch(s -> s != null && s.getSongID() == targetId);
        }

        String targetTitle = normalize(target.getTitle()).toLowerCase();
        return playlist.getSongList().stream().anyMatch(s ->
                s != null && normalize(s.getTitle()).toLowerCase().equals(targetTitle)
        );
    }

    private boolean playlistContainsAllSongs(Playlist playlist, List<Song> targets) {
        if (playlist == null || targets == null || targets.isEmpty()) return false;
        for (Song target : targets) {
            if (!playlistContainsSong(playlist, target)) return false;
        }
        return true;
    }

    private void renderList(ObservableList<Playlist> list) {
        contentController.listContainer.getChildren().clear();
        boolean userHasNoPlaylists = userPlaylists == null || userPlaylists.isEmpty();
        updateEmptyState(userHasNoPlaylists);
        if (userHasNoPlaylists || list == null || list.isEmpty()) return;

        for (Playlist playlist : list) {
            contentController.listContainer.getChildren().add(createRow(playlist));
        }
    }

    private void updateEmptyState(boolean empty) {
        if (contentController == null) return;

        if (contentController.playlistScrollPane != null) {
            contentController.playlistScrollPane.setVisible(!empty);
            contentController.playlistScrollPane.setManaged(!empty);
        }

        if (contentController.searchBox != null) {
            contentController.searchBox.setVisible(!empty);
            contentController.searchBox.setManaged(!empty);
        }

        if (contentController.emptyStateBox != null) {
            if (contentController.emptyStateBox.getChildren().isEmpty()) {
                contentController.emptyStateBox.getChildren().setAll(createEmptyMessage(), createCreatePlaylistLink());
            }
            contentController.emptyStateBox.setVisible(empty);
            contentController.emptyStateBox.setManaged(empty);
        }
    }

    private Node createRow(Playlist playlist) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(
                    "/io/github/guillermodubon/musicplayer/Views/components/items/playlistMembershipItem/PlaylistMembershipItem.fxml"
            ));
            Node row = loader.load();
            PlaylistMembershipItemController controller = loader.getController();
            controller.init(playlist, isPlaylistSelected(playlist), (oldSel, newSel) -> {
                if (suppressMembershipToggle) return;
                if (Objects.equals(oldSel, newSel)) return;
                if (bulkMode) {
                    onBulkMembershipToggle(playlist, controller, oldSel, newSel);
                } else {
                    onMembershipToggle(playlist, controller, oldSel, newSel);
                }
            });
            return row;
        } catch (IOException ex) {
            throw new RuntimeException("Could not load playlist membership item.", ex);
        }
    }

    private boolean isPlaylistSelected(Playlist playlist) {
        return bulkMode
                ? playlistContainsAllSongs(playlist, bulkSongs)
                : playlistContainsSong(playlist, song);
    }

    private void onBulkMembershipToggle(Playlist playlist,
                                        PlaylistMembershipItemController itemController,
                                        boolean oldSel,
                                        boolean newSel) {
        if (bulkSongs == null || bulkSongs.isEmpty()) {
            setSelectedSilently(itemController, oldSel);
            return;
        }

        itemController.setBusy(true);
        List<Song> snapshot = playlist.getSongList() == null
                ? new ArrayList<>()
                : new ArrayList<>(playlist.getSongList());

        applyBulkLocalChange(playlist, newSel);

        CompletableFuture.runAsync(() -> {
            try {
                if (newSel) {
                    playlistDialogService.addSongsToPlaylist(playlist.getId(), bulkSongs);
                } else {
                    playlistDialogService.removeSongsFromPlaylist(playlist.getId(), bulkSongs);
                }
            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        }).thenRun(() -> runFx(() -> {
            refreshBulkPlaybackAfterChange(playlist);
            itemController.setBusy(false);
        })).exceptionally(ex -> {
            runFx(() -> {
                playlist.getSongList().setAll(snapshot);
                setSelectedSilently(itemController, oldSel);
                itemController.setBusy(false);
                showError(resolveErrorMessage(ex));
            });
            return null;
        });
    }

    private void applyBulkLocalChange(Playlist playlist, boolean add) {
        if (playlist == null || playlist.getSongList() == null) return;

        if (add) {
            for (Song candidate : bulkSongs) {
                if (candidate != null && !playlistContainsSong(playlist, candidate)) {
                    playlist.getSongList().add(candidate);
                }
            }
            return;
        }

        Set<String> removeKeys = bulkSongs.stream()
                .filter(Objects::nonNull)
                .map(this::songMembershipKey)
                .filter(key -> !key.isBlank())
                .collect(Collectors.toCollection(HashSet::new));
        playlist.getSongList().removeIf(s -> s != null && removeKeys.contains(songMembershipKey(s)));
    }

    private void refreshBulkPlaybackAfterChange(Playlist playlist) {
        PlaybackManager mgr = PlaybackManager.getInstance();
        PlayerMenuController pmc = mgr.getMenuController();

        boolean viewMatch = pmc != null
                && pmc.getCurrentPlaylistModel() != null
                && pmc.getCurrentPlaylistModel().getId() == playlist.getId();

        boolean playingMatch = mgr.getCurrentContentTypePlaying() == PlayerMenuContext.ContentType.PLAYLIST
                && mgr.getCurrentPlaylistPlayingId() == playlist.getId();

        if (viewMatch && pmc != null) {
            pmc.refreshCurrentViewMinimal();
            pmc.refreshHeaderAndFooter();
        }
        if (playingMatch && pmc != null) {
            pmc.refreshPlaybackContext();
        }
        if (QueueController.getInstance() != null) {
            QueueController.getInstance().refreshAll();
        }
    }

    private void onMembershipToggle(Playlist playlist,
                                    PlaylistMembershipItemController itemController,
                                    boolean oldSel,
                                    boolean newSel) {
        itemController.setBusy(true);

        if (newSel) {
            if (!playlistContainsSong(playlist, song)) {
                playlist.getSongList().add(song);
            }
        } else {
            playlist.getSongList().removeIf(s -> s != null && s.getSongID() == song.getSongID());
        }

        CompletableFuture.runAsync(() -> {
            try {
                if (newSel) {
                    playlistDialogService.addSongToPlaylist(playlist.getId(), song);
                } else {
                    playlistDialogService.removeSongFromPlaylist(playlist.getId(), song.getSongID());
                }
            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        }).thenRun(() -> runFx(() -> {
            refreshPlaybackAfterChange(playlist, newSel);
            itemController.setBusy(false);
        })).exceptionally(ex -> {
            runFx(() -> {
                revertLocalChange(playlist, newSel);
                setSelectedSilently(itemController, oldSel);
                itemController.setBusy(false);
                showError(resolveErrorMessage(ex));
            });
            return null;
        });
    }

    private void revertLocalChange(Playlist playlist, boolean wasAdded) {
        if (wasAdded) {
            playlist.getSongList().removeIf(s -> s != null && s.getSongID() == song.getSongID());
        } else {
            if (playlist.getSongList().stream().noneMatch(s -> s != null && s.getSongID() == song.getSongID())) {
                playlist.getSongList().add(song);
            }
        }
    }

    private void setSelectedSilently(PlaylistMembershipItemController itemController, boolean selected) {
        if (itemController == null) return;
        suppressMembershipToggle = true;
        try {
            itemController.setSelectedSilently(selected);
        } finally {
            suppressMembershipToggle = false;
        }
    }

    private void refreshPlaybackAfterChange(Playlist playlist, boolean added) {
        PlaybackManager mgr = PlaybackManager.getInstance();
        PlayerMenuController pmc = mgr.getMenuController();

        boolean viewMatch = pmc != null
                && pmc.getCurrentPlaylistModel() != null
                && pmc.getCurrentPlaylistModel().getId() == playlist.getId();

        boolean playingMatch = mgr.getCurrentContentTypePlaying() == PlayerMenuContext.ContentType.PLAYLIST
                && mgr.getCurrentPlaylistPlayingId() == playlist.getId();

        if (!added) {
            if (viewMatch && pmc != null) {
                pmc.masterSongList.removeIf(s -> s.getSongID() == song.getSongID());
                pmc.currentSongList.removeIf(s -> s.getSongID() == song.getSongID());
                pmc.adjustListHeight(pmc.getSongsToPlayView());
                pmc.getSongsToPlayView().refresh();
            }

            if (playingMatch) {
                if (mgr.getCurrentSong() != null && mgr.getCurrentSong().getSongID() != song.getSongID()) {
                    mgr.removeFromQueue(song);
                }
                mgr.removeSongFromCurrentPlaylist(song);
                if (pmc != null) pmc.refreshPlaybackContext();
                if (QueueController.getInstance() != null) QueueController.getInstance().refreshAll();
            }
            return;
        }

        if (viewMatch && pmc != null) {
            if (pmc.masterSongList.stream().noneMatch(s -> s.getSongID() == song.getSongID())) {
                pmc.masterSongList.add(song);
            }
            if (song.isLocal() && pmc.currentSongList.stream().noneMatch(s -> s.getSongID() == song.getSongID())) {
                pmc.currentSongList.add(song);
            }
            song.setTrackOrder(pmc.masterSongList.size() - 1);
            pmc.adjustListHeight(pmc.getSongsToPlayView());
            pmc.getSongsToPlayView().refresh();
        }

        if (playingMatch) {
            mgr.addSongToCurrentPlaylist(song);
            List<Song> src = mgr.getSourceSongList();
            if (!mgr.isRandomMode()) {
                for (int i = 0; i < src.size(); i++) src.get(i).setTrackOrder(i);
            } else {
                Collections.shuffle(src);
                for (int i = 0; i < src.size(); i++) src.get(i).setTrackOrder(i);
            }
            mgr.setCurrentSongList(new java.util.ArrayList<>(src));
            if (pmc != null) pmc.refreshPlaybackContext();
            if (QueueController.getInstance() != null) QueueController.getInstance().refreshAll();
        }
    }

    private void addRequestedSongsToCreatedPlaylist(Playlist playlist) {
        if (playlist == null || playlist.getId() <= 0) return;

        List<Song> requestedSongs = sanitizeBulkSongs(
                bulkMode ? bulkSongs : List.of(song)
        );
        if (requestedSongs.isEmpty()) return;

        if (playlist.getSongList() == null) {
            playlist.setSongList(FXCollections.observableArrayList());
        }

        List<Song> before = new ArrayList<>(playlist.getSongList());
        applySongsToPlaylistModel(playlist, requestedSongs);

        CompletableFuture.runAsync(() -> {
            try {
                playlistDialogService.addSongsToPlaylist(playlist.getId(), requestedSongs);
            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        }).thenRun(() -> runFx(() -> refreshBulkPlaybackAfterChange(playlist)))
                .exceptionally(ex -> {
                    runFx(() -> {
                        playlist.getSongList().setAll(before);
                        refreshBulkPlaybackAfterChange(playlist);
                        showError(resolveErrorMessage(ex));
                    });
                    return null;
                });
    }

    private void applySongsToPlaylistModel(Playlist playlist, List<Song> songs) {
        if (playlist == null || playlist.getSongList() == null || songs == null) return;
        for (Song candidate : songs) {
            if (candidate != null && !playlistContainsSong(playlist, candidate)) {
                playlist.getSongList().add(candidate);
            }
        }
    }

    private void closePopoverOrDialog() {
        if (dialogStage != null) {
            dialogStage.close();
            return;
        }
        if (closeButton != null && closeButton.getScene() != null) {
            Window window = closeButton.getScene().getWindow();
            if (window != null) {
                window.hide();
            }
        }
    }

    private String resolveErrorMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() != null ? current.getMessage() : "Could not update playlist. Try again.";
    }

    private String resolveSongTitle(Song song) {
        String title = song == null ? "" : normalize(song.getTitle());
        return title.isBlank() ? "this song" : title;
    }

    private TextFlow createSubtitle() {
        Text prefix = new Text(bulkMode ? "Add or remove all songs from " : "Add or remove ");
        prefix.getStyleClass().add("manage-playlist-subtitle-text");

        Text title = new Text(bulkMode ? bulkCollectionTitle : resolveSongTitle(song));
        title.getStyleClass().add("manage-playlist-subtitle-highlight");

        Text suffix = new Text(" from your playlists");
        suffix.getStyleClass().add("manage-playlist-subtitle-text");

        TextFlow flow = new TextFlow(prefix, title, suffix);
        flow.getStyleClass().add("manage-playlist-subtitle-flow");
        return flow;
    }

    private List<Song> sanitizeBulkSongs(List<Song> songs) {
        if (songs == null || songs.isEmpty()) return List.of();

        List<Song> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Song candidate : songs) {
            if (candidate == null) continue;
            String key = songMembershipKey(candidate);
            if (key.isBlank() || !seen.add(key)) continue;
            result.add(candidate);
        }
        return List.copyOf(result);
    }

    private String songMembershipKey(Song song) {
        if (song == null) return "";
        if (song.getSongID() > 0) return "id:" + song.getSongID();
        return "title:" + normalize(song.getTitle()).toLowerCase();
    }

    private Text createEmptyMessage() {
        Text text = new Text("You don't have any playlists created yet");
        text.getStyleClass().add("manage-playlist-empty-text");
        return text;
    }

    private Hyperlink createCreatePlaylistLink() {
        Hyperlink link = new Hyperlink("Create a playlist");
        link.getStyleClass().add("manage-playlist-create-link");
        link.setFocusTraversable(false);

        VBox content = new VBox(6);
        content.setAlignment(javafx.geometry.Pos.CENTER);
        Node icon = SvgIconFactory.icon(ICON_CREATE_PLAYLIST, 26);
        SvgIconFactory.setIconColor(icon, "#BDBDBD");
        icon.getStyleClass().add("manage-playlist-create-icon");
        Text text = new Text("Create a playlist");
        text.getStyleClass().add("manage-playlist-create-text");
        content.getChildren().setAll(icon, text);

        link.setText("");
        link.setGraphic(content);
        link.hoverProperty().addListener((obs, oldValue, isHover) -> {
            SvgIconFactory.setIconColor(icon, isHover ? "#0077B6FF" : "#BDBDBD");
            text.setStyle("-fx-fill: " + (isHover ? "#0077B6FF" : "#BDBDBD") + ";");
        });
        link.setOnAction(event -> {
            closePopoverOrDialog();
            createPlaylistLauncher.accept(this::addRequestedSongsToCreatedPlaylist);
        });
        return link;
    }
}
