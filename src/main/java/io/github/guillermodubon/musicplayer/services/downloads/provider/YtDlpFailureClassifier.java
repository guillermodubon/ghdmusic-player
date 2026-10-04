package io.github.guillermodubon.musicplayer.services.downloads.provider;

import java.util.Locale;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class YtDlpFailureClassifier {

    private static final Pattern RETRY_AFTER_SECONDS = Pattern.compile(
            "(?im)^\\s*retry-after\\s*:\\s*(\\d+)\\s*$"
    );

    public YtDlpFailureKind classify(String output, int exitCode, Throwable processError) {
        String text = normalize(output);
        String exception = normalize(processError == null ? null : processError.getMessage());
        String combined = text + " " + exception;

        if (containsAny(combined,
                "sign in to confirm you’re not a bot",
                "sign in to confirm you are not a bot",
                "confirm you're not a bot",
                "confirm you are not a bot",
                "captcha",
                "verify you are human",
                "unusual traffic")) {
            return YtDlpFailureKind.BOT_CHALLENGE;
        }
        if (containsHttpStatus(combined, 429) || combined.contains("too many requests")) {
            return YtDlpFailureKind.PROVIDER_RATE_LIMITED;
        }
        if (containsAny(combined,
                "no supported javascript runtime",
                "javascript runtime is required",
                "javascript runtime required",
                "yt-dlp-ejs",
                "ejs challenge solver",
                "challenge solving is not supported",
                "install deno",
                "install node.js",
                "js runtime")) {
            return YtDlpFailureKind.JAVASCRIPT_RUNTIME_REQUIRED;
        }
        if (containsHttpStatus(combined, 403) || combined.contains("forbidden")) {
            return YtDlpFailureKind.PROVIDER_FORBIDDEN;
        }
        if (containsAny(combined,
                "sign in to access",
                "login required",
                "authentication required",
                "requires authentication",
                "cookies are needed",
                "cookies are required",
                "members-only content",
                "age-restricted")) {
            return YtDlpFailureKind.AUTHENTICATION_REQUIRED;
        }
        if (containsAny(combined,
                "video unavailable",
                "video is unavailable",
                "has been removed",
                "video not found",
                "private video",
                "video is private",
                "does not exist",
                "not available in your country",
                "not available in this country")) {
            return YtDlpFailureKind.CONTENT_UNAVAILABLE;
        }
        if (containsAny(combined,
                "drm protected",
                "drm-protected",
                "drm is not supported",
                "content is restricted",
                "not available for download",
                "playback on other websites has been disabled")) {
            return YtDlpFailureKind.CONTENT_RESTRICTED;
        }
        if (processError != null && containsAny(exception,
                "bundled dependency",
                "ffmpeg installation is incomplete",
                "currently support windows only")) {
            return YtDlpFailureKind.TOOL_CONFIGURATION;
        }
        if (processError != null && containsAny(exception,
                "cannot run program",
                "createprocess error",
                "failed to start",
                "the system cannot find the file specified")) {
            return YtDlpFailureKind.PROCESS_START_FAILURE;
        }
        if (containsAny(combined,
                "network is unreachable",
                "no route to host",
                "connection refused",
                "connection reset",
                "connection aborted",
                "remote end closed connection",
                "temporary failure in name resolution",
                "name or service not known",
                "getaddrinfo failed",
                "timed out",
                "timeout",
                "dns")) {
            return YtDlpFailureKind.TRANSIENT_NETWORK;
        }
        if (exitCode != 0 || (processError != null && !combined.isBlank())) {
            return YtDlpFailureKind.UNKNOWN;
        }
        return YtDlpFailureKind.NONE;
    }

    public YtDlpFailureKind classifyLine(String line) {
        return classify(line, 0, null);
    }

    public Optional<Duration> retryAfter(String output) {
        if (output == null || output.isBlank()) return Optional.empty();
        Matcher matcher = RETRY_AFTER_SECONDS.matcher(output);
        if (!matcher.find()) return Optional.empty();
        try {
            return Optional.of(Duration.ofSeconds(Long.parseLong(matcher.group(1))));
        } catch (ArithmeticException | NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private boolean containsHttpStatus(String text, int status) {
        return text.matches(".*(?:http\\s+error\\s+|http/\\d(?:\\.\\d)?\\s+)?"
                + status + "(?:\\s|:|$).*" );
    }

    private boolean containsAny(String text, String... fragments) {
        for (String fragment : fragments) {
            if (text.contains(fragment)) return true;
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
