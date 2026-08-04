package io.github.guillermodubon.musicplayer.services.images.colors;

/**
 * Compact, UI-independent representation of the color selected from cover art.
 *
 * <p>The RGB components are kept as integers so the result can be reused by
 * JavaFX, CSS, persistence, or any future presentation layer without coupling
 * the color extraction service to a specific UI toolkit.</p>
 */
public record CoverColorPalette(int red, int green, int blue) {

    private static final int DARK_SURFACE = 0x11;

    public CoverColorPalette {
        red = clamp(red);
        green = clamp(green);
        blue = clamp(blue);
    }

    /** Returns the extracted dominant color in CSS-compatible hexadecimal form. */
    public String dominantHex() {
        return toHex(red, green, blue);
    }

    /**
     * Returns a darkened variant suitable for the header surface. Cover colors
     * can be very bright, so blending with the application surface preserves
     * contrast while keeping the artwork's visual identity.
     */
    public String headerHex() {
        return toHex(headerRed(), headerGreen(), headerBlue());
    }

    /** A restrained highlight used at the top of the header gradient. */
    public String headerTopHex() {
        return toHex(
                blend(headerRed(), 0xFF, 0.17),
                blend(headerGreen(), 0xFF, 0.17),
                blend(headerBlue(), 0xFF, 0.17)
        );
    }

    /** A darker continuation that connects the header to the lower fade. */
    public String headerBottomHex() {
        return toHex(
                blend(headerRed(), DARK_SURFACE, 0.46),
                blend(headerGreen(), DARK_SURFACE, 0.46),
                blend(headerBlue(), DARK_SURFACE, 0.46)
        );
    }

    public String headerRgba(double opacity) {
        return rgbaFromHex(headerHex(), opacity);
    }

    /** A rich but controlled tone suitable for a fullscreen ambient background. */
    public String fullscreenHex() {
        return toHex(
                blend(red, DARK_SURFACE, 0.18),
                blend(green, DARK_SURFACE, 0.18),
                blend(blue, DARK_SURFACE, 0.18)
        );
    }

    /** Returns the rich fullscreen tone in CSS-compatible RGBA form. */
    public String fullscreenRgba(double opacity) {
        return rgbaFromHex(fullscreenHex(), opacity);
    }

    /** A darker edge tone that keeps the fullscreen artwork visually focused. */
    public String fullscreenDeepHex() {
        return toHex(
                blend(red, DARK_SURFACE, 0.64),
                blend(green, DARK_SURFACE, 0.64),
                blend(blue, DARK_SURFACE, 0.64)
        );
    }

    /** Returns the dark fullscreen base tone in CSS-compatible RGBA form. */
    public String fullscreenDeepRgba(double opacity) {
        return rgbaFromHex(fullscreenDeepHex(), opacity);
    }

    public String fadeMidRgba(double opacity) {
        return rgbaFromHex(headerBottomHex(), opacity);
    }

    public String fadeNearEndRgba(double opacity) {
        return rgbaFromHex(headerBottomHex(), opacity);
    }

    private int headerRed() {
        return blend(red, DARK_SURFACE, 0.52);
    }

    private int headerGreen() {
        return blend(green, DARK_SURFACE, 0.52);
    }

    private int headerBlue() {
        return blend(blue, DARK_SURFACE, 0.52);
    }

    private static String rgbaFromHex(String hex, double opacity) {
        return "rgba(%d, %d, %d, %.3f)".formatted(
                Integer.parseInt(hex.substring(1, 3), 16),
                Integer.parseInt(hex.substring(3, 5), 16),
                Integer.parseInt(hex.substring(5, 7), 16),
                Math.max(0.0, Math.min(1.0, opacity))
        );
    }

    private static int blend(int value, int target, double targetWeight) {
        return clamp((int) Math.round(value * (1.0 - targetWeight) + target * targetWeight));
    }

    private static String toHex(int red, int green, int blue) {
        return "#%02X%02X%02X".formatted(clamp(red), clamp(green), clamp(blue));
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
