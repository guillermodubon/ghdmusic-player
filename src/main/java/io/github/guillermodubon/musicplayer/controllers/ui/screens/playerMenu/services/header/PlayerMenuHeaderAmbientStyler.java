package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.header;

import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

/**
 * Renders a blurred cover behind the PlayerMenu header when no extracted
 * accent color passes the readability filters.
 *
 * <p>The nodes are unmanaged, so they never participate in the header layout
 * and cannot change the position or size of the existing header content.</p>
 */
final class PlayerMenuHeaderAmbientStyler {

    private static final double BLUR_RADIUS = 48.0;
    private static final int AMBIENT_IMAGE_SIZE = 32;
    private static final String SHADE_STYLE = "-fx-background-color: rgba(0, 0, 0, 0.40);";

    private HBox header;
    private ImageView ambientCover;
    private Region ambientShade;

    void bind(HBox header) {
        if (header == null || this.header == header) return;

        this.header = header;
        ambientCover = new ImageView();
        ambientCover.setManaged(false);
        ambientCover.setMouseTransparent(true);
        ambientCover.setPreserveRatio(false);
        ambientCover.setSmooth(true);
        ambientCover.setOpacity(0.78);
        ambientCover.setEffect(new GaussianBlur(BLUR_RADIUS));

        ambientShade = new Region();
        ambientShade.setManaged(false);
        ambientShade.setMouseTransparent(true);
        ambientShade.setStyle(SHADE_STYLE);

        // Insert both layers before the existing content. Unmanaged children
        // are ignored by HBox layout while still covering the full header.
        header.getChildren().add(0, ambientCover);
        header.getChildren().add(1, ambientShade);
        header.widthProperty().addListener((obs, oldValue, newValue) -> resizeLayers());
        header.heightProperty().addListener((obs, oldValue, newValue) -> resizeLayers());
        resizeLayers();
        hide();
    }

    void setImage(Image image) {
        if (ambientCover == null) return;
        ambientCover.setImage(toAmbientImage(image));
        if (image == null || image.isError()) {
            hide();
        } else {
            show();
        }
    }

    void show() {
        if (ambientCover == null || ambientCover.getImage() == null) return;
        ambientCover.setVisible(true);
        ambientShade.setVisible(true);
    }

    void hide() {
        if (ambientCover == null) return;
        ambientCover.setVisible(false);
        ambientShade.setVisible(false);
    }

    void clear() {
        if (ambientCover == null) return;
        ambientCover.setImage(null);
        hide();
    }

    private Image toAmbientImage(Image source) {
        if (source == null || source.isError() || source.getProgress() < 1.0) {
            return source;
        }

        PixelReader reader = source.getPixelReader();
        if (reader == null) return source;

        int sourceWidth = Math.max(1, (int) Math.ceil(source.getWidth()));
        int sourceHeight = Math.max(1, (int) Math.ceil(source.getHeight()));
        WritableImage ambient = new WritableImage(AMBIENT_IMAGE_SIZE, AMBIENT_IMAGE_SIZE);
        PixelWriter writer = ambient.getPixelWriter();
        for (int y = 0; y < AMBIENT_IMAGE_SIZE; y++) {
            int sourceY = Math.min(sourceHeight - 1,
                    (int) ((y + 0.5) * sourceHeight / AMBIENT_IMAGE_SIZE));
            for (int x = 0; x < AMBIENT_IMAGE_SIZE; x++) {
                int sourceX = Math.min(sourceWidth - 1,
                        (int) ((x + 0.5) * sourceWidth / AMBIENT_IMAGE_SIZE));
                writer.setColor(x, y, reader.getColor(sourceX, sourceY));
            }
        }
        return ambient;
    }

    private void resizeLayers() {
        if (header == null || ambientCover == null || ambientShade == null) return;
        double width = Math.max(0.0, header.getWidth());
        double height = Math.max(0.0, header.getHeight());

        ambientCover.setFitWidth(width);
        ambientCover.setFitHeight(height);
        ambientCover.relocate(0, 0);
        ambientShade.resizeRelocate(0, 0, width, height);
    }
}
