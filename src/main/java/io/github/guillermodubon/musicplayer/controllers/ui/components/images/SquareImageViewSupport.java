package io.github.guillermodubon.musicplayer.controllers.ui.components.images;

import javafx.beans.value.ChangeListener;
import javafx.geometry.Rectangle2D;
import javafx.scene.CacheHint;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

/** Keeps artwork inside a square viewport without distorting its aspect ratio. */
public final class SquareImageViewSupport {

    private static final String BINDING_KEY = SquareImageViewSupport.class.getName() + ".binding";

    private SquareImageViewSupport() {
    }

    public static void install(ImageView imageView) {
        if (imageView == null) return;

        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        imageView.setCache(true);
        imageView.setCacheHint(CacheHint.QUALITY);
        imageView.setScaleX(1.0);
        imageView.setScaleY(1.0);

        if (imageView.getProperties().containsKey(BINDING_KEY)) return;

        Binding binding = new Binding(imageView);
        imageView.getProperties().put(BINDING_KEY, binding);
        imageView.imageProperty().addListener(binding.imageListener);
        binding.observe(imageView.getImage());
    }

    private static final class Binding {
        private final ImageView imageView;
        private Image observedImage;

        private final ChangeListener<Number> dimensionListener =
                (obs, oldValue, newValue) -> updateViewport();
        private final ChangeListener<Image> imageListener =
                (obs, oldImage, newImage) -> observe(newImage);

        private Binding(ImageView imageView) {
            this.imageView = imageView;
        }

        private void observe(Image image) {
            if (observedImage == image) {
                updateViewport();
                return;
            }

            if (observedImage != null) {
                observedImage.widthProperty().removeListener(dimensionListener);
                observedImage.heightProperty().removeListener(dimensionListener);
            }

            observedImage = image;
            if (image == null) {
                imageView.setViewport(null);
                return;
            }

            image.widthProperty().addListener(dimensionListener);
            image.heightProperty().addListener(dimensionListener);
            updateViewport();
        }

        private void updateViewport() {
            Image image = observedImage;
            if (image == null) {
                imageView.setViewport(null);
                return;
            }

            double width = image.getWidth();
            double height = image.getHeight();
            if (width <= 0 || height <= 0) {
                imageView.setViewport(null);
                return;
            }

            double side = Math.min(width, height);
            imageView.setViewport(new Rectangle2D(
                    (width - side) / 2.0,
                    (height - side) / 2.0,
                    side,
                    side
            ));
        }
    }
}
