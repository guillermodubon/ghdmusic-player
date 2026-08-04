package io.github.guillermodubon.musicplayer.services.images;

import javafx.scene.CacheHint;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Provides resolution-appropriate versions of the single master app logo.
 * The source PNG remains untouched; each view receives a cached decode sized
 * for its actual UI role and for HiDPI rendering.
 */
public final class AppLogoImageLoader {

    private static final String RESOURCE =
            "/io/github/guillermodubon/musicplayer/assets/images/app_logo.png";
    private static final int SIDEBAR_LOAD_SIZE = 80;
    private static final int SPLASH_LOAD_SIZE = 640;

    private static final Map<Integer, Image> IMAGE_CACHE = new HashMap<>();

    private AppLogoImageLoader() {
    }

    public static synchronized Image loadSidebar() {
        return load(SIDEBAR_LOAD_SIZE);
    }

    public static synchronized Image loadSplash() {
        return load(SPLASH_LOAD_SIZE);
    }

    private static Image load(int requestedSize) {
        Image cached = IMAGE_CACHE.get(requestedSize);
        if (cached != null && !cached.isError()) {
            return cached;
        }

        try (InputStream stream = AppLogoImageLoader.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) return null;

            Image image = new Image(
                    stream,
                    requestedSize,
                    requestedSize,
                    true,
                    true
            );
            if (image.isError()) return null;

            IMAGE_CACHE.put(requestedSize, image);
            return image;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static synchronized void installSidebar(ImageView imageView) {
        install(imageView, loadSidebar());
    }

    public static synchronized void installSplash(ImageView imageView) {
        install(imageView, loadSplash());
    }

    private static void install(ImageView imageView, Image image) {
        if (imageView == null || image == null) return;

        imageView.setImage(image);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        imageView.setCache(true);
        imageView.setCacheHint(CacheHint.QUALITY);
        imageView.setScaleX(1.0);
        imageView.setScaleY(1.0);
    }
}
