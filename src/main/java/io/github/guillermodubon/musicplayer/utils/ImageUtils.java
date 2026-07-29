package io.github.guillermodubon.musicplayer.utils;

import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public final class ImageUtils {
    public static byte[] toByteArray(Image fxImage) throws IOException {
        if (fxImage == null) {
            return null;
        }
        // Convert to BufferedImage
        BufferedImage bImage = SwingFXUtils.fromFXImage(fxImage, null);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            // Write as PNG
            ImageIO.write(bImage, "png", baos);
            return baos.toByteArray();
        }
    }
}
