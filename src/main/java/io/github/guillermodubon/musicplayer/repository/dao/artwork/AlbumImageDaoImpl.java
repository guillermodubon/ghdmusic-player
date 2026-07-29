package io.github.guillermodubon.musicplayer.repository.dao.artwork;

import io.github.guillermodubon.musicplayer.repository.dao.support.JdbcDaoSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** SQLite implementation for persisted album artwork. */
public final class AlbumImageDaoImpl extends JdbcDaoSupport implements AlbumImageDao {

    private static final String FIND_BEST_IMAGE = """
            SELECT ImageData
              FROM AlbumImage
             WHERE AlbumID = ?
          ORDER BY CASE lower(ImageType)
                     WHEN 'xl' THEN 0
                     WHEN 'big' THEN 1
                     WHEN 'cover_xl' THEN 2
                     WHEN 'medium' THEN 3
                     WHEN 'cover_medium' THEN 4
                     WHEN 'small' THEN 5
                     ELSE 6
                   END
             LIMIT 1
            """;

    public AlbumImageDaoImpl() {
        super(null);
    }

    public AlbumImageDaoImpl(Connection connection) {
        super(connection);
    }

    @Override
    public Optional<byte[]> findBestImageData(long albumId) throws SQLException {
        if (albumId <= 0) {
            return Optional.empty();
        }

        Connection connection = openConnection();
        boolean closeConnection = closesConnection(connection);
        try (PreparedStatement statement = prepareStatementWithRetry(
                connection,
                FIND_BEST_IMAGE,
                DEFAULT_RETRY_ATTEMPTS
        )) {
            statement.setLong(1, albumId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                byte[] imageData = result.getBytes("ImageData");
                return imageData == null || imageData.length == 0
                        ? Optional.empty()
                        : Optional.of(imageData);
            }
        } finally {
            if (closeConnection) {
                connection.close();
            }
        }
    }
}
