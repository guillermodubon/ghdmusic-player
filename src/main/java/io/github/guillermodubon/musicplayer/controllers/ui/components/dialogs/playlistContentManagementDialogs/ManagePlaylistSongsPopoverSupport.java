package io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistContentManagementDialogs;

import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.stage.Screen;
import org.controlsfx.control.PopOver;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.base.DialogKeyboardSupport;

public final class ManagePlaylistSongsPopoverSupport {
    private static final double SCREEN_MARGIN = 12;

    private ManagePlaylistSongsPopoverSupport() {
    }

    public static void configure(PopOver popOver, Node content, Node anchor) {
        if (popOver == null) return;

        popOver.setDetachable(false);
        popOver.setAutoHide(true);
        popOver.setArrowLocation(PopOver.ArrowLocation.TOP_RIGHT);
        popOver.setCornerRadius(10);
        popOver.setArrowSize(0);
        installDragHandling(popOver, content);
        installKeyboardHandling(popOver, content);

        if (content instanceof Region region) {
            Rectangle2D bounds = resolveScreenBounds(anchor);
            double maxWidth = Math.min(460, bounds.getWidth() - (SCREEN_MARGIN * 2));
            double maxHeight = Math.min(560, bounds.getHeight() - (SCREEN_MARGIN * 2));
            double width = clamp(bounds.getWidth() * 0.32, 380, maxWidth);
            double height = clamp(bounds.getHeight() * 0.66, 500, maxHeight);
            region.setMinSize(Math.min(360, width), Math.min(460, height));
            region.setPrefSize(width, height);
            region.setMaxSize(width, height);
        }
    }

    public static void show(PopOver popOver, Node anchor) {
        if (popOver == null || anchor == null) return;
        popOver.show(anchor);
        Platform.runLater(() -> {
            keepInsideScreen(popOver, anchor);
            Platform.runLater(() -> keepInsideScreen(popOver, anchor));
        });
    }

    private static void installKeyboardHandling(PopOver popOver, Node content) {
        if (popOver == null || !(content instanceof Parent parent)) return;
        if (Boolean.TRUE.equals(content.getProperties().get("managePlaylistKeyboardInstalled"))) return;

        content.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                DialogKeyboardSupport.install(newScene, parent, null, popOver::hide);
            }
        });
        if (content.getScene() != null) {
            DialogKeyboardSupport.install(content.getScene(), parent, null, popOver::hide);
        }
        content.getProperties().put("managePlaylistKeyboardInstalled", Boolean.TRUE);
    }

    private static void installDragHandling(PopOver popOver, Node dragRoot) {
        if (popOver == null || dragRoot == null) return;

        final double[] dragOffset = new double[2];
        dragRoot.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() != MouseButton.PRIMARY || isNonDraggableTarget(event.getTarget())) return;
            dragOffset[0] = event.getScreenX() - popOver.getX();
            dragOffset[1] = event.getScreenY() - popOver.getY();
            event.consume();
        });

        dragRoot.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (!event.isPrimaryButtonDown() || isNonDraggableTarget(event.getTarget())) return;
            popOver.setX(event.getScreenX() - dragOffset[0]);
            popOver.setY(event.getScreenY() - dragOffset[1]);
            event.consume();
        });
    }

    private static Rectangle2D resolveScreenBounds(Node anchor) {
        if (anchor != null) {
            Bounds bounds = anchor.localToScreen(anchor.getBoundsInLocal());
            if (bounds != null) {
                return Screen.getScreensForRectangle(
                                bounds.getMinX(),
                                bounds.getMinY(),
                                Math.max(1, bounds.getWidth()),
                                Math.max(1, bounds.getHeight()))
                        .stream()
                        .findFirst()
                        .orElse(Screen.getPrimary())
                        .getVisualBounds();
            }
        }
        return Screen.getPrimary().getVisualBounds();
    }

    private static void keepInsideScreen(PopOver popOver, Node anchor) {
        if (popOver == null || !popOver.isShowing()) return;

        Rectangle2D bounds = resolveScreenBounds(anchor);
        double width = popOver.getWidth();
        double height = popOver.getHeight();
        if (width <= 0 || height <= 0) return;

        double minX = bounds.getMinX() + SCREEN_MARGIN;
        double minY = bounds.getMinY() + SCREEN_MARGIN;
        double maxX = bounds.getMaxX() - width - SCREEN_MARGIN;
        double maxY = bounds.getMaxY() - height - SCREEN_MARGIN;

        popOver.setX(clamp(popOver.getX(), minX, Math.max(minX, maxX)));
        popOver.setY(clamp(popOver.getY(), minY, Math.max(minY, maxY)));
    }

    private static double clamp(double value, double min, double max) {
        if (max < min) return max;
        return Math.max(min, Math.min(max, value));
    }

    private static boolean isNonDraggableTarget(Object target) {
        if (!(target instanceof Node node)) return false;

        Node current = node;
        while (current != null) {
            if (current instanceof ButtonBase || current instanceof TextInputControl) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }
}
