package io.github.guillermodubon.musicplayer.services.startup.library;

import io.github.guillermodubon.musicplayer.repository.DbConnectionManager;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Repairs the small, legacy subset of Deezer albums saved before their album
 * genre was resolved. Albums made from files without Deezer metadata are never
 * selected because they have no positive Deezer SongID.
 */
public final class AlbumGenreBackfillService {

    private static final List<String> PLACEHOLDER_GENRES =
            List.of("", "unknown", "unknown genre", "desconocido");

    private final DeezerApiService deezerService;

    public AlbumGenreBackfillService(DeezerApiService deezerService) {
        this.deezerService = Objects.requireNonNull(deezerService, "deezerService");
    }

    /**
     * Looks up only unresolved Deezer albums, performs network work outside a
     * transaction, then updates the matching Album.GenreID atomically.
     */
    public int repairUnresolvedAlbumGenres() throws SQLException {
        List<Long> albumIds = findUnresolvedAlbumIds();
        if (albumIds.isEmpty()) return 0;

        List<AlbumGenreUpdate> updates = new ArrayList<>();
        for (Long albumId : albumIds) {
            if (albumId == null || albumId <= 0) continue;
            deezerService.getAlbumGenreById(albumId)
                    .filter(genre -> isUsableGenre(genre.name()))
                    .ifPresent(genre -> updates.add(new AlbumGenreUpdate(albumId, genre)));
        }
        if (updates.isEmpty()) return 0;

        return DbConnectionManager.getInstance().runInTransaction(connection -> {
            try {
                int repaired = 0;
                for (AlbumGenreUpdate update : updates) {
                    long genreId = ensureGenre(connection, update.genre());
                    try (PreparedStatement statement = connection.prepareStatement(
                            "UPDATE Album SET GenreID = ? WHERE AlbumID = ?")) {
                        statement.setLong(1, genreId);
                        statement.setLong(2, update.albumId());
                        repaired += statement.executeUpdate();
                    }
                }
                removeUnusedPlaceholderGenres(connection);
                return repaired;
            } catch (Exception error) {
                throw new RuntimeException("Could not repair album genres", error);
            }
        });
    }

    private List<Long> findUnresolvedAlbumIds() {
        String sql = """
                SELECT DISTINCT album.AlbumID
                  FROM Album album
                  JOIN Song song ON song.Album = album.AlbumID
             LEFT JOIN Genre genre ON genre.GenreID = album.GenreID
                 WHERE album.AlbumID > 0
                   AND song.SongID > 0
                   AND LOWER(TRIM(COALESCE(genre.Name, ''))) IN ('', 'unknown', 'unknown genre', 'desconocido')
                 ORDER BY album.AlbumID
                """;
        List<Long> albumIds = new ArrayList<>();
        try (Connection connection = DbConnectionManager.getInstance().openConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                albumIds.add(result.getLong(1));
            }
        } catch (Exception error) {
            System.out.println("AlbumGenreBackfillService: could not find unresolved albums -> " + error.getMessage());
        }
        return albumIds;
    }

    private long ensureGenre(Connection connection, DeezerApiService.AlbumGenre genre) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT GenreID FROM Genre WHERE Name = ? LIMIT 1")) {
            statement.setString(1, genre.name());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) return result.getLong(1);
            }
        }

        if (genre.id() > 0) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT OR IGNORE INTO Genre(GenreID, Name) VALUES(?, ?)")) {
                statement.setInt(1, genre.id());
                statement.setString(2, genre.name());
                statement.executeUpdate();
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO Genre(Name) VALUES(?)")) {
            statement.setString(1, genre.name());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT GenreID FROM Genre WHERE Name = ? LIMIT 1")) {
            statement.setString(1, genre.name());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) return result.getLong(1);
            }
        }
        throw new IllegalStateException("Genre was not persisted: " + genre.name());
    }

    private void removeUnusedPlaceholderGenres(Connection connection) throws Exception {
        String sql = """
                DELETE FROM Genre
                 WHERE LOWER(TRIM(COALESCE(Name, ''))) IN ('', 'unknown', 'unknown genre', 'desconocido')
                   AND GenreID NOT IN (SELECT DISTINCT GenreID FROM Album)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private boolean isUsableGenre(String value) {
        return value != null && !PLACEHOLDER_GENRES.contains(value.trim().toLowerCase(Locale.ROOT));
    }

    private record AlbumGenreUpdate(long albumId, DeezerApiService.AlbumGenre genre) {
    }
}
