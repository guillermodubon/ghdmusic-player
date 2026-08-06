package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.actions;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.media.MediaPlayer;
import javafx.stage.Window;
import org.controlsfx.control.PopOver;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistContentManagementDialogs.ManagePlaylistSongsDialogController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistContentManagementDialogs.ManagePlaylistSongsPopoverSupport;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistManagementDialogs.DownloadAllConfirmationDialog;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistManagementDialogs.PlaylistManagementDialogLauncher;
import io.github.guillermodubon.musicplayer.controllers.ui.components.icons.SvgIconFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.components.popups.SmallPopupTooltip;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.downloads.bulk.BulkDownloadManager;
import io.github.guillermodubon.musicplayer.services.downloads.bulk.BulkDownloadSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Coordinates collection actions and their visual state for PlayerMenu. */
public final class PlayerMenuActionCoordinator {
    private static final String ICON_PLAY_COLLECTION =
            "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/play_circle_40dp_0077B6_FILL0_wght400_GRAD0_opsz40.svg";
    private static final String ICON_PAUSE_COLLECTION =
            "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/pause_circle_35dp_0077B6_FILL0_wght400_GRAD0_opsz40.svg";
    private static final double COLLECTION_PLAY_ICON_SIZE = 50;
    private static final String ICON_SHUFFLE =
            "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/shuffle_27dp_AFAFAF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_ADD_TO_PLAYLIST =
            "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/library_add_27dp_AFAFAF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_DOWNLOAD_ALL =
            "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/download_for_offline_27dp_FFFFFF_FILL0_wght400_GRAD0_opsz24.svg";

    private final PlayerMenuActionHost host;

    private Button playButton;
    private ToggleButton randomButton;
    private Button addToPlaylistButton;
    private Button downloadAllButton;
    private Node randomIcon;
    private Node downloadAllIcon;
    private Node playIcon;
    private boolean localRandomMode;
    private boolean playUnavailable;
    private boolean collectionPlaybackPlaying;
    private boolean collectionPlaybackPaused;
    private Boolean pauseIconVisible;
    private boolean downloadUnavailable;
    private MediaPlayer observedPlaybackPlayer;
    private ChangeListener<MediaPlayer.Status> playbackStatusListener;
    private javafx.scene.control.Tooltip playTooltip;
    private javafx.scene.control.Tooltip randomTooltip;
    private javafx.scene.control.Tooltip addTooltip;
    private javafx.scene.control.Tooltip downloadTooltip;

    public PlayerMenuActionCoordinator(PlayerMenuActionHost host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    public void configure(Button playButton,
                          ToggleButton randomButton,
                          Button addToPlaylistButton,
                          Button downloadAllButton) {
        this.playButton = playButton;
        this.randomButton = randomButton;
        this.addToPlaylistButton = addToPlaylistButton;
        this.downloadAllButton = downloadAllButton;

        playIcon = installButtonIcon(
                playButton,
                ICON_PLAY_COLLECTION,
                COLLECTION_PLAY_ICON_SIZE,
                "#0077B6",
                "#0ACBF2"
        );
        randomIcon = installButtonIcon(randomButton, ICON_SHUFFLE, 25, "#AFAFAF", "#FFFFFF");
        installButtonIcon(addToPlaylistButton, ICON_ADD_TO_PLAYLIST, 25, "#AFAFAF", "#FFFFFF");
        downloadAllIcon = installButtonIcon(downloadAllButton, ICON_DOWNLOAD_ALL, 25, "#AFAFAF", "#FFFFFF");

        if (playButton != null) playButton.setOnAction(event -> playVisibleSongs());
        if (addToPlaylistButton != null) addToPlaylistButton.setOnAction(event -> addVisibleSongsToPlaylist());
        if (downloadAllButton != null) {
            downloadAllButton.setOnAction(event -> downloadAllSongs());
            downloadAllButton.hoverProperty().addListener((obs, old, value) -> updateDownloadIconColor());
            downloadAllButton.focusedProperty().addListener((obs, old, value) -> updateDownloadIconColor());
        }

        if (randomButton != null) {
            randomButton.selectedProperty().addListener((obs, old, selected) -> {
                localRandomMode = Boolean.TRUE.equals(selected);
                if (host.actionIsPlaybackSource()) {
                    host.actionPlaybackManager().setRandomMode(localRandomMode);
                }
                refreshRandomIconColor();
                updateTooltips();
            });
            randomButton.hoverProperty().addListener((obs, old, value) -> refreshRandomIconColor());
            randomButton.focusedProperty().addListener((obs, old, value) -> refreshRandomIconColor());
            localRandomMode = false;
            randomButton.setSelected(false);
        }

        playTooltip = SmallPopupTooltip.install(playButton, playCollectionTooltipText());
        randomTooltip = SmallPopupTooltip.install(randomButton, randomCollectionTooltipText());
        addTooltip = SmallPopupTooltip.install(addToPlaylistButton, "Add to Playlist");
        downloadTooltip = SmallPopupTooltip.install(downloadAllButton, "Download All");
        updateActionState();
    }

    public void syncRandomMode(boolean enabled) {
        localRandomMode = enabled;
        if (randomButton != null) randomButton.setSelected(enabled);
        refreshRandomIconColor();
        updateTooltips();
    }

    public void updateVisibility(boolean visible, boolean isSingle) {
        setManagedVisible(randomButton, visible && !isSingle);
        setManagedVisible(downloadAllButton, visible && !isSingle);
        updateActionState();
    }

    public void updateActionState() {
        refreshPlaybackObserver();
        updatePlayButtonState();
        updateDownloadButtonState();
        updateTooltips();
    }

    public void updateTooltips() {
        if (playTooltip != null) playTooltip.setText(playCollectionTooltipText());
        if (randomTooltip != null) randomTooltip.setText(randomCollectionTooltipText());
        if (addTooltip != null) addTooltip.setText("Add to Playlist");
        if (downloadTooltip != null) downloadTooltip.setText(downloadAllTooltipText());
    }

    public void playVisibleSongs() {
        updatePlayButtonState();
        if (playUnavailable || host.actionPlaybackBridge() == null) {
            updateTooltips();
            return;
        }

        if (isCollectionPlaybackToggleAvailable()) {
            host.actionPlaybackManager().togglePlayPause();
            updateActionState();
            return;
        }

        List<Song> playable = host.actionPlayableSongs().stream()
                .filter(host::actionSongImmediatelyPlayable)
                .toList();
        if (playable.isEmpty()) {
            updateActionState();
            return;
        }

        List<Song> playOrder = new ArrayList<>(playable);

        Song first = playOrder.get(0);
        host.actionContext().ensurePlaylistNameFallback();
        host.actionPlaybackManager().setOriginSource(host.actionContext().getPlaylistName());
        host.actionPlaybackBridge().ensurePlayerMenuBarLoaded();
        List<Song> savedQueue = new ArrayList<>(host.actionPlaybackManager().getQueue());

        host.actionPlaybackManager().playSongs(
                playOrder,
                0,
                host.actionPlaylistId(),
                host.actionContext().getCurrentContentTypeInView(),
                localRandomMode
        );
        host.actionPersistPlaybackOrigin(first);
        savedQueue.forEach(song -> host.actionPlaybackManager().enqueue(song));
        host.actionRefreshPlaybackContext();
        host.actionRefreshQueue();
        updateActionState();
    }

    public void addVisibleSongsToPlaylist() {
        List<Song> songs = host.actionAllSongs();
        if (songs.isEmpty()) return;

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(
                    "/io/github/guillermodubon/musicplayer/Views/components/dialogs/"
                            + "playlistContentManagementDialogs/ManagePlaylistSongsDialog.fxml"
            ));
            AnchorPane content = loader.load();
            ManagePlaylistSongsDialogController controller = loader.getController();
            controller.initBulk(
                    host.actionStartUpService(),
                    songs,
                    host.actionPlaylistId(),
                    host.actionCollectionTitle()
            );
            controller.setCreatePlaylistLauncher(onCreated -> PlaylistManagementDialogLauncher.openCreatePlaylistDialog(
                    host.actionStartUpService(),
                    host.actionPlayerMenuRoot() != null
                            && host.actionPlayerMenuRoot().getScene() != null
                            ? host.actionPlayerMenuRoot().getScene().getWindow()
                            : null,
                    host.actionParentRoot(),
                    host.actionMusicCardManager(),
                    onCreated
            ));

            PopOver popOver = new PopOver(content);
            Node anchor = addToPlaylistButton != null ? addToPlaylistButton : host.actionPlayerMenuRoot();
            ManagePlaylistSongsPopoverSupport.configure(popOver, content, anchor);
            ManagePlaylistSongsPopoverSupport.show(popOver, anchor);
        } catch (IOException error) {
            error.printStackTrace();
        }
    }

    public void downloadAllSongs() {
        if (host.actionContentType() == PlayerMenuContext.ContentType.SINGLE) return;
        List<Song> songs = host.actionDownloadableSongs();
        if (songs.isEmpty()) {
            updateActionState();
            return;
        }

        String title = host.actionCollectionTitle();
        Window owner = host.actionPlayerMenuRoot() != null
                && host.actionPlayerMenuRoot().getScene() != null
                ? host.actionPlayerMenuRoot().getScene().getWindow()
                : null;
        if (!DownloadAllConfirmationDialog.confirm(owner, songs.size(), title)) return;

        BulkDownloadManager.getInstance().startSession(
                title,
                songs,
                host.actionDownloadSidebarOwner(),
                host.actionPlaylistId(),
                resolveBulkSourceType()
        );
        updateActionState();
    }

    private BulkDownloadSession.SourceType resolveBulkSourceType() {
        PlayerMenuContext.ContentType type = host.actionContentType();
        if (type == PlayerMenuContext.ContentType.ALBUM) return BulkDownloadSession.SourceType.ALBUM;
        if (type == PlayerMenuContext.ContentType.PLAYLIST) return BulkDownloadSession.SourceType.PLAYLIST;
        if (type == PlayerMenuContext.ContentType.SINGLE) return BulkDownloadSession.SourceType.SINGLE;
        return BulkDownloadSession.SourceType.UNKNOWN;
    }

    private void updatePlayButtonState() {
        if (playButton == null) return;

        refreshPlaybackObserver();
        playUnavailable = host.actionPlayableSongs().stream()
                .noneMatch(host::actionSongImmediatelyPlayable);
        collectionPlaybackPlaying = isCollectionPlaybackStatus(MediaPlayer.Status.PLAYING);
        collectionPlaybackPaused = isCollectionPlaybackStatus(MediaPlayer.Status.PAUSED);
        updatePlayButtonIcon(collectionPlaybackPlaying);

        playButton.setDisable(playUnavailable);
        playButton.setOpacity(playUnavailable ? 0.42 : 1.0);
        playButton.getStyleClass().removeAll(
                "player-menu-inline-play-button-disabled",
                "player-menu-inline-play-button-enabled"
        );
        playButton.getStyleClass().add(
                playUnavailable
                        ? "player-menu-inline-play-button-disabled"
                        : "player-menu-inline-play-button-enabled"
        );
        String text = playCollectionTooltipText();
        playButton.setAccessibleText(text);
        if (playTooltip != null) playTooltip.setText(text);
    }

    private void refreshPlaybackObserver() {
        if (playButton == null) return;

        MediaPlayer currentPlayer = host.actionPlaybackManager() == null
                ? null
                : host.actionPlaybackManager().getCurrentPlayer();
        if (observedPlaybackPlayer == currentPlayer) return;

        unbindPlaybackObserver();
        if (currentPlayer == null) return;

        observedPlaybackPlayer = currentPlayer;
        playbackStatusListener = (observable, oldStatus, newStatus) -> {
            Runnable refresh = this::updateActionState;
            if (Platform.isFxApplicationThread()) refresh.run();
            else Platform.runLater(refresh);
        };
        currentPlayer.statusProperty().addListener(playbackStatusListener);
    }

    private void unbindPlaybackObserver() {
        if (observedPlaybackPlayer != null && playbackStatusListener != null) {
            try {
                observedPlaybackPlayer.statusProperty().removeListener(playbackStatusListener);
            } catch (Exception ignored) {
            }
        }
        observedPlaybackPlayer = null;
        playbackStatusListener = null;
    }

    private boolean isCollectionPlaybackToggleAvailable() {
        if (!isCurrentCollectionPlaybackSource()) return false;

        MediaPlayer player = host.actionPlaybackManager().getCurrentPlayer();
        if (player == null) return false;

        MediaPlayer.Status status = player.getStatus();
        return status == MediaPlayer.Status.PLAYING
                || status == MediaPlayer.Status.PAUSED;
    }

    private boolean isCollectionPlaybackStatus(MediaPlayer.Status expectedStatus) {
        if (!isCurrentCollectionPlaybackSource()) return false;

        MediaPlayer player = host.actionPlaybackManager().getCurrentPlayer();
        return player != null && player.getStatus() == expectedStatus;
    }

    /**
     * Prevents a PlayerMenu button from controlling a different playback flow.
     * Albums and playlists have stable IDs. Singles created from local files can
     * use ID 0, so their current song is used as the fallback identity.
     */
    private boolean isCurrentCollectionPlaybackSource() {
        PlayerMenuContext.ContentType viewType = host.actionContentType();
        if (viewType == null
                || viewType != host.actionPlaybackManager().getCurrentContentTypePlaying()) {
            return false;
        }

        long viewId = host.actionPlaylistId();
        long playingId = host.actionPlaybackManager().getCurrentPlaylistPlayingId();
        if (viewId > 0 && playingId > 0) return viewId == playingId;
        if (viewType != PlayerMenuContext.ContentType.SINGLE) return false;

        Song currentSong = host.actionPlaybackManager().getCurrentSong();
        return currentSong != null && host.actionAllSongs().stream()
                .filter(Objects::nonNull)
                .anyMatch(candidate -> sameSong(candidate, currentSong));
    }

    private boolean sameSong(Song left, Song right) {
        if (left == null || right == null) return false;
        if (left.getSongID() > 0 && right.getSongID() > 0) {
            return left.getSongID() == right.getSongID();
        }

        String leftTitle = normalize(left.getTitle());
        String rightTitle = normalize(right.getTitle());
        return !leftTitle.isBlank() && leftTitle.equals(rightTitle);
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private void updatePlayButtonIcon(boolean playing) {
        if (playButton == null) return;
        if (pauseIconVisible == null || pauseIconVisible != playing) {
            String iconPath = playing ? ICON_PAUSE_COLLECTION : ICON_PLAY_COLLECTION;
            playIcon = SvgIconFactory.icon(iconPath, COLLECTION_PLAY_ICON_SIZE);
            playButton.setGraphic(playIcon);
            pauseIconVisible = playing;
        }
        updateIconColor(playButton, playIcon, "#0077B6", "#0ACBF2");
    }

    private void updateDownloadButtonState() {
        if (downloadAllButton == null) return;
        boolean isSingle = host.actionContentType() == PlayerMenuContext.ContentType.SINGLE;
        downloadUnavailable = !isSingle && host.actionDownloadableSongs().isEmpty();
        downloadAllButton.setOpacity(downloadUnavailable ? 0.72 : 1.0);
        downloadAllButton.setAccessibleText(downloadAllTooltipText());
        updateDownloadIconColor();
    }

    private void updateDownloadIconColor() {
        if (downloadAllButton == null || downloadAllIcon == null) return;
        if (downloadUnavailable) {
            downloadAllButton.getProperties().put("forceAccentIcon", Boolean.TRUE);
        } else {
            downloadAllButton.getProperties().remove("forceAccentIcon");
        }
        updateIconColor(downloadAllButton, downloadAllIcon, "#AFAFAF", "#FFFFFF");
    }

    public void refreshRandomIconColor() {
        if (randomIcon == null || randomButton == null) return;
        String color = randomButton.isSelected()
                ? "#0077B6"
                : randomButton.isHover() ? "#FFFFFF" : "#AFAFAF";
        SvgIconFactory.setIconColor(randomIcon, color);
    }

    private Node installButtonIcon(ButtonBase button,
                                   String iconPath,
                                   double size,
                                   String normalColor,
                                   String hoverColor) {
        if (button == null) return null;
        button.setText("");
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setStyle("-fx-background-color: transparent; -fx-background-insets: 0; "
                + "-fx-border-color: transparent; -fx-border-width: 0; -fx-padding: 0; -fx-effect: null;");
        Node icon = SvgIconFactory.icon(iconPath, size);
        button.setGraphic(icon);
        button.hoverProperty().addListener((obs, old, value) ->
                updateIconColor(button, button.getGraphic(), normalColor, hoverColor));
        button.focusedProperty().addListener((obs, old, value) ->
                updateIconColor(button, button.getGraphic(), normalColor, hoverColor));
        SvgIconFactory.setIconColor(icon, normalColor);
        return icon;
    }

    private void updateIconColor(ButtonBase button, Node icon, String normalColor, String hoverColor) {
        if (button == null || icon == null) return;
        if (Boolean.TRUE.equals(button.getProperties().get("forceAccentIcon"))) {
            SvgIconFactory.setIconColor(icon, "#0077B6");
            return;
        }
        SvgIconFactory.setIconColor(icon, button.isHover() || button.isFocused() ? hoverColor : normalColor);
    }

    private void setManagedVisible(Node node, boolean visible) {
        if (node == null) return;
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private String randomCollectionTooltipText() {
        return localRandomMode ? "Disable Shuffle mode" : "Enable Shuffle mode";
    }

    private String downloadAllTooltipText() {
        return downloadUnavailable
                ? "All songs from " + host.actionCollectionTitle() + " are already in your library"
                : "Download All";
    }

    private String playCollectionTooltipText() {
        PlayerMenuContext.ContentType type = host.actionContentType() == null
                ? PlayerMenuContext.ContentType.PLAYLIST
                : host.actionContentType();
        if (playUnavailable) {
            return switch (type) {
                case SINGLE -> "Download the song to play it.";
                case ALBUM, EPISODE -> "Download at least one song from this album to play it.";
                case PLAYLIST -> "Download at least one song from this playlist to play it.";
            };
        }
        if (collectionPlaybackPlaying) {
            return switch (type) {
                case SINGLE -> "Pause this single";
                case ALBUM, EPISODE -> "Pause this album";
                case PLAYLIST -> "Pause this playlist";
            };
        }
        if (collectionPlaybackPaused) {
            return switch (type) {
                case SINGLE -> "Resume this single";
                case ALBUM, EPISODE -> "Resume this album";
                case PLAYLIST -> "Resume this playlist";
            };
        }
        return switch (type) {
            case SINGLE -> "Play this single";
            case ALBUM, EPISODE -> "Play this album";
            case PLAYLIST -> "Play this playlist";
        };
    }
}
