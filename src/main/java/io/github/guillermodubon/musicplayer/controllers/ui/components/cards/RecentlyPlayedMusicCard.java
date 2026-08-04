package io.github.guillermodubon.musicplayer.controllers.ui.components.cards;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.TextAlignment;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.base.BaseCardController;

import java.util.function.Consumer;


public class RecentlyPlayedMusicCard extends BaseCardController<String> {

    private static final String RECENTLY_PLAYED_CLASS = "recently-played-card";
    private static final String RECENTLY_PLAYED_GLASS_CLASS = "recently-played-glass-card";

    @FXML private HBox rootHBox;
    @FXML private ImageView coverView;
    @FXML private Label titleLabel;

    public void init(String id, Image cover, String title, Consumer<String> onPlay) {
        setModel(id, onPlay);
        ensureGlassStyleClasses();

        setImageOrFallback(
                coverView,
                cover,
                defaultImage("/io/github/guillermodubon/musicplayer/assets/images/byDefaultImages/defaultPlaylist.png")
        );

        coverView.setFitWidth(56);
        coverView.setFitHeight(56);
        Rectangle coverClip = new Rectangle(56, 56);
        coverClip.setArcWidth(10);
        coverClip.setArcHeight(10);
        coverView.setClip(coverClip);

        titleLabel.setText(safeText(title, "Unknown"));
        titleLabel.setWrapText(false);
        titleLabel.setTextAlignment(TextAlignment.LEFT);
        titleLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        HBox.setHgrow(titleLabel, Priority.ALWAYS);
        coverView.setMouseTransparent(true);
        titleLabel.setMouseTransparent(true);

        rootHBox.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            fireOpen();
            e.consume();
        });
    }

    /** Updates a deferred Deezer cover without recreating the visible card. */
    public void updateCover(Image cover) {
        setImageOrFallback(
                coverView,
                cover,
                defaultImage("/io/github/guillermodubon/musicplayer/assets/images/byDefaultImages/defaultPlaylist.png")
        );
    }

    private void ensureGlassStyleClasses() {
        if (rootHBox == null) return;
        if (!rootHBox.getStyleClass().contains(RECENTLY_PLAYED_CLASS)) {
            rootHBox.getStyleClass().add(RECENTLY_PLAYED_CLASS);
        }
        if (!rootHBox.getStyleClass().contains(RECENTLY_PLAYED_GLASS_CLASS)) {
            rootHBox.getStyleClass().add(RECENTLY_PLAYED_GLASS_CLASS);
        }
    }

    @FXML
    protected void onPlayClicked(MouseEvent e) {
        fireOpen();
        if (e != null) e.consume();
    }

    private void fireOpen() {
        if (onClick != null && model != null && !model.isBlank()) {
            onClick.accept(model);
        }
    }
}
