package io.github.guillermodubon.musicplayer.repository.library;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/** Saved-library state independent of whether a song currently has a local file. */
public final class SavedMediaService {

    private final LongSupplier currentTimeMillis;

    public SavedMediaService() {
        this(System::currentTimeMillis);
    }

    public SavedMediaService(LongSupplier currentTimeMillis) {
        this.currentTimeMillis = Objects.requireNonNull(currentTimeMillis, "currentTimeMillis");
    }

    public void saveSong(Connection connection, long songId) throws SQLException {
        upsertSavedRow(connection, "SavedSong", "SongID", songId);
    }

    public void unsaveSong(Connection connection, long songId) throws SQLException {
        deleteSavedRow(connection, "SavedSong", "SongID", songId);
    }

    public void saveRelease(Connection connection, long albumId) throws SQLException {
        upsertSavedRow(connection, "SavedRelease", "AlbumID", albumId);
    }

    public void unsaveRelease(Connection connection, long albumId) throws SQLException {
        deleteSavedRow(connection, "SavedRelease", "AlbumID", albumId);
    }

    public boolean isSongSavedDirectly(Connection connection, long songId) throws SQLException {
        return exists(connection, "SELECT 1 FROM SavedSong WHERE SongID = ?", songId);
    }

    public boolean isReleaseSaved(Connection connection, long albumId) throws SQLException {
        return exists(connection, "SELECT 1 FROM SavedRelease WHERE AlbumID = ?", albumId);
    }

    public boolean isSongInLibrary(Connection connection, long songId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                  FROM Song song
                 WHERE song.SongID = ?
                   AND (
                       EXISTS (SELECT 1 FROM SavedSong saved WHERE saved.SongID = song.SongID)
                       OR EXISTS (SELECT 1 FROM SavedRelease release WHERE release.AlbumID = song.Album)
                   )
                 LIMIT 1
                """)) {
            statement.setLong(1, songId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    public SavedLibrarySnapshot loadSnapshot(Connection connection,
                                             Collection<Long> songIds,
                                             Collection<Long> albumIds) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Set<Long> songs = validIds(songIds);
        Set<Long> albums = validIds(albumIds);
        Set<Long> directlySaved = queryIds(connection, "SavedSong", "SongID", songs);
        Set<Long> savedReleases = queryIds(connection, "SavedRelease", "AlbumID", albums);
        Set<Long> librarySongs = new HashSet<>(directlySaved);
        librarySongs.addAll(queryReleaseSongs(connection, songs));
        return new SavedLibrarySnapshot(directlySaved, savedReleases, librarySongs);
    }

    public void saveDownloadedSongAndRelease(Connection connection,
                                             long songId,
                                             long albumId) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM Song WHERE SongID = ? AND Album = ?")) {
            statement.setLong(1, songId);
            statement.setLong(2, albumId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Downloaded song does not belong to the supplied release.");
            }
        }
        upsertSavedRow(connection, "SavedSong", "SongID", songId);
        upsertSavedRow(connection, "SavedRelease", "AlbumID", albumId);
    }

    public void saveLocalSongsAndReleases(Connection connection,
                                          Collection<Long> songIds) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Set<Long> ids = validIds(songIds);
        for (ListChunk chunk : chunks(ids)) {
            String placeholders = String.join(",", java.util.Collections.nCopies(chunk.ids().size(), "?"));
            long savedAt = currentTimeMillis.getAsLong();
            try (PreparedStatement songs = connection.prepareStatement(
                    "INSERT INTO SavedSong(SongID, SavedAt) SELECT SongID, ? FROM Song WHERE IsLocal = 1 AND SongID IN ("
                            + placeholders + ") ON CONFLICT(SongID) DO NOTHING")) {
                songs.setLong(1, savedAt);
                bindIds(songs, chunk.ids(), 2);
                songs.executeUpdate();
            }
            try (PreparedStatement releases = connection.prepareStatement(
                    "INSERT INTO SavedRelease(AlbumID, SavedAt) SELECT DISTINCT Album, ? FROM Song WHERE IsLocal = 1 AND SongID IN ("
                            + placeholders + ") ON CONFLICT(AlbumID) DO NOTHING")) {
                releases.setLong(1, savedAt);
                bindIds(releases, chunk.ids(), 2);
                releases.executeUpdate();
            }
        }
    }

    private void upsertSavedRow(Connection connection, String table, String idColumn, long id) throws SQLException {
        validate(connection, idColumn, id);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + table + "(" + idColumn + ", SavedAt) VALUES(?, ?) "
                        + "ON CONFLICT(" + idColumn + ") DO NOTHING")) {
            statement.setLong(1, id);
            statement.setLong(2, currentTimeMillis.getAsLong());
            statement.executeUpdate();
        }
    }

    private void deleteSavedRow(Connection connection, String table, String idColumn, long id) throws SQLException {
        validate(connection, idColumn, id);
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE " + idColumn + " = ?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
    }

    private boolean exists(Connection connection, String sql, long id) throws SQLException {
        validate(connection, "ID", id);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private Set<Long> queryIds(Connection connection,
                               String table,
                               String idColumn,
                               Set<Long> ids) throws SQLException {
        Set<Long> matches = new HashSet<>();
        for (ListChunk chunk : chunks(ids)) {
            String placeholders = String.join(",", java.util.Collections.nCopies(chunk.ids().size(), "?"));
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + idColumn + " FROM " + table + " WHERE " + idColumn + " IN (" + placeholders + ")")) {
                bindIds(statement, chunk.ids());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) matches.add(result.getLong(1));
                }
            }
        }
        return matches;
    }

    private Set<Long> queryReleaseSongs(Connection connection, Set<Long> songIds) throws SQLException {
        Set<Long> matches = new HashSet<>();
        for (ListChunk chunk : chunks(songIds)) {
            String placeholders = String.join(",", java.util.Collections.nCopies(chunk.ids().size(), "?"));
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT song.SongID FROM Song song JOIN SavedRelease saved ON saved.AlbumID = song.Album "
                            + "WHERE song.SongID IN (" + placeholders + ")")) {
                bindIds(statement, chunk.ids());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) matches.add(result.getLong(1));
                }
            }
        }
        return matches;
    }

    private Set<Long> validIds(Collection<Long> source) {
        Set<Long> ids = new HashSet<>();
        if (source != null) {
            for (Long id : source) {
                if (id != null && id > 0) ids.add(id);
            }
        }
        return ids;
    }

    private java.util.List<ListChunk> chunks(Set<Long> ids) {
        java.util.List<Long> values = new ArrayList<>(ids);
        java.util.List<ListChunk> chunks = new ArrayList<>();
        for (int start = 0; start < values.size(); start += 500) {
            chunks.add(new ListChunk(values.subList(start, Math.min(values.size(), start + 500))));
        }
        return chunks;
    }

    private void bindIds(PreparedStatement statement, java.util.List<Long> ids) throws SQLException {
        bindIds(statement, ids, 1);
    }

    private void bindIds(PreparedStatement statement, java.util.List<Long> ids, int startIndex) throws SQLException {
        for (int index = 0; index < ids.size(); index++) statement.setLong(startIndex + index, ids.get(index));
    }

    private record ListChunk(java.util.List<Long> ids) {
    }

    private void validate(Connection connection, String idColumn, long id) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        if (id <= 0) throw new IllegalArgumentException("A positive internal database ID is required.");
        if ("ID".equals(idColumn)) return;
        String table = "SongID".equals(idColumn) ? "Song" : "Album";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM " + table + " WHERE " + idColumn + " = ?")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Cannot save missing canonical " + table + " row " + id + ".");
            }
        }
    }
}
