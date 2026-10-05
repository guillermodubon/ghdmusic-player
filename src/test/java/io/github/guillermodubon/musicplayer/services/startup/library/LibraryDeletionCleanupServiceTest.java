package io.github.guillermodubon.musicplayer.services.startup.library;

import io.github.guillermodubon.musicplayer.repository.schema.DatabaseSchemaTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LibraryDeletionCleanupServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void cleanupRetainsRemoteSavedAndPlaylistReachableMediaAndLyrics() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + temporaryDirectory.resolve("cleanup.db").toAbsolutePath())) {
            DatabaseSchemaTestSupport.initializeFresh(connection);
            execute(connection, "INSERT INTO Genre(GenreID, Name) VALUES(1, 'Pop')");
            execute(connection, "INSERT INTO Artist(ArtistID, Name) VALUES(1, 'Artist')");
            execute(connection, "INSERT INTO Album(AlbumID, GenreID, Name) VALUES(1, 1, 'Remote release')");
            execute(connection, "INSERT INTO AlbumImage(AlbumID, ImageType, ImageData) VALUES(1, 'cover', X'0102')");
            execute(connection, "INSERT INTO AlbumArtist(AlbumID, ArtistID) VALUES(1, 1)");
            execute(connection, "INSERT INTO Song(SongID, Title, Album, IsLocal) VALUES(1, 'Remote song', 1, 0)");
            execute(connection, "INSERT INTO SongArtist(SongID, ArtistID) VALUES(1, 1)");
            execute(connection, "INSERT INTO SongLyrics(SongID, SourceKey, TrackName, ArtistName, Status) "
                    + "VALUES(1, 'track:1', 'Remote song', 'Artist', 'FOUND')");
            execute(connection, "INSERT INTO Playlist(PlaylistID, Title, Author, Origin) VALUES(1, 'User mix', 'User', 'USER')");
            execute(connection, "INSERT INTO SongsPlaylists(SongID, PlaylistID, Position, CustomPosition) VALUES(1, 1, 7, 4)");
            execute(connection, "INSERT INTO SavedSong(SongID, SavedAt) VALUES(1, 100)");
            execute(connection, "INSERT INTO SavedRelease(AlbumID, SavedAt) VALUES(1, 100)");

            LibraryDeletionCleanupService.CleanupSummary summary =
                    new LibraryDeletionCleanupService().cleanup(connection);

            assertEquals(0, summary.removedSongs());
            assertEquals(0, summary.removedAlbums());
            assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM Song WHERE SongID=1"));
            assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM Album WHERE AlbumID=1"));
            assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM AlbumImage WHERE AlbumID=1"));
            assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM SongLyrics WHERE SongID=1"));
            assertEquals(7, scalarInt(connection, "SELECT Position FROM SongsPlaylists WHERE SongID=1 AND PlaylistID=1"));
            assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM SavedSong WHERE SongID=1"));
            assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM SavedRelease WHERE AlbumID=1"));
        }
    }

    private int scalarInt(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new AssertionError(sql);
            return result.getInt(1);
        }
    }

    private void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
    }
}
