package io.github.guillermodubon.musicplayer.services.images.colors;

import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.paint.Color;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Extracts a representative color from an already decoded image.
 *
 * <p>Only a small, evenly distributed sample of the image is inspected. This
 * keeps the operation inexpensive for screen transitions while favoring the
 * most visually salient regions over transparent, pure-black, or pure-white
 * borders commonly found in cover artwork.</p>
 */
public final class CoverColorExtractor {

    private static final int SAMPLE_GRID_SIZE = 32;
    private static final double MIN_ALPHA = 0.18;
    private static final int QUANTIZATION_SHIFT = 4;

    private CoverColorExtractor() {
    }

    public static Optional<CoverColorPalette> extract(Image image) {
        return extractTopColors(image, 1).stream().findFirst();
    }

    /**
     * Returns the most visually salient distinct colors from the image. The
     * result is intentionally bounded so callers can build ambient gradients
     * without increasing image decoding or network work.
     */
    public static List<CoverColorPalette> extractTopColors(Image image, int maxColors) {
        Map<Integer, ColorBucket> buckets = extractBuckets(image);
        if (buckets.isEmpty()) return List.of();

        int requestedColors = Math.max(1, Math.min(8, maxColors));
        List<CoverColorPalette> selected = new ArrayList<>(requestedColors);
        buckets.values().stream()
                .sorted(Comparator.comparingDouble(ColorBucket::salienceScore).reversed())
                .map(ColorBucket::toPalette)
                .forEach(candidate -> {
                    if (selected.size() < requestedColors
                            && isDistinctFromSelected(candidate, selected)) {
                        selected.add(candidate);
                    }
                });
        return selected;
    }

    /**
     * Orders colors for fullscreen playback as: most salient, most frequent,
     * then second most frequent. Repeated or visually indistinguishable
     * buckets are skipped so the gradient receives distinct colors.
     */
    public static List<CoverColorPalette> extractFullscreenColors(Image image, int maxColors) {
        Map<Integer, ColorBucket> buckets = extractBuckets(image);
        if (buckets.isEmpty()) return List.of();

        int requestedColors = Math.max(1, Math.min(3, maxColors));
        List<CoverColorPalette> selected = new ArrayList<>(requestedColors);

        buckets.values().stream()
                .sorted(Comparator.comparingDouble(ColorBucket::salienceScore).reversed())
                .findFirst()
                .ifPresent(bucket -> addIfDistinct(bucket.toPalette(), selected, requestedColors));

        buckets.values().stream()
                .sorted(Comparator.comparingInt(ColorBucket::sampleCount).reversed()
                        .thenComparing(Comparator.comparingDouble(ColorBucket::salienceScore).reversed()))
                .forEach(bucket -> addIfDistinct(bucket.toPalette(), selected, requestedColors));

        return selected;
    }

    private static Map<Integer, ColorBucket> extractBuckets(Image image) {
        if (image == null || image.isError() || image.getProgress() < 1.0) {
            return Map.of();
        }

        PixelReader reader = image.getPixelReader();
        int width = safeDimension(image.getWidth());
        int height = safeDimension(image.getHeight());
        if (reader == null || width <= 0 || height <= 0) {
            return Map.of();
        }

        int step = Math.max(1, (int) Math.ceil(
                Math.max(width, height) / (double) SAMPLE_GRID_SIZE));
        Map<Integer, ColorBucket> buckets = new HashMap<>();

        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                Color color = reader.getColor(Math.min(x, width - 1), Math.min(y, height - 1));
                if (color == null || color.getOpacity() < MIN_ALPHA) {
                    continue;
                }

                int red = toChannel(color.getRed());
                int green = toChannel(color.getGreen());
                int blue = toChannel(color.getBlue());
                int key = bucketKey(red, green, blue);
                ColorBucket bucket = buckets.computeIfAbsent(key, ignored -> new ColorBucket());
                bucket.add(red, green, blue, sampleSalience(color));
            }
        }
        return buckets;
    }

    private static void addIfDistinct(
            CoverColorPalette candidate,
            List<CoverColorPalette> selected,
            int requestedColors
    ) {
        if (selected.size() < requestedColors && isDistinctFromSelected(candidate, selected)) {
            selected.add(candidate);
        }
    }

    private static boolean isDistinctFromSelected(
            CoverColorPalette candidate,
            List<CoverColorPalette> selected
    ) {
        if (selected.isEmpty()) return true;
        return selected.stream().allMatch(existing -> colorDistanceSquared(candidate, existing) >= 10 * 10);
    }

    private static int colorDistanceSquared(CoverColorPalette first, CoverColorPalette second) {
        int red = first.red() - second.red();
        int green = first.green() - second.green();
        int blue = first.blue() - second.blue();
        return red * red + green * green + blue * blue;
    }

    /**
     * Measures visual salience instead of raw pixel frequency. Saturation is
     * intentionally the strongest signal, while brightness prevents a very
     * dark saturated pixel from winning over a clearly visible accent color.
     */
    private static double sampleSalience(Color color) {
        double saturation = color.getSaturation();
        double brightness = color.getBrightness();

        // Avoid selecting black/white borders as the visual accent.
        if (brightness < 0.08 || (brightness > 0.97 && saturation < 0.10)) {
            return 0.01;
        }

        double saturationSignal = Math.pow(saturation, 1.35);
        double brightnessSignal = 0.25 + 0.75 * brightness;
        return saturationSignal * brightnessSignal;
    }

    private static int bucketKey(int red, int green, int blue) {
        return (red >> QUANTIZATION_SHIFT) << 16
                | (green >> QUANTIZATION_SHIFT) << 8
                | (blue >> QUANTIZATION_SHIFT);
    }

    private static int toChannel(double value) {
        return (int) Math.round(Math.max(0.0, Math.min(1.0, value)) * 255.0);
    }

    private static int safeDimension(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value <= 0) {
            return 0;
        }
        return (int) Math.ceil(value);
    }

    private static final class ColorBucket {
        private double salienceSum;
        private double peakSalience;
        private int sampleCount;
        private double colorWeight;
        private double red;
        private double green;
        private double blue;

        private void add(int red, int green, int blue, double salience) {
            salienceSum += salience;
            peakSalience = Math.max(peakSalience, salience);
            sampleCount++;
            colorWeight += salience;
            this.red += red * salience;
            this.green += green * salience;
            this.blue += blue * salience;
        }

        /**
         * Keeps coverage as only a small tie-breaker. A vivid accent occupying
         * a small area can therefore beat a dull background occupying most of
         * the cover, while repeated vivid pixels remain stable.
         */
        private double salienceScore() {
            if (sampleCount <= 0) return 0.0;
            double averageSalience = salienceSum / sampleCount;
            double coverageTieBreaker = Math.min(1.0, sampleCount / 8.0);
            return peakSalience * 0.65
                    + averageSalience * 0.30
                    + coverageTieBreaker * 0.05;
        }

        private int sampleCount() {
            return sampleCount;
        }

        private CoverColorPalette toPalette() {
            if (colorWeight <= 0) {
                return new CoverColorPalette(17, 17, 17);
            }
            return new CoverColorPalette(
                    (int) Math.round(red / colorWeight),
                    (int) Math.round(green / colorWeight),
                    (int) Math.round(blue / colorWeight)
            );
        }
    }
}
