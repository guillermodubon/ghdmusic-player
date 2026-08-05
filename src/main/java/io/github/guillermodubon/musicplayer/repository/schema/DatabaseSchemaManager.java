package io.github.guillermodubon.musicplayer.repository.schema;

import io.github.guillermodubon.musicplayer.repository.DbConnectionManager;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Owns database creation and additive schema migrations. */
public final class DatabaseSchemaManager {

    private DatabaseSchemaManager() {
    }

    public static void initialize(String databaseFile) {
        DbConnectionManager.init(databaseFile);
        File file = new File(databaseFile);
        ensureParentDirectory(file);

        try (Connection connection = DbConnectionManager.getInstance().openConnection()) {
            createTables(connection);
            ensureMigrations(connection);
        } catch (SQLException exception) {
            System.err.println("DatabaseSchemaManager.initialize: " + exception.getMessage());
            exception.printStackTrace();
        }
    }

    private static void ensureParentDirectory(File databaseFile) {
        File parent = databaseFile.getParentFile();
        if (parent == null || parent.exists()) {
            return;
        }
        try {
            if (!parent.mkdirs()) {
                System.out.println("DatabaseSchemaManager: could not create " + parent);
            }
        } catch (SecurityException exception) {
            System.out.println("DatabaseSchemaManager: permission problem -> " + exception.getMessage());
        }
    }

    private static void createTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS Playlist(
                        PlaylistID INTEGER PRIMARY KEY AUTOINCREMENT,
                        Title TEXT NOT NULL UNIQUE,
                        Author TEXT NOT NULL,
                        Description TEXT NULL,
                        CoverImage BLOB NULL,
                        CreationDate DATETIME DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS Artist(
                        ArtistID INTEGER PRIMARY KEY,
                        Name TEXT NOT NULL UNIQUE,
                        Biography TEXT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS Genre(
                        GenreID INTEGER PRIMARY KEY,
                        Name TEXT NOT NULL UNIQUE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ArtistImage(
                        ArtistImageID INTEGER PRIMARY KEY AUTOINCREMENT,
                        ArtistID INTEGER NOT NULL,
                        ImageType TEXT NOT NULL,
                        ImageData BLOB NOT NULL,
                        FOREIGN KEY (ArtistID) REFERENCES Artist(ArtistID)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS Album(
                        AlbumID INTEGER PRIMARY KEY,
                        GenreID INTEGER NOT NULL,
                        Name TEXT NOT NULL UNIQUE,
                        RecordType TEXT NOT NULL DEFAULT 'album',
                        ReleaseDate TEXT NULL,
                        NumberOfTracks INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY (GenreID) REFERENCES Genre(GenreID)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS AlbumImage(
                        AlbumImageID INTEGER PRIMARY KEY AUTOINCREMENT,
                        AlbumID INTEGER NOT NULL,
                        ImageType TEXT NOT NULL,
                        ImageData BLOB NOT NULL,
                        FOREIGN KEY (AlbumID) REFERENCES Album(AlbumID)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS AlbumArtist(
                        AlbumID INTEGER NOT NULL,
                        ArtistID INTEGER NOT NULL,
                        PRIMARY KEY (AlbumID, ArtistID),
                        FOREIGN KEY (AlbumID) REFERENCES Album(AlbumID),
                        FOREIGN KEY (ArtistID) REFERENCES Artist(ArtistID)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS Song(
                        SongID INTEGER PRIMARY KEY,
                        Title TEXT NOT NULL,
                        Album INTEGER NOT NULL,
                        TrackOrder INTEGER NOT NULL DEFAULT 0,
                        IsLocal INTEGER NOT NULL DEFAULT 1,
                        FilePath TEXT NULL,
                        FOREIGN KEY(Album) REFERENCES Album(AlbumID)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS SongsPlaylists(
                        SongID INTEGER NOT NULL,
                        PlaylistID INTEGER NOT NULL,
                        Position INTEGER NOT NULL DEFAULT 0,
                        CustomPosition INTEGER NOT NULL DEFAULT 0,
                        CreatedAt DATETIME DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (SongID, PlaylistID),
                        FOREIGN KEY (SongID) REFERENCES Song(SongID),
                        FOREIGN KEY (PlaylistID) REFERENCES Playlist(PlaylistID)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS SongArtist(
                        SongID INTEGER NOT NULL,
                        ArtistID INTEGER NOT NULL,
                        PRIMARY KEY (SongID, ArtistID),
                        FOREIGN KEY (SongID) REFERENCES Song(SongID),
                        FOREIGN KEY (ArtistID) REFERENCES Artist(ArtistID)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS PlaybackHistory(
                        HistoryID INTEGER PRIMARY KEY AUTOINCREMENT,
                        ItemID INTEGER NOT NULL,
                        ItemType TEXT NOT NULL DEFAULT 'ALBUM',
                        Name TEXT NOT NULL,
                        PlayedAt INTEGER NOT NULL DEFAULT (CAST(strftime('%s','now') AS INTEGER) * 1000)
                    )
                    """);
        }
    }

    private static void ensureMigrations(Connection connection) throws SQLException {
        ensureColumn(connection, "Song", "FilePath", "TEXT NULL");
        ensureColumn(connection, "PlaybackHistory", "ItemType", "TEXT NOT NULL DEFAULT 'ALBUM'");
        boolean positionAdded = ensureColumn(connection, "SongsPlaylists", "Position", "INTEGER NOT NULL DEFAULT 0");
        if (positionAdded) {
            backfillPlaylistPositions(connection);
        }
        if (ensureColumn(connection, "SongsPlaylists", "CustomPosition", "INTEGER NOT NULL DEFAULT 0")) {
            backfillPlaylistCustomPositions(connection);
        }
    }

    private static boolean ensureColumn(
            Connection connection,
            String tableName,
            String columnName,
            String definition
    ) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (resultSet.next()) {
                if (columnName.equalsIgnoreCase(resultSet.getString("name"))) {
                    return false;
                }
            }
        }

        try (PreparedStatement statement = connection.prepareStatement(
                "ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition)) {
            statement.executeUpdate();
        }
        return true;
    }

    private static void backfillPlaylistPositions(Connection connection) throws SQLException {
        String selectSql = """
                SELECT PlaylistID, SongID
                  FROM SongsPlaylists
                 ORDER BY PlaylistID, CreatedAt, SongID
                """;
        String updateSql = """
                UPDATE SongsPlaylists
                   SET Position = ?
                 WHERE PlaylistID = ? AND SongID = ?
                """;

        try (PreparedStatement select = connection.prepareStatement(selectSql);
             ResultSet rows = select.executeQuery();
             PreparedStatement update = connection.prepareStatement(updateSql)) {
            long currentPlaylistId = Long.MIN_VALUE;
            int position = 0;
            while (rows.next()) {
                long playlistId = rows.getLong("PlaylistID");
                if (playlistId != currentPlaylistId) {
                    currentPlaylistId = playlistId;
                    position = 0;
                }
                update.setInt(1, position++);
                update.setLong(2, playlistId);
                update.setLong(3, rows.getLong("SongID"));
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private static void backfillPlaylistCustomPositions(Connection connection) throws SQLException {
        String selectSql = """
                SELECT PlaylistID, SongID, Position
                  FROM SongsPlaylists
                 ORDER BY PlaylistID, Position, CreatedAt, SongID
                """;
        String updateSql = """
                UPDATE SongsPlaylists
                   SET CustomPosition = ?
                 WHERE PlaylistID = ? AND SongID = ?
                """;

        try (PreparedStatement select = connection.prepareStatement(selectSql);
             ResultSet rows = select.executeQuery();
             PreparedStatement update = connection.prepareStatement(updateSql)) {
            long currentPlaylistId = Long.MIN_VALUE;
            int position = 0;
            while (rows.next()) {
                long playlistId = rows.getLong("PlaylistID");
                if (playlistId != currentPlaylistId) {
                    currentPlaylistId = playlistId;
                    position = 0;
                }
                update.setInt(1, position++);
                update.setLong(2, playlistId);
                update.setLong(3, rows.getLong("SongID"));
                update.addBatch();
            }
            update.executeBatch();
        }
    }
}
