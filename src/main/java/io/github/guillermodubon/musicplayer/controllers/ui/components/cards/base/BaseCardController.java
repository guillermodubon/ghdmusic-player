package io.github.guillermodubon.musicplayer.controllers.ui.components.cards.base;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.shape.Circle;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;
import io.github.guillermodubon.musicplayer.utils.ArtistIdentity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.function.Consumer;

public abstract class BaseCardController<T> {

    protected T model;
    protected Consumer<T> onClick;

    protected void setModel(T model, Consumer<T> onClick) {
        this.model = model;
        this.onClick = onClick;
    }

    protected String safeText(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    protected Image defaultImage(String resourcePath) {
        return CardImageUtils.loadDefault(resourcePath);
    }

    protected void setImageOrFallback(ImageView imageView, Image image, Image fallback) {
        if (imageView == null) return;
        Image safeFallback = fallback != null ? fallback : MediaImageResolver.defaultCover();
        if (image == null || image.isError()) {
            imageView.setImage(safeFallback);
            return;
        }

        imageView.setImage(image);
        image.errorProperty().addListener((obs, wasError, isError) -> {
            if (Boolean.TRUE.equals(isError)) {
                Platform.runLater(() -> imageView.setImage(safeFallback));
            }
        });
        if (image.isError()) {
            imageView.setImage(safeFallback);
        }
    }

    protected void bindClick(Node node, Runnable action) {
        if (node == null || action == null) return;
        node.setOnMouseClicked(e -> action.run());
    }

    protected void bindCardClick(Node node, String id, Consumer<String> action) {
        if (node == null || action == null) return;
        node.setOnMouseClicked(e -> {
            if (id != null && !id.isBlank()) action.accept(id);
        });
    }

    protected void fillArtistLabels(HBox container, List<String> artists) {
        if (container == null) return;

        container.getChildren().clear();

        if (artists == null || artists.isEmpty()) {
            Label unknown = new Label("Unknown");
            unknown.getStyleClass().add("music-artist-text");
            container.getChildren().add(unknown);
            return;
        }

        for (int i = 0; i < artists.size(); i++) {
            Label label = new Label(safeText(artists.get(i), "Unknown"));
            label.getStyleClass().add("music-artist-text");
            container.getChildren().add(label);

            if (i < artists.size() - 1) {
                Label separator = new Label(",");
                separator.getStyleClass().add("music-artist-separator");
                container.getChildren().add(separator);
            }
        }
    }

    private Label createArtistLabel(String text) {
        Label label = new Label(safeText(text, "Unknown"));
        label.getStyleClass().addAll("app-text-caption", "music-artist-text");
        label.setMouseTransparent(true);
        return label;
    }

    protected void applyCircularClip(ImageView imageView) {
        if (imageView == null || imageView.getBoundsInLocal().isEmpty()) return;
        double r = Math.min(imageView.getBoundsInLocal().getWidth(), imageView.getBoundsInLocal().getHeight()) / 2.0;
        Circle circle = new Circle(r, r, r);
        imageView.setClip(circle);
    }

    protected void applyCircularClipLater(ImageView imageView) {
        if (imageView == null) return;
        Platform.runLater(() -> applyCircularClip(imageView));
    }

    public static final class CardImageUtils {

        private CardImageUtils() {}

        public static Image loadDefault(String resourcePath) {
            return MediaImageResolver.resourceImage(resourcePath, 0, 0);
        }

        public static byte[] downloadUrlToBytes(String urlStr) throws IOException {
            if (urlStr == null || urlStr.isBlank()) return null;

            HttpURLConnection con = null;
            try {
                URL url = new URL(urlStr);
                con = (HttpURLConnection) url.openConnection();
                con.setInstanceFollowRedirects(true);
                con.setRequestMethod("GET");
                con.setConnectTimeout(8_000);
                con.setReadTimeout(10_000);
                con.setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"
                );

                int code = con.getResponseCode();
                if (code < 200 || code >= 400) return null;

                try (InputStream in = con.getInputStream();
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) != -1) {
                        baos.write(buf, 0, r);
                    }
                    return baos.toByteArray();
                }
            } finally {
                if (con != null) con.disconnect();
            }
        }
    }

    protected void fillArtistLinks(HBox container,
                                   List<String> artists,
                                   Consumer<String> onArtistClick) {
        if (container == null) return;

        container.getChildren().clear();

        if (artists == null || artists.isEmpty()) {
            Label unknown = new Label("Unknown");
            unknown.getStyleClass().add("music-artist-text");
            container.getChildren().add(unknown);
            return;
        }

        for (int i = 0; i < artists.size(); i++) {
            String artist = ArtistIdentity.displayName(safeText(artists.get(i), "Unknown"));

            if (ArtistIdentity.isVariousArtists(artist)) {
                Label label = createArtistLabel(artist);
                label.getStyleClass().add("artist-plain-label");
                container.getChildren().add(label);
            } else {
                Hyperlink link = new Hyperlink(artist);

                if (!link.getStyleClass().contains("app-hyperlink")) {
                    link.getStyleClass().add("app-hyperlink");
                }
                if (!link.getStyleClass().contains("music-artist-link")) {
                    link.getStyleClass().add("music-artist-link");
                }

                link.setVisited(false);
                link.setFocusTraversable(false);

                if (onArtistClick != null) {
                    link.setOnAction(e -> {
                        link.setVisited(false);
                        onArtistClick.accept(artist);
                        e.consume();
                    });
                }

                container.getChildren().add(link);
            }

            if (i < artists.size() - 1) {
                Label separator = new Label(",");
                separator.getStyleClass().add("music-artist-separator");
                container.getChildren().add(separator);
            }
        }
    }
}
