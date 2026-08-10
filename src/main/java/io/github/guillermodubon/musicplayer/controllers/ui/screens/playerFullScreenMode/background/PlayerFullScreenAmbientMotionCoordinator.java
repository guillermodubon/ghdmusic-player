package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.background;

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.value.WritableValue;
import javafx.scene.CacheHint;
import javafx.scene.image.ImageView;
import javafx.util.Duration;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Coordinates the low-cost ambient movement shared by both fullscreen modes.
 *
 * <p>Only the cached, blurred artwork is transformed. The color glows remain
 * static while the dedicated blob layer provides the visible color movement;
 * this keeps the ambient depth without spending pulses on overlapping motion.</p>
 */
public final class PlayerFullScreenAmbientMotionCoordinator {

    private static final double AMBIENT_BASE_SCALE = 1.15;
    private static final Interpolator MOTION_INTERPOLATOR = Interpolator.EASE_BOTH;

    private final ImageView ambientArtwork;
    private final Timeline ambientMotion;

    private boolean running;
    private boolean disposed;

    public PlayerFullScreenAmbientMotionCoordinator(ImageView ambientArtwork) {
        this.ambientArtwork = ambientArtwork;

        configureCachedArtwork();
        this.ambientMotion = createAmbientArtworkMotion();
        primeRandomInitialState();
    }

    /** Starts or resumes the motion without rebuilding any transition. */
    public void play() {
        if (disposed || running || !hasAnimation()) {
            return;
        }

        if (!isPaused()) {
            resetTransforms();
            primeRandomInitialState();
        }

        if (ambientMotion != null) {
            ambientMotion.play();
        }
        running = true;
    }

    /** Pauses the current phase so the animation can resume without a jump. */
    public void pause() {
        if (disposed || !running) {
            return;
        }

        if (ambientMotion != null) {
            ambientMotion.pause();
        }
        running = false;
    }

    /** Stops the motion and returns all transformed nodes to their neutral state. */
    public void stop() {
        if (ambientMotion != null) {
            ambientMotion.stop();
        }
        resetTransforms();
        running = false;
    }

    public void dispose() {
        if (disposed) {
            return;
        }

        disposed = true;
        stop();
    }

    private Timeline createAmbientArtworkMotion() {
        if (ambientArtwork == null) {
            return null;
        }

        Timeline timeline = new Timeline(
                frame(0.0, -24.0, -16.0, AMBIENT_BASE_SCALE),
                frame(11.0, 18.0, -4.0, 1.20),
                frame(22.0, 24.0, 14.0, 1.22),
                frame(33.0, -16.0, 20.0, 1.18),
                frame(44.0, -24.0, -16.0, AMBIENT_BASE_SCALE)
        );
        timeline.setCycleCount(Animation.INDEFINITE);
        return timeline;
    }

    private KeyFrame frame(double seconds, double translateX, double translateY, double scale) {
        return new KeyFrame(
                Duration.seconds(seconds),
                keyValue(ambientArtwork.translateXProperty(), translateX),
                keyValue(ambientArtwork.translateYProperty(), translateY),
                keyValue(ambientArtwork.scaleXProperty(), scale),
                keyValue(ambientArtwork.scaleYProperty(), scale)
        );
    }

    private KeyValue keyValue(WritableValue<Number> property, double value) {
        return new KeyValue(property, value, MOTION_INTERPOLATOR);
    }

    private void configureCachedArtwork() {
        if (ambientArtwork == null) {
            return;
        }
        ambientArtwork.setCache(true);
        ambientArtwork.setCacheHint(CacheHint.SPEED);
    }

    private void primeRandomInitialState() {
        if (ambientMotion == null || ambientMotion.getCycleDuration().toMillis() <= 0.0) {
            return;
        }
        double randomMillis = ThreadLocalRandom.current().nextDouble(
                ambientMotion.getCycleDuration().toMillis()
        );
        ambientMotion.jumpTo(Duration.millis(randomMillis));
    }

    private boolean isPaused() {
        return ambientMotion != null && ambientMotion.getStatus() == Animation.Status.PAUSED;
    }

    private boolean hasAnimation() {
        return ambientMotion != null;
    }

    private void resetTransforms() {
        if (ambientArtwork == null) {
            return;
        }
        ambientArtwork.setTranslateX(0.0);
        ambientArtwork.setTranslateY(0.0);
        ambientArtwork.setScaleX(AMBIENT_BASE_SCALE);
        ambientArtwork.setScaleY(AMBIENT_BASE_SCALE);
    }
}
