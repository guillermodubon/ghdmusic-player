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
     * Returns a rich mid-tone for the header surface. The hue selected from
     * the cover is preserved while saturation is gently reinforced so the
     * result keeps its visual identity instead of becoming grey or washed out.
     */
    public String headerHex() {
        return headerTone(0.00, 1.08, 0.16, 0.62);
    }

    /** A vivid but controlled highlight used at the top of the header gradient. */
    public String headerTopHex() {
        return headerTone(0.06, 1.12, 0.22, 0.66);
    }

    /** A deep continuation that connects the header to the lower fade. */
    public String headerBottomHex() {
        return headerTone(-0.14, 1.05, 0.10, 0.44);
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

    private String headerTone(double lightnessOffset,
                              double saturationScale,
                              double minimumLightness,
                              double maximumLightness) {
        Hsl source = Hsl.fromRgb(red, green, blue);
        if (source.saturation() < 0.035) {
            return toHex(
                    blend(red, DARK_SURFACE, 0.20),
                    blend(green, DARK_SURFACE, 0.20),
                    blend(blue, DARK_SURFACE, 0.20)
            );
        }

        boolean blueFamily = isBlueFamily(source.hue());
        double hue = blueFamily ? deepenBlueHue(source.hue()) : source.hue();
        double adjustedOffset = lightnessOffset - (blueFamily ? 0.06 : 0.0);
        double adjustedMinimum = minimumLightness - (blueFamily ? 0.02 : 0.0);
        double adjustedMaximum = maximumLightness - (blueFamily ? 0.08 : 0.0);
        return Hsl.toHex(
                hue,
                clamp(source.saturation() * saturationScale, 0.0, 0.96),
                clamp(source.lightness() + adjustedOffset, adjustedMinimum, adjustedMaximum)
        );
    }

    private static boolean isBlueFamily(double hue) {
        return hue >= 0.48 && hue <= 0.70;
    }

    private static double deepenBlueHue(double hue) {
        double royalBlueHue = 0.65;
        return hue + (royalBlueHue - hue) * 0.70;
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

    private record Hsl(double hue, double saturation, double lightness) {

        private static Hsl fromRgb(int red, int green, int blue) {
            double r = red / 255.0;
            double g = green / 255.0;
            double b = blue / 255.0;
            double max = Math.max(r, Math.max(g, b));
            double min = Math.min(r, Math.min(g, b));
            double delta = max - min;
            double lightness = (max + min) * 0.5;
            if (delta < 0.000001) {
                return new Hsl(0.0, 0.0, lightness);
            }

            double saturation = delta / (1.0 - Math.abs(2.0 * lightness - 1.0));
            double hue;
            if (max == r) {
                hue = ((g - b) / delta) % 6.0;
            } else if (max == g) {
                hue = (b - r) / delta + 2.0;
            } else {
                hue = (r - g) / delta + 4.0;
            }
            hue /= 6.0;
            if (hue < 0.0) hue += 1.0;
            return new Hsl(hue, saturation, lightness);
        }

        private static String toHex(double hue, double saturation, double lightness) {
            double chroma = (1.0 - Math.abs(2.0 * lightness - 1.0)) * saturation;
            double second = chroma * (1.0 - Math.abs((hue * 6.0) % 2.0 - 1.0));
            double match = lightness - chroma * 0.5;
            double red;
            double green;
            double blue;
            double sector = hue * 6.0;
            if (sector < 1.0) {
                red = chroma;
                green = second;
                blue = 0.0;
            } else if (sector < 2.0) {
                red = second;
                green = chroma;
                blue = 0.0;
            } else if (sector < 3.0) {
                red = 0.0;
                green = chroma;
                blue = second;
            } else if (sector < 4.0) {
                red = 0.0;
                green = second;
                blue = chroma;
            } else if (sector < 5.0) {
                red = second;
                green = 0.0;
                blue = chroma;
            } else {
                red = chroma;
                green = 0.0;
                blue = second;
            }
            return CoverColorPalette.toHex(
                    (int) Math.round((red + match) * 255.0),
                    (int) Math.round((green + match) * 255.0),
                    (int) Math.round((blue + match) * 255.0)
            );
        }
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
