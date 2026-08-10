package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.background;

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.util.Duration;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Crossfades bright and dark fullscreen color fields without recreating their
 * gradients. This gives the ambient background a slow tonal evolution while
 * keeping each frame limited to inexpensive node-opacity updates.
 */
public final class PlayerFullScreenColorToneMotionCoordinator {

    private static final Interpolator TONE_INTERPOLATOR = Interpolator.EASE_BOTH;

    private final List<Node> darkFields;
    private final List<Node> brightFields;
    private final Timeline toneMotion;
    private boolean running;
    private boolean disposed;

    public PlayerFullScreenColorToneMotionCoordinator(
            List<Node> darkFields,
            List<Node> brightFields
    ) {
        this.darkFields = List.copyOf(darkFields);
        this.brightFields = List.copyOf(brightFields);
        this.toneMotion = createToneMotion();
        resetOpacities();
    }

    public void play() {
        if (disposed || running || toneMotion == null) {
            return;
        }

        if (toneMotion.getStatus() != Animation.Status.PAUSED) {
            resetOpacities();
            toneMotion.jumpTo(Duration.millis(ThreadLocalRandom.current().nextDouble(
                    toneMotion.getCycleDuration().toMillis()
            )));
        }
        toneMotion.play();
        running = true;
    }

    public void pause() {
        if (disposed || !running || toneMotion == null) {
            return;
        }

        toneMotion.pause();
        running = false;
    }

    public void stop() {
        if (toneMotion != null) {
            toneMotion.stop();
        }
        resetOpacities();
        running = false;
    }

    public void dispose() {
        if (disposed) {
            return;
        }

        disposed = true;
        stop();
    }

    private Timeline createToneMotion() {
        Timeline timeline = new Timeline(
                frame(0.0, 0.96, 0.34),
                frame(8.0, 0.82, 0.62),
                frame(18.0, 0.60, 1.00),
                frame(29.0, 0.74, 0.70),
                frame(40.0, 0.96, 0.34)
        );
        timeline.setCycleCount(Animation.INDEFINITE);
        return timeline;
    }

    private KeyFrame frame(double seconds, double darkOpacity, double brightOpacity) {
        KeyValue[] values = new KeyValue[darkFields.size() + brightFields.size()];
        int index = 0;
        for (Node field : darkFields) {
            values[index++] = new KeyValue(
                    field.opacityProperty(),
                    darkOpacity,
                    TONE_INTERPOLATOR
            );
        }
        for (Node field : brightFields) {
            values[index++] = new KeyValue(
                    field.opacityProperty(),
                    brightOpacity,
                    TONE_INTERPOLATOR
            );
        }
        return new KeyFrame(Duration.seconds(seconds), values);
    }

    private void resetOpacities() {
        for (Node field : darkFields) {
            field.setOpacity(1.0);
        }
        for (Node field : brightFields) {
            field.setOpacity(1.0);
        }
    }
}
