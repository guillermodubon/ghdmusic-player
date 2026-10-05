package io.github.guillermodubon.musicplayer.repository.schema;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseSchemaManagerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsVersionOneDirectlyAndAllowsDuplicateReleaseAndPlaylistNames() throws Exception {
        Path database = temporaryDirectory.resolve("fresh.db");
        try (Connection connection = open(database)) {
            DatabaseSchemaManager.initializeConnection(connection, database, () -> { });
            assertEquals(1, scalarInt(connection, "PRAGMA user_version"));
            assertTrue(columnExists(connection, "Playlist", "Origin"));
            assertTrue(tableExists(connection, "SavedSong"));
            assertTrue(tableExists(connection, "RecentSongPlay"));

            execute(connection, "INSERT INTO Genre(GenreID, Name) VALUES (1, 'Unknown')");
            execute(connection, "INSERT INTO Album(AlbumID, GenreID, Name) VALUES (1, 1, 'Same')");
            execute(connection, "INSERT INTO Album(AlbumID, GenreID, Name) VALUES (2, 1, 'Same')");
            execute(connection, "INSERT INTO Playlist(PlaylistID, Title, Author) VALUES (1, 'Same', 'User')");
            execute(connection, "INSERT INTO Playlist(PlaylistID, Title, Author) VALUES (2, 'Same', 'User')");
            assertEquals(2, scalarInt(connection, "SELECT COUNT(*) FROM Album WHERE Name = 'Same'"));
            assertEquals(2, scalarInt(connection, "SELECT COUNT(*) FROM Playlist WHERE Title = 'Same'"));
        }
    }

    @Test
    void migratesLegacyRowsAndRelationshipsOnceAndCreatesPrivateBackup() throws Exception {
        Path database = temporaryDirectory.resolve("legacy.db");
        try (Connection connection = open(database)) {
            createLegacySchema(connection);
            insertLegacyFixture(connection);
            DatabaseSchemaManager.initializeConnection(connection, database, () -> { });

            assertEquals(1, scalarInt(connection, "PRAGMA user_version"));
            assertEquals(7, scalarInt(connection, "SELECT SongID FROM Song"));
            assertEquals(4, scalarInt(connection, "SELECT AlbumID FROM Album"));
            assertEquals(8, scalarInt(connection, "SELECT PlaylistID FROM Playlist"));
            assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM SongsPlaylists WHERE SongID = 7 AND PlaylistID = 8"));
            assertEquals("USER", scalarString(connection, "SELECT Origin FROM Playlist WHERE PlaylistID = 8"));
            assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM SavedSong WHERE SongID = 7"));
            assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM SavedRelease WHERE AlbumID = 4"));
            assertEquals(9, scalarInt(connection, "SELECT SongID FROM Song WHERE Title = 'Remote Track'"));
            assertEquals(0, scalarInt(connection, "SELECT COUNT(*) FROM SavedSong WHERE SongID = 9"));
            assertEquals(1, scalarInt(connection,
                    "SELECT COUNT(*) FROM SongsPlaylists WHERE SongID = 9 AND PlaylistID = 8"));
            assertEquals("ok", scalarString(connection, "PRAGMA quick_check"));
            assertFalse(hasForeignKeyErrors(connection));

            try (Connection backup = DriverManager.getConnection("jdbc:sqlite:" + onlyBackup().toAbsolutePath())) {
                assertEquals(0, scalarInt(backup, "PRAGMA user_version"));
                assertEquals("ok", scalarString(backup, "PRAGMA quick_check"));
                assertEquals(2, scalarInt(backup, "SELECT COUNT(*) FROM Song"));
            }

            execute(connection, "INSERT INTO Album(AlbumID, GenreID, Name) VALUES (15, 1, 'Duplicate')");
            execute(connection, "INSERT INTO Album(AlbumID, GenreID, Name) VALUES (16, 1, 'Duplicate')");
            execute(connection, "INSERT INTO Playlist(PlaylistID, Title, Author) VALUES (10, 'Duplicate', 'User')");
            execute(connection, "INSERT INTO Playlist(PlaylistID, Title, Author) VALUES (11, 'Duplicate', 'User')");

            long backupsBeforeSecondStart = backupCount();
            DatabaseSchemaManager.initializeConnection(connection, database, () -> { });
            assertEquals(1, scalarInt(connection, "PRAGMA user_version"));
            assertEquals(backupsBeforeSecondStart, backupCount());
        }
        assertTrue(backupCount() == 1);
    }

    @Test
    void rollsBackMigrationWhenAChangeFailsAndRetainsBackup() throws Exception {
        Path database = temporaryDirectory.resolve("rollback.db");
        try (Connection connection = open(database)) {
            createLegacySchema(connection);
            insertLegacyFixture(connection);

            SQLException failure = assertThrows(SQLException.class, () ->
                    DatabaseSchemaManager.initializeConnection(connection, database,
                            () -> { throw new SQLException("injected migration failure"); }));

            assertEquals("injected migration failure", failure.getMessage());
            assertEquals(0, scalarInt(connection, "PRAGMA user_version"));
            assertFalse(columnExists(connection, "Playlist", "Origin"));
            assertFalse(tableExists(connection, "SavedSong"));
            assertTrue(isUniqueIndexPresent(connection, "Album"));
            assertEquals(1, backupCount());
        }
    }

    @Test
    void rejectsFutureSchemaWithoutWriting() throws Exception {
        Path database = temporaryDirectory.resolve("future.db");
        try (Connection connection = open(database)) {
            execute(connection, "CREATE TABLE marker(value TEXT)");
            execute(connection, "INSERT INTO marker VALUES ('untouched')");
            execute(connection, "PRAGMA user_version = 2");

            DatabaseInitializationException error = assertThrows(DatabaseInitializationException.class,
                    () -> DatabaseSchemaManager.initializeConnection(connection, database, () -> { }));
            assertEquals(DatabaseInitializationException.Reason.UNSUPPORTED_VERSION, error.reason());
            assertEquals("untouched", scalarString(connection, "SELECT value FROM marker"));
            assertEquals(2, scalarInt(connection, "PRAGMA user_version"));
            assertEquals(0, backupCount());
        }
    }

    private Connection open(Path database) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
        execute(connection, "PRAGMA foreign_keys = ON");
        return connection;
    }

    private void createLegacySchema(Connection connection) throws SQLException {
        execute(connection, "CREATE TABLE Playlist(PlaylistID INTEGER PRIMARY KEY AUTOINCREMENT, Title TEXT NOT NULL UNIQUE, Author TEXT NOT NULL, Description TEXT, CoverImage BLOB, CreationDate DATETIME DEFAULT CURRENT_TIMESTAMP)");
        execute(connection, "CREATE TABLE Artist(ArtistID INTEGER PRIMARY KEY, Name TEXT NOT NULL UNIQUE, Biography TEXT)");
        execute(connection, "CREATE TABLE Genre(GenreID INTEGER PRIMARY KEY, Name TEXT NOT NULL UNIQUE)");
        execute(connection, "CREATE TABLE ArtistImage(ArtistImageID INTEGER PRIMARY KEY AUTOINCREMENT, ArtistID INTEGER NOT NULL, ImageType TEXT NOT NULL, ImageData BLOB NOT NULL, FOREIGN KEY(ArtistID) REFERENCES Artist(ArtistID))");
        execute(connection, "CREATE TABLE Album(AlbumID INTEGER PRIMARY KEY, GenreID INTEGER NOT NULL, Name TEXT NOT NULL UNIQUE, RecordType TEXT NOT NULL DEFAULT 'album', ReleaseDate TEXT, NumberOfTracks INTEGER NOT NULL DEFAULT 0, FOREIGN KEY(GenreID) REFERENCES Genre(GenreID))");
        execute(connection, "CREATE TABLE AlbumImage(AlbumImageID INTEGER PRIMARY KEY AUTOINCREMENT, AlbumID INTEGER NOT NULL, ImageType TEXT NOT NULL, ImageData BLOB NOT NULL, FOREIGN KEY(AlbumID) REFERENCES Album(AlbumID))");
        execute(connection, "CREATE TABLE AlbumArtist(AlbumID INTEGER NOT NULL, ArtistID INTEGER NOT NULL, PRIMARY KEY(AlbumID, ArtistID), FOREIGN KEY(AlbumID) REFERENCES Album(AlbumID), FOREIGN KEY(ArtistID) REFERENCES Artist(ArtistID))");
        execute(connection, "CREATE TABLE Song(SongID INTEGER PRIMARY KEY, Title TEXT NOT NULL, Album INTEGER NOT NULL, TrackOrder INTEGER NOT NULL DEFAULT 0, IsLocal INTEGER NOT NULL DEFAULT 1, FOREIGN KEY(Album) REFERENCES Album(AlbumID))");
        execute(connection, "CREATE TABLE SongLyrics(LyricsID INTEGER PRIMARY KEY AUTOINCREMENT, SongID INTEGER, SourceKey TEXT NOT NULL UNIQUE, LrcLibID INTEGER, TrackName TEXT NOT NULL, ArtistName TEXT NOT NULL, AlbumName TEXT, DurationSeconds INTEGER NOT NULL DEFAULT 0, PlainLyrics TEXT, SyncedLyrics TEXT, Instrumental INTEGER NOT NULL DEFAULT 0, Status TEXT NOT NULL, FetchedAt INTEGER NOT NULL DEFAULT 0, LastAttemptAt INTEGER NOT NULL DEFAULT 0, NextRetryAt INTEGER NOT NULL DEFAULT 0, FOREIGN KEY(SongID) REFERENCES Song(SongID) ON DELETE CASCADE)");
        execute(connection, "CREATE TABLE SongsPlaylists(SongID INTEGER NOT NULL, PlaylistID INTEGER NOT NULL, CreatedAt DATETIME DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(SongID, PlaylistID), FOREIGN KEY(SongID) REFERENCES Song(SongID), FOREIGN KEY(PlaylistID) REFERENCES Playlist(PlaylistID))");
        execute(connection, "CREATE TABLE SongArtist(SongID INTEGER NOT NULL, ArtistID INTEGER NOT NULL, PRIMARY KEY(SongID, ArtistID), FOREIGN KEY(SongID) REFERENCES Song(SongID), FOREIGN KEY(ArtistID) REFERENCES Artist(ArtistID))");
        execute(connection, "CREATE TABLE PlaybackHistory(HistoryID INTEGER PRIMARY KEY AUTOINCREMENT, ItemID INTEGER NOT NULL, Name TEXT NOT NULL, PlayedAt INTEGER NOT NULL DEFAULT 0)");
    }

    private void insertLegacyFixture(Connection connection) throws SQLException {
        execute(connection, "INSERT INTO Genre(GenreID, Name) VALUES (1, 'Pop')");
        execute(connection, "INSERT INTO Artist(ArtistID, Name) VALUES (2, 'Artist')");
        execute(connection, "INSERT INTO Album(AlbumID, GenreID, Name, NumberOfTracks) VALUES (4, 1, 'Release', 1)");
        execute(connection, "INSERT INTO Album(AlbumID, GenreID, Name, NumberOfTracks) VALUES (5, 1, 'Remote release', 1)");
        execute(connection, "INSERT INTO Playlist(PlaylistID, Title, Author) VALUES (8, 'Mix', ' User ')");
        execute(connection, "INSERT INTO Song(SongID, Title, Album, TrackOrder, IsLocal) VALUES (7, 'Track', 4, 1, 1)");
        execute(connection, "INSERT INTO Song(SongID, Title, Album, TrackOrder, IsLocal) VALUES (9, 'Remote Track', 5, 1, 0)");
        execute(connection, "INSERT INTO SongArtist(SongID, ArtistID) VALUES (7, 2)");
        execute(connection, "INSERT INTO AlbumArtist(AlbumID, ArtistID) VALUES (4, 2)");
        execute(connection, "INSERT INTO SongsPlaylists(SongID, PlaylistID) VALUES (7, 8)");
        execute(connection, "INSERT INTO SongsPlaylists(SongID, PlaylistID) VALUES (9, 8)");
        execute(connection, "INSERT INTO SongLyrics(SongID, SourceKey, TrackName, ArtistName, Status) VALUES (7, 'legacy', 'Track', 'Artist', 'READY')");
        execute(connection, "INSERT INTO PlaybackHistory(HistoryID, ItemID, Name) VALUES (12, 4, 'Release')");
    }

    private long backupCount() throws Exception {
        try (Stream<Path> files = Files.list(temporaryDirectory)) {
            return files.filter(path -> path.getFileName().toString().startsWith("UserDataBase.pre-hybrid-v0-"))
                    .filter(path -> path.getFileName().toString().endsWith(".db"))
                    .count();
        }
    }

    private Path onlyBackup() throws Exception {
        try (Stream<Path> files = Files.list(temporaryDirectory)) {
            return files.filter(path -> path.getFileName().toString().startsWith("UserDataBase.pre-hybrid-v0-"))
                    .filter(path -> path.getFileName().toString().endsWith(".db"))
                    .findFirst().orElseThrow();
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private static boolean columnExists(Connection connection, String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) if (column.equalsIgnoreCase(result.getString("name"))) return true;
            return false;
        }
    }

    private static boolean isUniqueIndexPresent(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet indexes = statement.executeQuery("PRAGMA index_list(" + table + ")")) {
            while (indexes.next()) if (indexes.getInt("unique") == 1) return true;
            return false;
        }
    }

    private static boolean hasForeignKeyErrors(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("PRAGMA foreign_key_check")) {
            return result.next();
        }
    }

    private static int scalarInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next(), sql);
            return result.getInt(1);
        }
    }

    private static String scalarString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next(), sql);
            return result.getString(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
    }
}
