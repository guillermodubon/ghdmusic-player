package io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.layout.HBox;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import org.controlsfx.control.PopOver;
import io.github.guillermodubon.musicplayer.controllers.layout.AppShellController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.contextMenus.ActionContextMenuFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.components.icons.MeatballIconFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistContentManagementDialogs.ManagePlaylistSongsDialogController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistContentManagementDialogs.ManagePlaylistSongsPopoverSupport;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistManagementDialogs.PlaylistManagementDialogLauncher;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane.QueueController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext;
import io.github.guillermodubon.musicplayer.utils.NavigationHelper;
import io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.base.BaseSongCellController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.services.SongArtistResolver;
import io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.services.PlayingSongIndicatorSupport;
import io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.services.QueueSongItemHoverSupport;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;
import io.github.guillermodubon.musicplayer.utils.ArtistIdentity;

import java.io.IOException;
import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

public class PlayableSongItemController extends BaseSongCellController {

    private static final String ICON_NORMAL = "#DCDCDC";
    private static final String ICON_HOVER = "#FFFFFF";
    private static final String ICON_BUTTON_CHROMELESS_STYLE = """
            -fx-background-color: transparent;
            -fx-background-insets: 0;
            -fx-border-color: transparent;
            -fx-border-width: 0;
            -fx-effect: null;
            -fx-focus-color: transparent;
            -fx-faint-focus-color: transparent;
            -fx-padding: 0;
            """;

    @FXML private Button optionsButton;
    @FXML private HBox rootBox;
    @FXML private StackPane playingIndicatorHost;

    private Consumer<Song> onPlaySong;
    private Consumer<String> onArtistClick;
    private Consumer<Song> onAddToQueue;
    private ContextMenu optionsMenu;
    private boolean keyboardSelected;
    private boolean fullRowPlayable;
    private boolean deferPlayUntilRelease;
    private PlayingSongIndicatorSupport playingStateSupport;

    @FXML
    public void initialize() {
        normalizeRootStyle();
        installMeatballButton(optionsButton, "Song options");
        playingStateSupport = new PlayingSongIndicatorSupport(rootBox, titleLabel, playingIndicatorHost);
        configureTitleMarquee(rootBox);
        configureArtistMarquee(rootBox);
    }

    private void normalizeRootStyle() {
        if (rootBox == null) return;
        rootBox.setMinHeight(58);
        rootBox.setPrefHeight(58);
        rootBox.setMaxHeight(58);
        QueueSongItemHoverSupport.install(rootBox, () -> keyboardSelected);
    }

    public void setKeyboardSelected(boolean selected) {
        if (rootBox == null) return;
        keyboardSelected = selected;
        updateRootVisualState();
    }

    /** Enables the queue-specific behavior where the complete row plays. */
    public void setFullRowPlayable(boolean fullRowPlayable) {
        this.fullRowPlayable = fullRowPlayable;
        if (rootBox != null) {
            rootBox.setCursor(fullRowPlayable ? Cursor.HAND : Cursor.DEFAULT);
        }
    }

    /** Prevents a normal click from firing before a playlist drag is detected. */
    public void setDeferPlayUntilRelease(boolean deferPlayUntilRelease) {
        this.deferPlayUntilRelease = deferPlayUntilRelease;
    }

    private void updateRootVisualState() {
        if (rootBox == null) return;
        QueueSongItemHoverSupport.refresh(rootBox, () -> keyboardSelected);
    }

    public void init(Song song,
                     Consumer<Song> onPlaySong,
                     Consumer<String> onArtistClick,
                     Consumer<Song> onAddToQueue) {

        this.song = song;
        this.onPlaySong = playedSong -> {
            ensurePlayerMenuBarVisible();
            if (onPlaySong != null) {
                onPlaySong.accept(playedSong);
            } else {
                fallbackPlay(playedSong);
            }
        };
        this.onArtistClick = onArtistClick;
        this.onAddToQueue = onAddToQueue;

        bindSongBasics(song);
        loadCachedOrAsyncArtists(song);
        bindPlayableClick();
        refreshPlayingState();
    }

    public void refreshPlayingState() {
        if (playingStateSupport != null) {
            playingStateSupport.refresh(song);
        }
    }

    public void deactivatePlayingState() {
        if (playingStateSupport != null) {
            playingStateSupport.deactivate();
        }
    }

    private void bindPlayableClick() {
        Node clickTarget = fullRowPlayable ? rootBox : null;
        if (clickTarget == null && coverView != null) {
            clickTarget = coverView.getParent() != null ? coverView.getParent() : coverView;
        }
        // The base controller ignores nested buttons and artist hyperlinks.
        if (deferPlayUntilRelease) {
            bindPlayActionOnRelease(clickTarget, () -> {
                if (onPlaySong != null && song != null) onPlaySong.accept(song);
            });
        } else {
            bindSongPlayAction(clickTarget, onPlaySong);
        }
    }

    private void fallbackPlay(Song playedSong) {
        try {
            if (playedSong == null) return;

            Playlist single = new Playlist(
                    playedSong.getSongID(),
                    playedSong.getTitle(),
                    "",
                    "",
                    playedSong.getAlbum() != null && playedSong.getAlbum().getReleaseDate() != null
                            ? playedSong.getAlbum().getReleaseDate()
                            : "",
                    resolveCover(playedSong),
                    FXCollections.observableArrayList(playedSong)
            );

            PlaybackManager.getInstance().playSongs(
                    List.of(playedSong),
                    0,
                    single.getId(),
                    PlayerMenuContext.ContentType.SINGLE
            );
        } catch (Exception ignored) {
        }
    }

    private void ensurePlayerMenuBarVisible() {
        try {
            if (svc == null) return;
            AppShellController shell = svc.getAppShellController();
            if (shell != null) {
                shell.ensurePlayerMenuBarLoaded();
            }
        } catch (Exception ignored) {}
    }

    private void loadCachedOrAsyncArtists(Song expectedSong) {
        if (expectedSong == null || svc == null || expectedSong.getSongID() <= 0) return;

        long requestedTrackId = expectedSong.getSongID();
        long expectedGeneration = currentRenderGeneration();
        List<Artist> cached = svc.getCachedTrackArtists(requestedTrackId);
        if (cached != null && !cached.isEmpty()) {
            mergeArtists(cached, expectedGeneration, expectedSong);
            return;
        }

        svc.ensureTrackArtistsLoadedAsync(requestedTrackId, expectedSong, () ->
                Platform.runLater(() -> {
                    if (!isCurrentRender(expectedGeneration)
                            || !isCurrentSong(expectedSong)
                            || this.song == null
                            || this.song.getSongID() != requestedTrackId) return;
                    List<Artist> fresh = svc.getCachedTrackArtists(requestedTrackId);
                    mergeArtists(fresh, expectedGeneration, expectedSong);
                }));
    }

    private void mergeArtists(List<Artist> extra,
                              long expectedGeneration,
                              Song expectedSong) {
        if (song == null || extra == null || extra.isEmpty()) return;
        if (!isCurrentRender(expectedGeneration) || !isCurrentSong(expectedSong)) return;

        List<Artist> merged = SongArtistResolver.merge(song.getArtist(), extra);
        song.setArtist(merged);
        Platform.runLater(() -> {
            if (!isCurrentRender(expectedGeneration) || !isCurrentSong(expectedSong)) return;
            renderArtists(SongArtistResolver.resolveParticipants(song));
        });
    }

    @Override
    protected boolean shouldRenderArtistAsPlainText(Artist artist) {
        if (ArtistIdentity.isVariousArtists(artist)) {
            return true;
        }

        return song != null
                && song.isLocal()
                && song.getSongID() == 0L
                && "Unknown".equalsIgnoreCase(ArtistIdentity.displayName(
                        artist == null ? null : artist.getName()
                ));
    }

    @FXML private void onAddToPlaylist() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(
                    "/io/github/guillermodubon/musicplayer/Views/components/dialogs/playlistContentManagementDialogs/ManagePlaylistSongsDialog.fxml"
            ));
            AnchorPane content = loader.load();
            ManagePlaylistSongsDialogController ctrl = loader.getController();

            long activeId = PlaybackManager.getInstance().getCurrentPlaylistInViewId();
            ctrl.init(svc, song, activeId);
            ctrl.setCreatePlaylistLauncher(onCreated ->
                    PlaylistManagementDialogLauncher.openCreatePlaylistDialog(
                            svc,
                            resolveOwnerWindow(),
                            resolveParentRoot(),
                            null,
                            onCreated
                    )
            );

            PopOver pop = new PopOver(content);
            ManagePlaylistSongsPopoverSupport.configure(pop, content, optionsButton);

            ManagePlaylistSongsPopoverSupport.show(pop, optionsButton);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @FXML private void onAddToQueue() {
        if (onAddToQueue != null) onAddToQueue.accept(song);
        if (QueueController.getInstance() != null) {
            QueueController.getInstance().refreshAll();
        }
    }

    @FXML
    private void onShowOptionsMenu() {
        if (optionsButton == null) return;

        if (optionsMenu == null) {
            optionsMenu = ActionContextMenuFactory.songActions(
                    this::onAddToPlaylist,
                    this::onAddToQueue,
                    this::onOpenSongLocation
            );
        }

        ActionContextMenuFactory.showNearButton(optionsMenu, optionsButton);
    }

    private void onOpenSongLocation() {
        if (song == null || song.getFilePath() == null || song.getFilePath().isBlank()) return;

        try {
            Path songPath = Path.of(song.getFilePath()).toAbsolutePath().normalize();
            Path songDirectory = songPath.getParent();
            if (songDirectory == null || !Files.isDirectory(songDirectory)) return;

            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(songDirectory.toFile());
            }
        } catch (InvalidPathException | IOException | SecurityException ignored) {
        }
    }

    @Override
    protected void onArtistClicked(Artist artist) {
        try {
            if (artist == null) return;

            if (artist.getArtistID() > 0) {
                BorderPane root = (BorderPane) coverView.getScene().getRoot();
                NavigationHelper.showArtistScreen(artist, svc, root);
            } else if (onArtistClick != null) {
                onArtistClick.accept(artist.getName());
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private Node installMeatballButton(Button button, String accessibleText) {
        if (button == null) return null;
        button.setText("");
        button.setAccessibleText(accessibleText);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setStyle(ICON_BUTTON_CHROMELESS_STYLE);
        Node icon = MeatballIconFactory.vertical(22, 6, 4);
        MeatballIconFactory.setColor(icon, ICON_NORMAL);
        button.setGraphic(icon);
        button.hoverProperty().addListener((obs, oldValue, isHover) -> updateIconColor(button, icon));
        button.focusedProperty().addListener((obs, oldValue, isFocused) -> updateIconColor(button, icon));
        button.armedProperty().addListener((obs, oldValue, isArmed) -> button.setStyle(ICON_BUTTON_CHROMELESS_STYLE));
        button.pressedProperty().addListener((obs, oldValue, isPressed) -> button.setStyle(ICON_BUTTON_CHROMELESS_STYLE));
        return icon;
    }

    private void updateIconColor(Button button, Node icon) {
        if (icon == null) return;
        boolean highlighted = button != null && (button.isHover() || button.isFocused());
        MeatballIconFactory.setColor(icon, highlighted ? ICON_HOVER : ICON_NORMAL);
    }

    private javafx.stage.Window resolveOwnerWindow() {
        return optionsButton != null && optionsButton.getScene() != null
                ? optionsButton.getScene().getWindow()
                : null;
    }

    private BorderPane resolveParentRoot() {
        try {
            if (optionsButton != null
                    && optionsButton.getScene() != null
                    && optionsButton.getScene().getRoot() instanceof BorderPane root) {
                return root;
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
