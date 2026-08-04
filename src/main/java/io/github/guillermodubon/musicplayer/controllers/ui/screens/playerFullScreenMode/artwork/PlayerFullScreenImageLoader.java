package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.artwork;

import javafx.scene.image.Image;

import java.io.ByteArrayInputStream;

/** Centralizes safe image construction for fullscreen artwork. */
final class PlayerFullScreenImageLoader {

    Image fromUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            return new Image(url, false);
        } catch (Exception ignored) {
            return null;
        }
    }

    Image fromBytes(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        try {
            return new Image(new ByteArrayInputStream(data));
        } catch (Exception ignored) {
            return null;
        }
    }

    boolean isUsable(Image image) {
        return image != null
                && !image.isError()
                && image.getWidth() > 1
                && image.getHeight() > 1;
    }
}
