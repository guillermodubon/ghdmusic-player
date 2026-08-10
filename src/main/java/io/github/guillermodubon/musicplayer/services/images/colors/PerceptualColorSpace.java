package io.github.guillermodubon.musicplayer.services.images.colors;

import javafx.scene.paint.Color;

/** Small RGB/OKLab conversion utility used by the cover color analysis. */
final class PerceptualColorSpace {

    private PerceptualColorSpace() {
    }

    static Oklab from(Color color) {
        double red = toLinear(color.getRed());
        double green = toLinear(color.getGreen());
        double blue = toLinear(color.getBlue());

        double l = Math.cbrt(0.4122214708 * red + 0.5363325363 * green + 0.0514459929 * blue);
        double m = Math.cbrt(0.2119034982 * red + 0.6806995451 * green + 0.1073969566 * blue);
        double s = Math.cbrt(0.0883024619 * red + 0.2817188376 * green + 0.6299787005 * blue);

        return new Oklab(
                0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        );
    }

    static Rgb toRgb(Oklab color) {
        double l = cube(color.luminance() + 0.3963377774 * color.a() + 0.2158037573 * color.b());
        double m = cube(color.luminance() - 0.1055613458 * color.a() - 0.0638541728 * color.b());
        double s = cube(color.luminance() - 0.0894841775 * color.a() - 1.2914855480 * color.b());

        double red = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s;
        double green = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s;
        double blue = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s;
        return new Rgb(toSrgb(red), toSrgb(green), toSrgb(blue));
    }

    private static double toLinear(double value) {
        return value <= 0.04045
                ? value / 12.92
                : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static double toSrgb(double value) {
        double clamped = clamp01(value);
        return clamped <= 0.0031308
                ? clamped * 12.92
                : 1.055 * Math.pow(clamped, 1.0 / 2.4) - 0.055;
    }

    private static double cube(double value) {
        return value * value * value;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    record Oklab(double luminance, double a, double b) {
        double chroma() {
            return Math.hypot(a, b);
        }

        double hue() {
            return Math.atan2(b, a);
        }
    }

    record Rgb(double red, double green, double blue) {
    }
}
