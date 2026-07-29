package io.github.guillermodubon.musicplayer.repository.dao.album;

import io.github.guillermodubon.musicplayer.repository.dao.artist.ArtistDao;
import io.github.guillermodubon.musicplayer.repository.dao.artist.ArtistDaoImpl;
import io.github.guillermodubon.musicplayer.repository.dao.support.JdbcDaoOperations;
import io.github.guillermodubon.musicplayer.repository.dao.genre.GenreDao;
import io.github.guillermodubon.musicplayer.repository.dao.support.JdbcDaoSupport;
import io.github.guillermodubon.musicplayer.models.*;
import io.github.guillermodubon.musicplayer.utils.ArtistIdentity;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class AlbumDaoImpl extends JdbcDaoSupport implements AlbumDao {

    private static final Object DB_WRITE_LOCK = new Object();

    @Override
    public Connection getConn() throws SQLException {
        return connectionManager().openConnection();
    }

    // if nonNull -> DAO uses the provided connection (does not close it)
    private static final Object ALBUM_DAO_WRITE_LOCK = new Object();


    public AlbumDaoImpl(Connection connection){
        super(connection);
    }


    @Override
    public Optional<Album> findById(Long id) throws SQLException {
        String sql = """
       SELECT
         AlbumID, GenreID, Name, RecordType,
         ReleaseDate, NumberOfTracks
       FROM Album
      WHERE AlbumID = ?
    """;
        System.out.println("AlbumDaoImpl.findById: id=" + id + " thread=" + Thread.currentThread().getName());

        if (hasSharedConnection()) {
            // ensure pragmas applied before prepare
            configureConnection(sharedConnection());
            try (PreparedStatement ps = prepareStatementWithRetry(sharedConnection(), sql, 6)) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Album alb = mapRsToAlbum(rs); // base fields

                        // Album covers are resolved from AlbumImage on demand by MediaImageResolver.

                        // songs
                        List<Song> songs = new ArrayList<>();
                        try (PreparedStatement psSongs = prepareStatementWithRetry(sharedConnection(),
                                "SELECT SongID, Title, TrackOrder, IsLocal, FilePath FROM Song WHERE Album = ? ORDER BY TrackOrder", 6)) {
                            psSongs.setLong(1, id);
                            try (ResultSet rsS = psSongs.executeQuery()) {
                                while (rsS.next()) {
                                    long sid = rsS.getLong("SongID");
                                    String title = rsS.getString("Title");
                                    int order = rsS.getInt("TrackOrder");
                                    boolean isLocal = rsS.getInt("IsLocal") != 0;
                                    String filePath = null;
                                    try { filePath = rsS.getString("FilePath"); } catch (Exception ignored) {}

                                    // load song artists
                                    List<Artist> songArtists = new ArrayList<>();
                                    try (PreparedStatement psSa = prepareStatementWithRetry(sharedConnection(),
                                            "SELECT ar.ArtistID, ar.Name, ar.Biography FROM SongArtist sa JOIN Artist ar ON sa.ArtistID = ar.ArtistID WHERE sa.SongID = ? AND lower(trim(ar.Name)) NOT IN ('varios artistas', 'various artists')", 6)) {
                                        psSa.setLong(1, sid);
                                        try (ResultSet rs2 = psSa.executeQuery()) {
                                            while (rs2.next()) {
                                                long aid = rs2.getLong("ArtistID");
                                                String aname = rs2.getString("Name");
                                                String bio = rs2.getString("Biography");
                                                songArtists.add(new Artist(aid, aname, bio, new ArrayList<>()));
                                            }
                                        }
                                    } catch (Exception ignore) {}

                                    Song s = new Song(sid, title, songArtists, alb, filePath, order, isLocal);
                                    songs.add(s);
                                }
                            }
                        } catch (Exception ignore) {}

                        if (!songs.isEmpty()) alb.setSongList(songs);

                        // album artists (FIX: use rs2, get columns from rs2)
                        try (PreparedStatement psAA = prepareStatementWithRetry(sharedConnection(),
                                "SELECT ar.ArtistID, ar.Name, ar.Biography FROM AlbumArtist aa JOIN Artist ar ON aa.ArtistID = ar.ArtistID WHERE aa.AlbumID = ? AND lower(trim(ar.Name)) NOT IN ('varios artistas', 'various artists')", 6)) {
                            psAA.setLong(1, id);
                            try (ResultSet rs2 = psAA.executeQuery()) {
                                List<Artist> albumArtists = new ArrayList<>();
                                while (rs2.next()) {
                                    long artistId = rs2.getLong("ArtistID");
                                    String name = rs2.getString("Name");
                                    String bio = rs2.getString("Biography");
                                    albumArtists.add(new Artist(artistId, name, bio, new ArrayList<>()));
                                }
                                if (!albumArtists.isEmpty()) alb.setArtist(albumArtists);
                            }
                        } catch (Exception ignore) {}

                        return Optional.of(alb);
                    }
                }
            }
            return Optional.empty();
        } else {
            try (Connection conn = openConnection()) {
                configureConnection(conn);
                try (PreparedStatement ps = prepareStatementWithRetry(conn, sql, 6)) {
                    ps.setLong(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            Album alb = mapRsToAlbum(rs);

                            // Album covers are resolved from AlbumImage on demand by MediaImageResolver.

                            // --- songs ---
                            List<Song> songs = new ArrayList<>();
                            try (PreparedStatement psSongs = prepareStatementWithRetry(conn,
                                    "SELECT SongID, Title, TrackOrder, IsLocal, FilePath FROM Song WHERE Album = ? ORDER BY TrackOrder", 6)) {
                                psSongs.setLong(1, id);
                                try (ResultSet rsS = psSongs.executeQuery()) {
                                    while (rsS.next()) {
                                        long sid = rsS.getLong("SongID");
                                        String title = rsS.getString("Title");
                                        int order = rsS.getInt("TrackOrder");
                                        boolean isLocal = rsS.getInt("IsLocal") != 0;
                                        String filePath = null;
                                        try { filePath = rsS.getString("FilePath"); } catch (Exception ignored) {}

                                        List<Artist> songArtists = new ArrayList<>();
                                        try (PreparedStatement psSa = prepareStatementWithRetry(conn,
                                                "SELECT ar.ArtistID, ar.Name, ar.Biography FROM SongArtist sa JOIN Artist ar ON sa.ArtistID = ar.ArtistID WHERE sa.SongID = ? AND lower(trim(ar.Name)) NOT IN ('varios artistas', 'various artists')", 6)) {
                                            psSa.setLong(1, sid);
                                            try (ResultSet rs2 = psSa.executeQuery()) {
                                                while (rs2.next()) {
                                                    long aid = rs2.getLong("ArtistID");
                                                    String aname = rs2.getString("Name");
                                                    String bio = rs2.getString("Biography");
                                                    songArtists.add(new Artist(aid, aname, bio, new ArrayList<>()));
                                                }
                                            }
                                        } catch (Exception ignore) {}

                                        Song s = new Song(sid, title, songArtists, alb, filePath, order, isLocal);
                                        songs.add(s);
                                    }
                                }
                            } catch (Exception ignore) {}

                            if (!songs.isEmpty()) alb.setSongList(songs);

                            // --- album artists (non-shared) ---
                            try (PreparedStatement psAA = prepareStatementWithRetry(conn,
                                    "SELECT ar.ArtistID, ar.Name, ar.Biography FROM AlbumArtist aa JOIN Artist ar ON aa.ArtistID = ar.ArtistID WHERE aa.AlbumID = ? AND lower(trim(ar.Name)) NOT IN ('varios artistas', 'various artists')", 6)) {
                                psAA.setLong(1, id);
                                try (ResultSet rs2 = psAA.executeQuery()) {
                                    List<Artist> albumArtists = new ArrayList<>();
                                    while (rs2.next()) {
                                        long artistId = rs2.getLong("ArtistID");
                                        String name = rs2.getString("Name");
                                        String bio = rs2.getString("Biography");
                                        albumArtists.add(new Artist(artistId, name, bio, new ArrayList<>()));
                                    }
                                    if (!albumArtists.isEmpty()) alb.setArtist(albumArtists);
                                }
                            } catch (Exception ignore) {}

                            return Optional.of(alb);
                        }
                    }
                }
            }
            return Optional.empty();
        }
    }

    @Override
    public List<Album> findAll() throws SQLException {
        String sql = """
           SELECT
             AlbumID, GenreID, Name, RecordType,
             ReleaseDate, NumberOfTracks
           FROM Album
        """;
        List<Album> list = new ArrayList<>();
        System.out.println("AlbumDaoImpl.findAll: thread=" + Thread.currentThread().getName());

        if (hasSharedConnection()) {
            configureConnection(sharedConnection());
            try (Statement stmt = sharedConnection().createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) list.add(mapRsToAlbum(rs));
            }
        } else {
            try (Connection conn = openConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                configureConnection(conn);
                while (rs.next()) list.add(mapRsToAlbum(rs));
            }
        }
        return list;
    }

    @Override
    public void insert(Album album) throws SQLException {
        String sql = """
            INSERT INTO Album(
                GenreID, Name, RecordType, ReleaseDate, NumberOfTracks
            ) VALUES(?, ?, ?, ?, ?)
        """;
        System.out.println("AlbumDaoImpl.insert: name=" + album.getName() + " thread=" + Thread.currentThread().getName());

        if (hasSharedConnection()) {
            synchronized (DB_WRITE_LOCK) {
                try (PreparedStatement ps = prepareStatementWithRetry(sharedConnection(), sql, 6)) {
                    configureConnection(sharedConnection());
                    ps.setInt(1, album.getGenre().getGenreID());
                    ps.setString(2, album.getName());
                    ps.setString(3, album.getRecordType());
                    ps.setString(4, album.getReleaseDate());
                    ps.setInt(5, album.getNumberOfTracks());
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (rs.next()) album.setAlbumID(rs.getLong(1));
                    }
                }
            }
        } else {
            synchronized (DB_WRITE_LOCK) {
                try (Connection conn = openConnection();
                     PreparedStatement ps = prepareStatementWithRetry(conn, sql, 6)) {
                    configureConnection(conn);
                    ps.setInt(1, album.getGenre().getGenreID());
                    ps.setString(2, album.getName());
                    ps.setString(3, album.getRecordType());
                    ps.setString(4, album.getReleaseDate());
                    ps.setInt(5, album.getNumberOfTracks());
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (rs.next()) album.setAlbumID(rs.getLong(1));
                    }
                }
            }
        }
    }

    @Override
    public void update(Album entity) throws SQLException {
        String sql = """
                UPDATE Album
           SET GenreID        = ?,
               Name           = ?,
               RecordType     = ?,
               ReleaseDate    = ?,
               NumberOfTracks = ?
         WHERE AlbumID = ?
        """;
        System.out.println("AlbumDaoImpl.update: albumId=" + entity.getAlbumID() + " thread=" + Thread.currentThread().getName());

        if (hasSharedConnection()) {
            synchronized (DB_WRITE_LOCK) {
                try (PreparedStatement ps = prepareStatementWithRetry(sharedConnection(), sql, 6)) {
                    configureConnection(sharedConnection());
                    ps.setInt(1, entity.getGenre().getGenreID());
                    ps.setString(2, entity.getName());
                    ps.setString(3, entity.getRecordType());
                    ps.setString(4, entity.getReleaseDate());
                    ps.setInt(5, entity.getNumberOfTracks());
                    ps.setLong(6, entity.getAlbumID());
                    ps.executeUpdate();
                }
            }
        } else {
            synchronized (DB_WRITE_LOCK) {
                try (Connection conn = openConnection();
                     PreparedStatement ps = prepareStatementWithRetry(conn, sql, 6)) {
                    configureConnection(conn);
                    ps.setInt(1, entity.getGenre().getGenreID());
                    ps.setString(2, entity.getName());
                    ps.setString(3, entity.getRecordType());
                    ps.setString(4, entity.getReleaseDate());
                    ps.setInt(5, entity.getNumberOfTracks());
                    ps.setLong(6, entity.getAlbumID());
                    ps.executeUpdate();
                }
            }
        }
    }

    @Override
    public void delete(Long id) throws SQLException {
        String sql = "DELETE FROM Album WHERE AlbumID = ?";
        System.out.println("AlbumDaoImpl.delete: albumId=" + id + " thread=" + Thread.currentThread().getName());

        if (hasSharedConnection()) {
            synchronized (DB_WRITE_LOCK) {
                try (PreparedStatement ps = prepareStatementWithRetry(sharedConnection(), sql, 6)) {
                    configureConnection(sharedConnection());
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
            }
        } else {
            synchronized (DB_WRITE_LOCK) {
                try (Connection conn = openConnection();
                     PreparedStatement ps = prepareStatementWithRetry(conn, sql, 6)) {
                    configureConnection(conn);
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
            }
        }
    }

    @Override
    public Long findIdByName(String name) throws SQLException {
        String sql = "SELECT AlbumID FROM Album WHERE Name = ? LIMIT 1";
        System.out.println("AlbumDaoImpl.findIdByName: name=" + name + " thread=" + Thread.currentThread().getName());

        if (hasSharedConnection()) {
            try (PreparedStatement ps = prepareStatementWithRetry(sharedConnection(), sql, 6)) {
                configureConnection(sharedConnection());
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong("AlbumID") : null;
                }
            }
        } else {
            try (Connection conn = openConnection();
                 PreparedStatement ps = prepareStatementWithRetry(conn, sql, 6)) {
                configureConnection(conn);
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong("AlbumID") : null;
                }
            }
        }
    }

    @Override
    public void insertImage(long albumId, String type, byte[] data) throws SQLException {
        String sql = "INSERT INTO AlbumImage(AlbumID, ImageType, ImageData) VALUES(?, ?, ?)";
        System.out.println("AlbumDaoImpl.insertImage: albumId=" + albumId + " type=" + type + " thread=" + Thread.currentThread().getName());

        if (hasSharedConnection()) {
            synchronized (DB_WRITE_LOCK) {
                try (PreparedStatement ps = prepareStatementWithRetry(sharedConnection(), sql, 6)) {
                    configureConnection(sharedConnection());
                    ps.setLong(1, albumId);
                    ps.setString(2, type);
                    ps.setBytes(3, data);
                    ps.executeUpdate();
                }
            }
        } else {
            synchronized (DB_WRITE_LOCK) {
                try (Connection conn = openConnection();
                     PreparedStatement ps = prepareStatementWithRetry(conn, sql, 6)) {
                    configureConnection(conn);
                    ps.setLong(1, albumId);
                    ps.setString(2, type);
                    ps.setBytes(3, data);
                    ps.executeUpdate();
                }
            }
        }
    }

    @Override
    public boolean imageExists(long albumId, String type) throws SQLException {
        String sql = "SELECT 1 FROM AlbumImage WHERE AlbumID = ? AND ImageType = ?";
        System.out.println("AlbumDaoImpl.imageExists: albumId=" + albumId + " type=" + type + " thread=" + Thread.currentThread().getName());

        if (hasSharedConnection()) {
            try (PreparedStatement ps = prepareStatementWithRetry(sharedConnection(), sql, 6)) {
                configureConnection(sharedConnection());
                ps.setLong(1, albumId);
                ps.setString(2, type);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        } else {
            try (Connection conn = openConnection();
                 PreparedStatement ps = prepareStatementWithRetry(conn, sql, 6)) {
                configureConnection(conn);
                ps.setLong(1, albumId);
                ps.setString(2, type);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        }
    }

    @Override
    public void linkArtist(long albumId, long artistId) throws SQLException {
        String sql = "INSERT OR IGNORE INTO AlbumArtist(AlbumID, ArtistID) VALUES(?, ?)";
        System.out.println("AlbumDaoImpl.linkArtist: albumId=" + albumId + " artistId=" + artistId + " thread=" + Thread.currentThread().getName());

        if (hasSharedConnection()) {
            synchronized (DB_WRITE_LOCK) {
                try (PreparedStatement ps = prepareStatementWithRetry(sharedConnection(), sql, 6)) {
                    configureConnection(sharedConnection());
                    ps.setLong(1, albumId);
                    ps.setLong(2, artistId);
                    ps.executeUpdate();
                }
            }
        } else {
            synchronized (DB_WRITE_LOCK) {
                try (Connection conn = openConnection();
                     PreparedStatement ps = prepareStatementWithRetry(conn, sql, 6)) {
                    configureConnection(conn);
                    ps.setLong(1, albumId);
                    ps.setLong(2, artistId);
                    ps.executeUpdate();
                }
            }
        }
    }

    @Override
    public Long create(String name, int genreId, String recordType, String releaseDate, int numberOfTracks) throws SQLException {
        String sql = """
        INSERT INTO Album(
            GenreID, Name, RecordType, ReleaseDate, NumberOfTracks
        ) VALUES(?, ?, ?, ?, ?)
    """;
        System.out.println("AlbumDaoImpl.create: name=" + name + " thread=" + Thread.currentThread().getName());

        if (hasSharedConnection()) {
            synchronized (DB_WRITE_LOCK) {
                try (PreparedStatement ps = prepareStatementWithRetry(sharedConnection(), sql, 6)) {
                    configureConnection(sharedConnection());
                    ps.setInt(1, genreId);
                    ps.setString(2, name);
                    ps.setString(3, recordType != null && !recordType.isBlank() ? recordType : "album");
                    if (releaseDate != null && !releaseDate.isBlank()) ps.setString(4, releaseDate);
                    else ps.setNull(4, Types.VARCHAR);
                    ps.setInt(5, numberOfTracks);
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (rs.next()) return rs.getLong(1);
                    }
                }
            }
        } else {
            synchronized (DB_WRITE_LOCK) {
                try (Connection conn = openConnection();
                     PreparedStatement ps = prepareStatementWithRetry(conn, sql, 6)) {
                    configureConnection(conn);
                    ps.setInt(1, genreId);
                    ps.setString(2, name);
                    ps.setString(3, recordType != null && !recordType.isBlank() ? recordType : "album");
                    if (releaseDate != null && !releaseDate.isBlank()) ps.setString(4, releaseDate);
                    else ps.setNull(4, Types.VARCHAR);
                    ps.setInt(5, numberOfTracks);
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (rs.next()) return rs.getLong(1);
                    }
                }
            }
        }
        return null;
    }

    @Override
    public void insertImages(long albumId, List<byte[]> covers) throws SQLException {
        String sql = "INSERT INTO AlbumImage(AlbumID, ImageType, ImageData) VALUES(?, ?, ?)";
        String[] types = {"small", "medium", "xl"};
        System.out.println("AlbumDaoImpl.insertImages: albumId=" + albumId + " covers=" + (covers==null?0:covers.size()) + " thread=" + Thread.currentThread().getName());

        if (hasSharedConnection()) {
            synchronized (DB_WRITE_LOCK) {
                configureConnection(sharedConnection());
                JdbcDaoOperations.insertBlobs(sharedConnection(), sql, albumId, covers, types,
                        (c, p) -> {
                            try {
                                try (PreparedStatement ps = sharedConnection().prepareStatement("SELECT 1 FROM AlbumImage WHERE AlbumID = ? AND ImageType = ?")) {
                                    ps.setLong(1, p.getKey());
                                    ps.setString(2, p.getValue());
                                    try (ResultSet rs = ps.executeQuery()) {
                                        return rs.next();
                                    }
                                }
                            } catch (SQLException e) {
                                throw new RuntimeException(e);
                            }
                        }
                );
            }
        } else {
            synchronized (DB_WRITE_LOCK) {
                try (Connection conn = openConnection()) {
                    configureConnection(conn);
                    JdbcDaoOperations.insertBlobs(conn, sql, albumId, covers, types,
                            (c, p) -> {
                                try {
                                    try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM AlbumImage WHERE AlbumID = ? AND ImageType = ?")) {
                                        ps.setLong(1, p.getKey());
                                        ps.setString(2, p.getValue());
                                        try (ResultSet rs = ps.executeQuery()) {
                                            return rs.next();
                                        }
                                    }
                                } catch (SQLException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                    );
                }
            }
        }
    }

    @Override
    public void deleteWithoutSongs() throws SQLException {
        String sql = "DELETE FROM Album WHERE AlbumID NOT IN (SELECT DISTINCT Album FROM Song)";
        System.out.println("AlbumDaoImpl.deleteWithoutSongs: thread=" + Thread.currentThread().getName());

        if (hasSharedConnection()) {
            synchronized (DB_WRITE_LOCK) {
                try (PreparedStatement ps = prepareStatementWithRetry(sharedConnection(), sql, 6)) {
                    configureConnection(sharedConnection());
                    ps.executeUpdate();
                }
            }
        } else {
            synchronized (DB_WRITE_LOCK) {
                try (Connection conn = openConnection();
                     PreparedStatement ps = prepareStatementWithRetry(conn, sql, 6)) {
                    configureConnection(conn);
                    ps.executeUpdate();
                }
            }
        }
    }

    @Override
    public void deleteAlbumArtists(long albumId) throws SQLException {
        String sql = "DELETE FROM AlbumArtist WHERE AlbumID = ?";
        System.out.println("AlbumDaoImpl.deleteAlbumArtists: albumId=" + albumId + " thread=" + Thread.currentThread().getName());

        if (hasSharedConnection()) {
            synchronized (DB_WRITE_LOCK) {
                try (PreparedStatement ps = prepareStatementWithRetry(sharedConnection(), sql, 6)) {
                    configureConnection(sharedConnection());
                    ps.setLong(1, albumId);
                    ps.executeUpdate();
                }
            }
        } else {
            synchronized (DB_WRITE_LOCK) {
                try (Connection conn = openConnection();
                     PreparedStatement ps = prepareStatementWithRetry(conn, sql, 6)) {
                    configureConnection(conn);
                    ps.setLong(1, albumId);
                    ps.executeUpdate();
                }
            }
        }
    }

    @Override
    public boolean existsById(long albumId) throws SQLException {
        String sql = "SELECT 1 FROM Album WHERE AlbumID = ? LIMIT 1";
        System.out.println("AlbumDaoImpl.existsById: albumId=" + albumId + " thread=" + Thread.currentThread().getName());

        if (hasSharedConnection()) {
            try (PreparedStatement ps = prepareStatementWithRetry(sharedConnection(), sql, 6)) {
                configureConnection(sharedConnection());
                ps.setLong(1, albumId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        } else {
            try (Connection conn = openConnection();
                 PreparedStatement ps = prepareStatementWithRetry(conn, sql, 6)) {
                configureConnection(conn);
                ps.setLong(1, albumId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        }
    }

    @Override
    public void deleteAlbumImages(long albumId) throws SQLException {
        String sql = "DELETE FROM AlbumImage WHERE AlbumID = ?";
        System.out.println("AlbumDaoImpl.deleteAlbumImages: albumId=" + albumId + " thread=" + Thread.currentThread().getName());

        if (hasSharedConnection()) {
            synchronized (DB_WRITE_LOCK) {
                try (PreparedStatement ps = prepareStatementWithRetry(sharedConnection(), sql, 6)) {
                    configureConnection(sharedConnection());
                    ps.setLong(1, albumId);
                    ps.executeUpdate();
                }
            }
        } else {
            synchronized (DB_WRITE_LOCK) {
                try (Connection conn = openConnection();
                     PreparedStatement ps = prepareStatementWithRetry(conn, sql, 6)) {
                    configureConnection(conn);
                    ps.setLong(1, albumId);
                    ps.executeUpdate();
                }
            }
        }
    }

    /* ---------- helpers and mappings ---------- */

    private Album mapRsToAlbum(ResultSet rs) throws SQLException {
        return new Album(
                rs.getLong("AlbumID"),
                rs.getString("Name"),
                new ArrayList<>(),
                new Genre(rs.getInt("GenreID"), null),
                rs.getString("RecordType"),
                rs.getString("ReleaseDate"),
                new ArrayList<>(),
                new ArrayList<>(),
                rs.getInt("NumberOfTracks")
        );
    }

    public void upsertAll(Connection conn,
                          List<DeezerApiMetaData> metas,
                          GenreDao genreDao,
                          ArtistDao artistDao) throws SQLException {
        if (conn == null) throw new SQLException("conn requerido en upsertAll(conn, ...)");
        for (DeezerApiMetaData meta : metas) {
            long deezerAlbumId = meta.getAlbumId();
            String albName     = meta.getAlbumName();
            String genName     = meta.getGenre();
            if (albName == null || albName.isBlank() || genName == null || genName.isBlank()) continue;

            Integer genreId = genreDao.findIdByName(genName);
            if (genreId == null) continue;

            Long existingId = null;
            try (PreparedStatement psFind = conn.prepareStatement("SELECT AlbumID FROM Album WHERE Name = ? LIMIT 1")) {
                psFind.setString(1, albName);
                try (ResultSet rs = psFind.executeQuery()) {
                    if (rs.next()) existingId = rs.getLong("AlbumID");
                }
            }

            long persistedAlbumId;
            if (existingId != null) {
                String sqlUpdate = """
                        UPDATE Album
                           SET GenreID = ?,
                               Name = ?,
                               RecordType = ?,
                               ReleaseDate = ?,
                               NumberOfTracks = ?
                         WHERE AlbumID = ?
                        """;
                try (PreparedStatement ps = conn.prepareStatement(sqlUpdate)) {
                    ps.setInt(1, genreId);
                    ps.setString(2, albName);
                    ps.setString(3, meta.getRecordType());
                    if (meta.getAlbumReleaseDate() != null && !meta.getAlbumReleaseDate().isBlank()) ps.setString(4, meta.getAlbumReleaseDate());
                    else ps.setNull(4, Types.VARCHAR);
                    ps.setInt(5, meta.getNumberOfTracks());
                    ps.setLong(6, existingId);
                    ps.executeUpdate();
                }
                persistedAlbumId = existingId;
            } else {
                String sqlInsert = """
                        INSERT OR REPLACE INTO Album(
                            AlbumID, GenreID, Name, RecordType, ReleaseDate, NumberOfTracks
                        ) VALUES(?, ?, ?, ?, ?, ?)
                        """;
                try (PreparedStatement ps = conn.prepareStatement(sqlInsert)) {
                    ps.setLong(1, deezerAlbumId);
                    ps.setInt(2, genreId);
                    ps.setString(3, albName);
                    ps.setString(4, meta.getRecordType());
                    if (meta.getAlbumReleaseDate() != null && !meta.getAlbumReleaseDate().isBlank()) ps.setString(5, meta.getAlbumReleaseDate());
                    else ps.setNull(5, Types.VARCHAR);
                    ps.setInt(6, meta.getNumberOfTracks());
                    ps.executeUpdate();
                }
                persistedAlbumId = deezerAlbumId;
            }

            List<byte[]> covers = meta.getAlbumCoverBytesList();
            if (covers != null && !covers.isEmpty()) {
                insertImages(persistedAlbumId, covers);
            }

            List<String> albumArtistNames = meta.getAlbumArtistNames();
            if (albumArtistNames != null && !albumArtistNames.isEmpty()) {
                for (String an : albumArtistNames) {
                    if (an == null || an.isBlank()) continue;
                    if (ArtistIdentity.isVariousArtists(an)) continue;
                    Long artId = artistDao.findIdByName(an);
                    if (artId != null) {
                        try (PreparedStatement link = conn.prepareStatement("INSERT OR IGNORE INTO AlbumArtist(AlbumID, ArtistID) VALUES(?, ?)")) {
                            link.setLong(1, persistedAlbumId);
                            link.setLong(2, artId.longValue());
                            link.executeUpdate();
                        }
                    }
                }
            }
        }
    }

    @Override
    public void upsertFromMeta(DeezerApiMetaData meta) throws SQLException {
        if (meta == null) return;
        long deezerAlbumId = meta.getAlbumId();
        if (deezerAlbumId <= 0) return;

        var mgr = connectionManager();

        // Serializar accesos a la versión que abre su propia conexión para evitar writers concurrentes
        synchronized (ALBUM_DAO_WRITE_LOCK) {
            mgr.runInTransaction(conn -> {
                try {
                    // delegamos a la variante que usa la conexión proporcionada
                    upsertFromMeta(conn, meta);
                    return null;
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    /**
     * Variant that reuses the provided connection (does NOT open or manage a transaction).
     * All operations use 'conn' and prepareStatementWithRetry(conn, ...).
     * The caller controls commit/rollback.
     */
    public void upsertFromMeta(Connection conn, DeezerApiMetaData meta) throws SQLException {
        if (meta == null) return;
        long deezerAlbumId = meta.getAlbumId();
        if (deezerAlbumId <= 0) return;

        try {
            // 1) get genreId (if applicable)
            int genreId = 0;
            String genreName = meta.getGenre();
            if (genreName != null && !genreName.isBlank()) {
                try (PreparedStatement psGenre = prepareStatementWithRetry(conn, "SELECT GenreID FROM Genre WHERE Name = ? LIMIT 1", 6)) {
                    psGenre.setString(1, genreName);
                    try (ResultSet rs = psGenre.executeQuery()) {
                        if (rs.next()) genreId = rs.getInt("GenreID");
                    }
                }
                // create if it doesn't exist
                if (genreId == 0) {
                    try (PreparedStatement psIns = prepareStatementWithRetry(conn, "INSERT INTO Genre(Name) VALUES(?)", 6)) {
                        psIns.setString(1, genreName);
                        psIns.executeUpdate();
                    } catch (SQLException e) {
                        String msg = Optional.ofNullable(e.getMessage()).orElse("").toLowerCase();
                        if (!msg.contains("unique") && !msg.contains("constraint")) throw e;
                    }
                    try (PreparedStatement psGenre2 = prepareStatementWithRetry(conn, "SELECT GenreID FROM Genre WHERE Name = ? LIMIT 1", 6)) {
                        psGenre2.setString(1, genreName);
                        try (ResultSet rs = psGenre2.executeQuery()) {
                            if (rs.next()) genreId = rs.getInt("GenreID");
                        }
                    }
                }
            }

            // 2) check for existence by AlbumID (preferable)
            boolean existsById = false;
            try (PreparedStatement psChk = prepareStatementWithRetry(conn, "SELECT 1 FROM Album WHERE AlbumID = ? LIMIT 1", 6)) {
                psChk.setLong(1, deezerAlbumId);
                try (ResultSet rs = psChk.executeQuery()) { existsById = rs.next(); }
            }

            // 3) if it exists, update; otherwise, insert (using AlbumID from the API)
            String albName = meta.getAlbumName() == null ? "" : meta.getAlbumName();
            String recordType = meta.getRecordType();
            String releaseDate = meta.getAlbumReleaseDate();
            int numberOfTracks = meta.getNumberOfTracks();

            if (existsById) {
                try (PreparedStatement ps = prepareStatementWithRetry(conn,
                        "UPDATE Album SET GenreID=?, Name=?, RecordType=?, ReleaseDate=?, NumberOfTracks=? WHERE AlbumID=?", 6)) {
                    ps.setInt(1, genreId);
                    ps.setString(2, albName);
                    ps.setString(3, recordType);
                    if (releaseDate != null && !releaseDate.isBlank()) ps.setString(4, releaseDate);
                    else ps.setNull(4, Types.VARCHAR);
                    ps.setInt(5, numberOfTracks);
                    ps.setLong(6, deezerAlbumId);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = prepareStatementWithRetry(conn,
                        "INSERT OR REPLACE INTO Album(AlbumID, GenreID, Name, RecordType, ReleaseDate, NumberOfTracks) VALUES(?, ?, ?, ?, ?, ?)", 6)) {
                    ps.setLong(1, deezerAlbumId);
                    ps.setInt(2, genreId);
                    ps.setString(3, albName);
                    ps.setString(4, recordType);
                    if (releaseDate != null && !releaseDate.isBlank()) ps.setString(5, releaseDate);
                    else ps.setNull(5, Types.VARCHAR);
                    ps.setInt(6, numberOfTracks);
                    ps.executeUpdate();
                }
            }

            // 4) Insert/update cover images using the same connection (avoid opening another connection)
            List<byte[]> covers = meta.getAlbumCoverBytesList();
            if (covers != null && !covers.isEmpty()) {
                String[] types = {"small", "medium", "xl"};
                for (int i = 0; i < types.length && i < covers.size(); i++) {
                    byte[] data = covers.get(i);
                    if (data == null || data.length == 0) continue;
                    String type = types[i];
                    boolean exists = false;
                    try (PreparedStatement psChk = prepareStatementWithRetry(conn, "SELECT 1 FROM AlbumImage WHERE AlbumID = ? AND ImageType = ? LIMIT 1", 6)) {
                        psChk.setLong(1, deezerAlbumId);
                        psChk.setString(2, type);
                        try (ResultSet rs = psChk.executeQuery()) { exists = rs.next(); }
                    }
                    if (!exists) {
                        try (PreparedStatement psIns = prepareStatementWithRetry(conn, "INSERT INTO AlbumImage(AlbumID, ImageType, ImageData) VALUES(?, ?, ?)", 6)) {
                            psIns.setLong(1, deezerAlbumId);
                            psIns.setString(2, type);
                            psIns.setBytes(3, data);
                            psIns.executeUpdate();
                        } catch (SQLException e) {
                            String msg = Optional.ofNullable(e.getMessage()).orElse("").toLowerCase();
                            if (msg.contains("unique") || msg.contains("constraint")) {
                                // ignore duplicates
                                System.out.println("AlbumDaoImpl.upsertFromMeta: duplicate album image ignored for albumId=" + deezerAlbumId + " type=" + type);
                            } else {
                                throw e;
                            }
                        }
                    }
                }
            }

            // 5) link artists -> use ArtistDaoImpl(conn) to look up IDs and create AlbumArtist (within the same transaction)
            ArtistDao artistDao = new ArtistDaoImpl(conn);
            List<String> albumArtistNames = meta.getAlbumArtistNames();
            if (albumArtistNames != null && !albumArtistNames.isEmpty()) {
                for (String an : albumArtistNames) {
                    if (an == null || an.isBlank()) continue;
                    if (ArtistIdentity.isVariousArtists(an)) continue;
                    Long artId = artistDao.findIdByName(an);
                    if (artId != null && artId > 0) {
                        try (PreparedStatement link = prepareStatementWithRetry(conn, "INSERT OR IGNORE INTO AlbumArtist(AlbumID, ArtistID) VALUES(?, ?)", 6)) {
                            link.setLong(1, deezerAlbumId);
                            link.setLong(2, artId);
                            link.executeUpdate();
                        }
                    } else {

                        try (PreparedStatement insA = prepareStatementWithRetry(conn, "INSERT OR IGNORE INTO Artist(Name, Biography) VALUES(?, NULL)", 6)) {

                            insA.setString(1, an);
                            try {
                                insA.executeUpdate();
                            } catch (SQLException ignored) {

                            }
                        }
                        // search for newly created ID
                        long newId = 0L;
                        try (PreparedStatement psFind = prepareStatementWithRetry(conn, "SELECT ArtistID FROM Artist WHERE Name = ? LIMIT 1", 6)) {
                            psFind.setString(1, an);
                            try (ResultSet rsf = psFind.executeQuery()) {
                                if (rsf.next()) newId = rsf.getLong("ArtistID");
                            }
                        }
                        if (newId > 0) {
                            try (PreparedStatement link2 = prepareStatementWithRetry(conn, "INSERT OR IGNORE INTO AlbumArtist(AlbumID, ArtistID) VALUES(?, ?)", 6)) {
                                link2.setLong(1, deezerAlbumId);
                                link2.setLong(2, newId);
                                link2.executeUpdate();
                            }
                        }
                    }
                }
            }

            // finished successfully (no commit here; caller controls tx)
            return;
        } catch (SQLException ex) {
            // rethrow to let caller handle rollback
            throw ex;
        } catch (Throwable t) {
            throw new SQLException("Unexpected error in upsertFromMeta", t);
        }
    }

    @Override
    public void upsertAll(List<DeezerApiMetaData> metas,
                          GenreDao genreDao,
                          ArtistDao artistDao) throws SQLException {
        for (DeezerApiMetaData meta : metas) {
            long deezerAlbumId = meta.getAlbumId();
            String albName     = meta.getAlbumName();
            String genName     = meta.getGenre();
            if (albName == null || albName.isBlank() || genName == null || genName.isBlank()) continue;

            Integer genreId = genreDao.findIdByName(genName);
            if (genreId == null) continue;

            Long existingId = findIdByName(albName);
            long persistedAlbumId;
            if (existingId != null) {
                update(new Album(
                        existingId,
                        albName,
                        Collections.emptyList(),
                        new Genre(genreId,null),
                        meta.getRecordType(),
                        meta.getAlbumReleaseDate(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        meta.getNumberOfTracks()
                ));
                persistedAlbumId = existingId;
            } else {
                String sql =
                        """
                INSERT INTO Album(
                    AlbumID, GenreID, Name, RecordType, ReleaseDate, NumberOfTracks
                ) VALUES(?, ?, ?, ?, ?, ?)
                """;
                synchronized (DB_WRITE_LOCK) {
                    try (Connection conn = openConnection();
                         PreparedStatement ps = prepareStatementWithRetry(conn, sql, 6)) {
                        ps.setLong(1, deezerAlbumId);
                        ps.setInt(2, genreId);
                        ps.setString(3, albName);
                        ps.setString(4, meta.getRecordType());
                        if (meta.getAlbumReleaseDate() != null && !meta.getAlbumReleaseDate().isBlank()) {
                            ps.setString(5, meta.getAlbumReleaseDate());
                        } else {
                            ps.setNull(5, Types.VARCHAR);
                        }
                        ps.setInt(6, meta.getNumberOfTracks());
                        ps.executeUpdate();
                    }
                }
                persistedAlbumId = deezerAlbumId;
            }

            List<byte[]> covers = meta.getAlbumCoverBytesList();
            if (covers != null && !covers.isEmpty()) {
                insertImages(persistedAlbumId, covers);
            }

            for (String artName : meta.getAlbumArtistNames()) {
                Long artId = artistDao.findIdByName(artName);
                if (artId != null) linkArtist(persistedAlbumId, artId);
            }
        }
    }
}
