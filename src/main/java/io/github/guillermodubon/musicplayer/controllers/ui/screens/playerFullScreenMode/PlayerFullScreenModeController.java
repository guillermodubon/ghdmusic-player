package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.PlayerMenuBarController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.artwork.PlayerFullScreenArtworkCoordinator;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.layout.PlayerFullScreenOverlayCoordinator;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.layout.PlayerFullScreenPlayerBarCoordinator;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.layout.PlayerFullScreenWindowTracker;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.state.PlayerFullScreenModeState;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.view.PlayerFullScreenView;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.view.PlayerFullScreenViewFactory;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

/**
 * Facade for the internal player fullscreen mode.
 *
 * <p>The public API remains intentionally small. Node creation, artwork,
 * window synchronization and fullscreen-bar coordination are
 * delegated to focused coordinators so this class only owns the lifecycle.</p>
 */
public final class PlayerFullScreenModeController {

    private static final PlayerFullScreenModeController INSTANCE =
            new PlayerFullScreenModeController();

    private PlayerMenuBarController playerMenuBarController;
    private final PlayerFullScreenModeState state = new PlayerFullScreenModeState();
    private BorderPane hostRoot;
    private boolean active;
    private boolean exitingFullScreen;

    private PlayerFullScreenView view;
    private StackPane fullScreenOverlay;
    private PlayerFullScreenOverlayCoordinator overlayCoordinator;
    private PlayerFullScreenPlayerBarCoordinator playerBarCoordinator;
    private PlayerFullScreenArtworkCoordinator artworkCoordinator;
    private PlayerFullScreenWindowTracker windowTracker;

    private PlayerFullScreenModeController() {
    }

    public static PlayerFullScreenModeController getInstance() {
        return INSTANCE;
    }

    public void bindPlayerMenuBar(PlayerMenuBarController controller) {
        if (controller == null) {
            return;
        }
        playerMenuBarController = controller;
        BorderPane controllerRoot = controller.getParentRoot();
        if (controllerRoot != null) {
            hostRoot = controllerRoot;
        }
    }

    public void unbindPlayerMenuBar(PlayerMenuBarController controller) {
        if (playerMenuBarController != controller) {
            return;
        }
        if (active) {
            exitFullScreenMode();
        }
        disposeRuntimeCoordinators();
        playerMenuBarController = null;
    }

    public void setHostRoot(BorderPane hostRoot) {
        this.hostRoot = hostRoot;
    }

    public boolean isActive() {
        return active;
    }

    /** Closes only the internal presentation; JavaFX native fullscreen is untouched. */
    public boolean exitIfActive() {
        if (!active) {
            return false;
        }
        if (Platform.isFxApplicationThread()) {
            exitFullScreenMode();
        } else {
            Platform.runLater(this::exitFullScreenMode);
        }
        return true;
    }

    public boolean toggle(StartUpService service, Song song) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> toggle(service, song));
            return active;
        }
        if (active) {
            exitFullScreenMode();
            return false;
        }
        enterFullScreenMode(service, song);
        return active;
    }

    public void updateCurrentSong(StartUpService service, Song song) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> updateCurrentSong(service, song));
            return;
        }
        if (active && artworkCoordinator != null) {
            artworkCoordinator.updateSong(service, song);
            if (playerBarCoordinator != null) {
                playerBarCoordinator.updateCurrentSong(song);
            }
        }
    }

    public void syncLayout(StartUpService service) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> syncLayout(service));
            return;
        }
        if (!active) {
            return;
        }
        synchronizeFullScreenLayoutNow();
    }

    private void enterFullScreenMode(StartUpService service, Song song) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> enterFullScreenMode(service, song));
            return;
        }
        if (active) {
            if (artworkCoordinator != null) {
                artworkCoordinator.updateSong(service, song);
            }
            return;
        }

        BorderPane resolvedHost = resolveHostRoot();
        if (resolvedHost == null || resolvedHost.getScene() == null
                || resolvedHost.getScene().getRoot() == null) {
            return;
        }

        PlayerFullScreenOverlayCoordinator newOverlayCoordinator =
                new PlayerFullScreenOverlayCoordinator();
        Pane overlayHost = newOverlayCoordinator.resolveHost(resolvedHost);
        if (overlayHost == null) {
            return;
        }

        hostRoot = resolvedHost;
        exitingFullScreen = false;
        active = true;
        state.setActive(true);

        PlayerFullScreenView newView = new PlayerFullScreenViewFactory().create(
                this::exitFullScreenMode,
                () -> active,
                this::updateArtworkViewport
        );
        StackPane newOverlay = newOverlayCoordinator.createOverlay(newView);
        newOverlayCoordinator.installPlayerMenuBarStylesheet(newView);

        if (!newOverlayCoordinator.attach(overlayHost, newView,
                this::updateArtworkViewport)) {
            active = false;
            state.setActive(false);
            return;
        }

        view = newView;
        fullScreenOverlay = newOverlay;
        overlayCoordinator = newOverlayCoordinator;

        playerBarCoordinator = new PlayerFullScreenPlayerBarCoordinator(
                () -> playerMenuBarController,
                () -> fullScreenOverlay,
                () -> view
        );
        playerBarCoordinator.attachToOverlay(service);
        playerBarCoordinator.configureActionsButton(view.actionsMenuButton());
        state.bindCloseButton(view.closeButton());
        installCloseButtonActivityTracking(view);

        artworkCoordinator = new PlayerFullScreenArtworkCoordinator(
                view,
                () -> active,
                () -> exitingFullScreen,
                this::updateArtworkViewport,
                this::openArtistFromFullScreen
        );
        artworkCoordinator.updateSong(service, song);

        Scene scene = hostRoot.getScene();
        windowTracker = new PlayerFullScreenWindowTracker(
                () -> active,
                this::synchronizeFullScreenLayoutNow
        );
        windowTracker.install(scene);

        if (playerMenuBarController != null) {
            playerMenuBarController.setFullScreenVisualState(true);
            playerBarCoordinator.layoutInOverlay();
            playerBarCoordinator.bringToFront();
        }

        Platform.runLater(() -> {
            if (!active || view == null || overlayCoordinator == null) {
                return;
            }
            synchronizeFullScreenLayoutNow();
            view.root().requestFocus();
        });
    }

    private void exitFullScreenMode() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::exitFullScreenMode);
            return;
        }
        if (!active || exitingFullScreen) {
            return;
        }

        exitingFullScreen = true;
        active = false;
        state.setActive(false);

        Pane overlayHostToRefresh = overlayCoordinator == null
                ? null : overlayCoordinator.host();
        Parent sceneRootToRefresh = hostRoot != null && hostRoot.getScene() != null
                ? hostRoot.getScene().getRoot() : null;

        try {
            if (windowTracker != null) {
                windowTracker.dispose();
            }
            if (artworkCoordinator != null) {
                artworkCoordinator.dispose();
            }
            if (view != null) {
                view.unbindLayoutProperties();
            }

            if (overlayCoordinator != null) {
                overlayCoordinator.detach();
            }
            if (playerBarCoordinator != null) {
                playerBarCoordinator.restoreToOriginalParent();
            }

            if (playerMenuBarController != null) {
                playerMenuBarController.setFullScreenVisualState(false);
                playerMenuBarController.restorePlayerMenuBarAfterFullScreen();
                playerMenuBarController.restoreToPrimaryHost();
                playerMenuBarController.restorePlayerMenuBarAfterFullScreen();
            }

            if (fullScreenOverlay != null) {
                fullScreenOverlay.getChildren().clear();
            }
            restoreHostVisualState();
        } catch (Exception error) {
            error.printStackTrace();
            restorePlayerBarAfterFailure();
        } finally {
            state.releaseCloseButton();
            clearRuntimeReferences();
        }

        Platform.runLater(() -> restoreAfterPulse(
                overlayHostToRefresh,
                sceneRootToRefresh
        ));
    }

    private void synchronizeFullScreenLayoutNow() {
        if (!active || view == null || overlayCoordinator == null) {
            return;
        }
        overlayCoordinator.resize();
        Pane host = overlayCoordinator.host();
        if (host != null) {
            host.applyCss();
            host.requestLayout();
            host.layout();
        }
        StackPane overlay = overlayCoordinator.overlay();
        if (overlay != null) {
            overlay.applyCss();
            overlay.requestLayout();
            overlay.layout();
        }
        view.root().applyCss();
        view.root().requestLayout();
        view.root().layout();
        if (playerBarCoordinator != null) {
            playerBarCoordinator.layoutInOverlay();
        }
        updateArtworkViewport();
        if (overlay != null) {
            overlay.toFront();
        }
        if (playerBarCoordinator != null) {
            playerBarCoordinator.bringToFront();
        }
    }

    private void updateArtworkViewport() {
        if (artworkCoordinator != null) {
            artworkCoordinator.updateArtworkViewport();
        }
        // The fullscreen bar derives its compact width from the artwork. Run
        // its layout immediately after the cover changes size so both remain
        // aligned during window resizes as well as on track changes.
        if (playerBarCoordinator != null) {
            playerBarCoordinator.layoutInOverlay();
        }
    }

    private void installCloseButtonActivityTracking(PlayerFullScreenView fullScreenView) {
        if (fullScreenView == null || fullScreenView.root() == null) return;
        fullScreenView.root().addEventFilter(MouseEvent.MOUSE_MOVED,
                event -> state.registerUserActivity());
        fullScreenView.root().addEventFilter(MouseEvent.MOUSE_PRESSED,
                event -> state.registerUserActivity());
        fullScreenView.root().addEventFilter(ScrollEvent.SCROLL,
                event -> state.registerUserActivity());
        fullScreenView.root().addEventFilter(KeyEvent.KEY_PRESSED,
                event -> state.registerUserActivity());
    }

    private void openArtistFromFullScreen(javafx.scene.Node anchor, io.github.guillermodubon.musicplayer.models.Artist artist) {
        if (artist == null) return;
        exitIfActive();
        Platform.runLater(() -> {
            if (playerMenuBarController != null) {
                playerMenuBarController.openArtistAfterFullScreen(artist);
            }
        });
    }

    private BorderPane resolveHostRoot() {
        if (hostRoot != null) {
            return hostRoot;
        }
        return playerMenuBarController == null
                ? null : playerMenuBarController.getParentRoot();
    }

    private void restoreHostVisualState() {
        if (hostRoot == null) {
            return;
        }
        hostRoot.setManaged(true);
        hostRoot.setVisible(true);
        hostRoot.setMouseTransparent(false);
        hostRoot.setOpacity(1.0);
        hostRoot.setTranslateX(0.0);
        hostRoot.setTranslateY(0.0);
        hostRoot.setScaleX(1.0);
        hostRoot.setScaleY(1.0);
        hostRoot.requestLayout();
    }

    private void restoreAfterPulse(Pane overlayHostToRefresh,
                                   Parent sceneRootToRefresh) {
        try {
            if (overlayHostToRefresh != null) {
                overlayHostToRefresh.applyCss();
                overlayHostToRefresh.requestLayout();
                overlayHostToRefresh.layout();
            }
            if (sceneRootToRefresh != null) {
                sceneRootToRefresh.applyCss();
                sceneRootToRefresh.requestLayout();
                sceneRootToRefresh.layout();
            }
            restoreHostVisualState();
            restorePlayerBarAfterPulse();
        } catch (Exception error) {
            error.printStackTrace();
        } finally {
            Platform.runLater(this::restoreAfterSecondPulse);
        }
    }

    private void restoreAfterSecondPulse() {
        try {
            if (hostRoot != null) {
                hostRoot.requestLayout();
                hostRoot.layout();
                hostRoot.requestFocus();
            }
            restorePlayerBarAfterPulse();
        } catch (Exception error) {
            error.printStackTrace();
        } finally {
            exitingFullScreen = false;
        }
    }

    private void restorePlayerBarAfterPulse() {
        if (playerMenuBarController == null) {
            return;
        }
        playerMenuBarController.restoreToPrimaryHost();
        playerMenuBarController.restorePlayerMenuBarAfterFullScreen();
        playerMenuBarController.syncFullScreenStateFromController();
    }

    private void restorePlayerBarAfterFailure() {
        try {
            if (playerBarCoordinator != null) {
                playerBarCoordinator.restoreToOriginalParent();
            }
            if (playerMenuBarController != null) {
                playerMenuBarController.setFullScreenVisualState(false);
                playerMenuBarController.restorePlayerMenuBarAfterFullScreen();
                playerMenuBarController.syncFullScreenStateFromController();
            }
        } catch (Exception ignored) {
        }
    }

    private void disposeRuntimeCoordinators() {
        if (windowTracker != null) {
            windowTracker.dispose();
        }
        if (artworkCoordinator != null) {
            artworkCoordinator.dispose();
        }
        if (playerBarCoordinator != null) {
            playerBarCoordinator.dispose();
        }
    }

    private void clearRuntimeReferences() {
        if (playerBarCoordinator != null) {
            playerBarCoordinator.dispose();
        }
        windowTracker = null;
        artworkCoordinator = null;
        playerBarCoordinator = null;
        overlayCoordinator = null;
        fullScreenOverlay = null;
        view = null;
    }
}
