package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.header;

import javafx.scene.Node;
import javafx.scene.layout.Region;
import io.github.guillermodubon.musicplayer.services.images.colors.CoverColorPalette;

import java.util.regex.Pattern;

/** Applies and clears the dynamic cover-based colors of the PlayerMenu header. */
final class PlayerMenuHeaderGradientStyler {

    private static final String BACKGROUND_COLOR_PROPERTY = "-fx-background-color";
    private static final Pattern BACKGROUND_DECLARATION = Pattern.compile(
            "(?i)(?:^|;)\\s*-fx-background-color\\s*:[^;]*;?"
    );

    private PlayerMenuHeaderGradientStyler() {
    }

    static void apply(Region header,
                      Region fade,
                      Region actionsAndSearch,
                      Region firstSongsSurface,
                      CoverColorPalette palette) {
        if (palette == null) {
            clear(header, fade, actionsAndSearch, firstSongsSurface);
            return;
        }

        setBackground(header, "linear-gradient(to bottom, "
                + palette.headerTopHex() + " 0%, "
                + palette.headerHex() + " 40%, "
                + palette.headerHex() + " 58%, "
                + palette.headerBottomHex() + " 100%)");
        setBackground(fade, "linear-gradient(to bottom, "
                + palette.headerBottomHex() + " 0%, "
                + palette.fadeMidRgba(0.78) + " 30%, "
                + palette.fadeNearEndRgba(0.42) + " 100%)");
        setBackground(actionsAndSearch, "linear-gradient(to bottom, "
                + palette.fadeNearEndRgba(0.42) + " 0%, "
                + palette.fadeNearEndRgba(0.18) + " 28%, "
                + "#111111 58%, "
                + "#111111 100%)");
        setBackground(firstSongsSurface, "#111111");
    }

    static void clear(Region header,
                      Region fade,
                      Region actionsAndSearch,
                      Region firstSongsSurface) {
        removeBackground(header);
        removeBackground(fade);
        removeBackground(actionsAndSearch);
        removeBackground(firstSongsSurface);
    }

    private static void setBackground(Node node, String value) {
        if (node == null || value == null || value.isBlank()) return;
        node.setStyle(withBackground(node.getStyle(), value));
    }

    private static void removeBackground(Node node) {
        if (node == null) return;
        node.setStyle(BACKGROUND_DECLARATION.matcher(node.getStyle()).replaceAll(""));
    }

    private static String withBackground(String style, String value) {
        String withoutBackground = BACKGROUND_DECLARATION.matcher(style == null ? "" : style)
                .replaceAll("")
                .trim();
        return (withoutBackground.isEmpty() ? "" : withoutBackground + "; ")
                + BACKGROUND_COLOR_PROPERTY + ": " + value + ";";
    }
}
