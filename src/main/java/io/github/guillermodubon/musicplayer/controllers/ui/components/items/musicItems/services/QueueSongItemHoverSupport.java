package io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.services;

import javafx.scene.Cursor;
import javafx.scene.layout.HBox;

import java.util.function.BooleanSupplier;

/** Shares the queue-row hover treatment across playable item variants. */
public final class QueueSongItemHoverSupport {

    private static final String DROP_BEFORE_STYLE = "queue-reorder-drop-before";
    private static final String DROP_AFTER_STYLE = "queue-reorder-drop-after";

    private static final String NORMAL_STYLE = """
            -fx-background-color: transparent;
            -fx-border-color: transparent;
            -fx-background-radius: 8;
            -fx-border-radius: 8;
            -fx-padding: 8 10 8 10;
            """;
    private static final String HOVER_STYLE = """
            -fx-background-color: #222222;
            -fx-border-color: transparent;
            -fx-background-radius: 8;
            -fx-border-radius: 8;
            -fx-padding: 8 10 8 10;
            """;

    private QueueSongItemHoverSupport() {
    }

    public static void install(HBox row, BooleanSupplier selected) {
        if (row == null) return;
        row.setPickOnBounds(true);
        row.setCursor(Cursor.HAND);
        row.hoverProperty().addListener((obs, wasHovered, hovered) -> refresh(row, selected));
        refresh(row, selected);
    }

    public static void refresh(HBox row, BooleanSupplier selected) {
        if (row == null) return;
        boolean isSelected = selected != null && selected.getAsBoolean();
        String baseStyle = isSelected || row.isHover() ? HOVER_STYLE : NORMAL_STYLE;
        row.setStyle(baseStyle + dropIndicatorStyle(row));
    }

    private static String dropIndicatorStyle(HBox row) {
        if (row.getStyleClass().contains(DROP_BEFORE_STYLE)) {
            return "\n-fx-border-color: #0077B6 transparent transparent transparent;"
                    + "\n-fx-border-width: 2 0 0 0;"
                    + "\n-fx-border-radius: 0;";
        }

        if (row.getStyleClass().contains(DROP_AFTER_STYLE)) {
            return "\n-fx-border-color: transparent transparent #0077B6 transparent;"
                    + "\n-fx-border-width: 0 0 2 0;"
                    + "\n-fx-border-radius: 0;";
        }

        return "";
    }
}
