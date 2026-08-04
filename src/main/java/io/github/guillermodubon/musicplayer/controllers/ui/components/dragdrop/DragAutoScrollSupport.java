package io.github.guillermodubon.musicplayer.controllers.ui.components.dragdrop;

import javafx.animation.AnimationTimer;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.input.DragEvent;

import java.util.function.DoubleConsumer;

/**
 * Keeps a drag gesture moving when the pointer is held near a scrollable
 * viewport edge. The scroll operation remains owned by the caller so this
 * support works with both regular and virtualized lists.
 */
public final class DragAutoScrollSupport {

    private static final double EDGE_THRESHOLD = 72.0;
    private static final double MAX_PIXELS_PER_SECOND = 900.0;
    private static final long MAX_FRAME_NANOS = 50_000_000L;

    private final AnimationTimer timer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            scrollFrame(now);
        }
    };

    private Node viewport;
    private DoubleConsumer scrollBy = ignored -> { };
    private double pointerSceneY;
    private double direction;
    private long lastFrameNanos;

    public void bind(Node viewport, DoubleConsumer scrollBy) {
        stop();
        this.viewport = viewport;
        this.scrollBy = scrollBy == null ? ignored -> { } : scrollBy;
    }

    public void update(DragEvent event) {
        if (event == null || viewport == null || viewport.getScene() == null) {
            stop();
            return;
        }

        Bounds viewportBounds = viewport.localToScene(viewport.getBoundsInLocal());
        if (viewportBounds == null || viewportBounds.getHeight() <= 0) {
            stop();
            return;
        }

        pointerSceneY = event.getSceneY();
        direction = resolveDirection(pointerSceneY, viewportBounds);
        if (direction == 0.0) {
            stop();
            return;
        }

        if (lastFrameNanos == 0L) {
            lastFrameNanos = System.nanoTime();
        }
        timer.start();
    }

    public void stop() {
        direction = 0.0;
        lastFrameNanos = 0L;
        timer.stop();
    }

    private void scrollFrame(long now) {
        if (direction == 0.0 || viewport == null || viewport.getScene() == null) {
            stop();
            return;
        }

        Bounds viewportBounds = viewport.localToScene(viewport.getBoundsInLocal());
        if (viewportBounds == null || viewportBounds.getHeight() <= 0) {
            stop();
            return;
        }

        double currentDirection = resolveDirection(pointerSceneY, viewportBounds);
        if (currentDirection == 0.0) {
            stop();
            return;
        }
        direction = currentDirection;

        long elapsedNanos = lastFrameNanos == 0L
                ? 16_666_667L
                : Math.min(MAX_FRAME_NANOS, Math.max(0L, now - lastFrameNanos));
        lastFrameNanos = now;

        double distanceToEdge = direction < 0.0
                ? pointerSceneY - viewportBounds.getMinY()
                : viewportBounds.getMaxY() - pointerSceneY;
        double intensity = 1.0 - Math.max(0.0, distanceToEdge) / EDGE_THRESHOLD;
        intensity = Math.max(0.18, Math.min(1.0, intensity));

        scrollBy.accept(direction * MAX_PIXELS_PER_SECOND * intensity * elapsedNanos / 1_000_000_000.0);
    }

    private double resolveDirection(double pointerY, Bounds bounds) {
        if (pointerY <= bounds.getMinY() + EDGE_THRESHOLD) return -1.0;
        if (pointerY >= bounds.getMaxY() - EDGE_THRESHOLD) return 1.0;
        return 0.0;
    }
}
