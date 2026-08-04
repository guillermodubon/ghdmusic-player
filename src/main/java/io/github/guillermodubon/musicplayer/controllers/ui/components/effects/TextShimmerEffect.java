package io.github.guillermodubon.musicplayer.controllers.ui.components.effects;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.control.Labeled;
import javafx.scene.paint.Color;
import javafx.util.Duration;

public final class TextShimmerEffect {

    private static final String SHIMMER_TIMELINE_KEY = "textShimmer.timeline";

    private TextShimmerEffect() {
    }

    public static void apply(Labeled labeled) {
        apply(labeled, Color.web("#AFAFAF"), Color.web("#FFFFFF"));
    }

    public static void apply(Labeled labeled, Color base, Color highlight) {
        if (labeled == null) return;
        runFx(() -> {
            stop(labeled);
            Timeline timeline = new Timeline(
                    new KeyFrame(Duration.ZERO, new KeyValue(labeled.textFillProperty(), base)),
                    new KeyFrame(Duration.millis(650), new KeyValue(labeled.textFillProperty(), highlight)),
                    new KeyFrame(Duration.millis(1300), new KeyValue(labeled.textFillProperty(), base))
            );
            timeline.setCycleCount(Timeline.INDEFINITE);
            labeled.getProperties().put(SHIMMER_TIMELINE_KEY, timeline);
            timeline.play();
        });
    }

    public static void stop(Labeled labeled) {
        if (labeled == null) return;
        Object existing = labeled.getProperties().remove(SHIMMER_TIMELINE_KEY);
        if (existing instanceof Timeline timeline) {
            timeline.stop();
        }
    }

    public static void stop(Labeled labeled, Color finalColor) {
        if (labeled == null) return;
        runFx(() -> {
            stop(labeled);
            if (finalColor != null) labeled.setTextFill(finalColor);
        });
    }

    private static void runFx(Runnable action) {
        if (Platform.isFxApplicationThread()) action.run();
        else Platform.runLater(action);
    }
}
