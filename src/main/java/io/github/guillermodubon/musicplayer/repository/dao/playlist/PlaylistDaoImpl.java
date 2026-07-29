package io.github.guillermodubon.musicplayer.repository.dao.playlist;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import io.github.guillermodubon.musicplayer.repository.dao.support.JdbcDaoSupport;
import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;

import java.sql.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;


public class PlaylistDaoImpl extends JdbcDaoSupport implements PlaylistDao {

    private static final Object DB_WRITE_LOCK = new Object();

    public PlaylistDaoImpl(Connection connection){
        super(connection);
    }

    private Connection openConn() throws SQLException {
        return openConnection();
    }


    @Override
    public Optional<Playlist> findById(Long id) throws SQLException {
        System.out.println("PlaylistDaoImpl.findById: id=" + id);
        String sql = "SELECT Title, Author, Description, CreationDate FROM Playlist WHERE PlaylistID = ?";

        if (hasSharedConnection()) {
            configureConnection(sharedConnection());
            try (PreparedStatement ps = prepareStatementWithRetry(sharedConnection(), sql, 6, false)) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    String title = rs.getString("Title");
                    String author = rs.getString("Author");
                    String desc = rs.getString("Description");
                    Timestamp created = rs.getTimestamp("CreationDate");
                    List<String> songTitles = findSongTitlesInPlaylist(sharedConnection(), id);
                    List<Song> songs = songTitles.stream().map(t -> new Song(0L, t, null, null, null, 0, true)).collect(Collectors.toList());
                    ObservableList<Song> singleList = FXCollections.observableArrayList(songs);
                    Playlist p = new Playlist(id, title, author, desc, created == null ? null : created.toLocalDateTime().format(DateTimeFormatter.ISO_DATE_TIME), null, singleList);
                    System.out.println("PlaylistDaoImpl.findById: found playlist id=" + id + " title=" + title + " songs=" + songs.size());
                    return Optional.of(p);
                }
            }
        } else {
            try (Connection c = openConn()) {
                try (PreparedStatement ps = prepareStatementWithRetry(c, sql, 6, false)) {
                    ps.setLong(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) return Optional.empty();
                        String title = rs.getString("Title");
                        String author = rs.getString("Author");
                        String desc = rs.getString("Description");
                        Timestamp created = rs.getTimestamp("CreationDate");
                        List<String> songTitles = findSongTitlesInPlaylist(c, id);
                        List<Song> songs = songTitles.stream().map(t -> new Song(0L, t, null, null, null, 0, true)).collect(Collectors.toList());
                        ObservableList<Song> singleList = FXCollections.observableArrayList(songs);
                        Playlist p = new Playlist(id, title, author, desc, created == null ? null : created.toLocalDateTime().format(DateTimeFormatter.ISO_DATE_TIME), null, singleList);
                        System.out.println("PlaylistDaoImpl.findById: found playlist id=" + id + " title=" + title + " songs=" + songs.size());
                        return Optional.of(p);
                    }
                }
            }
        }
    }

    @Override
    public List<Playlist> findAll() throws SQLException {
        System.out.println("PlaylistDaoImpl.findAll: start");
        String sql = "SELECT PlaylistID, Title, Author, Description, CreationDate FROM Playlist";
        List<Playlist> result = new ArrayList<>();

        if (hasSharedConnection()) {
            configureConnection(sharedConnection());
            try (PreparedStatement ps = prepareStatementWithRetry(sharedConnection(), sql, 6, false);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("PlaylistID");
                    String title = rs.getString("Title");
                    String author = rs.getString("Author");
                    String desc = rs.getString("Description");
                    Timestamp created = rs.getTimestamp("CreationDate");
                    List<String> songTitles = findSongTitlesInPlaylist(sharedConnection(), id);
                    List<Song> songs = songTitles.stream().map(t -> new Song(0L, t, null, null, null, 0, true)).collect(Collectors.toList());
                    ObservableList<Song> singleList = FXCollections.observableArrayList(songs);
                    Playlist p = new Playlist(id, title, author, desc, created == null ? null : created.toLocalDateTime().format(DateTimeFormatter.ISO_DATE_TIME), null, singleList);
                    result.add(p);
                }
            }
        } else {
            try (Connection c = openConn();
                 PreparedStatement ps = prepareStatementWithRetry(c, sql, 6, false);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("PlaylistID");
                    String title = rs.getString("Title");
                    String author = rs.getString("Author");
                    String desc = rs.getString("Description");
                    Timestamp created = rs.getTimestamp("CreationDate");
                    List<String> songTitles = findSongTitlesInPlaylist(c, id);
                    List<Song> songs = songTitles.stream().map(t -> new Song(0L, t, null, null, null, 0, true)).collect(Collectors.toList());
                    ObservableList<Song> singleList = FXCollections.observableArrayList(songs);
                    Playlist p = new Playlist(id, title, author, desc, created == null ? null : created.toLocalDateTime().format(DateTimeFormatter.ISO_DATE_TIME), null, singleList);
                    result.add(p);
                }
            }
        }

        System.out.println("PlaylistDaoImpl.findAll: found count=" + result.size());
        return result;
    }

    @Override
    public void insert(Playlist entity) throws SQLException {
        System.out.println("PlaylistDaoImpl.insert: title=" + entity.getTitle());
        String sql = "INSERT INTO Playlist(Title, Author, Description, CoverImage) VALUES(?, ?, ?, ?)";

        synchronized (DB_WRITE_LOCK) {
            if (hasSharedConnection()) {
                configureConnection(sharedConnection());
                boolean prevAuto = sharedConnection().getAutoCommit();
                try {
                    if (prevAuto) sharedConnection().setAutoCommit(false);
                    try (PreparedStatement ps = prepareStatementWithRetry(sharedConnection(), sql, 6, true)) {
                        ps.setString(1, entity.getTitle());
                        ps.setString(2, entity.getAuthorName());
                        ps.setString(3, entity.getDescription());
                        ps.setNull(4, Types.BLOB);
                        ps.executeUpdate();
                        try (ResultSet keys = ps.getGeneratedKeys()) {
                            if (keys.next()) entity.setId(keys.getLong(1));
                        }
                        insertSongsInPlaylist(sharedConnection(), entity);
                    }
                    sharedConnection().commit();
                    System.out.println("PlaylistDaoImpl.insert: committed playlist id=" + entity.getId());
                } catch (SQLException ex) {
                    try { sharedConnection().rollback(); } catch (SQLException ignore) {}
                    System.out.println("PlaylistDaoImpl.insert: rollback due to " + ex.getMessage());
                    throw ex;
                } finally {
                    try { sharedConnection().setAutoCommit(prevAuto); } catch (SQLException ignore) {}
                }
            } else {
                try (Connection c = openConn()) {
                    configureConnection(c);
                    boolean prevAuto = c.getAutoCommit();
                    c.setAutoCommit(false);
                    try (PreparedStatement ps = prepareStatementWithRetry(c, sql, 6, true)) {
                        ps.setString(1, entity.getTitle());
                        ps.setString(2, entity.getAuthorName());
                        ps.setString(3, entity.getDescription());
                        ps.setNull(4, Types.BLOB);
                        ps.executeUpdate();
                        try (ResultSet keys = ps.getGeneratedKeys()) {
                            if (keys.next()) entity.setId(keys.getLong(1));
                        }

                        insertSongsInPlaylist(c, entity);
                        c.commit();
                        System.out.println("PlaylistDaoImpl.insert: committed playlist id=" + entity.getId());
                    } catch (SQLException ex) {
                        try { c.rollback(); } catch (SQLException ignore) {}
                        System.out.println("PlaylistDaoImpl.insert: rollback due to " + ex.getMessage());
                        throw ex;
                    } finally {
                        try { c.setAutoCommit(prevAuto); } catch (SQLException ignore) {}
                    }
                }
            }
        }
    }

    @Override
    public void update(Playlist entity) throws SQLException {
        System.out.println("PlaylistDaoImpl.update: id=" + entity.getId());
        String sql = """
                UPDATE Playlist
                   SET Title       = ?,
                       Author      = ?,
                       Description = ?,
                       CoverImage  = ?
                 WHERE PlaylistID = ?
                """;

        synchronized (DB_WRITE_LOCK) {
            if (hasSharedConnection()) {
                configureConnection(sharedConnection());
                boolean prevAuto = sharedConnection().getAutoCommit();
                try {
                    if (prevAuto) sharedConnection().setAutoCommit(false);
                    try (PreparedStatement ps = prepareStatementWithRetry(sharedConnection(), sql, 6, false)) {
                        ps.setString(1, entity.getTitle());
                        ps.setString(2, entity.getAuthorName());
                        ps.setString(3, entity.getDescription());
                        ps.setNull(4, Types.BLOB);
                        ps.setLong(5, entity.getId());
                        ps.executeUpdate();

                        deleteAllSongsFromPlaylist(sharedConnection(), entity.getId());
                        insertSongsInPlaylist(sharedConnection(), entity);
                    }
                    sharedConnection().commit();
                    System.out.println("PlaylistDaoImpl.update: committed id=" + entity.getId());
                } catch (SQLException ex) {
                    try { sharedConnection().rollback(); } catch (SQLException ignore) {}
                    System.out.println("PlaylistDaoImpl.update: rollback due to " + ex.getMessage());
                    throw ex;
                } finally {
                    try { sharedConnection().setAutoCommit(prevAuto); } catch (SQLException ignore) {}
                }
            } else {
                try (Connection c = openConn()) {
                    configureConnection(c);
                    boolean prevAuto = c.getAutoCommit();
                    c.setAutoCommit(false);
                    try (PreparedStatement ps = prepareStatementWithRetry(c, sql, 6, false)) {
                        ps.setString(1, entity.getTitle());
                        ps.setString(2, entity.getAuthorName());
                        ps.setString(3, entity.getDescription());
                        ps.setNull(4, Types.BLOB);
                        ps.setLong(5, entity.getId());
                        ps.executeUpdate();

                        deleteAllSongsFromPlaylist(c, entity.getId());
                        insertSongsInPlaylist(c, entity);
                        c.commit();
                        System.out.println("PlaylistDaoImpl.update: committed id=" + entity.getId());
                    } catch (SQLException ex) {
                        try { c.rollback(); } catch (SQLException ignore) {}
                        System.out.println("PlaylistDaoImpl.update: rollback due to " + ex.getMessage());
                        throw ex;
                    } finally {
                        try { c.setAutoCommit(prevAuto); } catch (SQLException ignore) {}
                    }
                }
            }
        }
    }

    @Override
    public void delete(Long id) throws SQLException {
        System.out.println("PlaylistDaoImpl.delete: id=" + id);
        synchronized (DB_WRITE_LOCK) {
            if (hasSharedConnection()) {
                configureConnection(sharedConnection());
                boolean prevAuto = sharedConnection().getAutoCommit();
                try {
                    if (prevAuto) sharedConnection().setAutoCommit(false);
                    try (PreparedStatement ps1 = prepareStatementWithRetry(sharedConnection(), "DELETE FROM SongsPlaylists WHERE PlaylistID = ?", 6, false)) {
                        ps1.setLong(1, id);
                        ps1.executeUpdate();
                    }
                    deletePlaybackHistoryForPlaylist(sharedConnection(), id);
                    try (PreparedStatement ps2 = prepareStatementWithRetry(sharedConnection(), "DELETE FROM Playlist WHERE PlaylistID = ?", 6, false)) {
                        ps2.setLong(1, id);
                        ps2.executeUpdate();
                    }
                    sharedConnection().commit();
                    System.out.println("PlaylistDaoImpl.delete: committed id=" + id);
                } catch (SQLException ex) {
                    try { sharedConnection().rollback(); } catch (SQLException ignore) {}
                    System.out.println("PlaylistDaoImpl.delete: rollback due to " + ex.getMessage());
                    throw ex;
                } finally {
                    try { sharedConnection().setAutoCommit(prevAuto); } catch (SQLException ignore) {}
                }
            } else {
                try (Connection c = openConn()) {
                    configureConnection(c);
                    boolean prevAuto = c.getAutoCommit();
                    c.setAutoCommit(false);
                    try (PreparedStatement ps1 = prepareStatementWithRetry(c, "DELETE FROM SongsPlaylists WHERE PlaylistID = ?", 6, false)) {
                        ps1.setLong(1, id);
                        ps1.executeUpdate();
                    }
                    deletePlaybackHistoryForPlaylist(c, id);
                    try (PreparedStatement ps2 = prepareStatementWithRetry(c, "DELETE FROM Playlist WHERE PlaylistID = ?", 6, false)) {
                        ps2.setLong(1, id);
                        ps2.executeUpdate();
                    }
                    c.commit();
                    System.out.println("PlaylistDaoImpl.delete: committed id=" + id);
                }
            }
        }
    }

    @Override
    public List<String> findAllTitles() throws SQLException {
        System.out.println("PlaylistDaoImpl.findAllTitles: start");
        String sql = "SELECT Title FROM Playlist";
        List<String> titles = new ArrayList<>();
        if (hasSharedConnection()) {
            configureConnection(sharedConnection());
            try (PreparedStatement ps = prepareStatementWithRetry(sharedConnection(), sql, 6, false);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) titles.add(rs.getString(1));
            }
        } else {
            try (Connection c = openConn();
                 PreparedStatement ps = prepareStatementWithRetry(c, sql, 6, false);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) titles.add(rs.getString(1));
            }
        }
        System.out.println("PlaylistDaoImpl.findAllTitles: found=" + titles.size());
        return titles;
    }

    @Override
    public Long findIdByTitle(String title) throws SQLException {
        System.out.println("PlaylistDaoImpl.findIdByTitle: title=" + title);
        String sql = "SELECT PlaylistID FROM Playlist WHERE Title = ?";
        if (hasSharedConnection()) {
            configureConnection(sharedConnection());
            try (PreparedStatement ps = prepareStatementWithRetry(sharedConnection(), sql, 6, false)) {
                ps.setString(1, title);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : null;
                }
            }
        } else {
            try (Connection c = openConn();
                 PreparedStatement ps = prepareStatementWithRetry(c, sql, 6, false)) {
                ps.setString(1, title);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : null;
                }
            }
        }
    }

    // overloaded: use provided connection (transactional)
    public List<String> findSongTitlesInPlaylist(Connection c, long playlistId) throws SQLException {
        String sql = """
        SELECT s.Title
          FROM Song s
          JOIN SongsPlaylists sp ON s.SongID = sp.SongID
         WHERE sp.PlaylistID = ?
      ORDER BY sp.CreatedAt
    """;
        List<String> songs = new ArrayList<>();
        try (PreparedStatement ps = (c.prepareStatement(sql))) {
            ps.setLong(1, playlistId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) songs.add(rs.getString(1));
            }
        }
        return songs;
    }

    @Override
    public List<String> findSongTitlesInPlaylist(long playlistId) throws SQLException {
        if (hasSharedConnection()) return findSongTitlesInPlaylist(sharedConnection(), playlistId);
        try (Connection c = openConn()) {
            return findSongTitlesInPlaylist(c, playlistId);
        }
    }

    @Override
    public void createPlaylist(Playlist entity, byte[] coverBytes) throws SQLException {
        System.out.println("PlaylistDaoImpl.createPlaylist: title=" + entity.getTitle());
        String sql = "INSERT INTO Playlist(Title, Author, Description, CoverImage) VALUES(?, ?, ?, ?)";
        synchronized (DB_WRITE_LOCK) {
            if (hasSharedConnection()) {
                configureConnection(sharedConnection());
                boolean prevAuto = sharedConnection().getAutoCommit();
                try {
                    if (prevAuto) sharedConnection().setAutoCommit(false);
                    try (PreparedStatement ps = prepareStatementWithRetry(sharedConnection(), sql, 6, true)) {
                        ps.setString(1, entity.getTitle());
                        ps.setString(2, entity.getAuthorName());
                        ps.setString(3, entity.getDescription());
                        if (coverBytes != null) ps.setBytes(4, coverBytes);
                        else ps.setNull(4, Types.BLOB);
                        ps.executeUpdate();
                        try (ResultSet keys = ps.getGeneratedKeys()) { if (keys.next()) entity.setId(keys.getLong(1)); }
                        insertSongsInPlaylist(sharedConnection(), entity);
                    }
                    sharedConnection().commit();
                } catch (SQLException ex) { try { sharedConnection().rollback(); } catch (SQLException ignore) {} throw ex; }
                finally { try { sharedConnection().setAutoCommit(prevAuto); } catch (SQLException ignore) {} }
            } else {
                try (Connection c = openConn()) {
                    configureConnection(c);
                    boolean prevAuto = c.getAutoCommit();
                    c.setAutoCommit(false);
                    try (PreparedStatement ps = prepareStatementWithRetry(c, sql, 6, true)) {
                        ps.setString(1, entity.getTitle());
                        ps.setString(2, entity.getAuthorName());
                        ps.setString(3, entity.getDescription());
                        if (coverBytes != null) ps.setBytes(4, coverBytes);
                        else ps.setNull(4, Types.BLOB);
                        ps.executeUpdate();
                        try (ResultSet keys = ps.getGeneratedKeys()) { if (keys.next()) entity.setId(keys.getLong(1)); }
                        insertSongsInPlaylist(c, entity);
                        c.commit();
                    } catch (SQLException ex) { try { c.rollback(); } catch (SQLException ignore) {} throw ex; }
                    finally { try { c.setAutoCommit(prevAuto); } catch (SQLException ignore) {} }
                }
            }
        }
    }

    @Override
    public void upsertRemotePlaylistPreservingId(Playlist playlist,
                                                  byte[] coverBytes,
                                                  long remoteId) throws SQLException {
        if (playlist == null || remoteId <= 0) {
            throw new IllegalArgumentException("Playlist y remoteId son obligatorios.");
        }

        try {
            connectionManager().runInTransaction(connection -> {
                try {
                    String updateSql = """
                            UPDATE Playlist
                               SET Title = ?,
                                   Author = ?,
                                   Description = ?,
                                   CoverImage = COALESCE(?, CoverImage)
                             WHERE PlaylistID = ?
                            """;

                    int updatedRows;
                    try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
                        statement.setString(1, playlist.getTitle());
                        statement.setString(2, playlist.getAuthorName());
                        setNullableText(statement, 3, playlist.getDescription());
                        setNullableBlob(statement, 4, coverBytes);
                        statement.setLong(5, remoteId);
                        updatedRows = statement.executeUpdate();
                    }

                    if (updatedRows == 0) {
                        String insertSql = """
                                INSERT INTO Playlist
                                    (PlaylistID, Title, Author, Description, CoverImage, CreationDate)
                                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                                """;

                        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
                            statement.setLong(1, remoteId);
                            statement.setString(2, playlist.getTitle());
                            statement.setString(3, playlist.getAuthorName());
                            setNullableText(statement, 4, playlist.getDescription());
                            setNullableBlob(statement, 5, coverBytes);
                            statement.executeUpdate();
                        }
                    }

                    return null;
                } catch (SQLException exception) {
                    throw new RuntimeException(exception);
                }
            });
        } catch (RuntimeException exception) {
            if (exception.getCause() instanceof SQLException sqlException) {
                throw sqlException;
            }
            throw exception;
        }

        playlist.setId(remoteId);
    }

    private static void setNullableText(PreparedStatement statement,
                                         int parameterIndex,
                                         String value) throws SQLException {
        if (value == null) {
            statement.setNull(parameterIndex, Types.VARCHAR);
        } else {
            statement.setString(parameterIndex, value);
        }
    }

    private static void setNullableBlob(PreparedStatement statement,
                                        int parameterIndex,
                                        byte[] value) throws SQLException {
        if (value == null || value.length == 0) {
            statement.setNull(parameterIndex, Types.BLOB);
        } else {
            statement.setBytes(parameterIndex, value);
        }
    }

    private void deleteAllSongsFromPlaylist(Connection c, long playlistId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM SongsPlaylists WHERE PlaylistID = ?")) {
            ps.setLong(1, playlistId);
            ps.executeUpdate();
        }
    }

    private void deletePlaybackHistoryForPlaylist(Connection c, long playlistId) throws SQLException {
        try (PreparedStatement ps = prepareStatementWithRetry(
                c,
                "DELETE FROM PlaybackHistory WHERE ItemType = 'PLAYLIST' AND ItemID = ?",
                6,
                false
        )) {
            ps.setLong(1, playlistId);
            ps.executeUpdate();
        }
    }

    private void insertSongsInPlaylist(Connection c, Playlist p) throws SQLException {
        if (p == null || p.getSongList() == null || p.getSongList().isEmpty()) return;

        // Ensure valid IDs for all songs
        List<Long> songIds = new ArrayList<>(p.getSongList().size());
        for (Song s : p.getSongList()) {
            long sid = ensureSongRow(c, s);
            if (sid <= 0) {
                throw new SQLException("Could not ensure Song row for: " + (s == null ? "null" : s.getTitle()));
            }
            songIds.add(sid);
        }

        // Insert relationships (INSERT OR IGNORE protects against duplicates)
        String sql = "INSERT OR IGNORE INTO SongsPlaylists (SongID, PlaylistID) VALUES (?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (Long sid : songIds) {
                ps.setLong(1, sid);
                ps.setLong(2, p.getId());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }


    /**
     * Ensures that an album with album.getName() (or a fallback name) exists.
     * If the album has a positive AlbumID and exists, it uses it. Returns the AlbumID.
     */
    private long ensureAlbumExists(Connection c, Album album) throws SQLException {
        String name = (album != null && album.getName() != null && !album.getName().isBlank())
                ? album.getName().trim()
                : "Unknown Album";

        // If the album already has an ID > 0 and exists, return it.
        if (album != null && album.getAlbumID() > 0) {
            String chk = "SELECT 1 FROM Album WHERE AlbumID = ?";
            try (PreparedStatement ps = prepareStatementWithRetry(c, chk, 6, false)) {
                ps.setLong(1, album.getAlbumID());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return album.getAlbumID();
                }
            }
        }


        String sel = "SELECT AlbumID FROM Album WHERE Name = ?";
        try (PreparedStatement ps = prepareStatementWithRetry(c, sel, 6, false)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }

        long genreId = ensureGenreExists(c, (album != null && album.getGenre() != null) ? album.getGenre().getName() : "Unknown");

        String ins = "INSERT INTO Album(GenreID, Name, RecordType, ReleaseDate, NumberOfTracks) VALUES(?, ?, ?, ?, ?)";
        try (PreparedStatement ps = prepareStatementWithRetry(c, ins, 6, true)) {
            ps.setLong(1, genreId);
            ps.setString(2, name);
            ps.setString(3, "album");
            ps.setString(4, null);
            ps.setInt(5, 0);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        }


        try (PreparedStatement ps = prepareStatementWithRetry(c, sel, 6, false)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }

        throw new SQLException("Could not ensure Album for name=" + name);
    }

    /**
     * Ensures that a row exists in the Song table for the given entity and returns its SongID.
     * - If SongID > 0: attempts to verify its existence; if it does not exist, inserts a minimal row with that SongID.
     * - If SongID <= 0: creates a new row and populates song.setSongID(generated).
     *
     * Before inserting a Song, it ensures a valid Album (non-zero) exists by calling ensureAlbumExists.
     */
    private long ensureSongRow(Connection c, Song s) throws SQLException {
        if (s == null) return 0L;

        // determine valid albumId (create fallback album if necessary)
        Album alb = s.getAlbum();
        long albumId = (alb != null && alb.getAlbumID() > 0) ? alb.getAlbumID() : -1L;
        if (albumId <= 0) {
            albumId = ensureAlbumExists(c, alb);
        } else {
            // confirm it exists; if not, create using name/genre fallback
            String chkAlb = "SELECT 1 FROM Album WHERE AlbumID = ?";
            try (PreparedStatement ps = prepareStatementWithRetry(c, chkAlb, 6, false)) {
                ps.setLong(1, albumId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        albumId = ensureAlbumExists(c, alb);
                    }
                }
            }
        }

        if (s.getSongID() > 0) {

            String chk = "SELECT 1 FROM Song WHERE SongID = ?";
            try (PreparedStatement ps = prepareStatementWithRetry(c, chk, 6, false)) {
                ps.setLong(1, s.getSongID());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return s.getSongID();
                }
            }

            String insWithId = "INSERT OR IGNORE INTO Song(SongID, Title, Album, TrackOrder, IsLocal) VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement ps = prepareStatementWithRetry(c, insWithId, 6, false)) {
                ps.setLong(1, s.getSongID());
                ps.setString(2, Optional.ofNullable(s.getTitle()).orElse(""));
                ps.setLong(3, albumId);
                ps.setInt(4, s.getTrackOrder());
                ps.setInt(5, s.isLocal() ? 1 : 0);
                ps.executeUpdate();

                return s.getSongID();
            }
        } else {
            // insert without ID and read generated key
            String ins = "INSERT INTO Song(Title, Album, TrackOrder, IsLocal) VALUES(?, ?, ?, ?)";
            try (PreparedStatement ps = prepareStatementWithRetry(c, ins, 6, true)) {
                ps.setString(1, Optional.ofNullable(s.getTitle()).orElse(""));
                ps.setLong(2, albumId);
                ps.setInt(3, s.getTrackOrder());
                ps.setInt(4, s.isLocal() ? 1 : 0);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        long newId = keys.getLong(1);
                        s.setSongID(newId);
                        return newId;
                    }
                }
            }
            throw new SQLException("Could not insert Song row for title=" + s.getTitle());
        }
    }

    /**
     * Ensures that a genre with the given name exists. Returns its GenreID.
     * If name is null or blank, uses "Unknown".
     */
    private long ensureGenreExists(Connection c, String name) throws SQLException {
        String gname = (name == null || name.isBlank()) ? "Unknown" : name.trim();

        String sel = "SELECT GenreID FROM Genre WHERE Name = ?";
        try (PreparedStatement ps = prepareStatementWithRetry(c, sel, 6, false)) {
            ps.setString(1, gname);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }

        String ins = "INSERT INTO Genre(Name) VALUES(?)";
        try (PreparedStatement ps = prepareStatementWithRetry(c, ins, 6, true)) {
            ps.setString(1, gname);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        }

        try (PreparedStatement ps = prepareStatementWithRetry(c, sel, 6, false)) {
            ps.setString(1, gname);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        throw new SQLException("Could not ensure Genre for name=" + gname);
    }


    public void addSongToPlaylist(Connection c, long playlistId, Song s) throws SQLException {
        if (c == null) throw new SQLException("Connection is null");
        if (s == null) throw new IllegalArgumentException("Song is null");
        if (playlistId <= 0) throw new IllegalArgumentException("Invalid playlistId");

        // verify playlist exists (fails fast)
        try (PreparedStatement chk = prepareStatementWithRetry(c, "SELECT 1 FROM Playlist WHERE PlaylistID = ?", 6, false)) {
            chk.setLong(1, playlistId);
            try (ResultSet rs = chk.executeQuery()) {
                if (!rs.next()) throw new SQLException("Playlist not found: " + playlistId);
            }
        }

        // Ensure song row exists (this will insert Song/Album/Genre if needed and return songId)
        long songId = ensureSongRow(c, s);
        if (songId <= 0) throw new SQLException("Could not ensure Song row for '" + s.getTitle() + "'");

        // Insert relation (INSERT OR IGNORE to avoid duplicates)
        String ins = "INSERT OR IGNORE INTO SongsPlaylists (SongID, PlaylistID) VALUES (?, ?)";
        try (PreparedStatement ps = c.prepareStatement(ins)) {
            ps.setLong(1, songId);
            ps.setLong(2, playlistId);
            ps.executeUpdate();
        }
    }

    /** Convenience that manages connection/transaction itself. */
    @Override
    public void addSongToPlaylist(long playlistId, Song s) throws SQLException {
        synchronized (DB_WRITE_LOCK) {
            Connection c = openConn();
            boolean closeConn = !hasSharedConnection();
            try {
                configureConnection(c);
                boolean prevAuto = c.getAutoCommit();
                try {
                    if (prevAuto) c.setAutoCommit(false);
                    addSongToPlaylist(c, playlistId, s);
                    if (prevAuto) c.commit();
                } catch (SQLException ex) {
                    if (prevAuto) {
                        try { c.rollback(); } catch (SQLException ignore) {}
                    }
                    throw ex;
                } finally {
                    if (prevAuto) {
                        try { c.setAutoCommit(true); } catch (SQLException ignore) {}
                    }
                }
            } finally {
                if (closeConn) {
                    try { c.close(); } catch (SQLException ignore) {}
                }
            }
        }
    }

    /**
     * Adds a collection in one transaction using a single playlist check and
     * reusable relation statement. Songs already persisted locally bypass the
     * costly metadata reconciliation path.
     */
    public int addSongsToPlaylist(Connection c, long playlistId, List<Song> songs) throws SQLException {
        if (c == null) throw new SQLException("Connection is null");
        if (playlistId <= 0) throw new IllegalArgumentException("Invalid playlistId");
        if (songs == null || songs.isEmpty()) return 0;

        try (PreparedStatement playlistCheck = prepareStatementWithRetry(
                c,
                "SELECT 1 FROM Playlist WHERE PlaylistID = ?",
                6,
                false
        )) {
            playlistCheck.setLong(1, playlistId);
            try (ResultSet rs = playlistCheck.executeQuery()) {
                if (!rs.next()) throw new SQLException("Playlist not found: " + playlistId);
            }
        }

        int inserted = 0;
        Set<String> processedSongs = new HashSet<>();
        try (PreparedStatement songExists = prepareStatementWithRetry(
                c,
                "SELECT 1 FROM Song WHERE SongID = ?",
                6,
                false
        );
             PreparedStatement relationInsert = c.prepareStatement(
                     "INSERT OR IGNORE INTO SongsPlaylists (SongID, PlaylistID) VALUES (?, ?)"
             )) {
            for (Song song : songs) {
                if (song == null) continue;

                String key = song.getSongID() > 0
                        ? "id:" + song.getSongID()
                        : "title:" + Optional.ofNullable(song.getTitle())
                        .orElse("")
                        .trim()
                        .toLowerCase(Locale.ROOT);
                if (!processedSongs.add(key)) continue;

                long songId = resolveSongIdForPlaylist(c, song, songExists);
                if (songId <= 0) {
                    throw new SQLException("Could not ensure Song row for '" + song.getTitle() + "'");
                }

                relationInsert.setLong(1, songId);
                relationInsert.setLong(2, playlistId);
                inserted += Math.max(0, relationInsert.executeUpdate());
            }
        }
        return inserted;
    }

    private long resolveSongIdForPlaylist(Connection c,
                                           Song song,
                                           PreparedStatement songExists) throws SQLException {
        if (song.getSongID() > 0) {
            songExists.setLong(1, song.getSongID());
            try (ResultSet rs = songExists.executeQuery()) {
                if (rs.next()) return song.getSongID();
            }
        }
        return ensureSongRow(c, song);
    }

    public void removeSongFromPlaylist(Connection c, long playlistId, long songId) throws SQLException {
        if (c == null) throw new SQLException("Connection is null");
        if (playlistId <= 0 || songId <= 0) return;
        String del = "DELETE FROM SongsPlaylists WHERE SongID = ? AND PlaylistID = ?";
        try (PreparedStatement ps = c.prepareStatement(del)) {
            ps.setLong(1, songId);
            ps.setLong(2, playlistId);
            ps.executeUpdate();
        }
    }

    /** Convenience that manages connection/transaction itself. */
    @Override
    public void removeSongFromPlaylist(long playlistId, long songId) throws SQLException {
        synchronized (DB_WRITE_LOCK) {
            Connection c = openConn();
            boolean closeConn = !hasSharedConnection();
            try {
                configureConnection(c);
                boolean prevAuto = c.getAutoCommit();
                try {
                    if (prevAuto) c.setAutoCommit(false);
                    removeSongFromPlaylist(c, playlistId, songId);
                    if (prevAuto) c.commit();
                } catch (SQLException ex) {
                    if (prevAuto) {
                        try { c.rollback(); } catch (SQLException ignore) {}
                    }
                    throw ex;
                } finally {
                    if (prevAuto) {
                        try { c.setAutoCommit(true); } catch (SQLException ignore) {}
                    }
                }
            } finally {
                if (closeConn) {
                    try { c.close(); } catch (SQLException ignore) {}
                }
            }
        }
    }
}
