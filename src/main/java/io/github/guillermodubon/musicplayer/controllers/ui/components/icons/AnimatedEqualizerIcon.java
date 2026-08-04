package io.github.guillermodubon.musicplayer.controllers.ui.components.icons;

import javafx.animation.Animation;
import javafx.animation.ScaleTransition;
import javafx.geometry.Pos;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

public final class AnimatedEqualizerIcon extends HBox {

    private static final Color ACTIVE_COLOR = Color.web("#0077B6");
    private static final double BAR_WIDTH = 2.4;
    private static final double BAR_HEIGHT = 16.0;
    private static final double[] TARGET_SCALES = {0.42, 0.78, 0.55, 0.92, 0.64};
    private static final double[] DURATIONS_MS = {310, 420, 275, 370, 335};

    private final List<ScaleTransition> animations = new ArrayList<>();

    public AnimatedEqualizerIcon() {
        setAlignment(Pos.CENTER);
        setSpacing(1.7);
        setMinSize(20, 20);
        setPrefSize(20, 20);
        setMaxSize(20, 20);
        setMouseTransparent(true);
        getStyleClass().add("animated-equalizer-icon");

        for (int i = 0; i < TARGET_SCALES.length; i++) {
            Rectangle bar = new Rectangle(BAR_WIDTH, BAR_HEIGHT, ACTIVE_COLOR);
            bar.setArcWidth(2);
            bar.setArcHeight(2);
            bar.setScaleY(TARGET_SCALES[i]);
            getChildren().add(bar);

            ScaleTransition animation = new ScaleTransition(Duration.millis(DURATIONS_MS[i]), bar);
            animation.setFromY(Math.max(0.25, TARGET_SCALES[i] * 0.45));
            animation.setToY(TARGET_SCALES[i]);
            animation.setAutoReverse(true);
            animation.setCycleCount(Animation.INDEFINITE);
            animation.setDelay(Duration.millis(i * 55L));
            animations.add(animation);
        }
    }

    public void setAnimating(boolean animating) {
        for (ScaleTransition animation : animations) {
            if (animating) {
                if (animation.getStatus() != Animation.Status.RUNNING) animation.play();
            } else {
                animation.pause();
            }
        }
    }

    public void stop() {
        for (ScaleTransition animation : animations) {
            animation.stop();
        }
    }
}
