package io.github.guillermodubon.musicplayer.services.downloads.provider;

public record ProviderCooldownState(
        int rejectionCount,
        long resumeAtMillis,
        boolean manualResumeRequired,
        YtDlpFailureKind failureKind,
        long generation
) {
    public static ProviderCooldownState clear(long generation) {
        return new ProviderCooldownState(0, 0, false, YtDlpFailureKind.NONE, generation);
    }

    public long remainingMillis(long nowMillis) {
        return manualResumeRequired ? Long.MAX_VALUE : Math.max(0L, resumeAtMillis - nowMillis);
    }

    public boolean isActive(long nowMillis) {
        return manualResumeRequired || resumeAtMillis > nowMillis;
    }
}
