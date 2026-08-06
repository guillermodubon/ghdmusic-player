package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.view;

import javafx.scene.control.Label;
import javafx.scene.control.Button;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.HBox;
import javafx.scene.shape.Rectangle;
import io.github.guillermodubon.musicplayer.controllers.ui.components.effects.MarqueeTextSupport;

/** Nodes owned by the internal fullscreen presentation. */
public record PlayerFullScreenView(
        StackPane root,
        StackPane artworkContainer,
        VBox nowPlayingOverlay,
        Pane ambientBackground,
        StackPane songCoverContainer,
        ImageView songCoverImageView,
        Label songTitleLabel,
        HBox artistsContainer,
        MarqueeTextSupport metadataMarquee,
        Button actionsMenuButton,
        Button closeButton,
        Rectangle songCoverClip
) {

    public void unbindLayoutProperties() {
        if (metadataMarquee != null) {
            metadataMarquee.deactivate();
        }
        unbind(root.prefWidthProperty());
        unbind(root.prefHeightProperty());
        if (songCoverClip != null) {
            unbind(songCoverClip.widthProperty());
            unbind(songCoverClip.heightProperty());
        }
    }

    private void unbind(javafx.beans.property.Property<?> property) {
        if (property.isBound()) {
            property.unbind();
        }
    }
}
