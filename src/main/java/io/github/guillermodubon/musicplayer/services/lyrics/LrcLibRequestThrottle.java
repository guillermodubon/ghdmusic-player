package io.github.guillermodubon.musicplayer.services.lyrics;

/**
 * Shared sequential pacing for LRCLIB. The API asks batch clients to wait
 * briefly between requests and to honour Retry-After responses.
 */
final class LrcLibRequestThrottle {

    private static final long MINIMUM_GAP_MILLIS = 350L;
    private long nextRequestAtMillis;

    synchronized void awaitTurn() throws InterruptedException {
        while (true) {
            long waitMillis = nextRequestAtMillis - System.currentTimeMillis();
            if (waitMillis <= 0L) {
                nextRequestAtMillis = System.currentTimeMillis() + MINIMUM_GAP_MILLIS;
                return;
            }
            wait(waitMillis);
        }
    }

    synchronized void deferFor(long delayMillis) {
        long safeDelay = Math.max(0L, delayMillis);
        nextRequestAtMillis = Math.max(nextRequestAtMillis, System.currentTimeMillis() + safeDelay);
        notifyAll();
    }
}
