package io.github.guillermodubon.musicplayer.controllers.ui.components.cards;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.base.BaseCardController;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class SearchResultArtistCard extends BaseCardController<Artist> {
    private static final double SEARCH_IMAGE_SIZE = 160.0;
    private static final ExecutorService COVER_EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "search-artist-image");
        thread.setDaemon(true);
        return thread;
    });

    private static final String HOVER_CLASS = "search-result-card-hover";
    private static final String BODY_HOVER_CLASS = "search-result-card-body-hover";
    private static final Color TITLE_COLOR = Color.WHITE;
    private static final Color SUBTITLE_COLOR = Color.web("#afafaf");

    @FXML private StackPane rootPane;
    @FXML private HBox body;
    @FXML private StackPane coverShell;
    @FXML private ImageView coverImage;
    @FXML private javafx.scene.control.Label nameLabel;
    @FXML private javafx.scene.control.Label typeLabel;

    private long coverRequestId;

    public void init(Artist artist, Consumer<Artist> onClick) {
        if (artist == null) throw new IllegalArgumentException("artist == null");
        setModel(artist, onClick);

        nameLabel.setText(safeText(artist.getName(), "Unknown Artist"));
        typeLabel.setText("Artist");
        enforceTitleStyle(nameLabel);
        enforceSubtitleStyle(typeLabel);
        configureCover();

        long requestId = ++coverRequestId;
        Image cached = MediaImageResolver.cachedArtistPortrait(
                artist,
                "xl",
                SEARCH_IMAGE_SIZE,
                SEARCH_IMAGE_SIZE
        );
        if (cached == null) {
            cached = MediaImageResolver.cachedRemoteImage(
                    artist.getPortraitUrl(),
                    SEARCH_IMAGE_SIZE,
                    SEARCH_IMAGE_SIZE
            );
        }
        setImageOrFallback(
                coverImage,
                cached,
                MediaImageResolver.defaultArtist(SEARCH_IMAGE_SIZE, SEARCH_IMAGE_SIZE)
        );
        loadCoverInBackground(artist, requestId);

        bindClick(rootPane, () -> {
            if (this.onClick != null && this.model != null) {
                this.onClick.accept(this.model);
            }
        });
    }

    private void loadCoverInBackground(Artist artist, long requestId) {
        COVER_EXECUTOR.execute(() -> {
            Image image = MediaImageResolver.artistPortrait(
                    artist,
                    "xl",
                    SEARCH_IMAGE_SIZE,
                    SEARCH_IMAGE_SIZE
            );
            if (image == null) return;

            Platform.runLater(() -> {
                if (requestId == coverRequestId && model == artist && coverImage != null) {
                    coverImage.setImage(image);
                }
            });
        });
    }

    private void configureCover() {
        if (coverImage == null || coverShell == null) return;
        if (!coverImage.fitWidthProperty().isBound()) {
            coverImage.fitWidthProperty().bind(coverShell.widthProperty());
        }
        if (!coverImage.fitHeightProperty().isBound()) {
            coverImage.fitHeightProperty().bind(coverShell.heightProperty());
        }
        coverImage.setPreserveRatio(false);
        coverImage.setSmooth(true);

        Circle clip = new Circle();
        clip.centerXProperty().bind(coverImage.fitWidthProperty().divide(2));
        clip.centerYProperty().bind(coverImage.fitHeightProperty().divide(2));
        clip.radiusProperty().bind(coverImage.fitWidthProperty().divide(2));
        coverImage.setClip(clip);
    }

    private void enforceTitleStyle(javafx.scene.control.Label label) {
        if (label == null) return;
        label.setFont(Font.font("Archivo", FontWeight.BOLD, 15));
        label.setTextFill(TITLE_COLOR);
        label.setStyle(
                "-fx-font-family: 'Archivo';"
                        + "-fx-font-weight: bold;"
                        + "-fx-font-size: 15px;"
                        + "-fx-font-smoothing-type: lcd;"
                        + "-fx-text-fill: #FFFFFF;"
        );
    }

    private void enforceSubtitleStyle(javafx.scene.control.Label label) {
        if (label == null) return;
        label.setFont(Font.font("Inter", FontWeight.NORMAL, 13));
        label.setTextFill(SUBTITLE_COLOR);
        label.setStyle(
                "-fx-font-family: 'Inter';"
                        + "-fx-font-weight: normal;"
                        + "-fx-font-size: 13px;"
                        + "-fx-font-smoothing-type: lcd;"
                        + "-fx-text-fill: #AFAFAF;"
        );
    }

    @FXML
    private void onHoverEnter(MouseEvent e) {
        if (rootPane != null && !rootPane.getStyleClass().contains(HOVER_CLASS)) {
            rootPane.getStyleClass().add(HOVER_CLASS);
        }
        if (body != null && !body.getStyleClass().contains(BODY_HOVER_CLASS)) {
            body.getStyleClass().add(BODY_HOVER_CLASS);
        }
    }

    @FXML
    private void onHoverExit(MouseEvent e) {
        if (rootPane != null) {
            rootPane.getStyleClass().remove(HOVER_CLASS);
        }
        if (body != null) {
            body.getStyleClass().remove(BODY_HOVER_CLASS);
        }
    }
}
