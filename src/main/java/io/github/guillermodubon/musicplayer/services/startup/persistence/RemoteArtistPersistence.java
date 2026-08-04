package io.github.guillermodubon.musicplayer.services.startup.persistence;

import io.github.guillermodubon.musicplayer.repository.dao.artist.ArtistDaoImpl;
import io.github.guillermodubon.musicplayer.utils.ArtistIdentity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persists the artists discovered while a remotely sourced album becomes local.
 * Keeping this work separate prevents the promotion workflow from mixing album,
 * song, and artist persistence concerns.
 */
final class RemoteArtistPersistence {

    private RemoteArtistPersistence() {
    }

    static long ensureByName(
            Connection connection,
            ArtistDaoImpl artistDao,
            String artistName,
            Map<Long, String> artistNamesById,
            Map<Long, List<byte[]>> artistImageBytes
    ) {
        if (artistName == null || artistName.isBlank()) {
            return 0L;
        }
        if (ArtistIdentity.isVariousArtists(artistName)) return 0L;

        Long explicitId = findIdByName(artistName, artistNamesById);
        if (explicitId != null && explicitId > 0) {
            return ensureById(connection, artistDao, explicitId, artistName, artistImageBytes.get(explicitId));
        }

        try {
            Long found = artistDao.findIdByName(artistName);
            if (found != null && found > 0) {
                return found;
            }
            artistDao.create(artistName);
            Long createdId = artistDao.findIdByName(artistName);
            return createdId == null ? 0L : createdId;
        } catch (Exception exception) {
            System.out.println("remote artist persistence: warning ensuring artist '" + artistName + "' -> "
                    + Optional.ofNullable(exception.getMessage()).orElse("null"));
            return 0L;
        }
    }

    static long ensureById(
            Connection connection,
            ArtistDaoImpl artistDao,
            Long deezerArtistId,
            String preferredName,
            List<byte[]> imageBytes
    ) {
        if (connection == null || deezerArtistId == null || deezerArtistId <= 0) {
            return 0L;
        }
        if (ArtistIdentity.isVariousArtists(preferredName)) return 0L;

        String cleanName = preferredName == null || preferredName.isBlank()
                ? "Unknown Artist " + deezerArtistId
                : preferredName.strip();

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT ArtistID FROM Artist WHERE ArtistID = ? LIMIT 1")) {
            statement.setLong(1, deezerArtistId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    insertImages(connection, artistDao, deezerArtistId, imageBytes);
                    return deezerArtistId;
                }
            }
        } catch (Exception exception) {
            System.out.println("remote artist persistence: warning checking artist id=" + deezerArtistId + " -> "
                    + Optional.ofNullable(exception.getMessage()).orElse("null"));
        }

        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO Artist(ArtistID, Name, Biography) VALUES(?, ?, NULL)")) {
            statement.setLong(1, deezerArtistId);
            statement.setString(2, cleanName);
            statement.executeUpdate();
        } catch (Exception exception) {
            System.out.println("remote artist persistence: warning inserting artist id=" + deezerArtistId + " -> "
                    + Optional.ofNullable(exception.getMessage()).orElse("null"));
        }

        try {
            Long persistedId = findPersistedId(connection, deezerArtistId);
            if (persistedId != null && persistedId > 0) {
                insertImages(connection, artistDao, persistedId, imageBytes);
                return persistedId;
            }

        } catch (Exception ignored) {
        }

        return 0L;
    }

    static boolean sameName(String first, String second) {
        return first != null && second != null && first.strip().equalsIgnoreCase(second.strip());
    }

    private static Long findIdByName(String artistName, Map<Long, String> artistNamesById) {
        if (artistName == null || artistNamesById == null || artistNamesById.isEmpty()) {
            return null;
        }
        for (Map.Entry<Long, String> entry : artistNamesById.entrySet()) {
            if (entry.getKey() != null && entry.getKey() > 0 && sameName(artistName, entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static Long findPersistedId(Connection connection, long artistId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT ArtistID FROM Artist WHERE ArtistID = ? LIMIT 1")) {
            statement.setLong(1, artistId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : null;
            }
        }
    }

    private static void insertImages(
            Connection connection,
            ArtistDaoImpl artistDao,
            long artistId,
            List<byte[]> imageBytes
    ) {
        if (connection == null || artistDao == null || artistId <= 0 || imageBytes == null || imageBytes.isEmpty()) {
            return;
        }
        try {
            artistDao.insertArtistImageBlobs(connection, artistId, imageBytes);
        } catch (Exception exception) {
            System.out.println("remote artist persistence: warning inserting artist images id=" + artistId + " -> "
                    + Optional.ofNullable(exception.getMessage()).orElse("null"));
        }
    }
}
