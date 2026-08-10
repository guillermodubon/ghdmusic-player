package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.background;

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.value.WritableValue;
import javafx.scene.Node;
import javafx.util.Duration;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Owns the multi-point, transform-only motion of the fullscreen color blobs.
 *
 * <p>Timelines are rebuilt only after a meaningful viewport resize. During
 * playback they touch translate, scale and rotation properties exclusively;
 * paints, blur effects and layout geometry remain static.</p>
 */
public final class PlayerFullScreenColorBlobMotionCoordinator {

    private static final Interpolator MOTION_INTERPOLATOR = Interpolator.EASE_BOTH;
    private static final double RESIZE_TOLERANCE = 24.0;

    private final Node primaryBlob;
    private final Node secondaryBlob;

    private Timeline primaryMotion;
    private Timeline secondaryMotion;
    private double viewportWidth;
    private double viewportHeight;
    private boolean running;
    private boolean disposed;

    public PlayerFullScreenColorBlobMotionCoordinator(
            Node primaryBlob,
            Node secondaryBlob
    ) {
        this.primaryBlob = primaryBlob;
        this.secondaryBlob = secondaryBlob;
    }

    /** Rebuilds only when the available screen area materially changes. */
    public void updateViewport(double width, double height) {
        if (disposed || width <= 1.0 || height <= 1.0 || isSameViewport(width, height)) {
            return;
        }

        boolean resumeAfterResize = running;
        MotionPhases phases = resumeAfterResize ? captureMotionPhases() : MotionPhases.ZERO;
        stopAnimations();
        viewportWidth = width;
        viewportHeight = height;

        primaryMotion = createPrimaryMotion();
        secondaryMotion = createSecondaryMotion();

        if (resumeAfterResize) {
            restoreMotionPhases(phases);
            playAnimations();
            running = true;
        } else {
            resetTransforms();
        }
    }

    public void play() {
        if (disposed || running || !hasAnimations()) {
            return;
        }

        if (!isPaused()) {
            resetTransforms();
            primeRandomPhases();
        }
        playAnimations();
        running = true;
    }

    public void pause() {
        if (disposed || !running) {
            return;
        }

        pause(primaryMotion);
        pause(secondaryMotion);
        running = false;
    }

    public void stop() {
        stopAnimations();
        resetTransforms();
        running = false;
    }

    public void dispose() {
        if (disposed) {
            return;
        }

        disposed = true;
        stop();
        primaryMotion = null;
        secondaryMotion = null;
    }

    private Timeline createPrimaryMotion() {
        double horizontal = horizontalTravel();
        double vertical = verticalTravel();
        return repeatingTimeline(
                frame(0.0, primaryBlob, -horizontal * 0.92, -vertical * 0.76, 1.04, 1.17),
                frame(3.5, primaryBlob, -horizontal * 0.26, vertical * 0.94, 1.20, 0.98),
                frame(7.9, primaryBlob, horizontal * 0.98, vertical * 0.30, 0.98, 1.22),
                frame(12.5, primaryBlob, horizontal * 0.38, -vertical * 0.96, 1.18, 1.04),
                frame(16.5, primaryBlob, -horizontal * 0.92, -vertical * 0.76, 1.04, 1.17)
        );
    }

    private Timeline createSecondaryMotion() {
        double horizontal = horizontalTravel();
        double vertical = verticalTravel();
        return repeatingTimeline(
                frame(0.0, secondaryBlob, horizontal * 0.94, vertical * 0.78, 1.14, 0.96),
                frame(4.6, secondaryBlob, horizontal * 0.20, -vertical * 0.92, 0.98, 1.20),
                frame(9.7, secondaryBlob, -horizontal * 0.96, -vertical * 0.24, 1.17, 1.02),
                frame(15.0, secondaryBlob, -horizontal * 0.36, vertical * 0.96, 0.96, 1.21),
                frame(20.0, secondaryBlob, horizontal * 0.94, vertical * 0.78, 1.14, 0.96)
        );
    }

    private Timeline repeatingTimeline(KeyFrame... frames) {
        Timeline timeline = new Timeline(frames);
        timeline.setCycleCount(Animation.INDEFINITE);
        return timeline;
    }

    private KeyFrame frame(
            double seconds,
            Node node,
            double translateX,
            double translateY,
            double scaleX,
            double scaleY
    ) {
        return new KeyFrame(
                Duration.seconds(seconds),
                keyValue(node.translateXProperty(), translateX),
                keyValue(node.translateYProperty(), translateY),
                keyValue(node.scaleXProperty(), scaleX),
                keyValue(node.scaleYProperty(), scaleY)
        );
    }

    private KeyValue keyValue(WritableValue<Number> property, double value) {
        return new KeyValue(property, value, MOTION_INTERPOLATOR);
    }

    private double horizontalTravel() {
        return clamp(viewportWidth * 0.30, 260.0, 650.0);
    }

    private double verticalTravel() {
        return clamp(viewportHeight * 0.27, 190.0, 420.0);
    }

    private boolean isSameViewport(double width, double height) {
        return Math.abs(viewportWidth - width) < RESIZE_TOLERANCE
                && Math.abs(viewportHeight - height) < RESIZE_TOLERANCE;
    }

    private void primeRandomPhases() {
        jumpToRandomPhase(primaryMotion);
        jumpToRandomPhase(secondaryMotion);
    }

    private MotionPhases captureMotionPhases() {
        return new MotionPhases(
                currentProgress(primaryMotion),
                currentProgress(secondaryMotion)
        );
    }

    private void restoreMotionPhases(MotionPhases phases) {
        jumpToProgress(primaryMotion, phases.primary());
        jumpToProgress(secondaryMotion, phases.secondary());
    }

    private void jumpToRandomPhase(Timeline timeline) {
        if (timeline == null || timeline.getCycleDuration().toMillis() <= 0.0) {
            return;
        }
        double randomMillis = ThreadLocalRandom.current().nextDouble(
                timeline.getCycleDuration().toMillis()
        );
        timeline.jumpTo(Duration.millis(randomMillis));
    }

    private double currentProgress(Timeline timeline) {
        if (timeline == null || timeline.getCycleDuration().toMillis() <= 0.0) {
            return 0.0;
        }
        return timeline.getCurrentTime().toMillis() / timeline.getCycleDuration().toMillis();
    }

    private void jumpToProgress(Timeline timeline, double progress) {
        if (timeline == null || timeline.getCycleDuration().toMillis() <= 0.0) {
            return;
        }
        double normalizedProgress = clamp(progress, 0.0, 1.0);
        timeline.jumpTo(Duration.millis(
                timeline.getCycleDuration().toMillis() * normalizedProgress
        ));
    }

    private boolean isPaused() {
        return statusOf(primaryMotion) == Animation.Status.PAUSED
                || statusOf(secondaryMotion) == Animation.Status.PAUSED;
    }

    private Animation.Status statusOf(Animation animation) {
        return animation == null ? Animation.Status.STOPPED : animation.getStatus();
    }

    private boolean hasAnimations() {
        return primaryMotion != null || secondaryMotion != null;
    }

    private void playAnimations() {
        play(primaryMotion);
        play(secondaryMotion);
    }

    private void stopAnimations() {
        stop(primaryMotion);
        stop(secondaryMotion);
    }

    private void resetTransforms() {
        reset(primaryBlob);
        reset(secondaryBlob);
    }

    private void reset(Node node) {
        if (node == null) {
            return;
        }
        node.setTranslateX(0.0);
        node.setTranslateY(0.0);
        node.setScaleX(1.0);
        node.setScaleY(1.0);
        node.setRotate(0.0);
    }

    private void play(Animation animation) {
        if (animation != null) {
            animation.play();
        }
    }

    private void pause(Animation animation) {
        if (animation != null) {
            animation.pause();
        }
    }

    private void stop(Animation animation) {
        if (animation != null) {
            animation.stop();
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record MotionPhases(double primary, double secondary) {
        private static final MotionPhases ZERO = new MotionPhases(0.0, 0.0);
    }
}
