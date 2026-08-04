package io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistManagementDialogs;

import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.TextInputControl;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

public final class PlaylistDialogWindowSupport {

    private PlaylistDialogWindowSupport() {
    }

    public static void configureFormDialog(Stage stage, Parent content, Window owner) {
        configure(stage, content, owner, 720, 470, 0.84, 0.82, 600, 430, 0.54, 0.48);
    }

    public static void configureCompactDialog(Stage stage, Parent content, Window owner) {
        configure(stage, content, owner, 430, 255, 0.70, 0.46, 350, 210, 0.34, 0.28);
    }

    public static void installDragHandling(Stage stage, Node dragRoot) {
        if (stage == null || dragRoot == null) return;

        final double[] dragOffset = new double[2];
        dragRoot.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() != MouseButton.PRIMARY || isNonDraggableTarget(event.getTarget())) return;
            dragOffset[0] = event.getScreenX() - stage.getX();
            dragOffset[1] = event.getScreenY() - stage.getY();
            event.consume();
        });

        dragRoot.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (!event.isPrimaryButtonDown() || isNonDraggableTarget(event.getTarget())) return;
            stage.setX(event.getScreenX() - dragOffset[0]);
            stage.setY(event.getScreenY() - dragOffset[1]);
            event.consume();
        });
    }

    private static void configure(Stage stage,
                                  Parent content,
                                  Window owner,
                                  double absoluteMaxWidth,
                                  double absoluteMaxHeight,
                                  double screenMaxWidthRatio,
                                  double screenMaxHeightRatio,
                                  double preferredMinWidth,
                                  double preferredMinHeight,
                                  double widthRatio,
                                  double heightRatio) {
        if (stage == null || content == null) return;

        Rectangle2D bounds = resolveScreenBounds(owner);
        double maxWidth = Math.min(absoluteMaxWidth, bounds.getWidth() * screenMaxWidthRatio);
        double maxHeight = Math.min(absoluteMaxHeight, bounds.getHeight() * screenMaxHeightRatio);
        double minWidth = Math.min(preferredMinWidth, maxWidth);
        double minHeight = Math.min(preferredMinHeight, maxHeight);
        double width = clamp(bounds.getWidth() * widthRatio, minWidth, maxWidth);
        double height = clamp(bounds.getHeight() * heightRatio, minHeight, maxHeight);

        stage.setResizable(false);
        stage.setWidth(width);
        stage.setHeight(height);

        if (content instanceof Region region) {
            region.setMinSize(minWidth, minHeight);
            region.setPrefSize(width, height);
            region.setMaxSize(width, height);
        }

        stage.setOnShown(event -> {
            stage.setWidth(width);
            stage.setHeight(height);
            stage.setX(bounds.getMinX() + (bounds.getWidth() - width) / 2.0);
            stage.setY(bounds.getMinY() + (bounds.getHeight() - height) / 2.0);
        });
    }

    private static Rectangle2D resolveScreenBounds(Window owner) {
        Screen screen = null;
        if (owner != null) {
            screen = Screen.getScreensForRectangle(
                            owner.getX(),
                            owner.getY(),
                            Math.max(1, owner.getWidth()),
                            Math.max(1, owner.getHeight()))
                    .stream()
                    .findFirst()
                    .orElse(null);
        }
        return (screen == null ? Screen.getPrimary() : screen).getVisualBounds();
    }

    private static boolean isNonDraggableTarget(Object target) {
        if (!(target instanceof Node node)) return false;

        Node current = node;
        while (current != null) {
            if (current instanceof ButtonBase
                    || current instanceof TextInputControl
                    || current instanceof ImageView) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private static double clamp(double value, double min, double max) {
        if (max < min) return max;
        return Math.max(min, Math.min(max, value));
    }
}
