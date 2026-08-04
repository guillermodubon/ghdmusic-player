package io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.base;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class DialogKeyboardSupport {

    private DialogKeyboardSupport() {
    }

    public static void install(Stage stage, Parent root, ButtonBase primaryAction) {
        if (stage == null) return;
        install(stage.getScene(), root, primaryAction, stage::close);
    }

    public static void install(Window window, Parent root, ButtonBase primaryAction) {
        if (window == null) return;
        install(window.getScene(), root, primaryAction, window::hide);
    }

    public static void install(Scene scene, Parent root, ButtonBase primaryAction, Runnable closeAction) {
        if (scene == null || root == null) return;

        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event == null || event.isConsumed()) return;

            if (event.getCode() == KeyCode.ESCAPE) {
                if (closeAction != null) closeAction.run();
                event.consume();
                return;
            }

            if (event.getCode() != KeyCode.ENTER) {
                return;
            }

            Node focused = scene.getFocusOwner();
            if (focused instanceof TextInputControl input) {
                TextInputControl nextInput = nextTextInputBelow(root, input);
                if (nextInput != null) {
                    nextInput.requestFocus();
                    event.consume();
                    return;
                }
            }

            firePrimary(primaryAction);
            event.consume();
        });
    }

    private static TextInputControl nextTextInputBelow(Parent root, TextInputControl current) {
        if (root == null || current == null) return null;

        List<TextInputControl> controls = new ArrayList<>();
        collectTextInputs(root, controls);
        if (controls.size() <= 1) return null;

        controls.sort(Comparator
                .comparingDouble(DialogKeyboardSupport::sceneY)
                .thenComparingDouble(DialogKeyboardSupport::sceneX));

        int currentIndex = controls.indexOf(current);
        if (currentIndex < 0 || currentIndex >= controls.size() - 1) {
            return null;
        }
        return controls.get(currentIndex + 1);
    }

    private static void collectTextInputs(Node node, List<TextInputControl> out) {
        if (node == null || out == null) return;
        if (node instanceof TextInputControl input
                && input.isVisible()
                && !input.isDisabled()) {
            out.add(input);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectTextInputs(child, out);
            }
        }
    }

    private static double sceneX(Node node) {
        return node == null ? 0 : node.localToScene(node.getBoundsInLocal()).getMinX();
    }

    private static double sceneY(Node node) {
        return node == null ? 0 : node.localToScene(node.getBoundsInLocal()).getMinY();
    }

    private static void firePrimary(ButtonBase primaryAction) {
        if (primaryAction == null || primaryAction.isDisabled()) return;
        primaryAction.fire();
    }
}
