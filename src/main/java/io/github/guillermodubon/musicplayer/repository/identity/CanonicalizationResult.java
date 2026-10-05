package io.github.guillermodubon.musicplayer.repository.identity;

import java.util.Objects;
import java.util.Optional;

public record CanonicalizationResult<T>(T value, boolean created, IdentityResolution.Conflict conflict) {

    public CanonicalizationResult {
        if ((value == null) == (conflict == null)) {
            throw new IllegalArgumentException("A canonicalization result must contain either a value or a conflict.");
        }
    }

    public static <T> CanonicalizationResult<T> success(T value, boolean created) {
        return new CanonicalizationResult<>(Objects.requireNonNull(value), created, null);
    }

    public static <T> CanonicalizationResult<T> conflict(IdentityResolution.Conflict conflict) {
        return new CanonicalizationResult<>(null, false, Objects.requireNonNull(conflict));
    }

    public Optional<T> canonicalValue() {
        return Optional.ofNullable(value);
    }

    public Optional<IdentityResolution.Conflict> identityConflict() {
        return Optional.ofNullable(conflict);
    }

    public boolean isSuccess() {
        return value != null;
    }
}
