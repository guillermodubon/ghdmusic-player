package io.github.guillermodubon.musicplayer.services.downloads.managers;

public class DownloadRetryPolicy {


    public long computeDelayMillis(int attempt) {
        return 1000L * (long) Math.pow(2, Math.max(0, attempt - 1));
    }

    public String buildRetryMessage(int attempt, int maxAttempts, long delayMs) {
        return "Retrying in " + (delayMs / 1000) + "s (" + (attempt + 1) + "/" + maxAttempts + ")";
    }
}
