package io.github.guillermodubon.musicplayer.services.images.colors;

import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/** Extracts representative UI colors from cover artwork. */
public final class CoverColorExtractor {

    private static final int SAMPLE_GRID_SIZE = 48;
    private static final int MAX_CLUSTERS = 6;
    private static final int MAX_KMEANS_ITERATIONS = 8;
    private static final double MIN_ALPHA = 0.18;
    private static final double MIN_PRIMARY_COVERAGE = 0.10;
    private static final double HUE_FAMILY_DISTANCE = Math.toRadians(35.0);
    private static final double MIN_INFORMATIVE_CHROMA = 0.025;
    private static final double MIN_ACCENT_LUMINANCE = 0.08;
    private static final double MAX_ACCENT_LUMINANCE = 0.95;
    private static final double RADIAL_BAND_MIN = 0.30;
    private static final double RADIAL_BAND_MAX = 0.58;

    private static final double IDEAL_CHROMA = 0.12;
    private static final double UI_MAX_CHROMA = 0.20;
    private static final double DISTINCT_DISTANCE = 0.18;
    private static final double ACCENT_POPULATION_WEIGHT = 0.20;
    private static final double ACCENT_CHROMA_WEIGHT = 0.25;
    private static final double ACCENT_CENTER_WEIGHT = 0.15;
    private static final double ACCENT_CONTOUR_WEIGHT = 0.10;
    private static final double ACCENT_IMPACT_WEIGHT = 0.15;
    private static final double ACCENT_UNIQUENESS_WEIGHT = 0.10;
    private static final double ACCENT_SEMANTIC_WEIGHT = 0.05;

    /** Weak keys keep a decoded cover from being retained by the palette cache. */
    private static final Map<Image, ColorAnalysis> ANALYSIS_CACHE = new WeakHashMap<>();

    private CoverColorExtractor() {
    }

    public static Optional<CoverColorPalette> extract(Image image) {
        return extractTopColors(image, 1).stream().findFirst();
    }

    public static List<CoverColorPalette> extractTopColors(Image image, int maxColors) {
        ColorAnalysis analysis = analyze(image);
        if (analysis.isEmpty()) return List.of();

        int requestedColors = Math.max(1, Math.min(8, maxColors));
        List<CoverColorPalette> selected = new ArrayList<>(requestedColors);
        analysis.rankedClusters().stream()
                .map(RankedCluster::cluster)
                .map(CoverColorExtractor::toPalette)
                .forEach(candidate -> addIfDistinct(candidate, selected, requestedColors));
        return selected;
    }

    public static List<CoverColorPalette> extractFullscreenColors(Image image, int maxColors) {
        ColorAnalysis analysis = analyze(image);
        if (analysis.isEmpty()) return List.of();

        int requestedColors = Math.max(1, Math.min(3, maxColors));
        List<CoverColorPalette> selected = new ArrayList<>(requestedColors);
        analysis.rankedClusters().stream()
                .map(RankedCluster::cluster)
                .map(CoverColorExtractor::toPalette)
                .forEach(candidate -> addIfDistinct(candidate, selected, requestedColors));
        return selected;
    }

    private static ColorAnalysis analyze(Image image) {
        if (!isReady(image)) return ColorAnalysis.EMPTY;

        synchronized (ANALYSIS_CACHE) {
            ColorAnalysis cached = ANALYSIS_CACHE.get(image);
            if (cached != null) return cached;
        }

        List<ColorSample> samples = readSamples(image);
        if (samples.isEmpty()) return ColorAnalysis.EMPTY;

        int informativeSamples = (int) samples.stream()
                .filter(ColorSample::informative)
                .count();
        double informativeSaliency = samples.stream()
                .filter(ColorSample::informative)
                .mapToDouble(ColorSample::saliencyWeight)
                .sum();
        ColorAnalysis analysis = rankClusters(
                cluster(samples),
                informativeSamples,
                informativeSaliency
        );
        synchronized (ANALYSIS_CACHE) {
            ANALYSIS_CACHE.put(image, analysis);
        }
        return analysis;
    }

    private static boolean isReady(Image image) {
        return image != null
                && !image.isError()
                && image.getProgress() >= 1.0
                && image.getPixelReader() != null;
    }

    private static List<ColorSample> readSamples(Image image) {
        PixelReader reader = image.getPixelReader();
        int width = safeDimension(image.getWidth());
        int height = safeDimension(image.getHeight());
        if (reader == null || width <= 0 || height <= 0) return List.of();

        int step = Math.max(1, (int) Math.ceil(
                Math.max(width, height) / (double) SAMPLE_GRID_SIZE));
        List<ColorSample> samples = new ArrayList<>(SAMPLE_GRID_SIZE * SAMPLE_GRID_SIZE);

        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                Color color = reader.getColor(
                        Math.min(x, width - 1),
                        Math.min(y, height - 1)
                );
                if (color == null || color.getOpacity() < MIN_ALPHA) continue;

                double normalizedX = width <= 1 ? 0.5 : x / (double) (width - 1);
                double normalizedY = height <= 1 ? 0.5 : y / (double) (height - 1);
                double distanceFromCenter = Math.sqrt(
                        square(normalizedX - 0.5) + square(normalizedY - 0.5)
                ) / Math.sqrt(0.5);
                double centerWeight = clamp01(1.0 - 0.45 * distanceFromCenter);
                boolean radialBand = distanceFromCenter >= RADIAL_BAND_MIN
                        && distanceFromCenter <= RADIAL_BAND_MAX;
                boolean edge = normalizedX < 0.12 || normalizedX > 0.88
                        || normalizedY < 0.12 || normalizedY > 0.88;
                boolean center = normalizedX >= 0.25 && normalizedX <= 0.75
                        && normalizedY >= 0.25 && normalizedY <= 0.75;
                int quadrant = (normalizedX >= 0.5 ? 1 : 0)
                        | (normalizedY >= 0.5 ? 2 : 0);
                PerceptualColorSpace.Oklab lab = PerceptualColorSpace.from(color);
                double contourWeight = localContrast(
                        reader,
                        x,
                        y,
                        step,
                        width,
                        height,
                        lab
                );
                double chromaSignal = clamp01(lab.chroma() / IDEAL_CHROMA);
                double brightnessSignal = clamp01((lab.luminance() - 0.08) / 0.50);
                double saliencyWeight = (0.40 + 0.60 * chromaSignal)
                        * (0.35 + 0.65 * brightnessSignal)
                        * (0.55 + 0.45 * centerWeight)
                        * (0.65 + 0.35 * contourWeight);

                samples.add(new ColorSample(
                        lab,
                        centerWeight,
                        edge,
                        center,
                        radialBand,
                        quadrant,
                        saliencyWeight,
                        contourWeight
                ));
            }
        }
        return samples;
    }

    private static double localContrast(PixelReader reader,
                                        int x,
                                        int y,
                                        int step,
                                        int width,
                                        int height,
                                        PerceptualColorSpace.Oklab center) {
        double total = neighborDistance(reader, x - step, y, width, height, center)
                + neighborDistance(reader, x + step, y, width, height, center)
                + neighborDistance(reader, x, y - step, width, height, center)
                + neighborDistance(reader, x, y + step, width, height, center);
        return clamp01(total / 4.0 / 0.18);
    }

    private static double neighborDistance(PixelReader reader,
                                           int x,
                                           int y,
                                           int width,
                                           int height,
                                           PerceptualColorSpace.Oklab center) {
        Color neighbor = reader.getColor(
                Math.max(0, Math.min(x, width - 1)),
                Math.max(0, Math.min(y, height - 1))
        );
        if (neighbor == null || neighbor.getOpacity() < MIN_ALPHA) return 0.0;
        return Math.sqrt(distanceSquared(center, PerceptualColorSpace.from(neighbor)));
    }

    private static List<CoverColorCluster> cluster(List<ColorSample> samples) {
        int clusterCount = Math.min(MAX_CLUSTERS, samples.size());
        List<PerceptualColorSpace.Oklab> centers = initializeCenters(samples, clusterCount);
        int[] assignments = new int[samples.size()];
        Arrays.fill(assignments, -1);

        for (int iteration = 0; iteration < MAX_KMEANS_ITERATIONS; iteration++) {
            ClusterAccumulator[] accumulators = createAccumulators(clusterCount);
            boolean changed = false;

            for (int index = 0; index < samples.size(); index++) {
                ColorSample sample = samples.get(index);
                int nearest = nearestCenter(sample.lab(), centers);
                changed |= assignments[index] != nearest;
                assignments[index] = nearest;
                accumulators[nearest].add(sample);
            }

            for (int index = 0; index < clusterCount; index++) {
                if (accumulators[index].count() > 0) {
                    centers.set(index, accumulators[index].center());
                }
            }
            if (!changed && iteration > 0) break;
        }

        ClusterAccumulator[] finalAccumulators = createAccumulators(clusterCount);
        for (ColorSample sample : samples) {
            finalAccumulators[nearestCenter(sample.lab(), centers)].add(sample);
        }

        return Arrays.stream(finalAccumulators)
                .filter(accumulator -> accumulator.count() > 0)
                .map(ClusterAccumulator::toCluster)
                .toList();
    }

    private static List<PerceptualColorSpace.Oklab> initializeCenters(
            List<ColorSample> samples,
            int count
    ) {
        List<PerceptualColorSpace.Oklab> centers = new ArrayList<>(count);
        ColorSample first = samples.stream()
                .max(Comparator.comparingDouble(sample ->
                        sample.centerWeight() * (0.5 + Math.min(1.0, sample.lab().chroma() * 3.0))))
                .orElse(samples.get(0));
        centers.add(first.lab());

        while (centers.size() < count) {
            ColorSample best = null;
            double bestDistance = -1.0;
            for (ColorSample sample : samples) {
                double nearestDistance = centers.stream()
                        .mapToDouble(center -> distanceSquared(sample.lab(), center))
                        .min()
                        .orElse(0.0);
                double weightedDistance = nearestDistance * (0.5 + sample.centerWeight());
                if (weightedDistance > bestDistance) {
                    bestDistance = weightedDistance;
                    best = sample;
                }
            }
            if (best == null) break;
            centers.add(best.lab());
        }
        return centers;
    }

    private static ClusterAccumulator[] createAccumulators(int count) {
        ClusterAccumulator[] accumulators = new ClusterAccumulator[count];
        for (int index = 0; index < count; index++) {
            accumulators[index] = new ClusterAccumulator();
        }
        return accumulators;
    }

    private static int nearestCenter(
            PerceptualColorSpace.Oklab sample,
            List<PerceptualColorSpace.Oklab> centers
    ) {
        int nearest = 0;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (int index = 0; index < centers.size(); index++) {
            double distance = distanceSquared(sample, centers.get(index));
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = index;
            }
        }
        return nearest;
    }

    private static ColorAnalysis rankClusters(List<CoverColorCluster> clusters,
                                              int informativeSamples,
                                              double informativeSaliency) {
        if (clusters.isEmpty()) return ColorAnalysis.EMPTY;

        List<RankedCluster> eyeCatchingRanked = clusters.stream()
                .map(cluster -> new RankedCluster(
                        cluster,
                        eyeCatchingScore(cluster, clusters, informativeSaliency)
                ))
                .sorted(Comparator.comparingDouble(RankedCluster::score).reversed()
                        .thenComparing(Comparator.comparingInt(
                                (RankedCluster rankedCluster) -> rankedCluster.cluster().count()).reversed()))
                .toList();

        List<RankedCluster> coverageCandidates = eyeCatchingRanked.stream()
                .filter(candidate -> isPreferredCoverage(
                        candidate.cluster(),
                        clusters,
                        informativeSamples
                ))
                .toList();
        List<RankedCluster> fallbackCandidates = eyeCatchingRanked.stream()
                .filter(candidate -> informativeSamples <= 0 || candidate.cluster().informative())
                .toList();
        RankedCluster primary = coverageCandidates.stream()
                .max(Comparator.comparingDouble(
                        (RankedCluster rankedCluster) -> rankedCluster.score())
                .thenComparing(Comparator.comparingInt(
                        (RankedCluster rankedCluster) -> rankedCluster.cluster().count())))
                .orElseGet(() -> fallbackCandidates.stream()
                        .max(Comparator.comparingDouble(
                                (RankedCluster rankedCluster) -> rankedCluster.score())
                                .thenComparing(Comparator.comparingInt(
                                        (RankedCluster rankedCluster) -> rankedCluster.cluster().count())))
                        .orElse(fallbackCandidates.isEmpty()
                                ? eyeCatchingRanked.get(0)
                                : fallbackCandidates.get(0)));

        List<RankedCluster> ordered = new ArrayList<>(eyeCatchingRanked.size());
        ordered.add(primary);
        eyeCatchingRanked.stream()
                .filter(candidate -> candidate != primary)
                .forEach(ordered::add);
        return new ColorAnalysis(ordered);
    }

    private static boolean isPreferredCoverage(CoverColorCluster cluster,
                                               List<CoverColorCluster> clusters,
                                               int informativeSamples) {
        double coverage = coverage(cluster, clusters, informativeSamples);
        return coverage >= MIN_PRIMARY_COVERAGE;
    }

    private static double coverage(CoverColorCluster cluster,
                                   List<CoverColorCluster> clusters,
                                   int informativeSamples) {
        if (informativeSamples <= 0 || !cluster.informative()) return 0.0;
        int familySamples = clusters.stream()
                .filter(candidate -> sameHueFamily(cluster, candidate))
                .mapToInt(CoverColorCluster::informativeCount)
                .sum();
        return familySamples / (double) informativeSamples;
    }

    private static boolean sameHueFamily(CoverColorCluster first, CoverColorCluster second) {
        if (!first.informative() || !second.informative()) {
            return !first.informative() && !second.informative();
        }
        double firstHue = first.lab().hue();
        double secondHue = second.lab().hue();
        double distance = Math.abs(firstHue - secondHue);
        distance = Math.min(distance, Math.PI * 2.0 - distance);
        return distance <= HUE_FAMILY_DISTANCE;
    }

    private static double eyeCatchingScore(CoverColorCluster cluster,
                                           List<CoverColorCluster> clusters,
                                           double informativeSaliency) {
        double population = informativeSaliency <= 0.0
                ? 0.0
                : cluster.saliencyWeight() / informativeSaliency;
        double rawChromaStrength = clamp01(
                (cluster.averageChroma() - MIN_INFORMATIVE_CHROMA) / 0.18
        );
        double toneQuality = clamp01(
                1.0 - Math.abs(cluster.lab().luminance() - 0.46) / 0.42
        );
        double chromaStrength = rawChromaStrength * (0.78 + 0.22 * toneQuality);
        double quadrantSpread = Integer.bitCount(cluster.quadrantMask()) / 4.0;
        double centerPresence = cluster.saliencyWeight() <= 0.0
                ? 0.0
                : cluster.centralSaliencyWeight() / cluster.saliencyWeight();
        double uniqueness = uniqueness(cluster, clusters);
        double visualImpact = clamp01(cluster.averageSaliency());
        double structuralPresence = clamp01(
                cluster.contourPresence() * 0.65
                        + cluster.radialPresence() * 0.35
        );
        double semanticPriority = clamp01(
                structuralPresence * 0.50
                        + cluster.radialPresence() * 0.30
                        + quadrantSpread * 0.12
                        + centerPresence * 0.08
        );
        double edgePenalty = cluster.edgeRatio() > 0.85 && quadrantSpread < 0.5
                ? (cluster.edgeRatio() - 0.85) * 0.45
                : 0.0;

        return population * ACCENT_POPULATION_WEIGHT
                + chromaStrength * ACCENT_CHROMA_WEIGHT
                + centerPresence * ACCENT_CENTER_WEIGHT
                + structuralPresence * ACCENT_CONTOUR_WEIGHT
                + visualImpact * ACCENT_IMPACT_WEIGHT
                + uniqueness * ACCENT_UNIQUENESS_WEIGHT
                + semanticPriority * ACCENT_SEMANTIC_WEIGHT
                - edgePenalty;
    }

    private static double uniqueness(CoverColorCluster cluster, List<CoverColorCluster> clusters) {
        if (clusters.size() <= 1) return 0.5;
        double nearest = clusters.stream()
                .filter(other -> other != cluster)
                .mapToDouble(other -> distanceSquared(cluster.lab(), other.lab()))
                .min()
                .orElse(0.0);
        return clamp01(Math.sqrt(nearest) / DISTINCT_DISTANCE);
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
        return selected.stream().allMatch(existing -> colorDistanceSquared(candidate, existing) >= 16 * 16);
    }

    private static int colorDistanceSquared(CoverColorPalette first, CoverColorPalette second) {
        int red = first.red() - second.red();
        int green = first.green() - second.green();
        int blue = first.blue() - second.blue();
        return red * red + green * green + blue * blue;
    }

    private static double distanceSquared(
            PerceptualColorSpace.Oklab first,
            PerceptualColorSpace.Oklab second
    ) {
        return square(first.luminance() - second.luminance())
                + square(first.a() - second.a())
                + square(first.b() - second.b());
    }

    private static PerceptualColorSpace.Oklab normalizeForUi(
            PerceptualColorSpace.Oklab source
    ) {
        double luminance = clamp(source.luminance(), 0.26, 0.56);
        double chroma = source.chroma();
        if (chroma < 0.025) {
            return new PerceptualColorSpace.Oklab(luminance, 0.0, 0.0);
        }

        double normalizedChroma = Math.min(UI_MAX_CHROMA, chroma);
        double scale = normalizedChroma / chroma;
        return new PerceptualColorSpace.Oklab(
                luminance,
                source.a() * scale,
                source.b() * scale
        );
    }

    private static int toChannel(double value) {
        return (int) Math.round(clamp01(value) * 255.0);
    }

    private static int safeDimension(double value) {
        if (!Double.isFinite(value) || value <= 0) return 0;
        return (int) Math.ceil(value);
    }

    private static double square(double value) {
        return value * value;
    }

    private static double clamp01(double value) {
        return clamp(value, 0.0, 1.0);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record ColorSample(
            PerceptualColorSpace.Oklab lab,
            double centerWeight,
            boolean edge,
            boolean center,
            boolean radialBand,
            int quadrant,
            double saliencyWeight,
            double contourWeight
    ) {
        private boolean informative() {
            return lab.chroma() >= MIN_INFORMATIVE_CHROMA
                    && lab.luminance() >= MIN_ACCENT_LUMINANCE
                    && lab.luminance() <= MAX_ACCENT_LUMINANCE;
        }
    }

    private static final class ClusterAccumulator {
        private int count;
        private int edgeCount;
        private int centerCount;
        private int informativeCount;
        private int quadrantMask;
        private double luminance;
        private double a;
        private double b;
        private double chroma;
        private double saliencyWeight;
        private double centralSaliencyWeight;
        private double radialSaliencyWeight;
        private double contourWeight;

        private void add(ColorSample sample) {
            count++;
            edgeCount += sample.edge() ? 1 : 0;
            centerCount += sample.center() ? 1 : 0;
            informativeCount += sample.informative() ? 1 : 0;
            quadrantMask |= 1 << sample.quadrant();
            luminance += sample.lab().luminance();
            a += sample.lab().a();
            b += sample.lab().b();
            chroma += sample.lab().chroma();
            if (sample.informative()) {
                saliencyWeight += sample.saliencyWeight();
                centralSaliencyWeight += sample.center()
                        ? sample.saliencyWeight()
                        : 0.0;
                radialSaliencyWeight += sample.radialBand()
                        ? sample.saliencyWeight()
                        : 0.0;
                contourWeight += sample.contourWeight();
            }
        }

        private int count() {
            return count;
        }

        private PerceptualColorSpace.Oklab center() {
            return new PerceptualColorSpace.Oklab(luminance / count, a / count, b / count);
        }

        private CoverColorCluster toCluster() {
            return new CoverColorCluster(
                    count,
                    edgeCount,
                    centerCount,
                    informativeCount,
                    quadrantMask,
                    center(),
                    chroma / count,
                    saliencyWeight,
                    centralSaliencyWeight,
                    radialSaliencyWeight,
                    informativeCount <= 0 ? 0.0 : contourWeight / informativeCount
            );
        }
    }

    private static CoverColorPalette toPalette(CoverColorCluster cluster) {
        PerceptualColorSpace.Rgb normalized = PerceptualColorSpace.toRgb(normalizeForUi(cluster.lab()));
        return new CoverColorPalette(
                toChannel(normalized.red()),
                toChannel(normalized.green()),
                toChannel(normalized.blue())
        );
    }

    private record RankedCluster(CoverColorCluster cluster, double score) {
    }

    private record ColorAnalysis(List<RankedCluster> rankedClusters) {
        private static final ColorAnalysis EMPTY = new ColorAnalysis(List.of());

        private ColorAnalysis {
            rankedClusters = List.copyOf(rankedClusters);
        }

        private boolean isEmpty() {
            return rankedClusters.isEmpty();
        }
    }

}
