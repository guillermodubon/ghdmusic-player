package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.layout;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.layout.BorderPane;
import javafx.scene.control.Label;

/**
 * Owns the responsive metrics of PlayerMenu. It only changes layout metrics;
 * playlist state and content remain owned by the screen controller/services.
 */
public final class PlayerMenuResponsiveLayout {
    private final BorderPane root;
    private final VBox surface;
    private final HBox header;
    private final StackPane coverShell;
    private final StackPane optionsSlot;
    private final StackPane songSearchBox;
    private final StackPane recommendationSearchBox;
    private final Pane songListVirtualShell;
    private final ImageView headerCover;
    private final Label headerTitle;
    private final ScrollPane scrollPane;
    private boolean configured;
    private double lastViewportWidth;

    public PlayerMenuResponsiveLayout(BorderPane root,
                                      VBox surface,
                                      HBox header,
                                      StackPane coverShell,
                                      StackPane optionsSlot,
                                      StackPane songSearchBox,
                                      StackPane recommendationSearchBox,
                                      Pane songListVirtualShell,
                                      ImageView headerCover,
                                      Label headerTitle,
                                      ScrollPane scrollPane) {
        this.root = root;
        this.surface = surface;
        this.header = header;
        this.coverShell = coverShell;
        this.optionsSlot = optionsSlot;
        this.songSearchBox = songSearchBox;
        this.recommendationSearchBox = recommendationSearchBox;
        this.songListVirtualShell = songListVirtualShell;
        this.headerCover = headerCover;
        this.headerTitle = headerTitle;
        this.scrollPane = scrollPane;
    }

    public void configure() {
        if (configured) {
            refresh();
            return;
        }
        configured = true;

        if (scrollPane != null) {
            scrollPane.setFitToWidth(true);
            scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        }

        // The song list owns the ScrollPane viewport for virtualization. The
        // header intentionally listens only to its enclosing screen width so
        // keyboard navigation, list virtualization and scrolling cannot
        // recalculate the header's responsive geometry.
        if (root != null) {
            root.widthProperty().addListener((obs, previous, current) -> refreshMetrics());
            root.parentProperty().addListener((obs, previous, current) -> scheduleAttachedRefresh());
            root.sceneProperty().addListener((obs, previous, current) -> scheduleAttachedRefresh());
        }

        if (headerTitle != null) {
            headerTitle.textProperty().addListener((obs, previous, current) -> {
                if (lastViewportWidth > 0) applyTitleMetrics(lastViewportWidth, null, 0, 0);
            });
        }

        Platform.runLater(this::refreshMetrics);
        Platform.runLater(() -> Platform.runLater(this::refreshMetrics));

        if (surface != null) surface.setMaxWidth(Double.MAX_VALUE);
        if (root != null) root.setMaxWidth(Double.MAX_VALUE);
    }

    /** Re-applies the current metrics after asynchronous header content arrives. */
    public void refresh() {
        refreshMetrics();
        Platform.runLater(this::refreshMetrics);
    }

    /**
     * Settles geometry after a collection replaces the virtualized list.
     * That update happens one pulse after the header metadata, so doing this
     * once here removes the former dependency on a user scroll event.
     */
    public void settleAfterContentUpdate() {
        Platform.runLater(() -> Platform.runLater(() -> {
            if (root == null || root.getScene() == null) {
                refreshMetrics();
                return;
            }

            root.applyCss();
            root.layout();
            refreshMetrics();
            root.applyCss();
            root.layout();
        }));
    }

    private void scheduleAttachedRefresh() {
        // The navigator initializes this view before it becomes the center
        // node. Wait for the parent layout pulse, then use the real viewport
        // dimensions instead of the FXML's design-time size.
        Platform.runLater(() -> Platform.runLater(this::refreshMetrics));
    }

    private void refreshMetrics() {
        double screenWidth = root == null ? 0 : root.getWidth();
        // Before the view is attached, only use the viewport as a one-time
        // fallback. Once the screen has a width, list-driven viewport changes
        // are deliberately ignored.
        if (screenWidth <= 0 && scrollPane != null) {
            screenWidth = scrollPane.getViewportBounds().getWidth();
        }
        if (screenWidth <= 0) return;

        applyMetrics(screenWidth);
        if (header != null) header.requestLayout();
        if (surface != null) surface.requestLayout();
        if (scrollPane != null) scrollPane.requestLayout();
        if (root != null) root.requestLayout();
    }

    private void applyMetrics(double viewportWidth) {
        if (viewportWidth <= 0) return;
        lastViewportWidth = viewportWidth;

        if (surface != null) {
            surface.setMinWidth(viewportWidth);
            surface.setPrefWidth(viewportWidth);
            surface.setMaxWidth(viewportWidth);
        }

        double cover;
        Insets headerPadding;
        double headerSpacing;
        double searchWidth;

        if (viewportWidth < 720) {
            cover = 140;
            headerPadding = new Insets(26, 20, 24, 20);
            headerSpacing = 18;
            searchWidth = Math.max(220, viewportWidth - 40);
        } else if (viewportWidth < 1040) {
            cover = 210;
            headerPadding = new Insets(32, 32, 28, 34);
            headerSpacing = 26;
            searchWidth = Math.min(380, Math.max(280, viewportWidth * 0.42));
        } else {
            cover = 280;
            headerPadding = new Insets(30, 36, 30, 36);
            headerSpacing = 32;
            searchWidth = Math.min(420, Math.max(320, viewportWidth * 0.30));
        }

        if (header != null) {
            header.setPadding(headerPadding);
            header.setSpacing(headerSpacing);
            header.setMinWidth(viewportWidth);
            header.setPrefWidth(viewportWidth);
            header.setMaxWidth(viewportWidth);
            double headerHeight = headerPadding.getTop() + cover + headerPadding.getBottom();
            header.setMinHeight(headerHeight);
        }
        setFixedSize(coverShell, cover, cover);
        if (headerCover != null) {
            headerCover.setFitWidth(cover);
            headerCover.setFitHeight(cover);
        }
        applyTitleMetrics(viewportWidth, headerPadding, cover, headerSpacing);

        setSearchBoxWidth(songSearchBox, searchWidth);
        setSearchBoxWidth(recommendationSearchBox, Math.min(420, Math.max(280, viewportWidth * 0.32)));
        setWidth(songListVirtualShell, viewportWidth);
        setFixedSize(optionsSlot, 48, 48);
    }

    private void applyTitleMetrics(double viewportWidth,
                                   Insets headerPadding,
                                   double cover,
                                   double headerSpacing) {
        if (headerTitle == null || viewportWidth <= 0) return;

        Insets padding = headerPadding == null && header != null
                ? header.getPadding()
                : headerPadding;
        if (padding == null) padding = Insets.EMPTY;

        double resolvedCover = cover > 0
                ? cover
                : coverShell == null ? 0 : coverShell.getPrefWidth();
        double resolvedSpacing = headerSpacing > 0
                ? headerSpacing
                : header == null ? 0 : header.getSpacing();
        double availableWidth = Math.max(
                120,
                viewportWidth - padding.getLeft() - padding.getRight()
                        - resolvedCover - resolvedSpacing
        );
        int titleLength = Math.max(1, headerTitle.getText() == null
                ? 0
                : headerTitle.getText().trim().length());

        if (viewportWidth < 720) {
            double size = clamp(24, 36, availableWidth / (Math.max(8, titleLength) * 0.52));
            headerTitle.setWrapText(false);
            headerTitle.setTextOverrun(OverrunStyle.ELLIPSIS);
            headerTitle.setStyle("-fx-font-size: " + Math.round(size) + "px;");
            return;
        }

        int maxLines = 2;
        double minimumSize = viewportWidth >= 1040 ? 34 : 30;
        double maximumSize = viewportWidth >= 1280 ? 72 : viewportWidth >= 1040 ? 62 : 50;
        double fittedSize = availableWidth * maxLines / (titleLength * 0.56);
        double size = clamp(minimumSize, maximumSize, fittedSize);

        headerTitle.setWrapText(true);
        headerTitle.setTextOverrun(OverrunStyle.CLIP);
        headerTitle.setStyle("-fx-font-size: " + Math.round(size) + "px;");
    }

    private double clamp(double minimum, double maximum, double value) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private void setFixedSize(Region region, double width, double height) {
        if (region == null) return;
        region.setMinSize(width, height);
        region.setPrefSize(width, height);
        region.setMaxSize(width, height);
    }

    private void setWidth(Region region, double width) {
        if (region == null) return;
        region.setMinWidth(width);
        region.setPrefWidth(width);
        region.setMaxWidth(width);
    }

    private void setSearchBoxWidth(Region region, double width) {
        if (region == null || width <= 0) return;
        region.setMinWidth(Math.min(260, width));
        region.setPrefWidth(width);
        region.setMaxWidth(width);
    }
}
