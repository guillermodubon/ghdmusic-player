package io.github.guillermodubon.musicplayer.repository.library;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

/** Records a qualified full-track play; selection and previews must not call this boundary. */
public final class SongListeningHistoryDao {

    public void recordSuccessfulPlay(Connection connection, long songId, long playedAtMillis) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        if (songId <= 0 || playedAtMillis < 0) throw new IllegalArgumentException("Invalid play event.");

        try (PreparedStatement update = connection.prepareStatement("""
                INSERT INTO SongPlayStats(SongID, PlayCount, LastPlayedAt)
                VALUES(?, 1, ?)
                ON CONFLICT(SongID) DO UPDATE SET
                    PlayCount = SongPlayStats.PlayCount + 1,
                    LastPlayedAt = excluded.LastPlayedAt
                """)) {
            update.setLong(1, songId);
            update.setLong(2, playedAtMillis);
            update.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO RecentSongPlay(SongID, PlayedAt) VALUES(?, ?)")) {
            insert.setLong(1, songId);
            insert.setLong(2, playedAtMillis);
            insert.executeUpdate();
        }
    }
}
