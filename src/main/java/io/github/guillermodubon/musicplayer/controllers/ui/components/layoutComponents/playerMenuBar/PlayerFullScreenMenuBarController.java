package io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.MediaPlayer;
import io.github.guillermodubon.musicplayer.controllers.ui.components.contextMenus.ActionContextMenuFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.helpers.PlayerMenuBarActionCoordinator;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.helpers.PlayerMenuBarIconManager;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.helpers.PlayerMenuSliderStyler;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.managers.PlayerMenuBarTimeBinder;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane.QueueController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.PlayerFullScreenModeController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.icons.SvgIconFactory;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

/**
 * Transparent playback controls used only by internal fullscreen mode.
 *
 * <p>This controller intentionally shares the playback services and focused
 * player-bar helpers with the normal bar. It owns only the fullscreen layout,
 * so the normal bar can remain initialized and synchronized while hidden.</p>
 */
public final class PlayerFullScreenMenuBarController {

    private static final String MORE_HORIZONTAL_ICON =
            "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/"
                    + "more_horiz_27dp_FAFAFA_FILL0_wght400_GRAD0_opsz24.svg";

    @FXML private BorderPane root;
    @FXML private VBox content;
    @FXML private HBox timeRow;
    @FXML private HBox transportRow;
    @FXML private HBox volumeRow;
    @FXML private StackPane songTimerSliderShell;
    @FXML private Slider songTimerSlider;
    @FXML private Region songTimerSliderBase;
    @FXML private Region songTimerSliderFill;
    @FXML private Label currentMinuteLabel;
    @FXML private Label songLengthLabel;
    @FXML private ToggleButton randomToggleButton;
    @FXML private Button previousButton;
    @FXML private ToggleButton playToggleButton;
    @FXML private Button nextButton;
    @FXML private ToggleButton replayToggleButton;
    @FXML private StackPane volumeIconHost;
    @FXML private StackPane volumeSliderShell;
    @FXML private Slider volumeSlider;
    @FXML private Region volumeSliderBase;
    @FXML private Region volumeSliderFill;

    private final PlaybackManager playbackManager = PlaybackManager.getInstance();
    private final PlayerFullScreenModeController fullScreenController =
            PlayerFullScreenModeController.getInstance();
    private final PlayerMenuBarActionCoordinator actionCoordinator =
            new PlayerMenuBarActionCoordinator();

    private PlayerMenuBarIconManager iconManager;
    private PlayerMenuBarTimeBinder timeBinder;
    private ContextMenu actionsContextMenu;
    private Button actionsMenuButton;
    private StartUpService startUpService;
    private MusicCardActionManager musicCardActionManager;
    private BorderPane parentRoot;
    private boolean initialized;

    @FXML
    private void initialize() {
        iconManager = new PlayerMenuBarIconManager(
                previousButton,
                nextButton,
                playToggleButton,
                null,
                replayToggleButton,
                randomToggleButton,
                null,
                null,
                volumeIconHost,
                volumeSlider,
                QueueController::isQueueVisible,
                this::replayTooltipText,
                this::randomTooltipText,
                null,
                "#C8C8C8",
                "#F0F0F0",
                "#F0F0F0",
                "#FFFFFF"
        );
        iconManager.setIconScale(0.86);
        iconManager.initialize();

        PlayerMenuSliderStyler.configureTimeSliderLayout(
                timeRow,
                songTimerSliderShell,
                songTimerSlider,
                300,
                700,
                900
        );
        PlayerMenuSliderStyler.configureProgressFill(
                songTimerSlider,
                songTimerSliderShell,
                songTimerSliderBase,
                songTimerSliderFill
        );
        PlayerMenuSliderStyler.installClickToValue(
                songTimerSlider,
                songTimerSliderShell,
                this::seekCurrentPlayerTo
        );
        PlayerMenuSliderStyler.configureFixedSliderShell(
                volumeSliderShell,
                volumeSlider,
                140,
                260,
                360
        );
        PlayerMenuSliderStyler.configureProgressFill(
                volumeSlider,
                volumeSliderShell,
                volumeSliderBase,
                volumeSliderFill
        );
        PlayerMenuSliderStyler.installClickToValue(volumeSlider, volumeSliderShell);
        configureProgressRegion(songTimerSliderBase, "rgba(235, 235, 235, 0.42)");
        configureProgressRegion(songTimerSliderFill, "rgba(235, 235, 235, 0.94)");
        configureProgressRegion(volumeSliderBase, "rgba(235, 235, 235, 0.42)");
        configureProgressRegion(volumeSliderFill, "rgba(235, 235, 235, 0.94)");
        ensureVolumeRailVisible();

        root.widthProperty().addListener((obs, oldWidth, newWidth) ->
                applyResponsiveLayout(newWidth.doubleValue(), root.getHeight())
        );
        root.heightProperty().addListener((obs, oldHeight, newHeight) ->
                applyResponsiveLayout(root.getWidth(), newHeight.doubleValue())
        );
        Platform.runLater(() -> {
            applyResponsiveLayout(root.getWidth(), root.getHeight());
            ensureVolumeRailVisible();
        });

        volumeSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            double volume = newValue.doubleValue() / 100.0;
            playbackManager.setLastVolume(volume);
            MediaPlayer player = playbackManager.getCurrentPlayer();
            if (player != null) {
                player.setVolume(volume);
            }
            iconManager.updateVolumeIcon();
            iconManager.updateVolumeSliderState();
        });
        volumeSlider.focusedProperty().addListener(
                (obs, oldValue, focused) -> iconManager.updateVolumeSliderState()
        );
        volumeSlider.valueChangingProperty().addListener(
                (obs, oldValue, changing) -> iconManager.updateVolumeSliderState()
        );
        randomToggleButton.selectedProperty().addListener((obs, oldValue, selected) -> {
            playbackManager.setRandomMode(selected);
            iconManager.updateRandomIcon();
        });
        replayToggleButton.selectedProperty().addListener((obs, oldValue, selected) -> {
            playbackManager.setReplayMode(selected);
            iconManager.updateReplayIcon();
        });

        root.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            switch (event.getCode()) {
                case SPACE -> {
                    playbackManager.togglePlayPause();
                    event.consume();
                }
                case LEFT -> {
                    seekBy(-5.0);
                    event.consume();
                }
                case RIGHT -> {
                    seekBy(5.0);
                    event.consume();
                }
                default -> {
                }
            }
        });
    }

    public void init(
            StartUpService service,
            MusicCardActionManager musicActions,
            BorderPane applicationRoot
    ) {
        startUpService = service != null ? service : StartUpService.getInstance();
        musicCardActionManager = musicActions;
        parentRoot = applicationRoot;
        initialized = true;
        refreshFromPlayback();
    }

    public BorderPane getRoot() {
        return root;
    }

    /** Updates the bar after the shared playback manager changes tracks. */
    public void updateCurrentSong(Song song) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> updateCurrentSong(song));
            return;
        }
        if (initialized) {
            refreshFromPlayback();
        }
    }

    /** Places the menu beside the fullscreen title and artist information. */
    public void configureActionsButton(Button button) {
        if (button == null) return;

        actionsMenuButton = button;
        button.setText("");
        button.setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
        button.setFocusTraversable(false);
        button.getStyleClass().add("player-fullscreen-actions-button");
        button.setGraphic(createMoreHorizontalIcon());
        button.setOnAction(event -> showActionsMenu());
    }

    public void dispose() {
        if (timeBinder != null) {
            timeBinder.dispose();
            timeBinder = null;
        }
        if (iconManager != null) {
            iconManager.dispose();
        }
        if (actionsContextMenu != null) {
            actionsContextMenu.hide();
            actionsContextMenu = null;
        }
        initialized = false;
    }

    @FXML
    private void previousSong() {
        playbackManager.previous();
    }

    @FXML
    private void playPauseSong() {
        playbackManager.togglePlayPause();
        Platform.runLater(() -> iconManager.updatePlayPauseIcon(
                playbackManager.getCurrentPlayer()
        ));
    }

    @FXML
    private void nextSong() {
        playbackManager.next();
    }

    private void refreshFromPlayback() {
        if (!initialized) return;

        MediaPlayer player = playbackManager.getCurrentPlayer();
        if (player != null) {
            bindTime(player);
        } else {
            if (timeBinder != null) timeBinder.unbind();
            iconManager.unbindPlaybackStatus();
        }

        volumeSlider.setValue(playbackManager.getLastVolume() * 100.0);
        randomToggleButton.setSelected(playbackManager.isRandomMode());
        replayToggleButton.setSelected(false);
        iconManager.updatePlayPauseIcon(player);
        iconManager.updateVolumeIcon();
        iconManager.updateVolumeSliderState();
    }

    private void applyResponsiveLayout(double width, double height) {
        if (width <= 0.0) return;

        boolean narrow = width < 500.0 || height < 560.0;
        boolean compact = !narrow && (width < 800.0 || height < 720.0);
        double sideSpacing = narrow ? 6.0 : compact ? 9.0 : 12.0;
        double controlSpacing = narrow ? 8.0 : compact ? 12.0 : 16.0;
        // The coordinator already gives this component the artwork width plus
        // the two time labels. Do not subtract a second set of margins here:
        // that keeps the time rail exactly as wide as the cover.
        double availableWidth = Math.max(220.0, width);
        double barWidth = Math.min(1040.0, availableWidth);
        double sliderMin = narrow ? 96.0 : compact ? 160.0 : 230.0;
        double volumeMin = narrow ? 92.0 : compact ? 132.0 : 180.0;
        double sliderWidth = Math.max(sliderMin, barWidth - 112.0);
        double volumeOffset = narrow ? 30.0 : compact ? 46.0 : 56.0;
        double volumeWidth = Math.max(volumeMin, sliderWidth - volumeOffset);

        if (content != null) {
            content.setSpacing(narrow ? 8.0 : compact ? 10.0 : 12.0);
            content.setPadding(new javafx.geometry.Insets(
                    narrow ? 4.0 : 10.0,
                    narrow ? 8.0 : 0.0,
                    narrow ? 8.0 : 12.0,
                    narrow ? 8.0 : 0.0
            ));
            content.setMinWidth(0.0);
            content.setPrefWidth(barWidth);
            content.setMaxWidth(barWidth);
        }
        if (timeRow != null) {
            timeRow.setSpacing(sideSpacing);
            timeRow.setMinWidth(Math.min(barWidth, sliderMin + 88.0));
            timeRow.setPrefWidth(barWidth);
            timeRow.setMaxWidth(barWidth);
        }
        if (songTimerSliderShell != null) {
            songTimerSliderShell.setMinWidth(sliderMin);
            songTimerSliderShell.setPrefWidth(sliderWidth);
            songTimerSliderShell.setMaxWidth(sliderWidth);
        }
        if (transportRow != null) {
            transportRow.setSpacing(controlSpacing);
            double transportWidth = Math.max(220.0, barWidth - 112.0);
            transportRow.setMinWidth(Math.min(barWidth, transportWidth));
            transportRow.setPrefWidth(transportWidth);
            transportRow.setMaxWidth(transportWidth);
        }
        if (volumeRow != null) {
            volumeRow.setSpacing(sideSpacing);
            volumeRow.setMinWidth(Math.min(barWidth, volumeMin + 36.0));
            double volumeRowWidth = Math.min(barWidth, volumeWidth + 36.0);
            volumeRow.setPrefWidth(volumeRowWidth);
            volumeRow.setMaxWidth(volumeRowWidth);
        }
        if (volumeSliderShell != null) {
            volumeSliderShell.setMinWidth(volumeMin);
            volumeSliderShell.setPrefWidth(volumeWidth);
            volumeSliderShell.setMaxWidth(volumeWidth);
            volumeSliderShell.setVisible(true);
            volumeSliderShell.setManaged(true);
            HBox.setHgrow(volumeSliderShell, Priority.ALWAYS);
        }
        if (volumeSlider != null) {
            volumeSlider.setVisible(true);
            volumeSlider.setManaged(true);
        }
        ensureVolumeRailVisible();
    }

    /**
     * Keeps the custom rail visible during the first JavaFX skin/layout pass.
     * The native track is intentionally transparent because the two regions
     * below it provide a consistent rail and progress fill on every theme.
     */
    private void ensureVolumeRailVisible() {
        if (volumeSliderShell == null || volumeSliderBase == null || volumeSliderFill == null) {
            return;
        }
        volumeSliderShell.setVisible(true);
        volumeSliderShell.setManaged(true);
        volumeSliderBase.setVisible(true);
        volumeSliderBase.setManaged(true);
        volumeSliderBase.setOpacity(1.0);
        volumeSliderFill.setVisible(true);
        volumeSliderFill.setManaged(true);
        volumeSliderFill.setOpacity(1.0);
        volumeSliderBase.toBack();
        volumeSliderFill.toFront();
        if (volumeSlider != null) {
            volumeSlider.toFront();
        }
        volumeSliderShell.requestLayout();
    }

    private void configureProgressRegion(Region region, String color) {
        if (region == null) return;
        region.setMinHeight(4.0);
        region.setPrefHeight(4.0);
        region.setMaxHeight(4.0);
        region.setStyle(
                "-fx-background-color: " + color + ";"
                        + "-fx-background-radius: 999;"
        );
    }

    private void bindTime(MediaPlayer player) {
        if (timeBinder == null) {
            timeBinder = new PlayerMenuBarTimeBinder(
                    songTimerSlider,
                    currentMinuteLabel,
                    songLengthLabel,
                    playbackManager::next
            );
        }
        timeBinder.bind(player);
        iconManager.bindPlaybackStatus(player);
    }

    private void showActionsMenu() {
        if (actionsMenuButton == null) return;
        if (actionsContextMenu == null) {
            actionsContextMenu = actionCoordinator.createActionsMenu(
                    this::openAddToPlaylist,
                    actionCoordinator::enqueueCurrentSong
            );
        }
        ActionContextMenuFactory.showNearButton(actionsContextMenu, actionsMenuButton);
    }

    private void openAddToPlaylist() {
        actionCoordinator.openAddToPlaylist(
                startUpService,
                musicCardActionManager,
                parentRoot,
                root,
                actionsMenuButton
        );
    }

    private Node createMoreHorizontalIcon() {
        Node icon = SvgIconFactory.icon(MORE_HORIZONTAL_ICON, 20.0);
        // SvgIconFactory scales a path to the bounds of its Region. This icon
        // has a naturally wide 4:1 geometry, so a square region would turn
        // its dots into vertical ovals. Keep a horizontal view box instead.
        if (icon instanceof Region region) {
            region.setMinSize(20.0, 5.0);
            region.setPrefSize(20.0, 5.0);
            region.setMaxSize(20.0, 5.0);
        }
        SvgIconFactory.setIconColor(icon, "#FAFAFA");
        icon.getStyleClass().add("player-fullscreen-more-horizontal-icon");
        return icon;
    }

    private void seekCurrentPlayerTo(double seconds) {
        MediaPlayer player = playbackManager.getCurrentPlayer();
        if (player == null) return;
        double safe = Math.max(
                songTimerSlider.getMin(),
                Math.min(songTimerSlider.getMax(), seconds)
        );
        player.seek(javafx.util.Duration.seconds(safe));
        currentMinuteLabel.setText(formatTime(safe));
    }

    private void seekBy(double deltaSeconds) {
        MediaPlayer player = playbackManager.getCurrentPlayer();
        if (player == null) return;
        double current = player.getCurrentTime() == null
                ? songTimerSlider.getValue()
                : player.getCurrentTime().toSeconds();
        seekCurrentPlayerTo(current + deltaSeconds);
    }

    private String replayTooltipText() {
        return replayToggleButton != null && replayToggleButton.isSelected()
                ? "Disable Replay Mode"
                : "Enable Replay Mode";
    }

    private String randomTooltipText() {
        return randomToggleButton != null && randomToggleButton.isSelected()
                ? "Disable Shuffle Mode"
                : "Enable Shuffle Mode";
    }

    private String formatTime(double seconds) {
        int total = (int) Math.floor(Math.max(0.0, seconds));
        return "%d:%02d".formatted(total / 60, total % 60);
    }
}
