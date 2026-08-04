package io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.helpers;

import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;

/** Shared references to the injected Artist page views. */
public record ArtistPageViewBindings(
        StackPane pageRoot,
        StackPane headerRoot,
        ImageView headerBackgroundImage,
        StackPane headerImageFrame,
        ImageView headerImage,
        HBox headerOverlay,
        VBox headerInfo,
        ScrollPane artistScrollPane,
        Label artistNameLabel,
        Text biographyTextHeader,
        Text biographyText,
        VBox centerVBox,
        VBox mainContent,
        StackPane localCarouselHost,
        FlowPane localFlow,
        FlowPane topTracksFlow,
        FlowPane albumsFlow,
        FlowPane singlesFlow,
        FlowPane playlistsFlow,
        Label localTitle,
        Label topTracksTitle,
        Label albumsTitle,
        Label singlesTitle,
        Label playlistsTitle
) {
}
