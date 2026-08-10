package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.layout;

import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
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
        // The bar is positioned dynamically and can temporarily overlap the
        // metadata column during a resize or first layout pulse. Only its
        // actual controls should receive mouse events; transparent bounds
        // must not block artist hyperlinks behind it.
        fullscreenBarRoot.setPickOnBounds(false);
        fullscreenBarRoot.setMouseTransparent(false);
        fullscreenBarRoot.setOpacity(1.0);
        fullscreenBarRoot.toFront();
    }

    public void configureActionsButton(Button button) {
        if (fullscreenBarController != null) {
            fullscreenBarController.configureActionsButton(button);
        }
    }

    /** Places the lyrics action beside the existing fullscreen actions menu. */
    public void placeLyricsButtonNextTo(Button menuButton) {
        if (fullscreenBarController == null || menuButton == null
                || !(menuButton.getParent() instanceof HBox actionContainer)) {
            return;
        }

        Button lyricsButton = fullscreenBarController.getLyricsButton();
        if (lyricsButton == null) {
            return;
        }

        if (lyricsButton.getParent() instanceof Pane currentParent) {
            currentParent.getChildren().remove(lyricsButton);
        }
        if (!actionContainer.getChildren().contains(lyricsButton)) {
            actionContainer.getChildren().add(lyricsButton);
        }

        // PlayerLyricsButtonSupport uses an inline transparent style for the
        // regular player bar. In fullscreen, the shared action-button class
        // supplies the circular surface used by the menu button.
        lyricsButton.setStyle("");
        if (!lyricsButton.getStyleClass().contains("player-fullscreen-actions-button")) {
            lyricsButton.getStyleClass().add("player-fullscreen-actions-button");
        }
        actionContainer.setAlignment(Pos.CENTER_RIGHT);
        actionContainer.setSpacing(10.0);
    }

    public void setLyricsButtonSuppressed(boolean suppressed) {
        if (fullscreenBarController != null) {
            fullscreenBarController.setLyricsButtonSuppressed(suppressed);
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

    /** Lays the playback bar below the left metadata column used by lyrics mode. */
    public void layoutBelowNowPlaying(PlayerFullScreenView view) {
        StackPane overlay = overlaySupplier.get();
        if (fullscreenBarRoot == null || overlay == null || view == null
                || fullscreenBarRoot.getParent() != overlay) {
            return;
        }

        Bounds coverBounds = boundsInOverlay(overlay, view.songCoverImageView());
        Bounds artworkBounds = boundsInOverlay(overlay, view.artworkContainer());
        Bounds nowPlayingBounds = boundsInOverlay(overlay, view.nowPlayingOverlay());

        // During a rapid open/close cycle the ImageView can still report a
        // zero-sized scene bound for one pulse. Use the already requested fit
        // size instead of allowing the bar to fall back to the whole overlay.
        double coverWidth = coverBounds.getWidth();
        if (coverWidth <= 1.0) {
            coverWidth = view.songCoverImageView().getFitWidth();
        }
        if (coverWidth <= 1.0) {
            coverWidth = artworkBounds.getWidth();
        }

        double left = coverBounds.getWidth() > 1.0
                ? coverBounds.getMinX()
                : artworkBounds.getWidth() > 1.0
                ? artworkBounds.getMinX()
                : nowPlayingBounds.getWidth() > 1.0
                ? nowPlayingBounds.getMinX()
                        + Math.max(0.0, (nowPlayingBounds.getWidth() - coverWidth) / 2.0)
                : resolveCenteredCoverLeft(overlay, coverWidth);
        left = Math.max(12.0, left);
        double availableWidth = Math.max(0.0, overlay.getWidth() - left - 16.0);
        double desiredWidth = coverWidth > 1.0
                ? coverWidth
                : Math.min(MAX_BAR_WIDTH, Math.max(320.0, availableWidth));
        double width = Math.min(MAX_BAR_WIDTH, Math.min(availableWidth, desiredWidth));
        if (width <= 0.0) {
            return;
        }

        if (fullscreenBarRoot.prefWidthProperty().isBound()) {
            fullscreenBarRoot.prefWidthProperty().unbind();
        }
        fullscreenBarRoot.setMinWidth(0.0);
        fullscreenBarRoot.setPrefWidth(width);
        fullscreenBarRoot.setMaxWidth(width);
        fullscreenBarRoot.setMinHeight(0.0);
        fullscreenBarRoot.setMaxHeight(Region.USE_PREF_SIZE);

        double contentBottom = Math.max(
                bottomInOverlay(overlay, view.artworkContainer()),
                Math.max(
                        bottomInOverlay(overlay, view.songTitleLabel()),
                        Math.max(
                                bottomInOverlay(overlay, view.artistsContainer()),
                                bottomInOverlay(overlay, view.actionsMenuButton())
                        )
                )
        );
        double barHeight = Math.max(178.0, fullscreenBarRoot.prefHeight(width));
        double maxTop = Math.max(0.0, overlay.getHeight() - barHeight - 14.0);
        boolean splitComposition = coverBounds.getWidth() > 1.0
                && coverBounds.getWidth() < overlay.getWidth() * 0.75;
        double preferredGap = splitComposition
                ? (overlay.getHeight() < 680.0 ? 24.0 : 32.0)
                : (overlay.getHeight() < 680.0 ? 14.0 : 22.0);
        double preferredTop = contentBottom + preferredGap;
        double top = Math.min(Math.max(0.0, preferredTop), maxTop);
        double right = Math.max(0.0, overlay.getWidth() - left - width);

        StackPane.setAlignment(fullscreenBarRoot, Pos.TOP_LEFT);
        StackPane.setMargin(fullscreenBarRoot, new Insets(top, right, 0.0, left));
        fullscreenBarRoot.setTranslateY(0.0);
        fullscreenBarRoot.requestLayout();
    }

    private double resolveCenteredCoverLeft(
            StackPane overlay,
            double coverWidth
    ) {
        double width = Math.max(0.0, overlay.getWidth());
        double height = Math.max(0.0, overlay.getHeight());
        boolean compact = width < 900.0 || height < 680.0;
        double sideMargin = compact
                ? Math.min(18.0, Math.max(8.0, width * 0.04))
                : Math.max(28.0, Math.min(72.0, width * 0.04));
        double columnGap = compact
                ? Math.min(18.0, Math.max(8.0, width * 0.03))
                : 34.0;
        double columnWidth = Math.max(
                0.0,
                (width - (sideMargin * 2.0) - columnGap) / 2.0
        );
        return sideMargin + Math.max(0.0, (columnWidth - coverWidth) / 2.0);
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
            fullscreenBarRoot.setPickOnBounds(false);
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
        return boundsInOverlay(overlay, node).getMaxY();
    }

    private Bounds boundsInOverlay(StackPane overlay, Node node) {
        if (node == null || node.getScene() == null || !node.isVisible()) {
            return new javafx.geometry.BoundingBox(0.0, 0.0, 0.0, 0.0);
        }
        return overlay.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
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
