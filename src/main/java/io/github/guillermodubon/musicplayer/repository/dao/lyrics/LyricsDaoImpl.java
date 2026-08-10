package io.github.guillermodubon.musicplayer.repository.dao.lyrics;

import io.github.guillermodubon.musicplayer.models.lyrics.LyricsLookupCandidate;
import io.github.guillermodubon.musicplayer.models.lyrics.LyricsStatus;
import io.github.guillermodubon.musicplayer.models.lyrics.LyricsTrackMatch;
import io.github.guillermodubon.musicplayer.models.lyrics.PlainLyrics;
import io.github.guillermodubon.musicplayer.models.lyrics.SongLyrics;
import io.github.guillermodubon.musicplayer.models.lyrics.SyncedLyrics;
import io.github.guillermodubon.musicplayer.repository.dao.support.JdbcDaoSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public final class LyricsDaoImpl extends JdbcDaoSupport implements LyricsDao {

    private static final int QUERY_CHUNK_SIZE = 400;

    public LyricsDaoImpl(Connection connection) {
        super(connection);
    }

    @Override
    public Optional<SongLyrics> findBySourceKey(String sourceKey) throws SQLException {
        if (sourceKey == null || sourceKey.isBlank()) return Optional.empty();

        Connection connection = openConnection();
        boolean close = closesConnection(connection);
        try (PreparedStatement statement = prepareStatementWithRetry(connection, """
                SELECT LrcLibID, PlainLyrics, SyncedLyrics, Instrumental, Status,
                       FetchedAt, LastAttemptAt, NextRetryAt
                  FROM SongLyrics
                 WHERE SourceKey = ?
                 LIMIT 1
                """, DEFAULT_RETRY_ATTEMPTS)) {
            statement.setString(1, sourceKey.trim());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(mapLyrics(rows)) : Optional.empty();
            }
        } finally {
            closeIfNeeded(connection, close);
        }
    }

    @Override
    public Optional<SongLyrics> findByTrackIdentity(LyricsLookupCandidate candidate) throws SQLException {
        if (candidate == null || !candidate.hasLookupIdentity()) return Optional.empty();

        Connection connection = openConnection();
        boolean close = closesConnection(connection);
        try (PreparedStatement statement = prepareStatementWithRetry(connection, """
                SELECT LrcLibID, PlainLyrics, SyncedLyrics, Instrumental, Status,
                       FetchedAt, LastAttemptAt, NextRetryAt
                  FROM SongLyrics
                 WHERE (
                         TrackKey = ?
                         OR (
                             (TrackKey IS NULL OR TrackKey = '')
                             AND ? = 1
                             AND lower(trim(TrackName)) = lower(trim(?))
                             AND lower(trim(ArtistName)) = lower(trim(?))
                         )
                       )
                   AND (
                         ? <= 0
                         OR DurationSeconds <= 0
                         OR ABS(DurationSeconds - ?) <= 4
                       )
                 ORDER BY CASE
                              WHEN trim(COALESCE(SyncedLyrics, '')) <> '' THEN 0
                              WHEN trim(COALESCE(PlainLyrics, '')) <> '' THEN 1
                              ELSE 2
                          END,
                          FetchedAt DESC
                 LIMIT 1
                """, DEFAULT_RETRY_ATTEMPTS)) {
            statement.setString(1, candidate.trackKey());
            statement.setInt(2, candidate.artistNames().size() <= 1 ? 1 : 0);
            statement.setString(3, candidate.trackName());
            statement.setString(4, candidate.artistName());
            statement.setInt(5, candidate.durationSeconds());
            statement.setInt(6, candidate.durationSeconds());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(mapLyrics(rows)) : Optional.empty();
            }
        } finally {
            closeIfNeeded(connection, close);
        }
    }

    @Override
    public List<LyricsTrackMatch> findByTrackKeys(Collection<String> trackKeys) throws SQLException {
        if (trackKeys == null || trackKeys.isEmpty()) return List.of();

        List<String> keys = trackKeys.stream()
                .filter(key -> key != null && !key.isBlank())
                .distinct()
                .toList();
        if (keys.isEmpty()) return List.of();

        Connection connection = openConnection();
        boolean close = closesConnection(connection);
        try {
            List<LyricsTrackMatch> result = new ArrayList<>();
            for (int start = 0; start < keys.size(); start += QUERY_CHUNK_SIZE) {
                List<String> chunk = keys.subList(start, Math.min(keys.size(), start + QUERY_CHUNK_SIZE));
                String placeholders = chunk.stream().map(ignored -> "?").collect(Collectors.joining(","));
                String sql = "SELECT TrackKey, DurationSeconds, LrcLibID, PlainLyrics, SyncedLyrics, Instrumental, Status, "
                        + "FetchedAt, LastAttemptAt, NextRetryAt FROM SongLyrics WHERE TrackKey IN ("
                        + placeholders + ") ORDER BY CASE WHEN trim(COALESCE(SyncedLyrics, '')) <> '' THEN 0 "
                        + "WHEN trim(COALESCE(PlainLyrics, '')) <> '' THEN 1 ELSE 2 END, FetchedAt DESC";
                try (PreparedStatement statement = prepareStatementWithRetry(
                        connection, sql, DEFAULT_RETRY_ATTEMPTS)) {
                    for (int index = 0; index < chunk.size(); index++) {
                        statement.setString(index + 1, chunk.get(index));
                    }
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            String key = rows.getString("TrackKey");
                            if (key != null && !key.isBlank()) {
                                result.add(new LyricsTrackMatch(
                                        key,
                                        rows.getInt("DurationSeconds"),
                                        mapLyrics(rows)
                                ));
                            }
                        }
                    }
                }
            }
            return result;
        } finally {
            closeIfNeeded(connection, close);
        }
    }

    @Override
    public Map<Long, SongLyrics> findBySongIds(Collection<Long> songIds) throws SQLException {
        if (songIds == null || songIds.isEmpty()) return Map.of();

        List<Long> ids = songIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        if (ids.isEmpty()) return Map.of();

        Connection connection = openConnection();
        boolean close = closesConnection(connection);
        try {
            Map<Long, SongLyrics> result = new LinkedHashMap<>();
            for (int start = 0; start < ids.size(); start += QUERY_CHUNK_SIZE) {
                List<Long> chunk = ids.subList(start, Math.min(ids.size(), start + QUERY_CHUNK_SIZE));
                String placeholders = chunk.stream().map(ignored -> "?").collect(Collectors.joining(","));
                String sql = "SELECT SongID, LrcLibID, PlainLyrics, SyncedLyrics, Instrumental, Status, "
                        + "FetchedAt, LastAttemptAt, NextRetryAt FROM SongLyrics WHERE SongID IN (" + placeholders + ")";
                try (PreparedStatement statement = prepareStatementWithRetry(
                        connection, sql, DEFAULT_RETRY_ATTEMPTS)) {
                    for (int index = 0; index < chunk.size(); index++) {
                        statement.setLong(index + 1, chunk.get(index));
                    }
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            result.put(rows.getLong("SongID"), mapLyrics(rows));
                        }
                    }
                }
            }
            return result;
        } finally {
            closeIfNeeded(connection, close);
        }
    }

    @Override
    public List<LyricsLookupCandidate> findPendingCandidates(int limit, long nowMillis) throws SQLException {
        int safeLimit = Math.max(1, Math.min(100, limit));
        Connection connection = openConnection();
        boolean close = closesConnection(connection);
        try (PreparedStatement statement = prepareStatementWithRetry(connection, """
                SELECT s.SongID,
                       s.Title,
                       s.FilePath,
                       s.DurationSeconds,
                       al.Name AS AlbumName,
                       COALESCE(
                           (SELECT ar.Name
                              FROM SongArtist sa
                              JOIN Artist ar ON ar.ArtistID = sa.ArtistID
                             WHERE sa.SongID = s.SongID
                             ORDER BY ar.ArtistID
                             LIMIT 1),
                           (SELECT ar.Name
                              FROM AlbumArtist aa
                              JOIN Artist ar ON ar.ArtistID = aa.ArtistID
                             WHERE aa.AlbumID = s.Album
                             ORDER BY ar.ArtistID
                             LIMIT 1),
                           ''
                       ) AS ArtistName
                  FROM Song s
                  JOIN Album al ON al.AlbumID = s.Album
             LEFT JOIN SongLyrics ly ON ly.SongID = s.SongID
                 WHERE s.IsLocal = 1
                   AND (
                        ly.LyricsID IS NULL
                        OR (
                            ly.Status IN ('PENDING', 'RETRYABLE_ERROR')
                            AND ly.NextRetryAt <= ?
                        )
                   )
                 ORDER BY CASE WHEN ly.LyricsID IS NULL THEN 0 ELSE 1 END,
                          ly.LastAttemptAt,
                          s.SongID
                 LIMIT ?
                """, DEFAULT_RETRY_ATTEMPTS)) {
            statement.setLong(1, Math.max(0L, nowMillis));
            statement.setInt(2, safeLimit);
            try (ResultSet rows = statement.executeQuery()) {
                List<LyricsLookupCandidate> candidates = new ArrayList<>();
                while (rows.next()) {
                    long songId = rows.getLong("SongID");
                    String filePath = rows.getString("FilePath");
                    String artistName = rows.getString("ArtistName");
                    List<String> artistNames = findArtistNames(connection, songId);
                    if (artistNames.isEmpty() && artistName != null && !artistName.isBlank()) {
                        artistNames = List.of(artistName);
                    }
                    candidates.add(new LyricsLookupCandidate(
                            songId,
                            sourceKey(songId, filePath),
                            rows.getString("Title"),
                            artistName,
                            artistNames,
                            rows.getString("AlbumName"),
                            rows.getInt("DurationSeconds")
                    ));
                }
                return candidates;
            }
        } finally {
            closeIfNeeded(connection, close);
        }
    }

    private List<String> findArtistNames(Connection connection, long songId) throws SQLException {
        try (PreparedStatement statement = prepareStatementWithRetry(connection, """
                SELECT DISTINCT ar.Name
                  FROM Artist ar
                 WHERE ar.ArtistID IN (
                       SELECT sa.ArtistID FROM SongArtist sa WHERE sa.SongID = ?
                       UNION
                       SELECT aa.ArtistID
                         FROM Song s
                         JOIN AlbumArtist aa ON aa.AlbumID = s.Album
                        WHERE s.SongID = ?
                 )
                   AND trim(COALESCE(ar.Name, '')) <> ''
                 ORDER BY lower(ar.Name), ar.Name
                """, DEFAULT_RETRY_ATTEMPTS)) {
            statement.setLong(1, songId);
            statement.setLong(2, songId);
            try (ResultSet rows = statement.executeQuery()) {
                List<String> names = new ArrayList<>();
                while (rows.next()) names.add(rows.getString(1));
                return names;
            }
        }
    }

    @Override
    public Optional<Long> findSongIdByFilePath(String filePath) throws SQLException {
        if (filePath == null || filePath.isBlank()) return Optional.empty();

        Connection connection = openConnection();
        boolean close = closesConnection(connection);
        try (PreparedStatement statement = prepareStatementWithRetry(connection, """
                SELECT SongID FROM Song
                 WHERE FilePath = ?
                 ORDER BY IsLocal DESC, SongID DESC
                 LIMIT 1
                """, DEFAULT_RETRY_ATTEMPTS)) {
            statement.setString(1, filePath);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(rows.getLong("SongID")) : Optional.empty();
            }
        } finally {
            closeIfNeeded(connection, close);
        }
    }

    @Override
    public void upsert(LyricsLookupCandidate candidate, SongLyrics lyrics) throws SQLException {
        if (candidate == null || candidate.sourceKey().isBlank() || lyrics == null) return;

        Connection connection = openConnection();
        boolean close = closesConnection(connection);
        try (PreparedStatement statement = prepareStatementWithRetry(connection, """
                INSERT INTO SongLyrics(
                    SongID, SourceKey, TrackKey, TrackKeyVersion, LrcLibID, TrackName, ArtistName, AlbumName,
                    DurationSeconds, PlainLyrics, SyncedLyrics, Instrumental, Status,
                    FetchedAt, LastAttemptAt, NextRetryAt
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(SourceKey) DO UPDATE SET
                    SongID = COALESCE(excluded.SongID, SongLyrics.SongID),
                    TrackKey = excluded.TrackKey,
                    TrackKeyVersion = 2,
                    LrcLibID = excluded.LrcLibID,
                    TrackName = excluded.TrackName,
                    ArtistName = excluded.ArtistName,
                    AlbumName = excluded.AlbumName,
                    DurationSeconds = excluded.DurationSeconds,
                    PlainLyrics = excluded.PlainLyrics,
                    SyncedLyrics = excluded.SyncedLyrics,
                    Instrumental = excluded.Instrumental,
                    Status = excluded.Status,
                    FetchedAt = excluded.FetchedAt,
                    LastAttemptAt = excluded.LastAttemptAt,
                    NextRetryAt = excluded.NextRetryAt
                """, DEFAULT_RETRY_ATTEMPTS)) {
            if (candidate.songId() > 0) statement.setLong(1, candidate.songId());
            else statement.setNull(1, Types.INTEGER);
            statement.setString(2, candidate.sourceKey());
            if (candidate.trackKey().isBlank()) statement.setNull(3, Types.VARCHAR);
            else statement.setString(3, candidate.trackKey());
            statement.setInt(4, 2);
            if (lyrics.lrcLibId() > 0) statement.setLong(5, lyrics.lrcLibId());
            else statement.setNull(5, Types.INTEGER);
            statement.setString(6, candidate.trackName());
            statement.setString(7, candidate.artistName());
            statement.setString(8, candidate.albumName());
            statement.setInt(9, candidate.durationSeconds());
            statement.setString(10, lyrics.plainLyrics().text());
            statement.setString(11, lyrics.syncedLyrics().rawText());
            statement.setInt(12, lyrics.instrumental() ? 1 : 0);
            statement.setString(13, lyrics.status().name());
            statement.setLong(14, lyrics.fetchedAtMillis());
            statement.setLong(15, lyrics.lastAttemptAtMillis());
            statement.setLong(16, lyrics.nextRetryAtMillis());
            statement.executeUpdate();
        } finally {
            closeIfNeeded(connection, close);
        }
    }

    private static SongLyrics mapLyrics(ResultSet row) throws SQLException {
        long id = row.getLong("LrcLibID");
        if (row.wasNull()) id = 0L;
        return new SongLyrics(
                id,
                new PlainLyrics(row.getString("PlainLyrics")),
                new SyncedLyrics(row.getString("SyncedLyrics")),
                row.getInt("Instrumental") != 0,
                parseStatus(row.getString("Status")),
                row.getLong("FetchedAt"),
                row.getLong("LastAttemptAt"),
                row.getLong("NextRetryAt")
        );
    }

    private static LyricsStatus parseStatus(String raw) {
        try {
            return raw == null ? LyricsStatus.PENDING : LyricsStatus.valueOf(raw);
        } catch (IllegalArgumentException ignored) {
            return LyricsStatus.PENDING;
        }
    }

    private static String sourceKey(long songId, String filePath) {
        if (songId > 0) return "track:" + songId;
        return "file:" + (filePath == null ? "" : filePath.trim().toLowerCase(java.util.Locale.ROOT));
    }

    private static void closeIfNeeded(Connection connection, boolean close) {
        if (!close || connection == null) return;
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }
}
