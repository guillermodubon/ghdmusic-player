package io.github.guillermodubon.musicplayer.services.images.colors;

/** Aggregated perceptual and spatial information for one color family. */
final class CoverColorCluster {

    private final int count;
    private final int edgeCount;
    private final int centerCount;
    private final int informativeCount;
    private final int quadrantMask;
    private final PerceptualColorSpace.Oklab lab;
    private final double averageChroma;
    private final double saliencyWeight;
    private final double centralSaliencyWeight;
    private final double radialSaliencyWeight;
    private final double contourPresence;

    CoverColorCluster(int count,
                      int edgeCount,
                      int centerCount,
                      int informativeCount,
                      int quadrantMask,
                      PerceptualColorSpace.Oklab lab,
                      double averageChroma,
                      double saliencyWeight,
                      double centralSaliencyWeight,
                      double radialSaliencyWeight,
                      double contourPresence) {
        this.count = count;
        this.edgeCount = edgeCount;
        this.centerCount = centerCount;
        this.informativeCount = informativeCount;
        this.quadrantMask = quadrantMask;
        this.lab = lab;
        this.averageChroma = averageChroma;
        this.saliencyWeight = saliencyWeight;
        this.centralSaliencyWeight = centralSaliencyWeight;
        this.radialSaliencyWeight = radialSaliencyWeight;
        this.contourPresence = contourPresence;
    }

    int count() {
        return count;
    }

    int edgeCount() {
        return edgeCount;
    }

    int centerCount() {
        return centerCount;
    }

    int informativeCount() {
        return informativeCount;
    }

    int quadrantMask() {
        return quadrantMask;
    }

    PerceptualColorSpace.Oklab lab() {
        return lab;
    }

    double averageChroma() {
        return averageChroma;
    }

    double saliencyWeight() {
        return saliencyWeight;
    }

    double centralSaliencyWeight() {
        return centralSaliencyWeight;
    }

    double radialPresence() {
        return saliencyWeight <= 0.0
                ? 0.0
                : radialSaliencyWeight / saliencyWeight;
    }

    double contourPresence() {
        return contourPresence;
    }

    boolean informative() {
        return informativeCount > 0;
    }

    double edgeRatio() {
        return count <= 0 ? 0.0 : edgeCount / (double) count;
    }

    double averageSaliency() {
        return informativeCount <= 0 ? 0.0 : saliencyWeight / informativeCount;
    }
}
