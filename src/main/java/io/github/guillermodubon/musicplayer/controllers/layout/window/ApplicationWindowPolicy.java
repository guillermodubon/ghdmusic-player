package io.github.guillermodubon.musicplayer.controllers.layout.window;

import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.Objects;

public final class ApplicationWindowPolicy {

    private static final double DESIGN_MIN_WIDTH = 960;
    private static final double DESIGN_MIN_HEIGHT = 640;
    private static final double SCREEN_MARGIN = 24;
    private static final double INITIAL_SCREEN_RATIO = 0.90;

    private ApplicationWindowPolicy() {
    }

    public static void configureMainStage(Stage stage) {
        if (stage == null) return;

        stage.setResizable(true);

        Rectangle2D bounds = screenBoundsFor(stage);
        double availableWidth = Math.max(1, bounds.getWidth() - SCREEN_MARGIN * 2);
        double availableHeight = Math.max(1, bounds.getHeight() - SCREEN_MARGIN * 2);
        double minWidth = Math.min(DESIGN_MIN_WIDTH, availableWidth);
        double minHeight = Math.min(DESIGN_MIN_HEIGHT, availableHeight);
        double initialWidth = clamp(bounds.getWidth() * INITIAL_SCREEN_RATIO, minWidth, availableWidth);
        double initialHeight = clamp(bounds.getHeight() * INITIAL_SCREEN_RATIO, minHeight, availableHeight);

        stage.setMinWidth(minWidth);
        stage.setMinHeight(minHeight);
        stage.setWidth(initialWidth);
        stage.setHeight(initialHeight);
        stage.setX(bounds.getMinX() + (bounds.getWidth() - initialWidth) / 2);
        stage.setY(bounds.getMinY() + (bounds.getHeight() - initialHeight) / 2);
    }

    public static boolean toggleFullScreen(Scene scene) {
        if (scene == null) return false;
        Window window = scene.getWindow();
        if (!(window instanceof Stage stage)) return false;
        stage.setFullScreen(!stage.isFullScreen());
        return true;
    }

    private static Rectangle2D screenBoundsFor(Stage stage) {
        if (stage != null && stage.isShowing()) {
            return Screen.getScreensForRectangle(
                            stage.getX(),
                            stage.getY(),
                            Math.max(1, stage.getWidth()),
                            Math.max(1, stage.getHeight()))
                    .stream()
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(Screen.getPrimary())
                    .getVisualBounds();
        }
        return Screen.getPrimary().getVisualBounds();
    }

    private static double clamp(double value, double min, double max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }
}
