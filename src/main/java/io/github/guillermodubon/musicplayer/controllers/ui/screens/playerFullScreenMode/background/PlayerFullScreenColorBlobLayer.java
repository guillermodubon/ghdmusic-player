package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.background;

import io.github.guillermodubon.musicplayer.services.images.colors.CoverColorPalette;
import javafx.scene.CacheHint;
import javafx.scene.effect.BlendMode;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Rectangle;

import java.util.List;

/**
 * Renders the layered, cover-driven color fields used by fullscreen playback.
 * The center fields provide broad color movement while darker upper layers and
 * brighter lower layers create a slow, paint-like shift across the viewport.
 */
public final class PlayerFullScreenColorBlobLayer {

    private static final double GEOMETRY_TOLERANCE = 0.5;

    private static final ColorTone MAIN_HIGHLIGHT = new ColorTone(false, 0.0, 1.15, 1.10);
    private static final ColorTone MAIN_SHADOW = new ColorTone(true, -14.0, 0.92, 0.66);
    // The upper fields stay darker than the lower glows, but start from the
    // cover color rather than its deepest variant so they remain perceptible
    // above the already-dark fullscreen base.
    private static final ColorTone BOTTOM_RIGHT_GLOW = new ColorTone(false, 9.0, 1.08, 1.04);
    private static final ColorTone TOP_RIBBON_SHADOW = new ColorTone(false, -18.0, 1.06, 0.66);
    private static final ColorTone BOTTOM_RIBBON_GLOW = new ColorTone(false, 6.0, 1.12, 1.10);
    private static final ColorTone LEFT_SIDE_SHADOW = new ColorTone(true, 16.0, 1.08, 0.74);
    private static final ColorTone RIGHT_SIDE_GLOW = new ColorTone(false, -15.0, 1.02, 1.02);

    private final Pane root = new Pane();
    private final Rectangle viewportClip = new Rectangle();

    private final Ellipse primaryBlob = createBlob(BlendMode.SCREEN);
    private final Ellipse secondaryBlob = createBlob(BlendMode.SRC_OVER);
    private final Ellipse bottomRightCornerGlow = createBlob(BlendMode.SCREEN);
    // These oversized edge fields deliberately use the same soft radial
    // falloff as the center blobs. Unlike a closed Path, they cannot expose a
    // moving geometric edge that looks like a panel over the background.
    private final Ellipse topRibbonShade = createBlob(BlendMode.SRC_OVER);
    private final Ellipse bottomRibbonGlow = createBlob(BlendMode.SCREEN);
    private final Ellipse leftSideVeil = createBlob(BlendMode.SRC_OVER);
    private final Ellipse rightSideVeil = createBlob(BlendMode.SCREEN);

    private final PlayerFullScreenColorBlobMotionCoordinator blobMotion =
            new PlayerFullScreenColorBlobMotionCoordinator(
                    primaryBlob,
                    secondaryBlob
            );
    private final PlayerFullScreenAmbientEdgeMotionCoordinator edgeMotion =
            new PlayerFullScreenAmbientEdgeMotionCoordinator(
                    bottomRightCornerGlow,
                    topRibbonShade,
                    bottomRibbonGlow,
                    leftSideVeil,
                    rightSideVeil
            );
    private final PlayerFullScreenColorToneMotionCoordinator toneMotion =
            new PlayerFullScreenColorToneMotionCoordinator(
                    List.of(
                            secondaryBlob,
                            topRibbonShade,
                            leftSideVeil
                    ),
                    List.of(
                            primaryBlob,
                            bottomRightCornerGlow,
                            bottomRibbonGlow,
                            rightSideVeil
                    )
            );

    private String appliedPaletteKey = "";
    private double laidOutWidth = -1.0;
    private double laidOutHeight = -1.0;
    private boolean disposed;

    public PlayerFullScreenColorBlobLayer() {
        root.setManaged(false);
        root.setMouseTransparent(true);
        root.setPickOnBounds(false);
        root.setClip(viewportClip);
        root.getChildren().setAll(
                primaryBlob,
                secondaryBlob,
                bottomRightCornerGlow,
                bottomRibbonGlow,
                rightSideVeil,
                leftSideVeil,
                topRibbonShade
        );
    }

    public Pane root() {
        return root;
    }

    /** Applies a new cover palette once; all existing movement continues. */
    public void applyPalette(
            CoverColorPalette primary,
            CoverColorPalette secondary,
            CoverColorPalette accent
    ) {
        if (disposed) {
            return;
        }

        CoverColorPalette safePrimary = safePalette(primary);
        CoverColorPalette safeSecondary = safePalette(secondary);
        CoverColorPalette safeAccent = safePalette(accent);
        String paletteKey = paletteKey(safePrimary, safeSecondary, safeAccent);
        if (paletteKey.equals(appliedPaletteKey)) {
            return;
        }

        primaryBlob.setFill(createSoftFieldGradient(safePrimary, 0.60, MAIN_HIGHLIGHT));
        secondaryBlob.setFill(createSoftFieldGradient(safeSecondary, 0.48, MAIN_SHADOW));

        bottomRightCornerGlow.setFill(createSoftFieldGradient(safeSecondary, 0.46, BOTTOM_RIGHT_GLOW));

        topRibbonShade.setFill(createSoftFieldGradient(safeAccent, 0.56, TOP_RIBBON_SHADOW));
        bottomRibbonGlow.setFill(createSoftFieldGradient(safePrimary, 0.54, BOTTOM_RIBBON_GLOW));
        leftSideVeil.setFill(createSoftFieldGradient(safeAccent, 0.38, LEFT_SIDE_SHADOW));
        rightSideVeil.setFill(createSoftFieldGradient(safeSecondary, 0.38, RIGHT_SIDE_GLOW));
        appliedPaletteKey = paletteKey;
    }

    public void clearPalette() {
        primaryBlob.setFill(Color.TRANSPARENT);
        secondaryBlob.setFill(Color.TRANSPARENT);
        bottomRightCornerGlow.setFill(Color.TRANSPARENT);
        topRibbonShade.setFill(Color.TRANSPARENT);
        bottomRibbonGlow.setFill(Color.TRANSPARENT);
        leftSideVeil.setFill(Color.TRANSPARENT);
        rightSideVeil.setFill(Color.TRANSPARENT);
        appliedPaletteKey = "";
    }

    /** Recalculates field geometry only after a meaningful viewport change. */
    public void resize(double width, double height) {
        if (disposed || width <= 1.0 || height <= 1.0 || hasSameGeometry(width, height)) {
            return;
        }

        root.resizeRelocate(0.0, 0.0, width, height);
        viewportClip.setWidth(width);
        viewportClip.setHeight(height);

        layoutBlob(
                primaryBlob,
                Math.max(520.0, width * 0.52),
                Math.max(390.0, height * 0.50),
                width * 0.30,
                height * 0.82
        );
        layoutBlob(
                secondaryBlob,
                Math.max(560.0, width * 0.56),
                Math.max(420.0, height * 0.54),
                width * 0.74,
                height * 0.32
        );
        layoutBlob(
                bottomRightCornerGlow,
                Math.max(500.0, width * 0.50),
                Math.max(420.0, height * 0.52),
                width * 1.04,
                height * 1.02
        );

        layoutBlob(
                topRibbonShade,
                Math.max(900.0, width * 0.92),
                Math.max(270.0, height * 0.36),
                width * 0.50,
                -height * 0.10
        );
        layoutBlob(
                bottomRibbonGlow,
                Math.max(960.0, width * 0.98),
                Math.max(340.0, height * 0.48),
                width * 0.50,
                height * 1.13
        );
        layoutBlob(
                leftSideVeil,
                Math.max(380.0, width * 0.40),
                Math.max(620.0, height * 0.82),
                -width * 0.12,
                height * 0.50
        );
        layoutBlob(
                rightSideVeil,
                Math.max(400.0, width * 0.42),
                Math.max(640.0, height * 0.84),
                width * 1.12,
                height * 0.50
        );

        laidOutWidth = width;
        laidOutHeight = height;
        blobMotion.updateViewport(width, height);
        edgeMotion.updateViewport(width, height);
    }

    public void setVisible(boolean visible) {
        root.setVisible(visible);
    }

    public void play() {
        blobMotion.play();
        edgeMotion.play();
        toneMotion.play();
    }

    public void pause() {
        blobMotion.pause();
        edgeMotion.pause();
        toneMotion.pause();
    }

    public void stop() {
        blobMotion.stop();
        edgeMotion.stop();
        toneMotion.stop();
    }

    public void dispose() {
        if (disposed) {
            return;
        }

        disposed = true;
        blobMotion.dispose();
        edgeMotion.dispose();
        toneMotion.dispose();
        root.getChildren().clear();
        root.setClip(null);
    }

    private Ellipse createBlob(BlendMode blendMode) {
        Ellipse blob = new Ellipse();
        configureColorField(blob, blendMode);
        return blob;
    }

    private void configureColorField(javafx.scene.shape.Shape field, BlendMode blendMode) {
        field.setManaged(false);
        field.setMouseTransparent(true);
        field.setBlendMode(blendMode);
        field.setCache(true);
        field.setCacheHint(CacheHint.SPEED);
        field.setFill(Color.TRANSPARENT);
        field.setStroke(null);
    }

    private RadialGradient createSoftFieldGradient(
            CoverColorPalette palette,
            double opacity,
            ColorTone tone
    ) {
        Color color = tone.apply(palette);
        return new RadialGradient(
                0.0,
                0.0,
                0.5,
                0.5,
                0.5,
                true,
                CycleMethod.NO_CYCLE,
                new Stop(0.0, color.deriveColor(0.0, 1.00, 1.00, opacity * 0.94)),
                new Stop(0.24, color.deriveColor(0.0, 0.98, 0.94, opacity * 0.78)),
                new Stop(0.52, color.deriveColor(0.0, 0.92, 0.82, opacity * 0.42)),
                new Stop(0.76, color.deriveColor(0.0, 0.84, 0.70, opacity * 0.12)),
                new Stop(1.0, Color.TRANSPARENT)
        );
    }

    private void layoutBlob(
            Ellipse blob,
            double radiusX,
            double radiusY,
            double anchorX,
            double anchorY
    ) {
        blob.setRadiusX(radiusX);
        blob.setRadiusY(radiusY);
        blob.setCenterX(radiusX);
        blob.setCenterY(radiusY);
        blob.setLayoutX(anchorX - radiusX);
        blob.setLayoutY(anchorY - radiusY);
    }


    private CoverColorPalette safePalette(CoverColorPalette palette) {
        return palette == null ? new CoverColorPalette(17, 17, 17) : palette;
    }

    private String paletteKey(
            CoverColorPalette primary,
            CoverColorPalette secondary,
            CoverColorPalette accent
    ) {
        return primary.fullscreenHex()
                + ':' + secondary.fullscreenHex()
                + ':' + accent.fullscreenHex();
    }

    private boolean hasSameGeometry(double width, double height) {
        return Math.abs(laidOutWidth - width) < GEOMETRY_TOLERANCE
                && Math.abs(laidOutHeight - height) < GEOMETRY_TOLERANCE;
    }

    private record ColorTone(
            boolean useDeepBase,
            double hueShift,
            double saturation,
            double brightness
    ) {
        private Color apply(CoverColorPalette palette) {
            Color base = Color.web(useDeepBase ? palette.fullscreenDeepHex() : palette.fullscreenHex());
            return base.deriveColor(hueShift, saturation, brightness, 1.0);
        }
    }
}
