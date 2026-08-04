package io.github.guillermodubon.musicplayer.controllers.ui.components.downloadComponents;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
import io.github.guillermodubon.musicplayer.controllers.ui.components.icons.SvgIconFactory;
import io.github.guillermodubon.musicplayer.services.downloads.activity.DownloadActivityTracker;

/**
 * Header-sized visual summary of active downloads. The arc follows the same
 * DownloadTask progress values bound to the individual download cells.
 */
public final class DownloadActivityIndicator extends StackPane {

    private static final String DOWNLOAD_ICON =
            "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/download_27dp_EDCC0E_FILL0_wght400_GRAD0_opsz24.svg";
    private static final double INDICATOR_SIZE = 44.0;
    private static final double CENTER = INDICATOR_SIZE / 2.0;
    private static final Color TRACK_COLOR = Color.web("#383F45");
    private static final Color PROGRESS_COLOR = Color.web("#0077B6");

    private final Pane activityLayer = new Pane();
    private final Arc progressArc = new Arc(CENTER, CENTER, 17.25, 17.25, 90, 0);
    private final Node downloadIcon = SvgIconFactory.icon(DOWNLOAD_ICON, 21);
    private final Label activeCountLabel = new Label();
    private final ScaleTransition pulse = new ScaleTransition(Duration.millis(760), downloadIcon);
    private Timeline progressAnimation;

    public DownloadActivityIndicator() {
        setMinSize(INDICATOR_SIZE, INDICATOR_SIZE);
        setPrefSize(INDICATOR_SIZE, INDICATOR_SIZE);
        setMaxSize(INDICATOR_SIZE, INDICATOR_SIZE);
        setMouseTransparent(true);
        getStyleClass().add("header-download-activity");

        activityLayer.setMinSize(INDICATOR_SIZE, INDICATOR_SIZE);
        activityLayer.setPrefSize(INDICATOR_SIZE, INDICATOR_SIZE);
        activityLayer.setMaxSize(INDICATOR_SIZE, INDICATOR_SIZE);
        StackPane.setAlignment(activityLayer, Pos.CENTER);

        Circle track = new Circle(CENTER, CENTER, 19, TRACK_COLOR);
        track.setMouseTransparent(true);

        progressArc.setType(ArcType.OPEN);
        progressArc.setFill(Color.TRANSPARENT);
        progressArc.setStroke(PROGRESS_COLOR);
        progressArc.setStrokeWidth(2.4);
        progressArc.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        progressArc.setMouseTransparent(true);

        activityLayer.getChildren().addAll(track, progressArc);
        activityLayer.setMouseTransparent(true);

        downloadIcon.setMouseTransparent(true);
        SvgIconFactory.setIconColor(downloadIcon, "#AFAFAF");

        activeCountLabel.getStyleClass().add("header-download-activity-count");
        activeCountLabel.setMinSize(17, 17);
        activeCountLabel.setPrefSize(17, 17);
        activeCountLabel.setMaxSize(17, 17);
        activeCountLabel.setAlignment(Pos.CENTER);
        activeCountLabel.setMouseTransparent(true);
        StackPane.setAlignment(activeCountLabel, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(activeCountLabel, new Insets(0, 2, 2, 0));

        getChildren().addAll(activityLayer, downloadIcon, activeCountLabel);

        pulse.setFromX(1.0);
        pulse.setFromY(1.0);
        pulse.setToX(1.04);
        pulse.setToY(1.04);
        pulse.setAutoReverse(true);
        pulse.setCycleCount(ScaleTransition.INDEFINITE);

        DownloadActivityTracker tracker = DownloadActivityTracker.getInstance();
        tracker.activeProperty().addListener((obs, oldValue, active) -> refresh(tracker));
        tracker.activeCountProperty().addListener((obs, oldValue, count) -> refresh(tracker));
        tracker.aggregateProgressProperty().addListener((obs, oldValue, progress) -> animateProgress(progress.doubleValue()));
        refresh(tracker);
    }

    public Node getDownloadIcon() {
        return downloadIcon;
    }

    public boolean isActive() {
        return activityLayer.isVisible();
    }

    private void refresh(DownloadActivityTracker tracker) {
        boolean active = tracker != null && tracker.isActive();
        activityLayer.setVisible(active);
        activeCountLabel.setVisible(active);
        activeCountLabel.setManaged(active);

        if (!active) {
            activeCountLabel.setText("");
            stopProgressAnimation();
            progressArc.setLength(0.0);
            pulse.stop();
            downloadIcon.setScaleX(1.0);
            downloadIcon.setScaleY(1.0);
            return;
        }

        activeCountLabel.setText(Integer.toString(tracker.getActiveCount()));
        animateProgress(tracker.getAggregateProgress());
        if (pulse.getStatus() != javafx.animation.Animation.Status.RUNNING) {
            pulse.play();
        }
    }

    private void animateProgress(double progress) {
        if (!activityLayer.isVisible()) return;
        double targetLength = -360.0 * Math.max(0.0, Math.min(1.0, progress));
        stopProgressAnimation();
        progressAnimation = new Timeline(new KeyFrame(
                Duration.millis(180),
                new KeyValue(progressArc.lengthProperty(), targetLength)
        ));
        progressAnimation.play();
    }

    private void stopProgressAnimation() {
        if (progressAnimation != null) {
            progressAnimation.stop();
            progressAnimation = null;
        }
    }
}
