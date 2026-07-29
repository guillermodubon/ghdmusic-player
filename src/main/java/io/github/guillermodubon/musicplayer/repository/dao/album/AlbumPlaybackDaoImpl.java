package io.github.guillermodubon.musicplayer.repository.dao.album;

import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.repository.dao.support.JdbcDaoSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** JDBC implementation for remote-album playback hydration. */
public final class AlbumPlaybackDaoImpl extends JdbcDaoSupport implements AlbumPlaybackDao {

    public AlbumPlaybackDaoImpl() {
        this(null);
    }

    public AlbumPlaybackDaoImpl(Connection connection) {
        super(connection);
    }

    @Override
    public void persistRemoteSongs(long albumId, Collection<Song> songs) {
        if (albumId <= 0 || songs == null || songs.isEmpty()) return;

        Map<Long, Song> uniqueSongs = new LinkedHashMap<>();
        for (Song song : songs) {
            if (song != null && song.getSongID() > 0) {
                uniqueSongs.putIfAbsent(song.getSongID(), song);
            }
        }
        if (uniqueSongs.isEmpty()) return;

        try {
            connectionManager().runInTransaction(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT OR IGNORE INTO Song(SongID, Title, Album, TrackOrder, IsLocal) "
                                + "VALUES(?, ?, ?, ?, 0)")) {
                    for (Song song : uniqueSongs.values()) {
                        statement.setLong(1, song.getSongID());
                        statement.setString(2, Objects.requireNonNullElse(song.getTitle(), ""));
                        statement.setLong(3, albumId);
                        statement.setInt(4, 0);
                        try {
                            statement.executeUpdate();
                        } catch (SQLException ignored) {
                            // Preserve the coordinator's best-effort hydration behavior.
                        }
                    }
                } catch (SQLException ignored) {
                    // A playback view must remain usable if persistence is unavailable.
                }
                return null;
            });
        } catch (Exception ignored) {
            // Persistence is an enhancement to the already loaded remote view.
        }
    }

    @Override
    public void updateReleaseDate(long albumId, String albumName, String releaseDate, int numberOfTracks) {
        if (albumId <= 0 || releaseDate == null || releaseDate.isBlank()) return;

        try {
            connectionManager().runInTransaction(connection -> {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE Album SET ReleaseDate = ? WHERE AlbumID = ?")) {
                    update.setString(1, releaseDate);
                    update.setLong(2, albumId);
                    int updated = update.executeUpdate();

                    if (updated <= 0) {
                        try (PreparedStatement insert = connection.prepareStatement(
                                "INSERT OR IGNORE INTO Album(AlbumID, Name, ReleaseDate, NumberOfTracks) "
                                        + "VALUES(?, ?, ?, ?)")) {
                            insert.setLong(1, albumId);
                            insert.setString(2, Objects.requireNonNullElse(albumName, ""));
                            insert.setString(3, releaseDate);
                            insert.setInt(4, numberOfTracks);
                            try {
                                insert.executeUpdate();
                            } catch (SQLException ignored) {
                                // Keep the remote playback flow independent from persistence.
                            }
                        } catch (SQLException ignored) {
                            // Keep the original best-effort behavior.
                        }
                    }
                } catch (SQLException ignored) {
                    // Keep the original best-effort behavior.
                }
                return null;
            });
        } catch (Exception ignored) {
            // The remote response is already available to the user.
        }
    }
}
