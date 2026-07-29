package io.github.guillermodubon.musicplayer.repository.dao.artist;

import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.repository.dao.support.JdbcDaoSupport;
import io.github.guillermodubon.musicplayer.utils.ArtistIdentity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** JDBC implementation for song-to-artist reads and writes. */
public final class SongArtistDaoImpl extends JdbcDaoSupport implements SongArtistDao {
    private static final Object WRITE_LOCK = new Object();

    public SongArtistDaoImpl(Connection connection) {
        super(connection);
    }

    @Override
    public List<Artist> findBySongId(long songId) throws SQLException {
        if (songId <= 0) return List.of();

        Connection connection = openConnection();
        boolean closeConnection = closesConnection(connection);
        try (PreparedStatement statement = prepareStatementWithRetry(
                connection,
                "SELECT a.ArtistID, a.Name, a.Biography "
                        + "FROM Artist a JOIN SongArtist sa ON a.ArtistID = sa.ArtistID "
                        + "WHERE sa.SongID = ?",
                DEFAULT_RETRY_ATTEMPTS
        )) {
            statement.setLong(1, songId);
            List<Artist> artists = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    artists.add(new Artist(
                            result.getLong("ArtistID"),
                            result.getString("Name"),
                            result.getString("Biography"),
                            new ArrayList<>()
                    ));
                }
            }
            return artists;
        } finally {
            closeIfNeeded(connection, closeConnection);
        }
    }

    @Override
    public void persistArtistsForSong(long songId, List<Artist> artists) throws SQLException {
        if (songId <= 0 || artists == null || artists.isEmpty()) return;

        Connection connection = openConnection();
        boolean closeConnection = closesConnection(connection);
        synchronized (WRITE_LOCK) {
            boolean manageTransaction = closeConnection;
            try {
                if (manageTransaction) connection.setAutoCommit(false);
                configureConnection(connection);

                try (PreparedStatement insertArtist = prepareStatementWithRetry(
                        connection,
                        "INSERT OR IGNORE INTO Artist (Name, Biography) VALUES (?, ?)",
                        DEFAULT_RETRY_ATTEMPTS
                ); PreparedStatement selectArtist = prepareStatementWithRetry(
                        connection,
                        "SELECT ArtistID FROM Artist WHERE Name = ? LIMIT 1",
                        DEFAULT_RETRY_ATTEMPTS
                ); PreparedStatement linkArtist = prepareStatementWithRetry(
                        connection,
                        "INSERT OR IGNORE INTO SongArtist (SongID, ArtistID) VALUES (?, ?)",
                        DEFAULT_RETRY_ATTEMPTS
                )) {
                    for (Artist artist : artists) {
                        if (artist == null
                                || artist.getName() == null
                                || artist.getName().isBlank()
                                || ArtistIdentity.isVariousArtists(artist)) {
                            continue;
                        }

                        insertArtist.setString(1, artist.getName());
                        insertArtist.setString(2, artist.getBiography());
                        insertArtist.executeUpdate();

                        selectArtist.setString(1, artist.getName());
                        try (ResultSet result = selectArtist.executeQuery()) {
                            if (!result.next()) continue;
                            long artistId = result.getLong("ArtistID");
                            artist.setArtistID(artistId);

                            linkArtist.setLong(1, songId);
                            linkArtist.setLong(2, artistId);
                            linkArtist.executeUpdate();
                        }
                    }
                }

                if (manageTransaction) connection.commit();
            } catch (SQLException error) {
                if (manageTransaction) {
                    try {
                        connection.rollback();
                    } catch (SQLException ignored) {
                    }
                }
                throw error;
            } finally {
                if (manageTransaction) {
                    try {
                        connection.setAutoCommit(true);
                    } catch (SQLException ignored) {
                    }
                }
                closeIfNeeded(connection, closeConnection);
            }
        }
    }

    @Override
    public void refreshBiographies(List<Artist> artists) throws SQLException {
        if (artists == null || artists.isEmpty()) return;

        Connection connection = openConnection();
        boolean closeConnection = closesConnection(connection);
        try (PreparedStatement statement = prepareStatementWithRetry(
                connection,
                "SELECT Biography FROM Artist WHERE ArtistID = ? LIMIT 1",
                DEFAULT_RETRY_ATTEMPTS
        )) {
            for (Artist artist : artists) {
                if (artist == null || artist.getArtistID() <= 0) continue;
                statement.setLong(1, artist.getArtistID());
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        String biography = result.getString(1);
                        if (biography != null && !biography.isBlank()) {
                            artist.setBiography(biography);
                        }
                    }
                }
            }
        } finally {
            closeIfNeeded(connection, closeConnection);
        }
    }

    private void closeIfNeeded(Connection connection, boolean closeConnection) {
        if (!closeConnection || connection == null) return;
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }
}
