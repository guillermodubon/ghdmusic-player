package io.github.guillermodubon.musicplayer.controllers.ui.screens.lyricsFullscreenMode.view;

import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.view.PlayerFullScreenView;
import javafx.scene.control.Tooltip;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Composition root for the lyrics overlay and its reusable player view. */
public final class LyricsFullscreenView {

    private final PlayerFullScreenView playbackView;
    private final StackPane lyricsPanel;
    private final ScrollPane lyricsScrollPane;
    private final VBox lyricsLines;
    private final Button playerFullscreenButton;
    private final Tooltip playerFullscreenTooltip;

    public LyricsFullscreenView(
            PlayerFullScreenView playbackView,
            StackPane lyricsPanel,
            ScrollPane lyricsScrollPane,
            VBox lyricsLines,
            Button playerFullscreenButton,
            Tooltip playerFullscreenTooltip
    ) {
        this.playbackView = playbackView;
        this.lyricsPanel = lyricsPanel;
        this.lyricsScrollPane = lyricsScrollPane;
        this.lyricsLines = lyricsLines;
        this.playerFullscreenButton = playerFullscreenButton;
        this.playerFullscreenTooltip = playerFullscreenTooltip;
    }

    public PlayerFullScreenView playbackView() {
        return playbackView;
    }

    public StackPane root() {
        return playbackView.root();
    }

    public StackPane lyricsPanel() {
        return lyricsPanel;
    }

    public ScrollPane lyricsScrollPane() {
        return lyricsScrollPane;
    }

    public VBox lyricsLines() {
        return lyricsLines;
    }

    public Button playerFullscreenButton() {
        return playerFullscreenButton;
    }

    public void dispose() {
        if (playerFullscreenButton != null) {
            playerFullscreenButton.setOnAction(null);
            if (playerFullscreenTooltip != null) {
                Tooltip.uninstall(playerFullscreenButton, playerFullscreenTooltip);
            }
        }
        playbackView.unbindLayoutProperties();
        playbackView.root().getChildren().remove(lyricsPanel);
    }
}
