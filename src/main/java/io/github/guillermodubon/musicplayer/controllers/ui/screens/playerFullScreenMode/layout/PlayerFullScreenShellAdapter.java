package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.layout;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import io.github.guillermodubon.musicplayer.controllers.layout.AppShellController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.state.PlayerFullScreenModeState;

import java.net.URL;

public final class PlayerFullScreenShellAdapter {

    private static final String ACTIVE_CLASS = "player-full-screen-active";
    private static final String BAR_CLASS = "player-bar-full-screen";
    private static final String STYLESHEET =
            "/io/github/guillermodubon/musicplayer/Views/screens/playerFullScreenMode/full-screen-mode.css";

    public boolean enter(AppShellController shell, PlayerFullScreenModeState state, Image background) {
        if (shell == null || shell.getShellRoot() == null || state == null) return false;

        BorderPane shellRoot = shell.getShellRoot();
        state.setPreviousShellBackground(shellRoot.getBackground());
        installStylesheet(shellRoot);

        refreshActiveLayout(shell, state);
        updateBackground(shell, background);
        return true;
    }

    public void refreshActiveLayout(AppShellController shell, PlayerFullScreenModeState state) {
        if (shell == null || shell.getShellRoot() == null || state == null) return;

        BorderPane shellRoot = shell.getShellRoot();
        hide(state, shell.getLeftHost());
        hide(state, shell.getHeaderHost());
        BorderPane sidePanelHost = shell.getSidePanelHost();
        hide(state, sidePanelHost == null ? shellRoot.getRight() : sidePanelHost.getRight());
        if (shell.getCenterHost() != null) {
            hide(state, shell.getCenterHost().getCenter());
            hide(state, shell.getCenterHost().getRight());
        }

        addClass(shellRoot, ACTIVE_CLASS);
        addClass(currentBottom(shell), BAR_CLASS);
    }

    public void updateBackground(AppShellController shell, Image background) {
        if (shell == null || shell.getShellRoot() == null || background == null) return;

        if (!background.isError() && background.getProgress() < 1.0) {
            background.progressProperty().addListener((obs, oldProgress, newProgress) -> {
                if (newProgress.doubleValue() >= 1.0 && !background.isError()) {
                    Platform.runLater(() -> updateBackground(shell, background));
                }
            });
            return;
        }
        if (background.isError()) return;

        addClass(shell.getShellRoot(), ACTIVE_CLASS);
        addClass(currentBottom(shell), BAR_CLASS);

        BackgroundSize size = new BackgroundSize(
                100,
                100,
                true,
                true,
                true,
                false
        );
        BackgroundImage image = new BackgroundImage(
                background,
                BackgroundRepeat.NO_REPEAT,
                BackgroundRepeat.NO_REPEAT,
                BackgroundPosition.CENTER,
                size
        );
        shell.getShellRoot().setBackground(new Background(
                new BackgroundFill[]{new BackgroundFill(Color.web("#050607"), CornerRadii.EMPTY, Insets.EMPTY)},
                new BackgroundImage[]{image}
        ));
    }

    public void exit(AppShellController shell, PlayerFullScreenModeState state) {
        if (shell == null || shell.getShellRoot() == null || state == null) return;

        BorderPane shellRoot = shell.getShellRoot();
        removeClass(shellRoot, ACTIVE_CLASS);
        removeClass(currentBottom(shell), BAR_CLASS);

        shellRoot.setBackground(state.previousShellBackground());
        state.restoreVisibility();
    }

    private Parent currentBottom(AppShellController shell) {
        if (shell == null || shell.getBottomHost() == null || shell.getBottomHost().getChildren().isEmpty()) return null;
        Node bottom = shell.getBottomHost().getChildren().get(0);
        return bottom instanceof Parent parent ? parent : null;
    }

    private void hide(PlayerFullScreenModeState state, Node node) {
        if (node == null) return;
        state.rememberVisibility(node);
        node.setVisible(false);
        node.setManaged(false);
    }

    private void installStylesheet(Parent parent) {
        if (parent == null) return;
        URL css = getClass().getResource(STYLESHEET);
        if (css == null) return;
        String external = css.toExternalForm();
        if (!parent.getStylesheets().contains(external)) {
            parent.getStylesheets().add(external);
        }
    }

    private void addClass(Node node, String styleClass) {
        if (node != null && !node.getStyleClass().contains(styleClass)) {
            node.getStyleClass().add(styleClass);
        }
    }

    private void removeClass(Node node, String styleClass) {
        if (node != null) {
            node.getStyleClass().remove(styleClass);
        }
    }
}
