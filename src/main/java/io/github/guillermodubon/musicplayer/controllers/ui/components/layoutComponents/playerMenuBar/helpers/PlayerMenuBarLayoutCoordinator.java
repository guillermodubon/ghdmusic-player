package io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.helpers;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.control.Slider;
import java.util.function.DoubleConsumer;

/**
 * Keeps the player bar attached to its shell and owns all responsive sizing.
 * The coordinator deliberately changes only layout properties; playback and
 * visibility state remain owned by the controller/fullscreen service.
 */
public final class PlayerMenuBarLayoutCoordinator {

    private enum LayoutDensity {
        ULTRA_COMPACT,
        NARROW,
        COMPACT,
        REGULAR
    }

    private final BorderPane root;
    private final HBox playerMainRow;
    private final HBox nowPlayingRow;
    private final StackPane playerCoverWrap;
    private final ImageView coverImageView;
    private final VBox playerTrackText;
    private final StackPane playerTitleViewport;
    private final StackPane playerArtistsViewport;
    private final VBox playerPlaybackCenter;
    private final HBox playerTransportRow;
    private final HBox timeRow;
    private final StackPane timeSliderShell;
    private final Slider timeSlider;
    private final HBox rightControls;
    private final HBox volumeBox;
    private final StackPane volumeSliderShell;
    private final Slider volumeSlider;
    private final Runnable refreshMarquee;
    private final Runnable markTimeSliderTarget;
    private final Runnable markVolumeSliderTarget;
    private final DoubleConsumer onTimeValueSet;

    private Pane primaryHost;
    private ChangeListener<Number> primaryHostWidthListener;
    private ChangeListener<Number> primaryHostHeightListener;
    private boolean primaryHostLayoutScheduled;
    private boolean primaryHostReclaimScheduled;
    private LayoutDensity appliedLayoutDensity;

    public PlayerMenuBarLayoutCoordinator(BorderPane root,
                                          HBox playerMainRow,
                                          HBox nowPlayingRow,
                                          StackPane playerCoverWrap,
                                          ImageView coverImageView,
                                          VBox playerTrackText,
                                          StackPane playerTitleViewport,
                                          StackPane playerArtistsViewport,
                                          VBox playerPlaybackCenter,
                                          HBox playerTransportRow,
                                          HBox timeRow,
                                          StackPane timeSliderShell,
                                          Slider timeSlider,
                                          HBox rightControls,
                                          HBox volumeBox,
                                          StackPane volumeSliderShell,
                                          Slider volumeSlider,
                                          Runnable refreshMarquee,
                                          Runnable markTimeSliderTarget,
                                          Runnable markVolumeSliderTarget,
                                          DoubleConsumer onTimeValueSet) {
        this.root = root;
        this.playerMainRow = playerMainRow;
        this.nowPlayingRow = nowPlayingRow;
        this.playerCoverWrap = playerCoverWrap;
        this.coverImageView = coverImageView;
        this.playerTrackText = playerTrackText;
        this.playerTitleViewport = playerTitleViewport;
        this.playerArtistsViewport = playerArtistsViewport;
        this.playerPlaybackCenter = playerPlaybackCenter;
        this.playerTransportRow = playerTransportRow;
        this.timeRow = timeRow;
        this.timeSliderShell = timeSliderShell;
        this.timeSlider = timeSlider;
        this.rightControls = rightControls;
        this.volumeBox = volumeBox;
        this.volumeSliderShell = volumeSliderShell;
        this.volumeSlider = volumeSlider;
        this.refreshMarquee = refreshMarquee;
        this.markTimeSliderTarget = markTimeSliderTarget;
        this.markVolumeSliderTarget = markVolumeSliderTarget;
        this.onTimeValueSet = onTimeValueSet;
    }

    public void initialize() {
        bindRootToParentWidth();
        configureSliderLayout();
        configureResponsiveLayout();
    }

    public void setPrimaryHost(Pane host) {
        if (primaryHost == host) {
            schedulePrimaryHostLayout();
            return;
        }

        detachPrimaryHostListeners();
        primaryHost = host;
        installPrimaryHostListeners();
        schedulePrimaryHostLayout();
    }

    public boolean bringPrimaryHostToFront() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::bringPrimaryHostToFront);
            return false;
        }

        if (root == null || primaryHost == null) return false;
        if (root.getParent() != primaryHost) return restoreToPrimaryHost();

        layoutInPrimaryHost();
        primaryHost.setManaged(true);
        primaryHost.setVisible(true);
        primaryHost.setMouseTransparent(false);
        primaryHost.toFront();
        root.toFront();
        primaryHost.requestLayout();
        return true;
    }

    public boolean restoreToPrimaryHost() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::restoreToPrimaryHost);
            return false;
        }

        if (root == null || primaryHost == null) return false;

        Parent currentParent = root.getParent();
        if (currentParent != primaryHost) {
            if (currentParent instanceof Pane parentPane) {
                parentPane.getChildren().remove(root);
            }
            if (!primaryHost.getChildren().contains(root)) {
                primaryHost.getChildren().add(root);
            }
        }

        root.setManaged(true);
        root.setVisible(true);
        root.setMouseTransparent(false);
        root.setOpacity(1.0);
        root.setTranslateX(0.0);
        root.setTranslateY(0.0);
        root.setScaleX(1.0);
        root.setScaleY(1.0);
        root.setLayoutX(0.0);
        root.setLayoutY(0.0);

        primaryHost.setManaged(true);
        primaryHost.setVisible(true);
        primaryHost.setMouseTransparent(false);
        layoutInPrimaryHost();

        primaryHost.requestLayout();
        Parent shell = primaryHost.getParent();
        if (shell != null) {
            shell.requestLayout();
            Platform.runLater(() -> {
                shell.applyCss();
                shell.requestLayout();
                shell.layout();
            });
        }

        return root.getParent() == primaryHost;
    }

    public void synchronizePrimaryHostLayout() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::synchronizePrimaryHostLayout);
            return;
        }
        layoutInPrimaryHost();
    }

    public void restoreAfterFullScreen() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::restoreAfterFullScreen);
            return;
        }

        root.getStyleClass().remove("player-menu-bar-fullscreen");
        restoreToPrimaryHost();
        root.setManaged(true);
        root.setVisible(true);
        root.setMouseTransparent(false);
        root.setOpacity(1.0);
        root.setTranslateX(0.0);
        root.setTranslateY(0.0);
        root.setLayoutX(0.0);
        root.setLayoutY(0.0);
        reclaimPrimaryHostBottomSlot();
    }

    public void setFullScreenVisualState(boolean fullScreen) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> setFullScreenVisualState(fullScreen));
            return;
        }

        root.getStyleClass().remove("player-menu-bar-fullscreen");
        if (fullScreen) {
            root.getStyleClass().add("player-menu-bar-fullscreen");
            root.setManaged(true);
            root.setVisible(true);
            root.setMouseTransparent(false);
            root.setOpacity(1.0);
            root.setTranslateY(0.0);
        }
    }

    public void dispose() {
        detachPrimaryHostListeners();
    }

    private void installPrimaryHostListeners() {
        if (primaryHost == null) return;

        primaryHostWidthListener = (obs, oldValue, newValue) -> schedulePrimaryHostLayout();
        primaryHostHeightListener = (obs, oldValue, newValue) -> schedulePrimaryHostLayout();
        primaryHost.widthProperty().addListener(primaryHostWidthListener);
        primaryHost.heightProperty().addListener(primaryHostHeightListener);
    }

    private void detachPrimaryHostListeners() {
        if (primaryHost == null) return;

        if (primaryHostWidthListener != null) {
            primaryHost.widthProperty().removeListener(primaryHostWidthListener);
        }
        if (primaryHostHeightListener != null) {
            primaryHost.heightProperty().removeListener(primaryHostHeightListener);
        }

        primaryHostWidthListener = null;
        primaryHostHeightListener = null;
    }

    private void schedulePrimaryHostLayout() {
        if (primaryHostLayoutScheduled) return;

        primaryHostLayoutScheduled = true;
        Platform.runLater(() -> {
            primaryHostLayoutScheduled = false;
            layoutInPrimaryHost();
        });
    }

    private void layoutInPrimaryHost() {
        if (root == null || primaryHost == null || root.getParent() != primaryHost) return;

        syncPrimaryHostSize();
        if (primaryHost instanceof StackPane) {
            StackPane.setAlignment(root, Pos.BOTTOM_CENTER);
            StackPane.setMargin(root, Insets.EMPTY);
            root.setMinWidth(0.0);
            root.setMaxWidth(Double.MAX_VALUE);
            root.setLayoutX(0.0);
            root.setLayoutY(0.0);
            root.requestLayout();
            primaryHost.requestLayout();
            return;
        }

        double width = Math.max(0.0, primaryHost.getWidth());
        double height = resolvePrimaryHostBarHeight(width);
        double y = Math.max(0.0, primaryHost.getHeight() - height);
        root.resizeRelocate(0.0, y, width, height);
        root.requestLayout();
        primaryHost.requestLayout();
    }

    private void syncPrimaryHostSize() {
        if (root == null || primaryHost == null) return;

        double width = Math.max(0.0, primaryHost.getWidth());
        double barHeight = resolvePrimaryHostBarHeight(width);
        primaryHost.setMinWidth(0.0);
        primaryHost.setMaxWidth(Double.MAX_VALUE);
        primaryHost.setMinHeight(barHeight);
        primaryHost.setPrefHeight(barHeight);
        primaryHost.setMaxHeight(barHeight);
    }

    private double resolvePrimaryHostBarHeight(double width) {
        double barHeight = root.prefHeight(width);
        if (!Double.isFinite(barHeight) || barHeight <= 1.0) barHeight = root.minHeight(width);
        if (!Double.isFinite(barHeight) || barHeight <= 1.0) barHeight = root.getHeight();
        if (!Double.isFinite(barHeight) || barHeight <= 1.0) barHeight = 96.0;
        return Math.max(86.0, barHeight);
    }

    private void reclaimPrimaryHostBottomSlot() {
        if (primaryHost == null || root == null) return;

        Parent hostParent = primaryHost.getParent();
        if (hostParent instanceof BorderPane shell && shell.getBottom() != primaryHost) {
            shell.setBottom(primaryHost);
        }

        root.applyCss();
        primaryHost.applyCss();
        syncPrimaryHostSize();
        layoutInPrimaryHost();
        primaryHost.requestLayout();
        if (hostParent != null) hostParent.requestLayout();
        schedulePrimaryHostReclaim();
    }

    private void schedulePrimaryHostReclaim() {
        if (primaryHostReclaimScheduled) return;

        primaryHostReclaimScheduled = true;
        Platform.runLater(() -> {
            primaryHostReclaimScheduled = false;
            if (primaryHost == null || root == null || root.getParent() != primaryHost) return;

            Parent hostParent = primaryHost.getParent();
            if (hostParent instanceof BorderPane shell && shell.getBottom() != primaryHost) {
                shell.setBottom(primaryHost);
            }

            root.applyCss();
            syncPrimaryHostSize();
            layoutInPrimaryHost();
            primaryHost.requestLayout();
            if (hostParent != null) {
                hostParent.requestLayout();
                hostParent.layout();
            }
        });
    }

    private void bindRootToParentWidth() {
        if (root == null) return;
        root.setMaxWidth(Double.MAX_VALUE);
        root.parentProperty().addListener((obs, oldParent, newParent) -> bindWidthToParent(newParent));
        Platform.runLater(() -> bindWidthToParent(root.getParent()));
    }

    private void bindWidthToParent(Parent parent) {
        if (root.prefWidthProperty().isBound()) root.prefWidthProperty().unbind();
        if (parent instanceof Region region) {
            root.prefWidthProperty().bind(region.widthProperty());
        }
    }

    private void configureSliderLayout() {
        PlayerMenuSliderStyler.configureTimeSliderLayout(
                timeRow, timeSliderShell, timeSlider, 260, 640, 720
        );
        if (volumeBox != null) {
            volumeBox.setMinWidth(106);
            volumeBox.setPrefWidth(150);
            volumeBox.setMaxWidth(150);
            volumeBox.setFillHeight(false);
            HBox.setHgrow(volumeBox, Priority.NEVER);
            PlayerMenuSliderStyler.clipToBounds(volumeBox);
        }
        PlayerMenuSliderStyler.configureFixedSliderShell(
                volumeSliderShell, volumeSlider, 96, 118, 118
        );
        installKeyboardSliderTargetTracking();
        PlayerMenuSliderStyler.installClickToValue(timeSlider, timeSliderShell, onTimeValueSet);
        PlayerMenuSliderStyler.installClickToValue(volumeSlider, volumeSliderShell);
    }

    public void initializeProgressFill(Region timeBase,
                                       Region timeFill,
                                       Region volumeBase,
                                       Region volumeFill) {
        PlayerMenuSliderStyler.configureProgressFill(timeSlider, timeSliderShell, timeBase, timeFill);
        PlayerMenuSliderStyler.configureProgressFill(volumeSlider, volumeSliderShell, volumeBase, volumeFill);
    }

    private void installKeyboardSliderTargetTracking() {
        markSliderTargetOnMouse(timeSliderShell, markTimeSliderTarget);
        markSliderTargetOnMouse(timeSlider, markTimeSliderTarget);
        markSliderTargetOnMouse(timeRow, markTimeSliderTarget);
        markSliderTargetOnMouse(volumeBox, markVolumeSliderTarget);
        markSliderTargetOnMouse(volumeSliderShell, markVolumeSliderTarget);
        markSliderTargetOnMouse(volumeSlider, markVolumeSliderTarget);

        if (volumeSlider != null) {
            volumeSlider.focusedProperty().addListener((obs, oldValue, focused) -> {
                if (focused && markVolumeSliderTarget != null) markVolumeSliderTarget.run();
            });
        }
        if (timeSlider != null) {
            timeSlider.focusedProperty().addListener((obs, oldValue, focused) -> {
                if (focused && markTimeSliderTarget != null) markTimeSliderTarget.run();
            });
        }
    }

    private void markSliderTargetOnMouse(Region node, Runnable target) {
        if (node == null || target == null
                || Boolean.TRUE.equals(node.getProperties().get("keyboardSliderTargetInstalled"))) {
            return;
        }

        node.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> target.run());
        node.getProperties().put("keyboardSliderTargetInstalled", Boolean.TRUE);
    }

    private void configureResponsiveLayout() {
        if (root == null) return;
        root.widthProperty().addListener((obs, oldWidth, newWidth) ->
                applyResponsiveLayout(newWidth.doubleValue()));
        Platform.runLater(() -> applyResponsiveLayout(root.getWidth()));
    }

    private void applyResponsiveLayout(double availableWidth) {
        if (availableWidth <= 0) return;

        LayoutDensity density = availableWidth < 860
                ? LayoutDensity.ULTRA_COMPACT
                : availableWidth < 1120
                ? LayoutDensity.NARROW
                : availableWidth < 1540 ? LayoutDensity.COMPACT : LayoutDensity.REGULAR;
        if (density == appliedLayoutDensity) return;
        appliedLayoutDensity = density;

        switch (density) {
            case ULTRA_COMPACT -> applyLayoutMetrics(
                    8, 6, 46, 150, 190, 230, 280, 6, 8,
                    218, 280, 340, 112, 200, 300, 126, 96
            );
            case NARROW -> applyLayoutMetrics(
                    10, 8, 54, 220, 250, 280, 294, 8, 8,
                    244, 330, 430, 140, 230, 330, 126, 96
            );
            case COMPACT -> applyLayoutMetrics(
                    14, 12, 62, 250, 330, 300, 310, 12, 14,
                    260, 470, 570, 156, 370, 470, 122, 90
            );
            case REGULAR -> applyLayoutMetrics(
                    24, 24, 70, 290, 410, 340, 358, 18, 20,
                    280, 640, 720, 180, 540, 650, 150, 118
            );
        }

        if (refreshMarquee != null) refreshMarquee.run();
    }

    private void applyLayoutMetrics(double horizontalPadding,
                                    double mainSpacing,
                                    double coverSize,
                                    double nowPlayingMin,
                                    double nowPlayingPref,
                                    double playbackMin,
                                    double rightControlsWidth,
                                    double rightControlsSpacing,
                                    double transportSpacing,
                                    double timeRowMin,
                                    double timeRowPref,
                                    double timeRowMax,
                                    double timeSliderMin,
                                    double timeSliderPref,
                                    double timeSliderMax,
                                    double volumeBoxWidth,
                                    double volumeSliderWidth) {
        if (playerMainRow != null) {
            playerMainRow.setPadding(new Insets(10, horizontalPadding, 10, horizontalPadding));
            playerMainRow.setSpacing(mainSpacing);
        }

        setRegionSize(playerCoverWrap, coverSize, coverSize, coverSize);
        if (coverImageView != null) {
            coverImageView.setFitWidth(coverSize);
            coverImageView.setFitHeight(coverSize);
        }

        if (playerTrackText != null) {
            playerTrackText.setMinWidth(0);
            HBox.setHgrow(playerTrackText, Priority.ALWAYS);
        }
        if (nowPlayingRow != null) {
            nowPlayingRow.setMinWidth(nowPlayingMin);
            nowPlayingRow.setPrefWidth(nowPlayingPref);
            nowPlayingRow.setMaxWidth(Math.max(nowPlayingPref, nowPlayingMin));
            HBox.setHgrow(nowPlayingRow, Priority.NEVER);
        }
        if (playerTitleViewport != null) playerTitleViewport.setMinWidth(0);
        if (playerArtistsViewport != null) playerArtistsViewport.setMinWidth(0);

        if (playerPlaybackCenter != null) {
            playerPlaybackCenter.setMinWidth(playbackMin);
            playerPlaybackCenter.setPrefWidth(Math.max(playbackMin, timeRowPref));
            playerPlaybackCenter.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(playerPlaybackCenter, Priority.ALWAYS);
        }
        if (playerTransportRow != null) playerTransportRow.setSpacing(transportSpacing);

        if (timeRow != null) {
            timeRow.setMinWidth(timeRowMin);
            timeRow.setPrefWidth(timeRowPref);
            timeRow.setMaxWidth(timeRowMax);
        }
        if (timeSliderShell != null) {
            timeSliderShell.setMinWidth(timeSliderMin);
            timeSliderShell.setPrefWidth(timeSliderPref);
            timeSliderShell.setMaxWidth(timeSliderMax);
            HBox.setHgrow(timeSliderShell, Priority.ALWAYS);
        }

        if (rightControls != null) {
            rightControls.setSpacing(rightControlsSpacing);
            rightControls.setMinWidth(rightControlsWidth);
            rightControls.setPrefWidth(rightControlsWidth);
            rightControls.setMaxWidth(rightControlsWidth);
            HBox.setHgrow(rightControls, Priority.NEVER);
        }
        if (volumeBox != null) {
            volumeBox.setSpacing(volumeBoxWidth <= 106 ? 6 : 8);
            volumeBox.setMinWidth(volumeBoxWidth);
            volumeBox.setPrefWidth(volumeBoxWidth);
            volumeBox.setMaxWidth(volumeBoxWidth);
        }
        if (volumeSliderShell != null) {
            volumeSliderShell.setMinWidth(volumeSliderWidth);
            volumeSliderShell.setPrefWidth(volumeSliderWidth);
            volumeSliderShell.setMaxWidth(volumeSliderWidth);
        }
        if (volumeSlider != null) {
            // pref/max are bound to the shell by PlayerMenuSliderStyler.
            volumeSlider.setMinWidth(volumeSliderWidth);
        }
    }

    private void setRegionSize(Region region, double min, double pref, double max) {
        if (region == null) return;
        region.setMinSize(min, min);
        region.setPrefSize(pref, pref);
        region.setMaxSize(max, max);
    }
}
