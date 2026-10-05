package io.github.guillermodubon.musicplayer.services.startup.library;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Removes only metadata rows that are unreachable from any retained media row. */
final class LibraryDeletionCleanupService {

    private static final String DELETE_UNREFERENCED_ARTIST_IMAGES = """
            DELETE FROM ArtistImage
             WHERE NOT EXISTS (
                 SELECT 1
                   FROM AlbumArtist albumArtist
                  WHERE albumArtist.ArtistID = ArtistImage.ArtistID
             )
               AND NOT EXISTS (
                 SELECT 1
                   FROM SongArtist songArtist
                  WHERE songArtist.ArtistID = ArtistImage.ArtistID
             )
            """;

    private static final String DELETE_UNREFERENCED_ARTISTS = """
            DELETE FROM Artist
             WHERE NOT EXISTS (
                 SELECT 1
                   FROM AlbumArtist albumArtist
                  WHERE albumArtist.ArtistID = Artist.ArtistID
             )
               AND NOT EXISTS (
                 SELECT 1
                   FROM SongArtist songArtist
                  WHERE songArtist.ArtistID = Artist.ArtistID
             )
            """;

    private static final String DELETE_GENRES_WITHOUT_SONGS = """
            DELETE FROM Genre
             WHERE NOT EXISTS (
                 SELECT 1 FROM Album album WHERE album.GenreID = Genre.GenreID
             )
            """;

    CleanupSummary cleanup(Connection connection) throws SQLException {
        if (connection == null) {
            return CleanupSummary.empty();
        }

        execute(connection, DELETE_UNREFERENCED_ARTIST_IMAGES);
        int removedArtists = execute(connection, DELETE_UNREFERENCED_ARTISTS);
        int removedGenres = execute(connection, DELETE_GENRES_WITHOUT_SONGS);

        return new CleanupSummary(0, 0, removedArtists, removedGenres);
    }

    private int execute(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            return statement.executeUpdate();
        }
    }

    record CleanupSummary(int removedSongs,
                          int removedAlbums,
                          int removedArtists,
                          int removedGenres) {
        static CleanupSummary empty() {
            return new CleanupSummary(0, 0, 0, 0);
        }
    }
}
