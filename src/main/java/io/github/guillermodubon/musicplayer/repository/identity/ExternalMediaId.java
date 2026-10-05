package io.github.guillermodubon.musicplayer.repository.identity;

import java.math.BigInteger;
import java.util.Locale;
import java.util.Objects;

/** Provider-scoped identity; it is never interchangeable with a SQLite row ID. */
public record ExternalMediaId(String provider, String externalId) {

    public ExternalMediaId {
        provider = Objects.requireNonNull(provider, "provider").trim().toUpperCase(Locale.ROOT);
        if (!provider.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException("Provider must be a canonical provider name.");
        }
        String value = Objects.requireNonNull(externalId, "externalId").trim();
        if (!value.matches("[0-9]+") || new BigInteger(value).signum() <= 0) {
            throw new IllegalArgumentException("External ID must be a positive decimal identifier.");
        }
        externalId = new BigInteger(value).toString();
    }

    public static ExternalMediaId deezer(long externalId) {
        return new ExternalMediaId("DEEZER", Long.toString(externalId));
    }

    public long numericCandidateId() {
        try {
            return Long.parseLong(externalId);
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }
}
