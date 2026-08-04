package io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.helpers;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.MediaPlayer;
import io.github.guillermodubon.musicplayer.controllers.ui.components.icons.SvgIconFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.components.popups.SmallPopupTooltip;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Owns icons, colors, tooltips and playback-state visuals of the player bar. */
public final class PlayerMenuBarIconManager {

    private static final String ICON_ROOT = "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/";
    private static final String ICON_PREVIOUS = ICON_ROOT + "skip_previous_27dp_AFAFAF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_NEXT = ICON_ROOT + "skip_next_27dp_AFAFAF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_PLAY = ICON_ROOT + "play_arrow_27dp_FFFFFF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_PAUSE = ICON_ROOT + "pause_27dp_FFFFFF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_REPLAY = ICON_ROOT + "replay_27dp_AFAFAF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_SHUFFLE = ICON_ROOT + "shuffle_27dp_AFAFAF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_QUEUE = ICON_ROOT + "queue_music_27dp_AFAFAF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_FULLSCREEN = ICON_ROOT + "fullscreen_27dp_AFAFAF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_FULLSCREEN_EXIT = ICON_ROOT + "fullscreen_exit_27dp_AFAFAF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_VOLUME = ICON_ROOT + "volume_up_27dp_FFFFFF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_VOLUME_OFF = ICON_ROOT + "volume_off_27dp_FFFFFF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_NORMAL = "#AFAFAF";
    private static final String ICON_BRIGHT = "#FFFFFF";
    private static final String ICON_ACTIVE = "#0077B6FF";
    private static final String ICON_ACTIVE_HOVER = "#0A8FCEFF";
    private static final String ICON_BUTTON_STYLE = """
            -fx-background-color: transparent;
            -fx-background-insets: 0;
            -fx-border-color: transparent;
            -fx-border-width: 0;
            -fx-effect: null;
            -fx-focus-color: transparent;
            -fx-faint-focus-color: transparent;
            -fx-padding: 0;
            """;

    private final Button previousButton;
    private final Button nextButton;
    private final ToggleButton playToggleButton;
    private final Button actionsMenuButton;
    private final ToggleButton replayToggleButton;
    private final ToggleButton randomToggleButton;
    private final Button queueButton;
    private final ToggleButton fullScreenToggleButton;
    private final StackPane volumeIconHost;
    private final Slider volumeSlider;
    private final BooleanSupplier queueVisible;
    private final Supplier<String> replayTooltipText;
    private final Supplier<String> randomTooltipText;
    private final Supplier<String> fullScreenTooltipText;
    private final String normalIconColor;
    private final String brightIconColor;
    private final String activeIconColor;
    private final String activeHoverIconColor;

    private Node previousIcon;
    private Node nextIcon;
    private Node playPauseIcon;
    private Node moreIcon;
    private Node replayIcon;
    private Node randomIcon;
    private Node queueIcon;
    private Node fullScreenIcon;
    private Node volumeIcon;
    private String currentVolumeIconPath;
    private javafx.scene.control.Tooltip replayTooltip;
    private javafx.scene.control.Tooltip randomTooltip;
    private javafx.scene.control.Tooltip queueTooltip;
    private javafx.scene.control.Tooltip fullScreenTooltip;
    private MediaPlayer observedStatusPlayer;
    private ChangeListener<MediaPlayer.Status> playbackStatusListener;
    private double iconScale = 1.0;

    public PlayerMenuBarIconManager(Button previousButton,
                                    Button nextButton,
                                    ToggleButton playToggleButton,
                                    Button actionsMenuButton,
                                    ToggleButton replayToggleButton,
                                    ToggleButton randomToggleButton,
                                    Button queueButton,
                                    ToggleButton fullScreenToggleButton,
                                    StackPane volumeIconHost,
                                    Slider volumeSlider,
                                    BooleanSupplier queueVisible,
                                    Supplier<String> replayTooltipText,
                                    Supplier<String> randomTooltipText,
                                    Supplier<String> fullScreenTooltipText) {
        this(
                previousButton,
                nextButton,
                playToggleButton,
                actionsMenuButton,
                replayToggleButton,
                randomToggleButton,
                queueButton,
                fullScreenToggleButton,
                volumeIconHost,
                volumeSlider,
                queueVisible,
                replayTooltipText,
                randomTooltipText,
                fullScreenTooltipText,
                ICON_NORMAL,
                ICON_BRIGHT,
                ICON_ACTIVE,
                ICON_ACTIVE_HOVER
        );
    }

    public PlayerMenuBarIconManager(Button previousButton,
                                    Button nextButton,
                                    ToggleButton playToggleButton,
                                    Button actionsMenuButton,
                                    ToggleButton replayToggleButton,
                                    ToggleButton randomToggleButton,
                                    Button queueButton,
                                    ToggleButton fullScreenToggleButton,
                                    StackPane volumeIconHost,
                                    Slider volumeSlider,
                                    BooleanSupplier queueVisible,
                                    Supplier<String> replayTooltipText,
                                    Supplier<String> randomTooltipText,
                                    Supplier<String> fullScreenTooltipText,
                                    String normalIconColor,
                                    String brightIconColor,
                                    String activeIconColor,
                                    String activeHoverIconColor) {
        this.previousButton = previousButton;
        this.nextButton = nextButton;
        this.playToggleButton = playToggleButton;
        this.actionsMenuButton = actionsMenuButton;
        this.replayToggleButton = replayToggleButton;
        this.randomToggleButton = randomToggleButton;
        this.queueButton = queueButton;
        this.fullScreenToggleButton = fullScreenToggleButton;
        this.volumeIconHost = volumeIconHost;
        this.volumeSlider = volumeSlider;
        this.queueVisible = queueVisible;
        this.replayTooltipText = replayTooltipText;
        this.randomTooltipText = randomTooltipText;
        this.fullScreenTooltipText = fullScreenTooltipText;
        this.normalIconColor = normalIconColor == null ? ICON_NORMAL : normalIconColor;
        this.brightIconColor = brightIconColor == null ? ICON_BRIGHT : brightIconColor;
        this.activeIconColor = activeIconColor == null ? ICON_ACTIVE : activeIconColor;
        this.activeHoverIconColor = activeHoverIconColor == null
                ? ICON_ACTIVE_HOVER : activeHoverIconColor;
    }

    public void initialize() {
        installIcons();
        installTooltips();
        updateAllIconColors();
        updateVolumeIcon();
    }

    /** Applies a presentation-only scale without changing playback logic. */
    public void setIconScale(double scale) {
        if (Double.isFinite(scale) && scale > 0.0) {
            iconScale = scale;
        }
    }

    public void updateAllIconColors() {
        updateIconColor(previousButton, previousIcon);
        updateIconColor(nextButton, nextIcon);
        updateIconColor(playToggleButton, playPauseIcon);
        updateIconColor(actionsMenuButton, moreIcon);
        updateToggleIconColor(replayToggleButton, replayIcon);
        updateToggleIconColor(randomToggleButton, randomIcon);
        updateIconColor(queueButton, queueIcon);
        updateFullScreenIcon();
    }

    public void updateIconColor(ButtonBase button) {
        if (button == null) return;
        updateIconColor(button, button.getGraphic());
    }

    public void updateRandomIcon() {
        updateToggleIconColor(randomToggleButton, randomIcon);
    }

    public void updateReplayIcon() {
        updateToggleIconColor(replayToggleButton, replayIcon);
    }

    public void updateFullScreenIcon() {
        if (fullScreenToggleButton == null) return;
        boolean active = fullScreenToggleButton.isSelected();
        Node icon = SvgIconFactory.icon(
                active ? ICON_FULLSCREEN_EXIT : ICON_FULLSCREEN,
                scaled(23)
        );
        fullScreenIcon = icon;
        fullScreenToggleButton.setGraphic(icon);
        updateIconColor(fullScreenToggleButton, icon);
    }

    public void updatePlayPauseIcon(MediaPlayer player) {
        if (playToggleButton == null) return;
        boolean playing = player != null && player.getStatus() == MediaPlayer.Status.PLAYING;
        playToggleButton.setSelected(playing);
        Node icon = SvgIconFactory.icon(playing ? ICON_PAUSE : ICON_PLAY, scaled(30));
        playPauseIcon = icon;
        playToggleButton.setGraphic(icon);
        updateIconColor(playToggleButton, icon);
    }

    public void bindPlaybackStatus(MediaPlayer player) {
        if (observedStatusPlayer == player) {
            updatePlayPauseIcon(player);
            return;
        }

        unbindPlaybackStatus();
        observedStatusPlayer = player;
        if (player == null) {
            updatePlayPauseIcon(null);
            return;
        }

        playbackStatusListener = (obs, oldStatus, newStatus) ->
                Platform.runLater(() -> updatePlayPauseIcon(player));
        player.statusProperty().addListener(playbackStatusListener);
        updatePlayPauseIcon(player);
    }

    public void unbindPlaybackStatus() {
        if (observedStatusPlayer != null && playbackStatusListener != null) {
            try {
                observedStatusPlayer.statusProperty().removeListener(playbackStatusListener);
            } catch (Exception ignored) {
            }
        }
        observedStatusPlayer = null;
        playbackStatusListener = null;
        updatePlayPauseIcon(null);
    }

    public void updateVolumeIcon() {
        if (volumeIconHost == null || volumeSlider == null) return;
        String iconPath = volumeSlider.getValue() <= volumeSlider.getMin()
                ? ICON_VOLUME_OFF
                : ICON_VOLUME;
        if (volumeIcon == null
                || volumeIconHost.getChildren().isEmpty()
                || !iconPath.equals(currentVolumeIconPath)) {
            currentVolumeIconPath = iconPath;
            volumeIcon = SvgIconFactory.icon(iconPath, scaled(22));
            SvgIconFactory.setIconColor(volumeIcon, ICON_BRIGHT);
            volumeIconHost.getChildren().setAll(volumeIcon);
        }
    }

    public void updateVolumeSliderState() {
        if (volumeSlider == null) return;
        boolean active = volumeSlider.isFocused() || volumeSlider.isValueChanging();
        volumeSlider.pseudoClassStateChanged(
                javafx.css.PseudoClass.getPseudoClass("active"),
                active
        );
    }

    public void updateTooltips() {
        if (replayTooltip != null && replayTooltipText != null) {
            replayTooltip.setText(replayTooltipText.get());
        }
        if (randomTooltip != null && randomTooltipText != null) {
            randomTooltip.setText(randomTooltipText.get());
        }
        if (fullScreenTooltip != null && fullScreenTooltipText != null) {
            fullScreenTooltip.setText(fullScreenTooltipText.get());
        }
    }

    public void dispose() {
        unbindPlaybackStatus();
    }

    private void installTooltips() {
        replayTooltip = SmallPopupTooltip.install(
                replayToggleButton,
                replayTooltipText == null ? "" : replayTooltipText.get()
        );
        randomTooltip = SmallPopupTooltip.install(
                randomToggleButton,
                randomTooltipText == null ? "" : randomTooltipText.get()
        );
        queueTooltip = SmallPopupTooltip.install(queueButton, "Manage \"Queue\"");
        fullScreenTooltip = SmallPopupTooltip.install(
                fullScreenToggleButton,
                fullScreenTooltipText == null ? "" : fullScreenTooltipText.get()
        );
    }

    private void installIcons() {
        previousIcon = installIconButton(previousButton, ICON_PREVIOUS, 25);
        nextIcon = installIconButton(nextButton, ICON_NEXT, 25);
        playPauseIcon = installIconButton(playToggleButton, ICON_PLAY, 30);
        moreIcon = installMeatballButton(actionsMenuButton);
        replayIcon = installIconButton(replayToggleButton, ICON_REPLAY, 23);
        randomIcon = installIconButton(randomToggleButton, ICON_SHUFFLE, 23);
        queueIcon = installIconButton(queueButton, ICON_QUEUE, 23);
        fullScreenIcon = installIconButton(fullScreenToggleButton, ICON_FULLSCREEN, 23);
    }

    private Node installIconButton(ButtonBase button, String iconPath, double size) {
        if (button == null) return null;
        button.setText("");
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setStyle(ICON_BUTTON_STYLE);
        Node icon = SvgIconFactory.icon(iconPath, scaled(size));
        button.setGraphic(icon);
        button.hoverProperty().addListener((obs, oldValue, newValue) -> updateIconColor(button));
        button.focusedProperty().addListener((obs, oldValue, newValue) -> updateIconColor(button));
        if (button instanceof ToggleButton toggleButton) {
            toggleButton.selectedProperty().addListener(
                    (obs, oldValue, newValue) -> updateIconColor(button)
            );
        }
        updateIconColor(button, icon);
        return icon;
    }

    private Node installMeatballButton(ButtonBase button) {
        if (button == null) return null;
        button.setText("");
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setStyle(ICON_BUTTON_STYLE);

        VBox icon = new VBox(4);
        icon.setAlignment(javafx.geometry.Pos.CENTER);
        icon.setMinSize(22, 22);
        icon.setPrefSize(22, 22);
        icon.setMaxSize(22, 22);
        icon.setMouseTransparent(true);
        icon.setFocusTraversable(false);
        icon.getStyleClass().add("player-meatball-icon");

        for (int i = 0; i < 3; i++) {
            Region dot = new Region();
            dot.setMinSize(6, 6);
            dot.setPrefSize(6, 6);
            dot.setMaxSize(6, 6);
            dot.getStyleClass().add("player-meatball-dot");
            icon.getChildren().add(dot);
        }

        button.setGraphic(icon);
        button.hoverProperty().addListener((obs, oldValue, newValue) -> updateIconColor(button));
        button.focusedProperty().addListener((obs, oldValue, newValue) -> updateIconColor(button));
        updateIconColor(button, icon);
        return icon;
    }

    private void updateIconColor(ButtonBase button, Node icon) {
        if (icon == null) return;
        boolean selected = button instanceof ToggleButton toggleButton
                && button != playToggleButton
                && toggleButton.isSelected();
        if (button == queueButton && queueVisible != null && queueVisible.getAsBoolean()) {
            selected = true;
        }

        boolean hover = button != null && (button.isHover() || button.isFocused());
        String color;
        if (selected) {
            color = hover ? activeHoverIconColor : activeIconColor;
        } else if (button == playToggleButton) {
            color = brightIconColor;
        } else {
            color = hover ? brightIconColor : normalIconColor;
        }

        if (!updateMeatballIconColor(icon, color)) {
            SvgIconFactory.setIconColor(icon, color);
        }
    }

    private boolean updateMeatballIconColor(Node icon, String color) {
        if (!(icon instanceof Parent parent)
                || !icon.getStyleClass().contains("player-meatball-icon")) {
            return false;
        }

        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof Region region) {
                region.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 999;");
            }
        }
        return true;
    }

    private double scaled(double size) {
        return size * iconScale;
    }

    private void updateToggleIconColor(ToggleButton button, Node icon) {
        updateIconColor(button, icon);
    }
}
