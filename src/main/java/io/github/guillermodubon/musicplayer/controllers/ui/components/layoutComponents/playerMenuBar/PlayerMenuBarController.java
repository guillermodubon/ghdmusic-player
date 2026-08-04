package io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.MediaPlayer;
import io.github.guillermodubon.musicplayer.controllers.ui.components.contextMenus.ActionContextMenuFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.helpers.PlayerMenuBarArtworkResolver;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.helpers.PlayerMenuBarActionCoordinator;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.helpers.PlayerMenuBarIconManager;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.helpers.PlayerMenuBarLayoutCoordinator;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.managers.PlayerMenuBarTimeBinder;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.helpers.PlayerMenuBarTrackPresenter;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane.QueueController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.PlayerFullScreenModeController;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.ArtistOpenCoordinator;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.navigation.PlayerMenuNavigator;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

/**
 * FXML facade for the persistent player bar.
 *
 * <p>Playback commands and the public integration API remain here. Layout,
 * icon state and current-track rendering are delegated to focused helpers so
 * the bar can be changed without mixing UI concerns with playback wiring.</p>
 */
public class PlayerMenuBarController {

    private enum KeyboardSliderTarget {
        TIME,
        VOLUME
    }

    @FXML private BorderPane root;
    @FXML private HBox playerMainRow;
    @FXML private HBox nowPlayingRow;
    @FXML private StackPane playerCoverWrap;
    @FXML private ImageView coverImageView;
    @FXML private VBox playerTrackText;
    @FXML private StackPane playerTitleViewport;
    @FXML private HBox playerTitleMarqueeBox;
    @FXML private Label CurrentSongNameLabelBar;
    @FXML private StackPane playerArtistsViewport;
    @FXML private HBox ArtistsLinksContainer;
    @FXML private Slider VolumeSlider;
    @FXML private Button PreviousButton;
    @FXML private Button NextButton;
    @FXML private ToggleButton PlayToggleButton;
    @FXML private VBox playerPlaybackCenter;
    @FXML private HBox playerTransportRow;
    @FXML private HBox TimeRow;
    @FXML private Slider SongTimerSlider;
    @FXML private StackPane SongTimerSliderShell;
    @FXML private Region SongTimerSliderBase;
    @FXML private Region SongTimerSliderFill;
    @FXML private Label CurrentMinuteLabel;
    @FXML private Label SongLengthLabel;
    @FXML private ToggleButton RandomToggleButton;
    @FXML private ToggleButton ReplayToggleButton;
    @FXML private ToggleButton FullScreenToggleButton;
    @FXML private Button QueueButton;
    @FXML private Button actionsMenuButton;
    @FXML private HBox playerRightControls;
    @FXML private HBox VolumeBox;
    @FXML private StackPane VolumeIconHost;
    @FXML private StackPane VolumeSliderShell;
    @FXML private Region VolumeSliderBase;
    @FXML private Region VolumeSliderFill;

    private final PlaybackManager pm = PlaybackManager.getInstance();
    private final PlayerFullScreenModeController fullScreenModeController =
            PlayerFullScreenModeController.getInstance();
    private final PlayerMenuBarArtworkResolver artworkResolver =
            new PlayerMenuBarArtworkResolver();
    private final io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.managers.PlayerMenuBarNavigationHandler navigationHandler =
            new io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.managers.PlayerMenuBarNavigationHandler();

    private BorderPane parentRoot;
    private BorderPane sidePanelHost;
    private StartUpService startUpService;
    private MusicCardActionManager musicCardActionManager;
    private PlayerMenuBarTimeBinder timeBinder;
    private PlayerMenuBarTrackPresenter trackPresenter;
    private PlayerMenuBarIconManager iconManager;
    private PlayerMenuBarLayoutCoordinator layoutCoordinator;
    private final PlayerMenuBarActionCoordinator actionCoordinator =
            new PlayerMenuBarActionCoordinator();
    private javafx.scene.control.ContextMenu actionsContextMenu;
    private boolean trackListenerRegistered;
    private KeyboardSliderTarget keyboardSliderTarget = KeyboardSliderTarget.TIME;

    public void setStartUpService(StartUpService service) {
        startUpService = service != null ? service : StartUpService.getInstance();
    }

    public void setParentRoot(BorderPane root) {
        parentRoot = root;
        fullScreenModeController.bindPlayerMenuBar(this);
        fullScreenModeController.setHostRoot(root);
    }

    public void setSidePanelHost(BorderPane host) {
        sidePanelHost = host;
    }

    public void setPrimaryHost(Pane host) {
        if (layoutCoordinator != null) {
            layoutCoordinator.setPrimaryHost(host);
        }
    }

    public boolean bringPrimaryHostToFront() {
        return layoutCoordinator != null && layoutCoordinator.bringPrimaryHostToFront();
    }

    public boolean restoreToPrimaryHost() {
        return layoutCoordinator != null && layoutCoordinator.restoreToPrimaryHost();
    }

    public void synchronizePrimaryHostLayout() {
        if (layoutCoordinator != null) {
            layoutCoordinator.synchronizePrimaryHostLayout();
        }
    }

    public void init(StartUpService service,
                     MusicCardActionManager musicActions,
                     BorderPane root) {
        setStartUpService(service);
        setParentRoot(root);

        if (musicActions != null) {
            musicCardActionManager = musicActions;
        } else {
            PlayerMenuNavigator navigator = new PlayerMenuNavigator(startUpService);
            ArtistOpenCoordinator artistCoordinator =
                    new ArtistOpenCoordinator(startUpService, navigator);
            musicCardActionManager = new MusicCardActionManager(
                    startUpService,
                    navigator,
                    artistCoordinator
            );
        }

        setupUiHooks();
        bindPlayerState();
        refreshFromPlayback();
    }

    @FXML
    public void initialize() {
        fullScreenModeController.bindPlayerMenuBar(this);

        trackPresenter = new PlayerMenuBarTrackPresenter(
                playerTitleViewport,
                playerTitleMarqueeBox,
                CurrentSongNameLabelBar,
                playerArtistsViewport,
                ArtistsLinksContainer,
                playerTrackText,
                coverImageView,
                artworkResolver,
                this::openArtist
        );
        trackPresenter.initialize();

        layoutCoordinator = new PlayerMenuBarLayoutCoordinator(
                root,
                playerMainRow,
                nowPlayingRow,
                playerCoverWrap,
                coverImageView,
                playerTrackText,
                playerTitleViewport,
                playerArtistsViewport,
                playerPlaybackCenter,
                playerTransportRow,
                TimeRow,
                SongTimerSliderShell,
                SongTimerSlider,
                playerRightControls,
                VolumeBox,
                VolumeSliderShell,
                VolumeSlider,
                this::refreshMarquee,
                () -> keyboardSliderTarget = KeyboardSliderTarget.TIME,
                () -> keyboardSliderTarget = KeyboardSliderTarget.VOLUME,
                this::seekCurrentPlayerTo
        );
        layoutCoordinator.initialize();
        layoutCoordinator.initializeProgressFill(
                SongTimerSliderBase,
                SongTimerSliderFill,
                VolumeSliderBase,
                VolumeSliderFill
        );

        iconManager = new PlayerMenuBarIconManager(
                PreviousButton,
                NextButton,
                PlayToggleButton,
                actionsMenuButton,
                ReplayToggleButton,
                RandomToggleButton,
                QueueButton,
                FullScreenToggleButton,
                VolumeIconHost,
                VolumeSlider,
                QueueController::isQueueVisible,
                this::replayTooltipText,
                this::randomTooltipText,
                this::fullScreenTooltipText
        );
        iconManager.initialize();

        coverImageView.setCursor(Cursor.HAND);
        coverImageView.setOnMouseEntered(event -> {
            coverImageView.setScaleX(1.05);
            coverImageView.setScaleY(1.05);
        });
        coverImageView.setOnMouseExited(event -> {
            coverImageView.setScaleX(1.0);
            coverImageView.setScaleY(1.0);
        });
        coverImageView.setOnMouseClicked(this::handleCoverClick);

        VolumeSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            double volume = newValue.doubleValue() / 100.0;
            pm.setLastVolume(volume);
            MediaPlayer player = pm.getCurrentPlayer();
            if (player != null) player.setVolume(volume);
            iconManager.updateVolumeIcon();
            iconManager.updateVolumeSliderState();
        });
        VolumeSlider.focusedProperty().addListener(
                (obs, oldValue, focused) -> iconManager.updateVolumeSliderState()
        );
        VolumeSlider.valueChangingProperty().addListener(
                (obs, oldValue, changing) -> iconManager.updateVolumeSliderState()
        );

        QueueController.queueVisibleProperty().addListener(
                (obs, oldValue, newValue) -> iconManager.updateIconColor(QueueButton)
        );
        RandomToggleButton.selectedProperty().addListener((obs, oldValue, selected) -> {
            pm.setRandomMode(selected);
            iconManager.updateRandomIcon();
            updateToggleTooltips();
        });
        ReplayToggleButton.selectedProperty().addListener((obs, oldValue, selected) -> {
            pm.setReplayMode(selected);
            iconManager.updateReplayIcon();
            updateToggleTooltips();
        });
        FullScreenToggleButton.selectedProperty().addListener((obs, oldValue, selected) -> {
            iconManager.updateFullScreenIcon();
            updateToggleTooltips();
        });

        VolumeSlider.setValue(pm.getLastVolume() * 100);
        RandomToggleButton.setSelected(pm.isRandomMode());
        ReplayToggleButton.setSelected(false);
        syncFullScreenToggle();
        iconManager.updatePlayPauseIcon(pm.getCurrentPlayer());
        iconManager.updateAllIconColors();
        iconManager.updateVolumeIcon();
        iconManager.updateVolumeSliderState();
        updateToggleTooltips();
    }

    private void setupUiHooks() {
        if (timeBinder == null) {
            timeBinder = new PlayerMenuBarTimeBinder(
                    SongTimerSlider,
                    CurrentMinuteLabel,
                    SongLengthLabel,
                    pm::next
            );
        }
    }

    public boolean isUserInteractingWithPlayerControls() {
        boolean timeSliderActive = SongTimerSlider != null
                && (SongTimerSlider.isValueChanging() || SongTimerSlider.isPressed());
        boolean volumeSliderActive = VolumeSlider != null
                && (VolumeSlider.isValueChanging() || VolumeSlider.isPressed());
        boolean actionsMenuOpen = actionsContextMenu != null && actionsContextMenu.isShowing();
        return timeSliderActive || volumeSliderActive || actionsMenuOpen;
    }

    private void bindPlayerState() {
        if (trackListenerRegistered) return;
        trackListenerRegistered = true;
        pm.addTrackChangeListener(() -> Platform.runLater(() -> {
            refreshFromPlayback();
            QueueController queue = QueueController.getInstance();
            if (queue != null) queue.refreshAll();
        }));
    }

    private void refreshFromPlayback() {
        Song current = pm.getCurrentSong();
        MediaPlayer player = pm.getCurrentPlayer();

        updateCurrentSong(current);
        fullScreenModeController.updateCurrentSong(startUpService, current);

        if (player != null) {
            bindTime(player);
        } else {
            if (timeBinder != null) timeBinder.unbind();
            if (iconManager != null) iconManager.unbindPlaybackStatus();
        }

        if (VolumeSlider != null) VolumeSlider.setValue(pm.getLastVolume() * 100);
        if (RandomToggleButton != null) RandomToggleButton.setSelected(pm.isRandomMode());
        if (ReplayToggleButton != null) ReplayToggleButton.setSelected(false);
        syncFullScreenToggle();
        updateToggleTooltips();
    }

    public void updateCurrentSong(Song song) {
        if (trackPresenter != null) {
            trackPresenter.updateCurrentSong(song);
        }
    }

    public void bindTime(MediaPlayer newPlayer) {
        if (timeBinder == null) setupUiHooks();
        timeBinder.bind(newPlayer);
        if (iconManager != null) iconManager.bindPlaybackStatus(newPlayer);
    }

    private void openArtist(Node anchor, Artist artist) {
        if (musicCardActionManager == null || artist == null || anchor == null) return;
        musicCardActionManager.artistClick(anchor).accept(artist);
    }

    /** Opens an exact artist after the internal fullscreen overlay has closed. */
    public void openArtistAfterFullScreen(Artist artist) {
        if (musicCardActionManager == null || artist == null) return;
        Node anchor = parentRoot != null ? parentRoot : root;
        if (anchor != null) {
            musicCardActionManager.artistClick(anchor).accept(artist);
        }
    }

    @FXML
    private void PreviousSong() {
        pm.previous();
    }

    @FXML
    private void PlayPauseSong() {
        pm.togglePlayPause();
        Platform.runLater(() -> {
            if (iconManager != null) iconManager.updatePlayPauseIcon(pm.getCurrentPlayer());
        });
    }

    @FXML
    private void nextSong() {
        pm.next();
    }

    @FXML
    private void onAddToQueue() {
        actionCoordinator.enqueueCurrentSong();
    }

    @FXML
    private void onShowActionsMenu() {
        if (actionsMenuButton == null) return;
        if (actionsContextMenu == null) {
            actionsContextMenu = actionCoordinator.createActionsMenu(
                    this::onAddToPlaylist,
                    this::onAddToQueue
            );
        }
        ActionContextMenuFactory.showNearButton(actionsContextMenu, actionsMenuButton);
    }

    @FXML
    private void onShowQueue() {
        BorderPane panelHost = resolveSidePanelHost();
        actionCoordinator.toggleQueue(
                startUpService,
                musicCardActionManager,
                panelHost,
                fullScreenModeController
        );
        iconManager.updateIconColor(QueueButton);
    }

    private BorderPane resolveSidePanelHost() {
        return sidePanelHost != null ? sidePanelHost : parentRoot;
    }

    @FXML
    private void onAddToPlaylist() {
        actionCoordinator.openAddToPlaylist(
                startUpService,
                musicCardActionManager,
                parentRoot,
                root,
                actionsMenuButton
        );
    }

    private void handleCoverClick(MouseEvent event) {
        if (musicCardActionManager == null) return;
        navigationHandler.openCurrentPlayingContext(pm, musicCardActionManager, coverImageView);
    }

    public void dispose() {
        fullScreenModeController.unbindPlayerMenuBar(this);
        if (timeBinder != null) {
            timeBinder.dispose();
            timeBinder = null;
        }
        if (iconManager != null) {
            iconManager.dispose();
        }
        if (layoutCoordinator != null) {
            layoutCoordinator.dispose();
        }
    }

    public void setVolumeSlider(double value) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> setVolumeSlider(value));
            return;
        }
        if (VolumeSlider != null) VolumeSlider.setValue(value);
    }

    public boolean handleGlobalKeyboardShortcut(KeyEvent event) {
        if (event == null || event.isConsumed()) return false;
        return switch (event.getCode()) {
            case SPACE -> {
                PlayPauseSong();
                yield true;
            }
            case LEFT -> {
                handleDirectionalShortcut(-5.0);
                yield true;
            }
            case RIGHT -> {
                handleDirectionalShortcut(5.0);
                yield true;
            }
            default -> false;
        };
    }

    private void handleDirectionalShortcut(double delta) {
        if (keyboardSliderTarget == KeyboardSliderTarget.VOLUME) {
            adjustVolumeBy(delta);
        } else {
            seekBy(delta);
        }
    }

    private void seekBy(double deltaSeconds) {
        MediaPlayer player = pm.getCurrentPlayer();
        if (player == null || SongTimerSlider == null) return;
        double current = player.getCurrentTime() == null
                ? SongTimerSlider.getValue()
                : player.getCurrentTime().toSeconds();
        double target = current + deltaSeconds;
        seekCurrentPlayerTo(target);
        SongTimerSlider.setValue(Math.max(
                SongTimerSlider.getMin(),
                Math.min(SongTimerSlider.getMax(), target)
        ));
    }

    private void adjustVolumeBy(double delta) {
        if (VolumeSlider == null) return;
        double target = Math.max(
                VolumeSlider.getMin(),
                Math.min(VolumeSlider.getMax(), VolumeSlider.getValue() + delta)
        );
        VolumeSlider.setValue(target);
    }

    @FXML
    private void onToggleFullScreenMode() {
        boolean active = fullScreenModeController.toggle(
                startUpService,
                pm.getCurrentSong()
        );
        if (FullScreenToggleButton != null) FullScreenToggleButton.setSelected(active);
        if (iconManager != null) iconManager.updateFullScreenIcon();
        updateToggleTooltips();
    }

    private void updateToggleTooltips() {
        if (iconManager != null) iconManager.updateTooltips();
    }

    private String replayTooltipText() {
        return ReplayToggleButton != null && ReplayToggleButton.isSelected()
                ? "Disable Replay Mode"
                : "Enable Replay Mode";
    }

    private String randomTooltipText() {
        return RandomToggleButton != null && RandomToggleButton.isSelected()
                ? "Disable Shuffle Mode"
                : "Enable Shuffle Mode";
    }

    private String fullScreenTooltipText() {
        return FullScreenToggleButton != null && FullScreenToggleButton.isSelected()
                ? "Exit Full Screen mode"
                : "Enter Full Screen Mode";
    }

    private void syncFullScreenToggle() {
        if (FullScreenToggleButton == null) return;
        boolean active = fullScreenModeController.isActive();
        if (FullScreenToggleButton.isSelected() != active) {
            FullScreenToggleButton.setSelected(active);
        }
        if (iconManager != null) iconManager.updateFullScreenIcon();
    }

    private void refreshMarquee() {
        if (trackPresenter != null) trackPresenter.refreshMarquee();
    }

    private void seekCurrentPlayerTo(double seconds) {
        MediaPlayer player = pm.getCurrentPlayer();
        if (player == null || SongTimerSlider == null) return;
        double safe = Math.max(
                SongTimerSlider.getMin(),
                Math.min(SongTimerSlider.getMax(), seconds)
        );
        player.seek(javafx.util.Duration.seconds(safe));
        if (CurrentMinuteLabel != null) CurrentMinuteLabel.setText(formatTime(safe));
    }

    private String formatTime(double seconds) {
        int total = (int) Math.floor(Math.max(0, seconds));
        return "%d:%02d".formatted(total / 60, total % 60);
    }

    public BorderPane getPlayerMenuBarRoot() {
        return root;
    }

    public BorderPane getParentRoot() {
        return parentRoot;
    }

    public void restorePlayerMenuBarAfterFullScreen() {
        if (layoutCoordinator != null) {
            layoutCoordinator.restoreAfterFullScreen();
        }
    }

    public void setFullScreenVisualState(boolean fullScreen) {
        if (layoutCoordinator != null) {
            layoutCoordinator.setFullScreenVisualState(fullScreen);
        }
        if (root != null) {
            root.setManaged(!fullScreen);
            root.setVisible(!fullScreen);
            root.setMouseTransparent(fullScreen);
            root.setOpacity(fullScreen ? 0.0 : 1.0);
        }
    }

    public void syncFullScreenStateFromController() {
        syncFullScreenToggle();
        updateToggleTooltips();
    }

    public StartUpService getStartUpService() {
        return startUpService;
    }

    public MusicCardActionManager getMusicCardActionManager() {
        return musicCardActionManager;
    }
}
