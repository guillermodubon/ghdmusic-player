package io.github.guillermodubon.musicplayer.services.downloads.provider;

final class YouTubeStartSpacing {

    private long nextStartNanos;
    private boolean startReserved;

    long remainingNanos(long nowNanos) {
        if (!startReserved) return 0L;
        long remaining = nextStartNanos - nowNanos;
        return remaining > 0L ? remaining : 0L;
    }

    long reserveStartNanos(long nowNanos, long spacingNanos) {
        long startNanos = !startReserved || nowNanos - nextStartNanos >= 0L
                ? nowNanos
                : nextStartNanos;
        long safeSpacing = Math.max(0L, spacingNanos);
        nextStartNanos = startNanos + safeSpacing;
        startReserved = true;
        return startNanos;
    }

    void reset() {
        nextStartNanos = 0L;
        startReserved = false;
    }
}
