package io.github.guillermodubon.musicplayer.services.downloads.provider;

public enum YtDlpFailureKind {
    NONE,
    TRANSIENT_NETWORK,
    PROVIDER_FORBIDDEN,
    PROVIDER_RATE_LIMITED,
    BOT_CHALLENGE,
    JAVASCRIPT_RUNTIME_REQUIRED,
    AUTHENTICATION_REQUIRED,
    CONTENT_UNAVAILABLE,
    CONTENT_RESTRICTED,
    TOOL_CONFIGURATION,
    PROCESS_START_FAILURE,
    PROVIDER_COOLDOWN,
    COORDINATOR_SHUTDOWN,
    UNKNOWN;

    public boolean isProviderRejection() {
        return this == PROVIDER_FORBIDDEN
                || this == PROVIDER_RATE_LIMITED
                || this == BOT_CHALLENGE;
    }

    public boolean isProviderCooldown() {
        return isProviderRejection() || this == PROVIDER_COOLDOWN;
    }

    public boolean isRetryableNetworkFailure() {
        return this == TRANSIENT_NETWORK;
    }
}
