package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.layout;

import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.PlayerFullScreenMenuBarController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.view.PlayerFullScreenView;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.PlayerMenuBarController;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.io.IOException;
import java.util.function.Supplier;

/** Installs and sizes the dedicated fullscreen playback bar. */
public final class PlayerFullScreenPlayerBarCoordinator {

    private static final String FULLSCREEN_BAR_FXML =
            "/io/github/guillermodubon/musicplayer/Views/components/layoutComponents/playerMenuBar/PlayerFullScreenMenuBar.fxml";
    /*
     * The fullscreen controls deliberately keep a narrower reading width than
     * the window. Besides matching the cover-first composition, this leaves
     * enough negative space on wide displays and prevents the slider rows
     * from feeling stretched.
     */
    private static final double MAX_BAR_WIDTH = 1040.0;
    private static final double HORIZONTAL_MARGIN = 24.0;
    private static final double BOTTOM_MARGIN = 16.0;

    private final Supplier<PlayerMenuBarController> controllerSupplier;
    private final Supplier<StackPane> overlaySupplier;
    private final Supplier<PlayerFullScreenView> viewSupplier;

    private BorderPane fullscreenBarRoot;
    private PlayerFullScreenMenuBarController fullscreenBarController;

    public PlayerFullScreenPlayerBarCoordinator(
            Supplier<PlayerMenuBarController> controllerSupplier,
            Supplier<StackPane> overlaySupplier,
            Supplier<PlayerFullScreenView> viewSupplier
    ) {
        this.controllerSupplier = controllerSupplier;
        this.overlaySupplier = overlaySupplier;
        this.viewSupplier = viewSupplier;
    }

    public void attachToOverlay() {
        PlayerMenuBarController controller = controllerSupplier.get();
        attachToOverlay(controller == null ? null : controller.getStartUpService());
    }

    public void attachToOverlay(StartUpService service) {
        PlayerMenuBarController normalBar = controllerSupplier.get();
        StackPane overlay = overlaySupplier.get();
        if (normalBar == null || overlay == null) {
            return;
        }

        if (fullscreenBarRoot == null) {
            loadFullscreenBar(normalBar, service);
        }
        if (fullscreenBarRoot == null) {
            return;
        }

        if (fullscreenBarRoot.getParent() != overlay) {
            if (fullscreenBarRoot.getParent() instanceof Pane currentParent) {
                currentParent.getChildren().remove(fullscreenBarRoot);
            }
            overlay.getChildren().add(fullscreenBarRoot);
        }
        alignInOverlay();
        fullscreenBarRoot.setManaged(true);
        fullscreenBarRoot.setVisible(true);
        fullscreenBarRoot.setMouseTransparent(false);
        fullscreenBarRoot.setOpacity(1.0);
        fullscreenBarRoot.toFront();
    }

    public void configureActionsButton(Button button) {
        if (fullscreenBarController != null) {
            fullscreenBarController.configureActionsButton(button);
        }
    }

    public void updateCurrentSong(Song song) {
        if (fullscreenBarController != null) {
            fullscreenBarController.updateCurrentSong(song);
        }
    }

    public void layoutInOverlay() {
        if (fullscreenBarRoot == null
                || fullscreenBarRoot.getParent() != overlaySupplier.get()) {
            return;
        }
        alignInOverlay();
        fullscreenBarRoot.requestLayout();
    }

    public void bringToFront() {
        if (fullscreenBarRoot != null) {
            fullscreenBarRoot.toFront();
        }
    }

    public void restoreToOriginalParent() {
        detachFullscreenBar();
    }

    public void dispose() {
        detachFullscreenBar();
    }

    private void loadFullscreenBar(
            PlayerMenuBarController normalBar,
            StartUpService service
    ) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource(FULLSCREEN_BAR_FXML)
            );
            BorderPane loadedRoot = loader.load();
            PlayerFullScreenMenuBarController loadedController = loader.getController();
            if (loadedController == null) {
                return;
            }

            loadedController.init(
                    service != null ? service : normalBar.getStartUpService(),
                    normalBar.getMusicCardActionManager(),
                    normalBar.getParentRoot()
            );
            fullscreenBarRoot = loadedRoot;
            fullscreenBarController = loadedController;
        } catch (IOException error) {
            error.printStackTrace();
        }
    }

    private void alignInOverlay() {
        StackPane overlay = overlaySupplier.get();
        if (overlay == null || fullscreenBarRoot == null) {
            return;
        }

        double horizontalMargin = overlay.getWidth() < 560.0
                ? 12.0 : HORIZONTAL_MARGIN;
        double bottomMargin = overlay.getHeight() < 620.0
                ? 8.0 : BOTTOM_MARGIN;
        double availableWidth = Math.max(0.0, overlay.getWidth() - horizontalMargin * 2.0);
        double coverWidth = resolveCoverWidth(availableWidth);
        // The time slider uses the cover width exactly; labels and their gaps
        // account for the remaining width of the compact control group.
        double desiredWidth = coverWidth + 112.0;
        double width = Math.min(MAX_BAR_WIDTH, Math.min(availableWidth, desiredWidth));
        if (width <= 0.0) {
            width = MAX_BAR_WIDTH;
        }

        if (fullscreenBarRoot.prefWidthProperty().isBound()) {
            fullscreenBarRoot.prefWidthProperty().unbind();
        }
        fullscreenBarRoot.setMinWidth(0.0);
        fullscreenBarRoot.setPrefWidth(width);
        fullscreenBarRoot.setMaxWidth(width);
        fullscreenBarRoot.setMinHeight(0.0);
        fullscreenBarRoot.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane.setAlignment(fullscreenBarRoot, Pos.TOP_CENTER);
        StackPane.setMargin(
                fullscreenBarRoot,
                new Insets(0.0, horizontalMargin, 0.0, horizontalMargin)
        );
        fullscreenBarRoot.setTranslateY(resolveMetadataBottom(overlay, bottomMargin));
    }

    private double resolveCoverWidth(double availableWidth) {
        PlayerFullScreenView view = viewSupplier == null ? null : viewSupplier.get();
        if (view == null || view.artworkContainer() == null) {
            return Math.min(availableWidth - 112.0, 420.0);
        }
        double width = view.artworkContainer().getWidth();
        if (width <= 1.0) {
            width = view.artworkContainer().prefWidth(-1.0);
        }
        return Math.max(140.0, Math.min(width, Math.max(140.0, availableWidth - 112.0)));
    }

    private double resolveMetadataBottom(StackPane overlay, double bottomMargin) {
        PlayerFullScreenView view = viewSupplier == null ? null : viewSupplier.get();
        if (view == null || view.artistsContainer() == null || view.artistsContainer().getScene() == null) {
            return Math.max(0.0, overlay.getHeight() - fullscreenBarRoot.prefHeight(-1.0) - bottomMargin);
        }

        double metadataBottom = Math.max(
                bottomInOverlay(overlay, view.songTitleLabel()),
                Math.max(
                        bottomInOverlay(overlay, view.artistsContainer()),
                        bottomInOverlay(overlay, view.actionsMenuButton())
                )
        );
        double maxTop = Math.max(
                0.0,
                overlay.getHeight() - fullscreenBarRoot.prefHeight(fullscreenBarRoot.getWidth()) - bottomMargin
        );
        double preferredGap = overlay.getHeight() < 620.0
                ? 16.0 : overlay.getHeight() < 860.0 ? 22.0 : 28.0;
        double availableGap = Math.max(10.0, maxTop - metadataBottom);
        double responsiveGap = Math.min(preferredGap, availableGap);
        return Math.min(metadataBottom + responsiveGap, maxTop);
    }

    private double bottomInOverlay(StackPane overlay, Node node) {
        if (node == null || node.getScene() == null || !node.isVisible()) {
            return 0.0;
        }
        Bounds bounds = overlay.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
        return bounds.getMaxY();
    }

    private void detachFullscreenBar() {
        if (fullscreenBarController != null) {
            fullscreenBarController.dispose();
        }
        if (fullscreenBarRoot != null
                && fullscreenBarRoot.getParent() instanceof Pane currentParent) {
            currentParent.getChildren().remove(fullscreenBarRoot);
        }
        fullscreenBarController = null;
        fullscreenBarRoot = null;
    }
}
