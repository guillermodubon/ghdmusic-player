package io.github.guillermodubon.musicplayer.repository.dao.artist;

import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.repository.dao.support.JdbcDaoSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Optional;

/** JDBC implementation for artist-page lookup operations. */
public final class ArtistOpenDaoImpl extends JdbcDaoSupport implements ArtistOpenDao {

    public ArtistOpenDaoImpl() {
        this(null);
    }

    public ArtistOpenDaoImpl(Connection connection) {
        super(connection);
    }

    @Override
    public Optional<Artist> findByIdIncludingAggregate(long artistId) throws SQLException {
        if (artistId <= 0) return Optional.empty();

        return queryArtist(
                "SELECT ArtistID, Name, Biography FROM Artist WHERE ArtistID = ? LIMIT 1",
                statement -> statement.setLong(1, artistId)
        );
    }

    @Override
    public Optional<Artist> findByNameIgnoreCase(String name) throws SQLException {
        if (name == null || name.isBlank()) return Optional.empty();

        return queryArtist(
                "SELECT ArtistID, Name, Biography FROM Artist WHERE lower(Name) = ? LIMIT 1",
                statement -> statement.setString(1, name.trim().toLowerCase(java.util.Locale.ROOT))
        );
    }

    @Override
    public boolean hasPreferredPortrait(long artistId) throws SQLException {
        if (artistId <= 0) return false;

        Connection connection = openConnection();
        boolean closeConnection = closesConnection(connection);
        try (PreparedStatement statement = prepareStatementWithRetry(
                connection,
                "SELECT 1 FROM ArtistImage "
                        + "WHERE ArtistID = ? "
                        + "AND lower(ImageType) IN ('big', 'xl') LIMIT 1",
                DEFAULT_RETRY_ATTEMPTS
        )) {
            statement.setLong(1, artistId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } finally {
            closeIfNeeded(connection, closeConnection);
        }
    }

    private Optional<Artist> queryArtist(String sql, StatementBinder binder) throws SQLException {
        Connection connection = openConnection();
        boolean closeConnection = closesConnection(connection);
        try (PreparedStatement statement = prepareStatementWithRetry(
                connection,
                sql,
                DEFAULT_RETRY_ATTEMPTS
        )) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new Artist(
                        result.getLong("ArtistID"),
                        result.getString("Name"),
                        result.getString("Biography"),
                        new ArrayList<>()
                ));
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

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
