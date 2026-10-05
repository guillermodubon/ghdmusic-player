package io.github.guillermodubon.musicplayer.repository.identity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Optional;

/** Provider identity mapping operations; callers own the surrounding transaction. */
public final class ExternalIdentityDao {

    public enum EntityType {
        SONG("SongExternalIdentity", "SongID", "Song"),
        ALBUM("AlbumExternalIdentity", "AlbumID", "Album"),
        PLAYLIST("PlaylistExternalIdentity", "PlaylistID", "Playlist");

        private final String identityTable;
        private final String entityIdColumn;
        private final String entityTable;

        EntityType(String identityTable, String entityIdColumn, String entityTable) {
            this.identityTable = identityTable;
            this.entityIdColumn = entityIdColumn;
            this.entityTable = entityTable;
        }
    }

    private final Connection connection;

    public ExternalIdentityDao(Connection connection) {
        this.connection = java.util.Objects.requireNonNull(connection, "connection");
    }

    public OptionalLong resolve(EntityType type, ExternalMediaId identity) throws SQLException {
        String sql = "SELECT " + type.entityIdColumn + " FROM " + type.identityTable
                + " WHERE Provider = ? AND ExternalId = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, identity.provider());
            statement.setString(2, identity.externalId());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? OptionalLong.of(result.getLong(1)) : OptionalLong.empty();
            }
        }
    }

    public Map<ExternalMediaId, Long> resolveAll(EntityType type,
                                                 Collection<ExternalMediaId> identities) throws SQLException {
        Map<ExternalMediaId, Long> resolved = new HashMap<>();
        if (identities == null || identities.isEmpty()) return Map.of();
        Map<String, List<ExternalMediaId>> byProvider = new HashMap<>();
        for (ExternalMediaId identity : identities) {
            if (identity != null) byProvider.computeIfAbsent(identity.provider(), ignored -> new ArrayList<>()).add(identity);
        }
        for (Map.Entry<String, List<ExternalMediaId>> provider : byProvider.entrySet()) {
            List<ExternalMediaId> entries = provider.getValue();
            for (int start = 0; start < entries.size(); start += 500) {
                List<ExternalMediaId> chunk = entries.subList(start, Math.min(entries.size(), start + 500));
                String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
                String sql = "SELECT ExternalId, " + type.entityIdColumn + " FROM " + type.identityTable
                        + " WHERE Provider = ? AND ExternalId IN (" + placeholders + ")";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, provider.getKey());
                    for (int index = 0; index < chunk.size(); index++) {
                        statement.setString(index + 2, chunk.get(index).externalId());
                    }
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            resolved.put(new ExternalMediaId(provider.getKey(), result.getString("ExternalId")),
                                    result.getLong(type.entityIdColumn));
                        }
                    }
                }
            }
        }
        return Map.copyOf(resolved);
    }

    public Optional<ExternalMediaId> resolveExternalIdentity(EntityType type,
                                                             String provider,
                                                             long internalId) throws SQLException {
        if (internalId <= 0) return Optional.empty();
        String sql = "SELECT ExternalId FROM " + type.identityTable + " WHERE Provider = ? AND "
                + type.entityIdColumn + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, provider);
            statement.setLong(2, internalId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(new ExternalMediaId(provider, result.getString("ExternalId")))
                        : Optional.empty();
            }
        }
    }

    public IdentityResolution attach(EntityType type,
                                     ExternalMediaId identity,
                                     long internalId) throws SQLException {
        if (internalId <= 0) throw new IllegalArgumentException("Internal ID must be positive.");

        OptionalLong existingExternal = resolve(type, identity);
        if (existingExternal.isPresent()) {
            return existingExternal.getAsLong() == internalId
                    ? new IdentityResolution.Resolved(internalId, false)
                    : new IdentityResolution.Conflict(
                            IdentityResolution.Reason.EXTERNAL_ID_ALREADY_ATTACHED,
                            existingExternal.getAsLong()
                    );
        }

        OptionalLong existingProviderId = findProviderIdentityForEntity(type, identity.provider(), internalId);
        if (existingProviderId.isPresent()) {
            return new IdentityResolution.Conflict(
                    IdentityResolution.Reason.ENTITY_ALREADY_HAS_PROVIDER_ID,
                    internalId
            );
        }

        if (!entityExists(type, internalId)) {
            return new IdentityResolution.Conflict(IdentityResolution.Reason.ENTITY_NOT_FOUND, 0);
        }

        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + type.identityTable + "(Provider, ExternalId, " + type.entityIdColumn + ") VALUES(?, ?, ?)")) {
            statement.setString(1, identity.provider());
            statement.setString(2, identity.externalId());
            statement.setLong(3, internalId);
            statement.executeUpdate();
            return new IdentityResolution.Resolved(internalId, true);
        } catch (SQLException insertFailure) {
            OptionalLong racedMapping = resolve(type, identity);
            if (racedMapping.isPresent()) {
                return racedMapping.getAsLong() == internalId
                        ? new IdentityResolution.Resolved(internalId, false)
                        : new IdentityResolution.Conflict(
                                IdentityResolution.Reason.EXTERNAL_ID_ALREADY_ATTACHED,
                                racedMapping.getAsLong()
                        );
            }
            OptionalLong racedEntityMapping = findProviderIdentityForEntity(type, identity.provider(), internalId);
            if (racedEntityMapping.isPresent()) {
                return new IdentityResolution.Conflict(
                        IdentityResolution.Reason.ENTITY_ALREADY_HAS_PROVIDER_ID,
                        internalId
                );
            }
            throw insertFailure;
        }
    }

    private OptionalLong findProviderIdentityForEntity(EntityType type,
                                                       String provider,
                                                       long internalId) throws SQLException {
        String sql = "SELECT 1 FROM " + type.identityTable + " WHERE Provider = ? AND "
                + type.entityIdColumn + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, provider);
            statement.setLong(2, internalId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? OptionalLong.of(internalId) : OptionalLong.empty();
            }
        }
    }

    private boolean entityExists(EntityType type, long internalId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM " + type.entityTable + " WHERE " + type.entityIdColumn + " = ?")) {
            statement.setLong(1, internalId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }
}
