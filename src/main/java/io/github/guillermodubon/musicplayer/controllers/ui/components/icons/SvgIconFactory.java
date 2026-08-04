package io.github.guillermodubon.musicplayer.controllers.ui.components.icons;

import javafx.scene.Node;
import javafx.geometry.Bounds;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SvgIconFactory {

    private static final Pattern PATH_PATTERN = Pattern.compile("<path\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATH_D_PATTERN = Pattern.compile("d=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILL_PATTERN = Pattern.compile("fill=\"([^\"]+)\"");

    private SvgIconFactory() {}

    public static Node icon(String resourcePath, double size) {
        Region icon = new Region();
        icon.setMinSize(size, size);
        icon.setPrefSize(size, size);
        icon.setMaxSize(size, size);
        icon.setMouseTransparent(true);
        icon.setFocusTraversable(false);

        String svg = readResource(resourcePath);
        if (svg == null || svg.isBlank()) return icon;

        String pathData = parsePath(svg);
        if (pathData == null || pathData.isBlank()) return icon;

        SVGPath shape = new SVGPath();
        shape.setContent(pathData);
        icon.setShape(shape);
        icon.setScaleShape(true);
        icon.setCenterShape(true);
        icon.setCacheShape(true);

        Color fill = Color.web(parseFill(svg));
        icon.setStyle("-fx-background-color: " + toRgba(fill) + ";");
        return icon;
    }

    /**
     * Creates an icon in a fixed square container while preserving the
     * original SVG path aspect ratio. This is useful for icons whose path is
     * intentionally wider or taller than the viewBox, such as more_horiz.
     */
    public static Node iconPreservingAspectRatio(String resourcePath, double size) {
        AspectRatioIcon icon = new AspectRatioIcon(size);

        String svg = readResource(resourcePath);
        if (svg == null || svg.isBlank()) return icon;

        String pathData = parsePath(svg);
        if (pathData == null || pathData.isBlank()) return icon;

        SVGPath shape = new SVGPath();
        shape.setContent(pathData);
        shape.setFill(Color.web(parseFill(svg)));

        Bounds bounds = shape.getLayoutBounds();
        double maxDimension = Math.max(bounds.getWidth(), bounds.getHeight());
        if (maxDimension > 0) {
            double scale = (size * 0.82) / maxDimension;
            shape.getTransforms().add(new Scale(scale, scale));
            shape.setManaged(false);
            shape.setTranslateX((size - bounds.getWidth() * scale) / 2
                    - bounds.getMinX() * scale);
            shape.setTranslateY((size - bounds.getHeight() * scale) / 2
                    - bounds.getMinY() * scale);
        }

        icon.setPath(shape);
        return icon;
    }

    public static void setIconColor(Node icon, String color) {
        if (icon instanceof AspectRatioIcon aspectRatioIcon && color != null && !color.isBlank()) {
            aspectRatioIcon.setPathColor(color);
            return;
        }
        if (icon instanceof Region region && color != null && !color.isBlank()) {
            region.setStyle("-fx-background-color: " + color + ";");
            return;
        }
        if (icon instanceof SVGPath path && color != null && !color.isBlank()) {
            path.setFill(Color.web(color));
        }
    }

    private static String readResource(String resourcePath) {
        try (InputStream in = SvgIconFactory.class.getResourceAsStream(resourcePath)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String parsePath(String svg) {
        Matcher matcher = PATH_PATTERN.matcher(svg);
        StringBuilder paths = new StringBuilder();
        while (matcher.find()) {
            String tag = matcher.group();
            if (tag == null || tag.toLowerCase().contains("fill=\"none\"")) continue;

            Matcher pathMatcher = PATH_D_PATTERN.matcher(tag);
            if (!pathMatcher.find()) continue;

            String path = pathMatcher.group(1);
            if (path == null || path.isBlank()) continue;

            if (!paths.isEmpty()) paths.append(' ');
            paths.append(path.trim());
        }
        return paths.isEmpty() ? null : paths.toString();
    }

    private static String parseFill(String svg) {
        Matcher matcher = FILL_PATTERN.matcher(svg);
        return matcher.find() ? matcher.group(1) : "#EDCC0E";
    }

    private static String toRgba(Color color) {
        int r = (int) Math.round(color.getRed() * 255);
        int g = (int) Math.round(color.getGreen() * 255);
        int b = (int) Math.round(color.getBlue() * 255);
        return "rgba(" + r + "," + g + "," + b + "," + color.getOpacity() + ")";
    }

    private static final class AspectRatioIcon extends StackPane {
        private SVGPath path;

        private AspectRatioIcon(double size) {
            setMinSize(size, size);
            setPrefSize(size, size);
            setMaxSize(size, size);
            setMouseTransparent(true);
            setFocusTraversable(false);
        }

        private void setPath(SVGPath path) {
            this.path = path;
            getChildren().setAll(path);
        }

        private void setPathColor(String color) {
            if (path != null) path.setFill(Color.web(color));
        }
    }
}
