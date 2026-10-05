package io.github.guillermodubon.musicplayer.repository.schema;

import io.github.guillermodubon.musicplayer.repository.DbConnectionManager;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Owns database creation and additive schema migrations. */
public final class DatabaseSchemaManager {

    private static final int CURRENT_SCHEMA_VERSION = 1;
    private static final DateTimeFormatter BACKUP_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC);

    private DatabaseSchemaManager() {
    }

    public static synchronized void initialize(String databaseFile) throws DatabaseInitializationException {
        DbConnectionManager.init(databaseFile);
        File file = new File(databaseFile);
        ensureParentDirectory(file);

        try (Connection connection = DbConnectionManager.getInstance().openConnection()) {
            initializeConnection(connection, Path.of(databaseFile), () -> { });
        } catch (DatabaseInitializationException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw new DatabaseInitializationException(
                    DatabaseInitializationException.Reason.MIGRATION_FAILED,
                    "Could not initialize the music database. The original database was left in place.",
                    exception
            );
        }
    }

    static void initializeConnection(Connection connection,
                                     Path databasePath,
                                     MigrationHook migrationHook) throws SQLException {
        int version = userVersion(connection);
        if (version > CURRENT_SCHEMA_VERSION) {
            throw new DatabaseInitializationException(
                    DatabaseInitializationException.Reason.UNSUPPORTED_VERSION,
                    "Database schema version " + version + " is newer than supported version "
                            + CURRENT_SCHEMA_VERSION + "."
            );
        }
        if (version == CURRENT_SCHEMA_VERSION) return;
        if (!hasApplicationTables(connection)) {
            initializeFreshDatabase(connection);
        } else {
            migrateVersionZeroDatabase(connection, databasePath, migrationHook);
        }
    }

    @FunctionalInterface
    interface MigrationHook {
        void afterSchemaChanges() throws SQLException;
    }

    private static void ensureParentDirectory(File databaseFile) throws DatabaseInitializationException {
        File parent = databaseFile.getParentFile();
        if (parent == null || parent.exists()) {
            return;
        }
        try {
            if (!parent.mkdirs()) {
                throw new DatabaseInitializationException(
                        DatabaseInitializationException.Reason.MIGRATION_FAILED,
                        "Could not create the database directory: " + parent
                );
            }
        } catch (SecurityException exception) {
            throw new DatabaseInitializationException(
                    DatabaseInitializationException.Reason.MIGRATION_FAILED,
                    "Could not access the database directory.",
                    exception
            );
        }
    }

    private static int userVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private static boolean hasApplicationTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT 1 FROM sqlite_master
                      WHERE type = 'table' AND name NOT LIKE 'sqlite_%'
                      LIMIT 1
                     """)) {
            return result.next();
        }
    }

    private static void initializeFreshDatabase(Connection connection) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        try {
            if (previousAutoCommit) connection.setAutoCommit(false);
            createTables(connection);
            ensureMigrations(connection);
            createHybridTablesAndIndexes(connection);
            backfillSavedLocalMedia(connection);
            setUserVersion(connection, CURRENT_SCHEMA_VERSION);
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            if (previousAutoCommit) connection.setAutoCommit(true);
        }
        verifyIntegrity(connection);
    }

    private static void migrateVersionZeroDatabase(Connection connection,
                                                   Path databasePath,
                                                   MigrationHook migrationHook) throws SQLException {
        createMigrationBackup(databasePath);
        setForeignKeys(connection, false);
        boolean previousAutoCommit = connection.getAutoCommit();
        try {
            if (previousAutoCommit) connection.setAutoCommit(false);
            ensureMigrations(connection);
            Map<String, Set<String>> before = snapshotStableRows(connection);
            rebuildAlbum(connection);
            rebuildPlaylist(connection);
            createHybridTablesAndIndexes(connection);
            backfillSavedLocalMedia(connection);
            migrationHook.afterSchemaChanges();
            Map<String, Set<String>> after = snapshotStableRows(connection);
            if (!before.equals(after)) {
                throw new SQLException("Hybrid schema migration changed existing row identities or relationships.");
            }
            assertForeignKeyIntegrity(connection);
            setUserVersion(connection, CURRENT_SCHEMA_VERSION);
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            if (previousAutoCommit) connection.setAutoCommit(true);
            setForeignKeys(connection, true);
        }
        verifyIntegrity(connection);
    }

    private static void createMigrationBackup(Path databasePath) throws SQLException {
        String fileName = "UserDataBase.pre-hybrid-v0-" + BACKUP_TIMESTAMP.format(Instant.now()) + ".db";
        Path parent = databasePath.toAbsolutePath().getParent();
        if (parent == null) throw new SQLException("Database backup directory could not be resolved.");
        Path backup = parent.resolve(fileName);
        if (Files.exists(backup)) {
            throw new SQLException("A migration backup already exists; refusing to overwrite it.");
        }
        String url = "jdbc:sqlite:" + databasePath.toAbsolutePath();
        try (Connection backupConnection = java.sql.DriverManager.getConnection(url);
             Statement statement = backupConnection.createStatement()) {
            if (!backupConnection.getAutoCommit()) backupConnection.setAutoCommit(true);
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("VACUUM INTO '" + backup.toString().replace("'", "''") + "'");
        } catch (SQLException exception) {
            throw new SQLException("Could not create the required private database backup.", exception);
        }
    }

    private static void setForeignKeys(Connection connection, boolean enabled) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = " + (enabled ? "ON" : "OFF"));
        }
    }

    private static void setUserVersion(Connection connection, int version) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = " + version);
        }
    }

    private static void verifyIntegrity(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA quick_check")) {
            if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                throw new SQLException("SQLite quick_check reported database corruption.");
            }
        }
        assertForeignKeyIntegrity(connection);
    }

    private static void assertForeignKeyIntegrity(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (result.next()) {
                throw new SQLException("SQLite foreign_key_check found an invalid reference in "
                        + result.getString(1) + ".");
            }
        }
    }

    private static Map<String, Set<String>> snapshotStableRows(Connection connection) throws SQLException {
        Map<String, Set<String>> snapshots = new LinkedHashMap<>();
        snapshotKeys(connection, snapshots, "Song", "SongID");
        snapshotKeys(connection, snapshots, "Album", "AlbumID");
        snapshotKeys(connection, snapshots, "Playlist", "PlaylistID");
        snapshotKeys(connection, snapshots, "SongsPlaylists", "SongID", "PlaylistID", "Position", "CustomPosition");
        snapshotKeys(connection, snapshots, "SongArtist", "SongID", "ArtistID");
        snapshotKeys(connection, snapshots, "AlbumArtist", "AlbumID", "ArtistID");
        snapshotKeys(connection, snapshots, "SongLyrics", "LyricsID");
        snapshotKeys(connection, snapshots, "ArtistImage", "ArtistImageID");
        snapshotKeys(connection, snapshots, "AlbumImage", "AlbumImageID");
        snapshotKeys(connection, snapshots, "PlaybackHistory", "HistoryID");
        return snapshots;
    }

    private static void snapshotKeys(Connection connection,
                                     Map<String, Set<String>> snapshots,
                                     String table,
                                     String... columns) throws SQLException {
        String projection = String.join(", ", columns);
        Set<String> keys = new java.util.TreeSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT " + projection + " FROM " + table
                     + " ORDER BY " + projection)) {
            while (rows.next()) {
                List<String> values = new java.util.ArrayList<>(columns.length);
                for (int i = 1; i <= columns.length; i++) values.add(rows.getString(i));
                keys.add(String.join("\u001f", values));
            }
        }
        snapshots.put(table, keys);
    }

    private static void rebuildAlbum(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE Album__hybrid(
                        AlbumID INTEGER PRIMARY KEY,
                        GenreID INTEGER NOT NULL,
                        Name TEXT NOT NULL,
                        RecordType TEXT NOT NULL DEFAULT 'album',
                        ReleaseDate TEXT NULL,
                        NumberOfTracks INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY (GenreID) REFERENCES Genre(GenreID)
                    )
                    """);
            statement.execute("""
                    INSERT INTO Album__hybrid(AlbumID, GenreID, Name, RecordType, ReleaseDate, NumberOfTracks)
                    SELECT AlbumID, GenreID, Name, RecordType, ReleaseDate, NumberOfTracks FROM Album
                    """);
            statement.execute("DROP TABLE Album");
            statement.execute("ALTER TABLE Album__hybrid RENAME TO Album");
            statement.execute("CREATE INDEX idx_album_name ON Album(Name)");
            statement.execute("CREATE INDEX idx_album_genre ON Album(GenreID)");
        }
    }

    private static void rebuildPlaylist(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE Playlist__hybrid(
                        PlaylistID INTEGER PRIMARY KEY AUTOINCREMENT,
                        Title TEXT NOT NULL,
                        Author TEXT NOT NULL,
                        Description TEXT NULL,
                        CoverImage BLOB NULL,
                        CreationDate DATETIME DEFAULT CURRENT_TIMESTAMP,
                        Origin TEXT NOT NULL DEFAULT 'LEGACY'
                            CHECK (Origin IN ('USER', 'DEEZER', 'LEGACY'))
                    )
                    """);
            statement.execute("""
                    INSERT INTO Playlist__hybrid(
                        PlaylistID, Title, Author, Description, CoverImage, CreationDate, Origin
                    )
                    SELECT PlaylistID, Title, Author, Description, CoverImage, CreationDate,
                           CASE WHEN lower(trim(Author)) = 'user' THEN 'USER' ELSE 'LEGACY' END
                      FROM Playlist
                    """);
            statement.execute("DROP TABLE Playlist");
            statement.execute("ALTER TABLE Playlist__hybrid RENAME TO Playlist");
            statement.execute("CREATE INDEX idx_playlist_title ON Playlist(Title)");
        }
    }

    private static void createHybridTablesAndIndexes(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE Playlist ADD COLUMN Origin TEXT NOT NULL DEFAULT 'LEGACY' "
                    + "CHECK (Origin IN ('USER', 'DEEZER', 'LEGACY'))");
        } catch (SQLException alreadyPresent) {
            if (!columnExists(connection, "Playlist", "Origin")) throw alreadyPresent;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE INDEX IF NOT EXISTS idx_album_name ON Album(Name)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_playlist_title ON Playlist(Title)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_songs_playlists_playlist_position_song "
                    + "ON SongsPlaylists(PlaylistID, Position, SongID)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_songs_playlists_playlist_custom_song "
                    + "ON SongsPlaylists(PlaylistID, CustomPosition, SongID)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_songs_playlists_playlist_created_song "
                    + "ON SongsPlaylists(PlaylistID, CreatedAt, SongID)");
            statement.execute("CREATE TABLE IF NOT EXISTS SongExternalIdentity ("
                    + "Provider TEXT NOT NULL, ExternalId TEXT NOT NULL, SongID INTEGER NOT NULL REFERENCES Song(SongID), "
                    + "PRIMARY KEY (Provider, ExternalId), UNIQUE (Provider, SongID))");
            statement.execute("CREATE TABLE IF NOT EXISTS AlbumExternalIdentity ("
                    + "Provider TEXT NOT NULL, ExternalId TEXT NOT NULL, AlbumID INTEGER NOT NULL REFERENCES Album(AlbumID), "
                    + "PRIMARY KEY (Provider, ExternalId), UNIQUE (Provider, AlbumID))");
            statement.execute("CREATE TABLE IF NOT EXISTS PlaylistExternalIdentity ("
                    + "Provider TEXT NOT NULL, ExternalId TEXT NOT NULL, PlaylistID INTEGER NOT NULL REFERENCES Playlist(PlaylistID), "
                    + "PRIMARY KEY (Provider, ExternalId), UNIQUE (Provider, PlaylistID))");
            statement.execute("CREATE TABLE IF NOT EXISTS SavedSong ("
                    + "SongID INTEGER PRIMARY KEY REFERENCES Song(SongID) ON DELETE RESTRICT, SavedAt INTEGER NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS SavedRelease ("
                    + "AlbumID INTEGER PRIMARY KEY REFERENCES Album(AlbumID) ON DELETE RESTRICT, SavedAt INTEGER NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS SongPlayStats ("
                    + "SongID INTEGER PRIMARY KEY REFERENCES Song(SongID) ON DELETE CASCADE, "
                    + "PlayCount INTEGER NOT NULL DEFAULT 0 CHECK (PlayCount >= 0), LastPlayedAt INTEGER NOT NULL DEFAULT 0)");
            statement.execute("CREATE TABLE IF NOT EXISTS RecentSongPlay ("
                    + "PlayID INTEGER PRIMARY KEY AUTOINCREMENT, SongID INTEGER NOT NULL REFERENCES Song(SongID) ON DELETE CASCADE, "
                    + "PlayedAt INTEGER NOT NULL)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_recent_song_played_at "
                    + "ON RecentSongPlay(PlayedAt DESC, PlayID DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_recent_song_song "
                    + "ON RecentSongPlay(SongID)");
        }
    }

    private static boolean columnExists(Connection connection, String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) if (column.equalsIgnoreCase(result.getString("name"))) return true;
        }
        return false;
    }

    private static void backfillSavedLocalMedia(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT OR IGNORE INTO SavedSong(SongID, SavedAt) "
                    + "SELECT SongID, CAST(strftime('%s','now') AS INTEGER) * 1000 FROM Song WHERE IsLocal = 1");
            statement.executeUpdate("INSERT OR IGNORE INTO SavedRelease(AlbumID, SavedAt) "
                    + "SELECT DISTINCT Album, CAST(strftime('%s','now') AS INTEGER) * 1000 "
                    + "FROM Song WHERE IsLocal = 1");
            statement.executeUpdate("UPDATE Playlist SET Origin = CASE WHEN lower(trim(Author)) = 'user' "
                    + "THEN 'USER' ELSE 'LEGACY' END WHERE Origin = 'LEGACY'");
        }
    }

    private static void createTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS Playlist(
                        PlaylistID INTEGER PRIMARY KEY AUTOINCREMENT,
                        Title TEXT NOT NULL,
                        Author TEXT NOT NULL,
                        Description TEXT NULL,
                        CoverImage BLOB NULL,
                        CreationDate DATETIME DEFAULT CURRENT_TIMESTAMP,
                        Origin TEXT NOT NULL DEFAULT 'LEGACY'
                            CHECK (Origin IN ('USER', 'DEEZER', 'LEGACY'))
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
                        Name TEXT NOT NULL,
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
                        DurationSeconds INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(Album) REFERENCES Album(AlbumID)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS SongLyrics(
                        LyricsID INTEGER PRIMARY KEY AUTOINCREMENT,
                        SongID INTEGER NULL,
                        SourceKey TEXT NOT NULL UNIQUE,
                        TrackKey TEXT NULL,
                        TrackKeyVersion INTEGER NOT NULL DEFAULT 2,
                        LrcLibID INTEGER NULL,
                        TrackName TEXT NOT NULL,
                        ArtistName TEXT NOT NULL,
                        AlbumName TEXT NULL,
                        DurationSeconds INTEGER NOT NULL DEFAULT 0,
                        PlainLyrics TEXT NULL,
                        SyncedLyrics TEXT NULL,
                        Instrumental INTEGER NOT NULL DEFAULT 0,
                        Status TEXT NOT NULL,
                        FetchedAt INTEGER NOT NULL DEFAULT 0,
                        LastAttemptAt INTEGER NOT NULL DEFAULT 0,
                        NextRetryAt INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(SongID) REFERENCES Song(SongID) ON DELETE CASCADE
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

            /*
             * These indexes are additive and support the startup maintenance
             * paths that resolve an album's local songs and remove metadata
             * no longer referenced by albums or tracks.
             */
            statement.execute("CREATE INDEX IF NOT EXISTS idx_song_album_local ON Song(Album, IsLocal)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_album_genre ON Album(GenreID)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_song_artist_artist ON SongArtist(ArtistID)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_album_artist_artist ON AlbumArtist(ArtistID)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_artist_image_artist ON ArtistImage(ArtistID)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_album_image_album ON AlbumImage(AlbumID)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_song_lyrics_status_retry ON SongLyrics(Status, NextRetryAt)");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_song_lyrics_song ON SongLyrics(SongID) WHERE SongID IS NOT NULL");
        }
    }

    private static void ensureMigrations(Connection connection) throws SQLException {
        ensureColumn(connection, "Song", "FilePath", "TEXT NULL");
        ensureColumn(connection, "Song", "DurationSeconds", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(connection, "SongLyrics", "TrackKey", "TEXT NULL");
        ensureColumn(connection, "SongLyrics", "TrackKeyVersion", "INTEGER NOT NULL DEFAULT 0");
        backfillLyricsTrackKeys(connection);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE INDEX IF NOT EXISTS idx_song_lyrics_track_key ON SongLyrics(TrackKey)");
        }
        ensureColumn(connection, "PlaybackHistory", "ItemType", "TEXT NOT NULL DEFAULT 'ALBUM'");
        boolean positionAdded = ensureColumn(connection, "SongsPlaylists", "Position", "INTEGER NOT NULL DEFAULT 0");
        if (positionAdded) {
            backfillPlaylistPositions(connection);
        }
        if (ensureColumn(connection, "SongsPlaylists", "CustomPosition", "INTEGER NOT NULL DEFAULT 0")) {
            backfillPlaylistCustomPositions(connection);
        }
    }

    private static void backfillLyricsTrackKeys(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE SongLyrics
                       SET TrackKey = lower(trim(COALESCE(TrackName, '')))
                                   || char(31)
                                   || COALESCE((
                                        SELECT group_concat(name, char(30))
                                          FROM (
                                                SELECT DISTINCT lower(trim(ar.Name)) AS name
                                                  FROM Artist ar
                                                  JOIN SongArtist sa ON sa.ArtistID = ar.ArtistID
                                                 WHERE sa.SongID = SongLyrics.SongID
                                                UNION
                                                SELECT DISTINCT lower(trim(ar2.Name)) AS name
                                                  FROM Artist ar2
                                                  JOIN AlbumArtist aa ON aa.ArtistID = ar2.ArtistID
                                                  JOIN Song s2 ON s2.Album = aa.AlbumID
                                                 WHERE s2.SongID = SongLyrics.SongID
                                                ORDER BY name
                                          )
                                     ), lower(trim(COALESCE(ArtistName, '')))),
                           TrackKeyVersion = 2
                     WHERE COALESCE(TrackKeyVersion, 0) < 2
                       AND trim(COALESCE(TrackName, '')) <> ''
                       AND trim(COALESCE(ArtistName, '')) <> ''
                    """);
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
