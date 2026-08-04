package io.github.guillermodubon.musicplayer.controllers.ui.components.cards;

import javafx.fxml.FXML;
import javafx.scene.Cursor;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.base.BaseCardController;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;
import io.github.guillermodubon.musicplayer.utils.ArtistIdentity;

import java.util.function.Consumer;

public class ArtistCard extends BaseCardController<Artist> {
    private static final String HOVER_CLASS = "music-card-hover";

    @FXML private StackPane rootPane;
    @FXML private StackPane imageContainer;
    @FXML private ImageView coverImage;
    @FXML private StackPane labelBackground;
    @FXML private javafx.scene.control.Label nameLabel;

    public void initialize() {
        if (rootPane != null) rootPane.setCursor(Cursor.HAND);
        if (nameLabel != null && !nameLabel.getStyleClass().contains("artist-card-title")) {
            nameLabel.getStyleClass().add("artist-card-title");
        }

        if (coverImage != null) {
            coverImage.boundsInLocalProperty().addListener((obs, oldB, newB) -> applyCircularClip(coverImage));
            applyCircularClipLater(coverImage);
        }
    }

    public void init(Artist artist, Consumer<Artist> onClick) {
        if (artist == null) throw new IllegalArgumentException("artist == null");
        setModel(artist, onClick);

        if (ArtistIdentity.isVariousArtists(artist)) {
            rootPane.setCursor(Cursor.DEFAULT);
        }

        nameLabel.setText(safeText(artist.getName(), ""));

        javafx.scene.image.Image img = MediaImageResolver.artistCardPortrait(artist);
        setImageOrFallback(coverImage, img, MediaImageResolver.defaultArtist(220, 220));

        bindClick(rootPane, () -> {
            if (ArtistIdentity.isVariousArtists(model)) return;
            if (this.onClick != null && this.model != null) {
                this.onClick.accept(this.model);
            }
        });
    }

    @FXML
    private void onHoverEnter(MouseEvent e) {
        if (rootPane == null) return;
        if (!rootPane.getStyleClass().contains(HOVER_CLASS)) {
            rootPane.getStyleClass().add(HOVER_CLASS);
        }
    }

    @FXML
    private void onHoverExit(MouseEvent e) {
        if (rootPane == null) return;
        rootPane.getStyleClass().remove(HOVER_CLASS);
    }
}
