package io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.helpers;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Slider;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

import java.util.List;
import java.util.function.DoubleConsumer;

public final class PlayerMenuSliderStyler {

    private PlayerMenuSliderStyler() {
    }

    public static void configureTimeSliderLayout(HBox row,
                                                 StackPane shell,
                                                 Slider slider,
                                                 double minWidth,
                                                 double prefWidth,
                                                 double maxWidth) {
        if (row != null) {
            row.setMinWidth(minWidth);
            row.setPrefWidth(prefWidth);
            row.setMaxWidth(maxWidth);
            clipToBounds(row);
        }

        if (shell != null) {
            shell.setMinWidth(Math.max(120, minWidth - 40));
            shell.setPrefWidth(Math.max(160, prefWidth - 100));
            shell.setMaxWidth(Math.max(180, maxWidth - 70));
            HBox.setHgrow(shell, Priority.ALWAYS);
            clipToBounds(shell);
        }

        bindSliderWidthToShell(slider, shell);
    }

    public static void configureFixedSliderShell(StackPane shell,
                                                 Slider slider,
                                                 double minWidth,
                                                 double prefWidth,
                                                 double maxWidth) {
        if (shell != null) {
            shell.setMinWidth(minWidth);
            shell.setPrefWidth(prefWidth);
            shell.setMaxWidth(maxWidth);
            HBox.setHgrow(shell, Priority.NEVER);
            clipToBounds(shell);
        }

        bindSliderWidthToShell(slider, shell);
    }

    public static void bindSliderWidthToShell(Slider slider, StackPane shell) {
        if (slider == null || shell == null) return;
        slider.setMinWidth(0);
        if (!slider.prefWidthProperty().isBound()) {
            slider.prefWidthProperty().bind(shell.widthProperty());
        }
        if (!slider.maxWidthProperty().isBound()) {
            slider.maxWidthProperty().bind(shell.widthProperty());
        }
    }

    public static void configureProgressFill(Slider slider, StackPane shell, Region base, Region fill) {
        if (slider == null || shell == null || base == null || fill == null) return;

        StackPane.setAlignment(base, Pos.CENTER_LEFT);
        StackPane.setAlignment(fill, Pos.CENTER_LEFT);
        base.setMouseTransparent(true);
        fill.setMouseTransparent(true);

        bindTrackRegionToShell(base, shell);

        var fillWidth = Bindings.createDoubleBinding(() -> {
            double range = slider.getMax() - slider.getMin();
            if (range <= 0) {
                return 0.0;
            }

            double ratio = (slider.getValue() - slider.getMin()) / range;
            ratio = Math.max(0.0, Math.min(1.0, ratio));
            return shell.getWidth() * ratio;
        }, slider.valueProperty(), slider.minProperty(), slider.maxProperty(), shell.widthProperty());

        fill.minWidthProperty().bind(fillWidth);
        fill.prefWidthProperty().bind(fillWidth);
        fill.maxWidthProperty().bind(fillWidth);

        installNativeTrackHider(slider);
    }

    public static void installClickToValue(Slider slider, StackPane shell) {
        installClickToValue(slider, shell, null);
    }

    public static void installClickToValue(Slider slider, StackPane shell, DoubleConsumer afterValueSet) {
        if (slider == null || shell == null) return;

        shell.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() != MouseButton.PRIMARY || isNativeThumb(event.getTarget())) return;
            double value = setSliderValueFromMouse(slider, shell, localX(shell, event));
            if (afterValueSet != null) afterValueSet.accept(value);
            event.consume();
        });

        shell.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (!event.isPrimaryButtonDown() || isNativeThumb(event.getTarget())) return;
            double value = setSliderValueFromMouse(slider, shell, localX(shell, event));
            if (afterValueSet != null) afterValueSet.accept(value);
            event.consume();
        });
    }

    public static void clipToBounds(Region region) {
        if (region == null) return;
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(region.widthProperty());
        clip.heightProperty().bind(region.heightProperty());
        region.setClip(clip);
    }

    private static void installNativeTrackHider(Slider slider) {
        if (slider == null) return;
        Runnable hideTracks = () -> {
            hideNativeSliderTrack(slider);
            Node thumb = slider.lookup(".thumb");
            if (thumb != null) {
                thumb.toFront();
            }
        };
        Platform.runLater(hideTracks);
        slider.skinProperty().addListener((obs, oldSkin, newSkin) -> Platform.runLater(hideTracks));
        slider.sceneProperty().addListener((obs, oldScene, newScene) -> Platform.runLater(hideTracks));
        slider.widthProperty().addListener((obs, oldWidth, newWidth) -> Platform.runLater(hideTracks));
    }

    private static void hideNativeSliderTrack(Slider slider) {
        if (slider == null) return;
        slider.applyCss();
        for (String selector : List.of(".track", ".colored-track")) {
            for (Node node : slider.lookupAll(selector)) {
                node.setStyle("""
                        -fx-background-color: transparent;
                        -fx-background-insets: 0;
                        -fx-background-radius: 999;
                        -fx-opacity: 0;
                        """);
            }
        }
    }

    private static void bindTrackRegionToShell(Region region, StackPane shell) {
        if (region == null || shell == null) return;
        region.minWidthProperty().bind(shell.widthProperty());
        region.prefWidthProperty().bind(shell.widthProperty());
        region.maxWidthProperty().bind(shell.widthProperty());
    }

    private static double setSliderValueFromMouse(Slider slider, StackPane shell, double mouseX) {
        double width = shell.getWidth();
        if (width <= 0) return slider.getValue();

        double ratio = Math.max(0.0, Math.min(1.0, mouseX / width));
        double value = slider.getMin() + ratio * (slider.getMax() - slider.getMin());
        slider.setValue(value);
        return value;
    }

    private static double localX(StackPane shell, MouseEvent event) {
        return shell.sceneToLocal(event.getSceneX(), event.getSceneY()).getX();
    }

    private static boolean isNativeThumb(Object target) {
        if (!(target instanceof Node node)) return false;

        Node current = node;
        while (current != null) {
            if (current.getStyleClass().contains("thumb")) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }
}
