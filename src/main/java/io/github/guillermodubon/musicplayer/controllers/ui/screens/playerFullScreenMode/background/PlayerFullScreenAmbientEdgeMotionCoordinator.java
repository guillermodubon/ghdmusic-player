package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.background;

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.value.WritableValue;
import javafx.scene.Node;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Coordinates the slow corner and edge color fields used around fullscreen
 * playback. The fields move as grouped, transform-only layers so their
 * geometry and gradients remain static during rendering.
 */
public final class PlayerFullScreenAmbientEdgeMotionCoordinator {

    private static final Interpolator MOTION_INTERPOLATOR = Interpolator.EASE_BOTH;
    private static final double RESIZE_TOLERANCE = 24.0;

    private final Node bottomRightCornerGlow;
    private final Node topRibbonShade;
    private final Node bottomRibbonGlow;
    private final Node leftSideVeil;
    private final Node rightSideVeil;

    private Timeline cornerMotion;
    private Timeline horizontalEdgeMotion;
    private Timeline sideMotion;
    private double viewportWidth;
    private double viewportHeight;
    private boolean running;
    private boolean disposed;

    public PlayerFullScreenAmbientEdgeMotionCoordinator(
            Node bottomRightCornerGlow,
            Node topRibbonShade,
            Node bottomRibbonGlow,
            Node leftSideVeil,
            Node rightSideVeil
    ) {
        this.bottomRightCornerGlow = bottomRightCornerGlow;
        this.topRibbonShade = topRibbonShade;
        this.bottomRibbonGlow = bottomRibbonGlow;
        this.leftSideVeil = leftSideVeil;
        this.rightSideVeil = rightSideVeil;
    }

    /** Rebuilds the timelines only after a meaningful viewport change. */
    public void updateViewport(double width, double height) {
        if (disposed || width <= 1.0 || height <= 1.0 || isSameViewport(width, height)) {
            return;
        }

        boolean resumeAfterResize = running;
        MotionPhases phases = resumeAfterResize ? captureMotionPhases() : MotionPhases.ZERO;
        stopAnimations();
        viewportWidth = width;
        viewportHeight = height;

        cornerMotion = createCornerMotion();
        horizontalEdgeMotion = createHorizontalEdgeMotion();
        sideMotion = createSideMotion();

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

        pause(cornerMotion);
        pause(horizontalEdgeMotion);
        pause(sideMotion);
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
        cornerMotion = null;
        horizontalEdgeMotion = null;
        sideMotion = null;
    }

    private Timeline createCornerMotion() {
        double horizontal = cornerHorizontalTravel();
        double vertical = cornerVerticalTravel();
        return repeatingTimeline(
                frame(
                        0.0,
                        target(bottomRightCornerGlow, horizontal * 0.30, vertical * 0.20, 1.00, 1.02)
                ),
                frame(
                        11.2,
                        target(bottomRightCornerGlow, -horizontal * 0.64, -vertical * 0.14, 0.99, 1.04)
                ),
                frame(
                        23.2,
                        target(bottomRightCornerGlow, -horizontal * 0.12, -vertical * 0.66, 1.04, 0.99)
                ),
                frame(
                        35.2,
                        target(bottomRightCornerGlow, horizontal * 0.66, vertical * 0.08, 1.00, 1.03)
                ),
                frame(
                        48.0,
                        target(bottomRightCornerGlow, horizontal * 0.30, vertical * 0.20, 1.00, 1.02)
                )
        );
    }

    private Timeline createHorizontalEdgeMotion() {
        double horizontal = edgeHorizontalTravel();
        double vertical = edgeVerticalTravel();
        return repeatingTimeline(
                frame(
                        0.0,
                        target(topRibbonShade, -horizontal * 0.72, -vertical * 0.32, 1.01, 1.00),
                        target(bottomRibbonGlow, horizontal * 0.66, vertical * 0.38, 1.02, 1.00)
                ),
                frame(
                        10.5,
                        target(topRibbonShade, -horizontal * 0.08, vertical * 0.54, 1.00, 1.03),
                        target(bottomRibbonGlow, horizontal * 0.10, -vertical * 1.08, 1.04, 0.99)
                ),
                frame(
                        21.5,
                        target(topRibbonShade, horizontal * 0.76, vertical * 0.18, 1.03, 0.99),
                        target(bottomRibbonGlow, -horizontal * 0.78, -vertical * 0.68, 0.99, 1.04)
                ),
                frame(
                        33.5,
                        target(topRibbonShade, horizontal * 0.16, -vertical * 0.62, 0.99, 1.02),
                        target(bottomRibbonGlow, -horizontal * 0.20, vertical * 0.58, 1.02, 0.99)
                ),
                frame(
                        46.0,
                        target(topRibbonShade, -horizontal * 0.72, -vertical * 0.32, 1.01, 1.00),
                        target(bottomRibbonGlow, horizontal * 0.66, vertical * 0.38, 1.02, 1.00)
                )
        );
    }

    private Timeline createSideMotion() {
        double horizontal = sideHorizontalTravel();
        double vertical = sideVerticalTravel();
        return repeatingTimeline(
                frame(
                        0.0,
                        target(leftSideVeil, -horizontal * 0.18, -vertical * 0.82, 1.00, 1.02),
                        target(rightSideVeil, horizontal * 0.20, vertical * 0.74, 1.02, 1.00)
                ),
                frame(
                        12.5,
                        target(leftSideVeil, horizontal * 0.48, -vertical * 0.12, 1.03, 0.99),
                        target(rightSideVeil, -horizontal * 0.44, vertical * 0.08, 0.99, 1.03)
                ),
                frame(
                        25.0,
                        target(leftSideVeil, horizontal * 0.08, vertical * 0.78, 0.99, 1.04),
                        target(rightSideVeil, -horizontal * 0.10, -vertical * 0.72, 1.04, 0.99)
                ),
                frame(
                        37.5,
                        target(leftSideVeil, -horizontal * 0.50, vertical * 0.16, 1.02, 1.00),
                        target(rightSideVeil, horizontal * 0.52, -vertical * 0.14, 1.00, 1.02)
                ),
                frame(
                        52.0,
                        target(leftSideVeil, -horizontal * 0.18, -vertical * 0.82, 1.00, 1.02),
                        target(rightSideVeil, horizontal * 0.20, vertical * 0.74, 1.02, 1.00)
                )
        );
    }

    private Timeline repeatingTimeline(KeyFrame... frames) {
        Timeline timeline = new Timeline(frames);
        timeline.setCycleCount(Animation.INDEFINITE);
        return timeline;
    }

    private KeyFrame frame(double seconds, MotionTarget... targets) {
        List<KeyValue> values = new ArrayList<>(targets.length * 4);
        for (MotionTarget target : targets) {
            if (target.node() == null) {
                continue;
            }
            values.add(keyValue(target.node().translateXProperty(), target.translateX()));
            values.add(keyValue(target.node().translateYProperty(), target.translateY()));
            values.add(keyValue(target.node().scaleXProperty(), target.scaleX()));
            values.add(keyValue(target.node().scaleYProperty(), target.scaleY()));
        }
        return new KeyFrame(Duration.seconds(seconds), values.toArray(new KeyValue[0]));
    }

    private MotionTarget target(
            Node node,
            double translateX,
            double translateY,
            double scaleX,
            double scaleY
    ) {
        return new MotionTarget(node, translateX, translateY, scaleX, scaleY);
    }

    private KeyValue keyValue(WritableValue<Number> property, double value) {
        return new KeyValue(property, value, MOTION_INTERPOLATOR);
    }

    private double cornerHorizontalTravel() {
        return clamp(viewportWidth * 0.14, 100.0, 240.0);
    }

    private double cornerVerticalTravel() {
        return clamp(viewportHeight * 0.14, 80.0, 190.0);
    }

    private double edgeHorizontalTravel() {
        return clamp(viewportWidth * 0.20, 150.0, 340.0);
    }

    private double edgeVerticalTravel() {
        return clamp(viewportHeight * 0.20, 130.0, 310.0);
    }

    private double sideHorizontalTravel() {
        return clamp(viewportWidth * 0.10, 70.0, 170.0);
    }

    private double sideVerticalTravel() {
        return clamp(viewportHeight * 0.20, 120.0, 300.0);
    }

    private boolean isSameViewport(double width, double height) {
        return Math.abs(viewportWidth - width) < RESIZE_TOLERANCE
                && Math.abs(viewportHeight - height) < RESIZE_TOLERANCE;
    }

    private void primeRandomPhases() {
        jumpToRandomPhase(cornerMotion);
        jumpToRandomPhase(horizontalEdgeMotion);
        jumpToRandomPhase(sideMotion);
    }

    private MotionPhases captureMotionPhases() {
        return new MotionPhases(
                currentProgress(cornerMotion),
                currentProgress(horizontalEdgeMotion),
                currentProgress(sideMotion)
        );
    }

    private void restoreMotionPhases(MotionPhases phases) {
        jumpToProgress(cornerMotion, phases.corners());
        jumpToProgress(horizontalEdgeMotion, phases.horizontalEdges());
        jumpToProgress(sideMotion, phases.sides());
    }

    private void jumpToRandomPhase(Timeline timeline) {
        if (timeline == null || timeline.getCycleDuration().toMillis() <= 0.0) {
            return;
        }
        timeline.jumpTo(Duration.millis(ThreadLocalRandom.current().nextDouble(
                timeline.getCycleDuration().toMillis()
        )));
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
        timeline.jumpTo(Duration.millis(
                timeline.getCycleDuration().toMillis() * clamp(progress, 0.0, 1.0)
        ));
    }

    private boolean isPaused() {
        return statusOf(cornerMotion) == Animation.Status.PAUSED
                || statusOf(horizontalEdgeMotion) == Animation.Status.PAUSED
                || statusOf(sideMotion) == Animation.Status.PAUSED;
    }

    private Animation.Status statusOf(Animation animation) {
        return animation == null ? Animation.Status.STOPPED : animation.getStatus();
    }

    private boolean hasAnimations() {
        return cornerMotion != null || horizontalEdgeMotion != null || sideMotion != null;
    }

    private void playAnimations() {
        play(cornerMotion);
        play(horizontalEdgeMotion);
        play(sideMotion);
    }

    private void stopAnimations() {
        stop(cornerMotion);
        stop(horizontalEdgeMotion);
        stop(sideMotion);
    }

    private void resetTransforms() {
        reset(bottomRightCornerGlow);
        reset(topRibbonShade);
        reset(bottomRibbonGlow);
        reset(leftSideVeil);
        reset(rightSideVeil);
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

    private record MotionTarget(
            Node node,
            double translateX,
            double translateY,
            double scaleX,
            double scaleY
    ) {
    }

    private record MotionPhases(double corners, double horizontalEdges, double sides) {
        private static final MotionPhases ZERO = new MotionPhases(0.0, 0.0, 0.0);
    }
}
