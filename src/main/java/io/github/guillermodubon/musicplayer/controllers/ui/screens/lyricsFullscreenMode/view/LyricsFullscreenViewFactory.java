package io.github.guillermodubon.musicplayer.controllers.ui.screens.lyricsFullscreenMode.view;

import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.view.PlayerFullScreenView;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.view.PlayerFullScreenViewFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.components.icons.SvgIconFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.components.popups.SmallPopupTooltip;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.Node;
import javafx.collections.ListChangeListener;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.concurrent.atomic.AtomicBoolean;

/** Builds the lyrics composition while reusing the existing fullscreen layers. */
public final class LyricsFullscreenViewFactory {

    private static final String LYRICS_OFF_ICON =
            "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/"
                    + "mic_external_off_27dp_E3E3E3_FILL0_wght400_GRAD0_opsz24.svg";

    public LyricsFullscreenView create(
            Runnable onEscape,
            java.util.function.BooleanSupplier active,
            Runnable updateArtworkViewport,
            Runnable onOpenPlayerFullscreen
    ) {
        PlayerFullScreenView playbackView = new PlayerFullScreenViewFactory().create(
                onEscape,
                active,
                updateArtworkViewport == null ? () -> { } : updateArtworkViewport
        );

        StackPane root = playbackView.root();
        VBox nowPlaying = playbackView.nowPlayingOverlay();
        LyricsPanelNodes panelNodes = createLyricsPanel();
        StackPane lyricsPanel = panelNodes.panel();
        ScrollPane scrollPane = panelNodes.scrollPane();
        VBox lyricsLines = panelNodes.lines();
        Button playerFullscreenButton = createPlayerFullscreenButton(onOpenPlayerFullscreen);
        Tooltip playerFullscreenTooltip = SmallPopupTooltip.install(
                playerFullscreenButton,
                "Hide lyrics"
        );
        addPlayerFullscreenButton(playbackView.actionsMenuButton(), playerFullscreenButton);
        AtomicBoolean typographyUpdateScheduled = new AtomicBoolean();
        lyricsLines.getChildren().addListener(
                (ListChangeListener<Node>) change -> requestResponsiveTypographyUpdate(
                        typographyUpdateScheduled,
                        lyricsLines,
                        root
                )
        );

        // Lyrics mode uses the artwork as a clean image, without the framed
        // cover treatment used by the regular fullscreen player.
        playbackView.songCoverImageView().setClip(playbackView.songCoverClip());
        playbackView.artworkContainer().getStyleClass().removeAll(
                "player-fullscreen-artwork-container",
                "player-fullscreen-song-cover-container"
        );
        playbackView.artworkContainer().getStyleClass().add("lyrics-fullscreen-cover");

        nowPlaying.setAlignment(Pos.CENTER_LEFT);
        StackPane.setAlignment(nowPlaying, Pos.TOP_LEFT);
        // The width is adjusted by applyResponsiveLayout. Keep it mutable so
        // the fullscreen composition can react to window resizing without
        // attempting to write to a bound JavaFX property.
        nowPlaying.setPrefWidth(0.0);
        nowPlaying.setMaxWidth(Double.MAX_VALUE);

        StackPane.setAlignment(lyricsPanel, Pos.TOP_LEFT);
        root.getChildren().add(lyricsPanel);
        keepPlaybackMetadataInteractive(root, nowPlaying, playbackView.closeButton());

        AtomicBoolean responsiveLayoutScheduled = new AtomicBoolean();
        Runnable requestResponsiveLayout = () -> requestResponsiveLayoutUpdate(
                responsiveLayoutScheduled,
                root,
                nowPlaying,
                lyricsPanel,
                lyricsLines
        );
        root.widthProperty().addListener((obs, oldValue, newValue) -> requestResponsiveLayout.run());
        root.heightProperty().addListener((obs, oldValue, newValue) -> requestResponsiveLayout.run());
        requestResponsiveLayout.run();
        // The lyrics panel is intentionally added last so it can occupy the
        // complete right column. Re-assert the metadata layer after the first
        // pulse as well, because JavaFX may reorder/layout the newly attached
        // overlay while it is being inserted into the scene.
        javafx.application.Platform.runLater(() ->
                keepPlaybackMetadataInteractive(root, nowPlaying, playbackView.closeButton())
        );

        return new LyricsFullscreenView(
                playbackView,
                lyricsPanel,
                scrollPane,
                lyricsLines,
                playerFullscreenButton,
                playerFullscreenTooltip
        );
    }

    private void requestResponsiveLayoutUpdate(
            AtomicBoolean scheduled,
            StackPane root,
            VBox nowPlaying,
            StackPane lyricsPanel,
            VBox lyricsLines
    ) {
        if (!scheduled.compareAndSet(false, true)) {
            return;
        }
        javafx.application.Platform.runLater(() -> {
            scheduled.set(false);
            applyResponsiveLayout(
                    root,
                    nowPlaying,
                    lyricsPanel,
                    lyricsLines,
                    root.getWidth(),
                    root.getHeight()
            );
        });
    }

    private void requestResponsiveTypographyUpdate(
            AtomicBoolean scheduled,
            VBox lyricsLines,
            StackPane root
    ) {
        if (!scheduled.compareAndSet(false, true)) {
            return;
        }
        javafx.application.Platform.runLater(() -> {
            scheduled.set(false);
            applyResponsiveTypography(
                    lyricsLines,
                    root.getWidth(),
                    root.getHeight()
            );
        });
    }

    private void keepPlaybackMetadataInteractive(
            StackPane root,
            VBox nowPlaying,
            Button closeButton
    ) {
        if (root == null || nowPlaying == null || !root.getChildren().contains(nowPlaying)) {
            return;
        }
        nowPlaying.setMouseTransparent(false);
        nowPlaying.setPickOnBounds(false);
        nowPlaying.toFront();
        if (closeButton != null) {
            closeButton.toFront();
        }
    }

    private Button createPlayerFullscreenButton(Runnable onOpenPlayerFullscreen) {
        Button button = new Button();
        button.setFocusTraversable(false);
        button.setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
        button.getStyleClass().add("player-fullscreen-actions-button");
        var icon = SvgIconFactory.icon(LYRICS_OFF_ICON, 20.0);
        SvgIconFactory.setIconColor(icon, "#AFAFAF");
        button.setGraphic(icon);
        button.setOnAction(event -> {
            if (onOpenPlayerFullscreen != null) {
                onOpenPlayerFullscreen.run();
            }
        });
        return button;
    }

    private void addPlayerFullscreenButton(Button menuButton, Button fullscreenButton) {
        if (menuButton == null || fullscreenButton == null
                || !(menuButton.getParent() instanceof HBox actionButtons)) {
            return;
        }
        if (!actionButtons.getChildren().contains(fullscreenButton)) {
            actionButtons.getChildren().add(fullscreenButton);
        }
        actionButtons.setSpacing(10.0);
        actionButtons.setAlignment(Pos.CENTER_RIGHT);
    }

    private LyricsPanelNodes createLyricsPanel() {
        VBox lines = new VBox(12);
        lines.setFillWidth(true);
        lines.getStyleClass().add("lyrics-lines");

        ScrollPane scrollPane = new ScrollPane(lines);
        scrollPane.setFitToWidth(true);
        scrollPane.setPannable(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.getStyleClass().add("lyrics-scroll-pane");

        VBox content = new VBox(scrollPane);
        content.setFillWidth(true);
        content.getStyleClass().add("lyrics-panel-content");

        StackPane panel = new StackPane(content);
        panel.setMinSize(0, 0);
        panel.setPrefSize(620, 720);
        panel.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        panel.getStyleClass().add("lyrics-panel");
        return new LyricsPanelNodes(panel, scrollPane, lines);
    }

    private void applyResponsiveLayout(
            StackPane root,
            VBox nowPlaying,
            StackPane lyricsPanel,
            VBox lyricsLines,
            double width,
            double height
    ) {
        if (width <= 1 || height <= 1) return;
        boolean compact = width < 900 || height < 680;
        double sideMargin = compact
                ? Math.min(18.0, Math.max(8.0, width * 0.04))
                : Math.max(28.0, Math.min(72.0, width * 0.04));
        double columnGap = compact
                ? Math.min(18.0, Math.max(8.0, width * 0.03))
                : 34.0;
        // Never impose a fixed minimum that is wider than the current
        // monitor. Both columns must be derived from the actual viewport so
        // resizing or moving the window cannot make them overlap.
        double availableColumns = Math.max(
                0.0,
                width - (sideMargin * 2.0) - columnGap
        );
        double columnWidth = availableColumns / 2.0;
        double leftWidth = columnWidth;
        double panelWidth = columnWidth;

        nowPlaying.setPrefWidth(leftWidth);
        nowPlaying.setMinWidth(leftWidth);
        nowPlaying.setMaxWidth(leftWidth);
        StackPane.setMargin(nowPlaying, new Insets(0.0, 0.0, 0.0, sideMargin));

        double panelLeftMargin = sideMargin + leftWidth + columnGap;
        lyricsPanel.setPrefWidth(panelWidth);
        lyricsPanel.setMinWidth(panelWidth);
        lyricsPanel.setMaxWidth(panelWidth);
        lyricsPanel.setMinHeight(height);
        lyricsPanel.setPrefHeight(height);
        lyricsPanel.setMaxHeight(height);
        StackPane.setMargin(lyricsPanel, new Insets(
                0.0, sideMargin, 0.0, panelLeftMargin
        ));

        // Keep the first and last lines away from the fade boundaries. This
        // gives the active line room to travel smoothly through the center,
        // instead of starting or ending inside the low-opacity edge region.
        double topReadingInset = Math.max(120.0, Math.min(280.0, height * 0.27));
        double bottomReadingInset = Math.max(180.0, Math.min(560.0, height * 0.50));
        lyricsLines.setPadding(new Insets(
                topReadingInset, 0.0, bottomReadingInset, 0.0
        ));
        applyResponsiveTypography(lyricsLines, panelWidth, height);
    }

    private void applyResponsiveTypography(VBox lyricsLines, double panelWidth, double height) {
        if (lyricsLines == null || panelWidth <= 1.0 || height <= 1.0) return;

        // Use the actual lyric-column width as the breakpoint so laptop
        // layouts keep their current rhythm while wide monitors gain scale.
        boolean largeColumn = panelWidth >= 760.0 && height >= 820.0;
        for (Node node : lyricsLines.getChildren()) {
            if (node.getStyleClass().contains("lyrics-line")) {
                updateScaleClass(node, "lyrics-line-large", largeColumn);
            } else if (node.getStyleClass().contains("lyrics-plain-line")) {
                updateScaleClass(node, "lyrics-plain-line-large", largeColumn);
            }
        }
    }

    private void updateScaleClass(Node node, String largeStyleClass, boolean shouldUseLargeStyle) {
        boolean alreadyUsesLargeStyle = node.getStyleClass().contains(largeStyleClass);
        if (shouldUseLargeStyle == alreadyUsesLargeStyle) {
            return;
        }
        if (shouldUseLargeStyle) {
            node.getStyleClass().add(largeStyleClass);
        } else {
            node.getStyleClass().remove(largeStyleClass);
        }
    }

    private record LyricsPanelNodes(StackPane panel, ScrollPane scrollPane, VBox lines) {
    }
}
