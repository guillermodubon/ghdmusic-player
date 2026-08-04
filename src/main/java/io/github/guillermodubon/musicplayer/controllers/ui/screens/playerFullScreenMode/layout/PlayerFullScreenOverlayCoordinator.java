package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.layout;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.BorderPane;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.geometry.Rectangle2D;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.view.PlayerFullScreenView;

import java.net.URL;

/** Installs, sizes and removes the fullscreen overlay layer. */
public final class PlayerFullScreenOverlayCoordinator {

    private static final String ACTIVE_CLASS = "player-fullscreen-overlay";
    private static final String PLAYER_MENU_BAR_STYLESHEET =
            "/io/github/guillermodubon/musicplayer/Views/components/layoutComponents/playerMenuBar/player-menu-bar.css";

    private Pane host;
    private StackPane overlay;
    private javafx.beans.value.ChangeListener<Number> widthListener;
    private javafx.beans.value.ChangeListener<Number> heightListener;
    private Runnable updateViewport;

    public StackPane createOverlay(PlayerFullScreenView view) {
        StackPane created = new StackPane(view.root());
        created.setManaged(false);
        created.setPickOnBounds(true);
        created.setMinSize(0, 0);
        created.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        created.getStyleClass().add(ACTIVE_CLASS);
        StackPane.setAlignment(view.root(), Pos.CENTER);
        overlay = created;
        return created;
    }

    public Pane resolveHost(BorderPane hostRoot) {
        if (hostRoot == null || hostRoot.getScene() == null || hostRoot.getScene().getRoot() == null) {
            return null;
        }
        Parent sceneRoot = hostRoot.getScene().getRoot();
        return sceneRoot instanceof Pane pane ? pane : null;
    }

    public boolean attach(Pane host, PlayerFullScreenView view, Runnable updateViewport) {
        if (host == null || overlay == null) {
            return false;
        }
        this.host = host;
        this.updateViewport = updateViewport;
        boolean stackHost = host instanceof StackPane;
        overlay.setManaged(stackHost);
        overlay.setMinSize(0, 0);
        overlay.setPrefSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
        overlay.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        if (stackHost) {
            StackPane.setAlignment(overlay, Pos.CENTER);
            StackPane.setMargin(overlay, Insets.EMPTY);
        } else {
            overlay.setLayoutX(0);
            overlay.setLayoutY(0);
        }

        resize();
        widthListener = (obs, oldValue, newValue) -> {
            resize();
            if (this.updateViewport != null) {
                Platform.runLater(this.updateViewport);
            }
        };
        heightListener = (obs, oldValue, newValue) -> {
            resize();
            if (this.updateViewport != null) {
                Platform.runLater(this.updateViewport);
            }
        };
        host.widthProperty().addListener(widthListener);
        host.heightProperty().addListener(heightListener);
        if (!host.getChildren().contains(overlay)) {
            host.getChildren().add(overlay);
        }
        overlay.toFront();
        return true;
    }

    public void installPlayerMenuBarStylesheet(PlayerFullScreenView view) {
        URL stylesheet = view.root().getClass().getResource(PLAYER_MENU_BAR_STYLESHEET);
        if (stylesheet == null) {
            stylesheet = PlayerFullScreenOverlayCoordinator.class.getResource(PLAYER_MENU_BAR_STYLESHEET);
        }
        if (stylesheet == null) {
            return;
        }
        String externalForm = stylesheet.toExternalForm();
        if (!view.root().getStylesheets().contains(externalForm)) {
            view.root().getStylesheets().add(externalForm);
        }
    }

    public void resize() {
        if (host == null || overlay == null) {
            return;
        }
        if (host instanceof StackPane) {
            overlay.setManaged(true);
            overlay.setMinSize(0, 0);
            overlay.setPrefSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
            overlay.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            StackPane.setAlignment(overlay, Pos.CENTER);
            host.requestLayout();
            overlay.requestLayout();
            return;
        }

        Scene scene = host.getScene();
        double width = scene != null && scene.getWidth() > 1
                ? scene.getWidth() : Math.max(0, host.getWidth());
        double height = scene != null && scene.getHeight() > 1
                ? scene.getHeight() : Math.max(0, host.getHeight());

        Stage stage = scene != null && scene.getWindow() instanceof Stage sceneStage
                ? sceneStage : null;
        if (stage != null && stage.isFullScreen()) {
            var screens = Screen.getScreensForRectangle(
                    stage.getX(),
                    stage.getY(),
                    Math.max(1, stage.getWidth()),
                    Math.max(1, stage.getHeight())
            );
            Screen target = screens.isEmpty() ? Screen.getPrimary() : screens.getFirst();
            Rectangle2D bounds = target.getBounds();
            width = bounds.getWidth();
            height = bounds.getHeight();
        }
        overlay.resizeRelocate(0, 0, width, height);
        overlay.requestLayout();
    }

    public void detach() {
        if (host != null) {
            if (widthListener != null) {
                host.widthProperty().removeListener(widthListener);
            }
            if (heightListener != null) {
                host.heightProperty().removeListener(heightListener);
            }
            if (overlay != null) {
                host.getChildren().remove(overlay);
            }
            host.requestLayout();
        }
        widthListener = null;
        heightListener = null;
        updateViewport = null;
        host = null;
    }

    public Pane host() {
        return host;
    }

    public StackPane overlay() {
        return overlay;
    }

}
