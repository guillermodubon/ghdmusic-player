package io.github.guillermodubon.musicplayer.repository.identity;

public sealed interface IdentityResolution permits IdentityResolution.Resolved, IdentityResolution.Conflict {

    record Resolved(long internalId, boolean created) implements IdentityResolution {
        public Resolved {
            if (internalId <= 0) throw new IllegalArgumentException("Internal ID must be positive.");
        }
    }

    record Conflict(Reason reason, long existingInternalId) implements IdentityResolution {
    }

    enum Reason {
        ENTITY_NOT_FOUND,
        EXTERNAL_ID_ALREADY_ATTACHED,
        ENTITY_ALREADY_HAS_PROVIDER_ID,
        AMBIGUOUS_LEGACY_MATCH,
        CONTRADICTORY_METADATA
    }
}
