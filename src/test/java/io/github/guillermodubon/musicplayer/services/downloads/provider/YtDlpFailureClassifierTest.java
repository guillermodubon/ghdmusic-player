package io.github.guillermodubon.musicplayer.services.downloads.provider;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class YtDlpFailureClassifierTest {

    private final YtDlpFailureClassifier classifier = new YtDlpFailureClassifier();

    @Test
    void classifiesProviderAndToolFailuresBeforeGenericFailures() {
        assertEquals(YtDlpFailureKind.PROVIDER_FORBIDDEN,
                classifier.classify("HTTP Error 403: Forbidden; connection reset", 1, null));
        assertEquals(YtDlpFailureKind.PROVIDER_RATE_LIMITED,
                classifier.classify("Too Many Requests (HTTP 429)", 1, null));
        assertEquals(YtDlpFailureKind.BOT_CHALLENGE,
                classifier.classify("Sign in to confirm you are not a bot", 1, null));
        assertEquals(YtDlpFailureKind.JAVASCRIPT_RUNTIME_REQUIRED,
                classifier.classify("No supported JavaScript runtime; install Deno", 1, null));
        assertEquals(YtDlpFailureKind.AUTHENTICATION_REQUIRED,
                classifier.classify("This video requires authentication", 1, null));
    }

    @Test
    void classifiesUnavailableRestrictedNetworkAndUnknownFailures() {
        assertEquals(YtDlpFailureKind.CONTENT_UNAVAILABLE,
                classifier.classify("Video unavailable", 1, null));
        assertEquals(YtDlpFailureKind.CONTENT_RESTRICTED,
                classifier.classify("This video is DRM protected", 1, null));
        assertEquals(YtDlpFailureKind.TRANSIENT_NETWORK,
                classifier.classify("Temporary failure in name resolution", 1, null));
        assertEquals(YtDlpFailureKind.UNKNOWN,
                classifier.classify("ERROR: unexpected extractor failure", 1, null));
        assertEquals(YtDlpFailureKind.NONE, classifier.classify("", 0, null));
    }

    @Test
    void coversAnonymousAccessAndTransportFailureVariants() {
        assertEquals(YtDlpFailureKind.AUTHENTICATION_REQUIRED,
                classifier.classify("Sign in to access this video", 1, null));
        assertEquals(YtDlpFailureKind.CONTENT_UNAVAILABLE,
                classifier.classify("Private video", 1, null));
        assertEquals(YtDlpFailureKind.CONTENT_UNAVAILABLE,
                classifier.classify("Video not available in your country", 1, null));
        assertEquals(YtDlpFailureKind.TRANSIENT_NETWORK,
                classifier.classify("The read operation timed out", 1, null));
        assertEquals(YtDlpFailureKind.TRANSIENT_NETWORK,
                classifier.classify("Connection reset by peer", 1, null));
        assertEquals(YtDlpFailureKind.TRANSIENT_NETWORK,
                classifier.classify("getaddrinfo failed", 1, null));
        assertEquals(YtDlpFailureKind.PROCESS_START_FAILURE,
                classifier.classify("", -1, new IOException("Cannot run program: CreateProcess error=2")));
    }

    @Test
    void explicitRuntimeWarningWinsDisplayClassificationButProviderStatusIsStillRecognized() {
        assertEquals(YtDlpFailureKind.JAVASCRIPT_RUNTIME_REQUIRED,
                classifier.classify("No supported JavaScript runtime; HTTP Error 403: Forbidden", 1, null));
        assertEquals(YtDlpFailureKind.PROVIDER_FORBIDDEN,
                classifier.classify("HTTP Error 403: Forbidden; DNS lookup failed", 1, null));
        assertEquals(Duration.ofSeconds(90),
                classifier.retryAfter("Retry-After: 90").orElseThrow());
    }
}
