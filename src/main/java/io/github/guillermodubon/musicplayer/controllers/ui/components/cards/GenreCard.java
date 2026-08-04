package io.github.guillermodubon.musicplayer.controllers.ui.components.cards;

import javafx.fxml.FXML;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.base.BaseCardController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.GenreCardData;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;

import java.util.List;
import java.util.Locale;

public class GenreCard extends BaseCardController<GenreCardData> {
    private static final String MOOD_PREFIX = "genre-mood-";
    private static final List<String> FALLBACK_MOODS = List.of(
            "pop", "dance", "hiphop", "rock", "jazz", "soul", "world", "film"
    );

    @FXML private StackPane root;
    @FXML private StackPane coverShell;
    @FXML private ImageView coverView;
    @FXML private Label titleLabel;

    public void init(GenreCardData data) {
        if (data == null) throw new IllegalArgumentException("data == null");
        setModel(data, null);

        if (titleLabel != null) {
            titleLabel.setText(safeText(data.title(), ""));
        }
        applyGenreMood(data);
        configureCoverFill();
        configureCoverClip();

        Image img = data.coverLocal();
        if (img == null && data.coverUrl() != null && !data.coverUrl().isBlank()) {
            try {
                img = MediaImageResolver.remoteImage(data.coverUrl(), 220, 0);
            } catch (Exception ignored) {
                img = null;
            }
        }

        if (img != null && coverView != null) {
            try {
                coverView.setImage(img);
            } catch (Exception ignored) {}
        }

        if (root != null) {
            root.setCursor(Cursor.HAND);
            bindClick(root, () -> {
                if (data.onClick() != null) {
                    data.onClick().accept(data.genreId());
                }
            });
        }
    }

    private void applyGenreMood(GenreCardData data) {
        if (root == null || data == null) return;
        root.getStyleClass().removeIf(styleClass -> styleClass != null && styleClass.startsWith(MOOD_PREFIX));
        root.getStyleClass().add(moodStyleClassFor(data.genreId(), data.title()));
    }

    public static String moodStyleClassFor(int genreId, String title) {
        return MOOD_PREFIX + moodFor(genreId, title);
    }

    private static String moodFor(int genreId, String title) {
        return switch (genreId) {
            case 132 -> "pop";
            case 106, 113 -> "dance";
            case 116 -> "hiphop";
            case 152 -> "rock";
            case 129 -> "jazz";
            case 98 -> "classical";
            case 144 -> "reggae";
            case 153 -> "blues";
            case 165, 169 -> "soul";
            case 464 -> "metal";
            case 12, 16, 75 -> "world";
            case 173 -> "film";
            case 466 -> "folk";
            default -> moodFromTitleOrFallback(genreId, title);
        };
    }

    private static String moodFromTitleOrFallback(int genreId, String title) {
        String normalized = title == null ? "" : title.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("pop")) return "pop";
        if (normalized.contains("dance") || normalized.contains("electro")) return "dance";
        if (normalized.contains("rap") || normalized.contains("hip")) return "hiphop";
        if (normalized.contains("rock")) return "rock";
        if (normalized.contains("jazz")) return "jazz";
        if (normalized.contains("classic")) return "classical";
        if (normalized.contains("reggae")) return "reggae";
        if (normalized.contains("blues")) return "blues";
        if (normalized.contains("metal")) return "metal";
        if (normalized.contains("film") || normalized.contains("game")) return "film";
        if (genreId <= 0) return "default";
        return FALLBACK_MOODS.get(Math.floorMod(genreId, FALLBACK_MOODS.size()));
    }

    private void configureCoverClip() {
        if (coverView == null || coverView.getClip() != null) return;
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(coverView.fitWidthProperty());
        clip.heightProperty().bind(coverView.fitHeightProperty());
        clip.setArcWidth(10);
        clip.setArcHeight(10);
        coverView.setClip(clip);
    }

    private void configureCoverFill() {
        if (coverView == null || coverShell == null) return;
        if (!coverView.fitWidthProperty().isBound()) {
            coverView.fitWidthProperty().bind(coverShell.widthProperty());
        }
        if (!coverView.fitHeightProperty().isBound()) {
            coverView.fitHeightProperty().bind(coverShell.heightProperty());
        }
        coverView.setPreserveRatio(false);
        coverView.setSmooth(true);
    }

    @FXML
    private void onRootClicked(MouseEvent e) {
        GenreCardData data = model;
        if (data != null && data.onClick() != null) {
            data.onClick().accept(data.genreId());
        }
    }
}
