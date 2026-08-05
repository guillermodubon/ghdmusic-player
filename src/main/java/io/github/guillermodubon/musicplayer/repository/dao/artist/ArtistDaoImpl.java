package io.github.guillermodubon.musicplayer.repository.dao.artist;

import io.github.guillermodubon.musicplayer.repository.dao.support.JdbcDaoOperations;
import io.github.guillermodubon.musicplayer.repository.dao.support.JdbcDaoSupport;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.DeezerApiMetaData;
import io.github.guillermodubon.musicplayer.utils.ArtistIdentity;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ArtistDaoImpl extends JdbcDaoSupport implements ArtistDao {

    private static final Object DB_WRITE_LOCK = new Object();

    public ArtistDaoImpl(Connection connection) {
        super(connection);
    }


    @Override
    public Optional<Artist> findById(Long id) throws SQLException {
        String sql = "SELECT ArtistID, Name, Biography FROM Artist WHERE ArtistID = ? "
                + "AND lower(trim(Name)) NOT IN ('varios artistas', 'various artists')";
        System.out.println("ArtistDaoImpl.findById: id=" + id + " thread=" + Thread.currentThread().getName());

        if (hasSharedConnection()) {
            try (PreparedStatement ps = prepareStatementWithRetry(sharedConnection(), sql, 6)) {
                configureConnection(sharedConnection());
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Artist a = new Artist(rs.getLong("ArtistID"), rs.getString("Name"), rs.getString("Biography"), new ArrayList<>());
                        return Optional.of(a);
                    }
                }
            }
            return Optional.empty();
        } else {
            try (Connection conn = openConnection();
                 PreparedStatement ps = prepareStatementWithRetry(conn, sql, 6)) {
                configureConnection(conn);
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Artist a = new Artist(rs.getLong("ArtistID"), rs.getString("Name"), rs.getString("Biography"), new ArrayList<>());
                        return Optional.of(a);
                    }
                }
            }
            return Optional.empty();
        }
    }

    @Override
    public List<Artist> findAll() throws SQLException {
        String sql = "SELECT ArtistID, Name, Biography FROM Artist "
                + "WHERE lower(trim(Name)) NOT IN ('varios artistas', 'various artists')";
        List<Artist> list = new ArrayList<>();
        System.out.println("ArtistDaoImpl.findAll: thread=" + Thread.currentThread().getName());

        if (hasSharedConnection()) {
            configureConnection(sharedConnection());
            try (Statement stmt = sharedConnection().createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    list.add(new Artist(rs.getLong("ArtistID"), rs.getString("Name"), rs.getString("Biography"), new ArrayList<>()));
                }
            }
        } else {
            try (Connection conn = openConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                configureConnection(conn);
                while (rs.next()) {
                    list.add(new Artist(rs.getLong("ArtistID"), rs.getString("Name"), rs.getString("Biography"), new ArrayList<>()));
                }
            }
        }
        return list;
    }

    @Override
    public void insert(Artist artist) throws SQLException {
        if (artist == null || ArtistIdentity.isVariousArtists(artist)) return;
        String sql = "INSERT INTO Artist(Name, Biography) VALUES(?, ?)";
        System.out.println("ArtistDaoImpl.insert: name=" + artist.getName() + " thread=" + Thread.currentThread().getName());

        if (hasSharedConnection()) {
            synchronized (DB_WRITE_LOCK) {
                try (PreparedStatement ps = prepareStatementWithRetry(sharedConnection(), sql, 6)) {
                    configureConnection(sharedConnection());
                    ps.setString(1, artist.getName());
                    ps.setString(2, artist.getBiography());
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (rs.next()) artist.setArtistID(rs.getLong(1));
                    }
                }
            }
        } else {
            synchronized (DB_WRITE_LOCK) {
                try (Connection conn = openConnection()) {
                    configureConnection(conn);
                    conn.setAutoCommit(false);
                    try (PreparedStatement ps = prepareStatementWithRetry(conn, sql, 6)) {
                        ps.setString(1, artist.getName());
                        ps.setString(2, artist.getBiography());
                        ps.executeUpdate();
                        try (ResultSet rs = ps.getGeneratedKeys()) {
                            if (rs.next()) artist.setArtistID(rs.getLong(1));
                        }
                    }
                    conn.commit();
                } catch (SQLException e) {
                    throw e;
                }
            }
        }
    }

    @Override
    public void update(Artist entity) throws SQLException {
        String sql = "UPDATE Artist SET Name = ?, Biography = ? WHERE ArtistID = ?";
        System.out.println("ArtistDaoImpl.update: id=" + entity.getArtistID() + " thread=" + Thread.currentThread().getName());

        if (hasSharedConnection()) {
            synchronized (DB_WRITE_LOCK) {
                try (PreparedStatement ps = prepareStatementWithRetry(sharedConnection(), sql, 6)) {
                    configureConnection(sharedConnection());
                    ps.setString(1, entity.getName());
                    ps.setString(2, entity.getBiography());
                    ps.setLong(3, entity.getArtistID());
                    ps.executeUpdate();
                }
            }
        } else {
            synchronized (DB_WRITE_LOCK) {
                try (Connection conn = openConnection()) {
                    configureConnection(conn);
                    conn.setAutoCommit(false);
                    try (PreparedStatement ps = prepareStatementWithRetry(conn, sql, 6)) {
                        ps.setString(1, entity.getName());
                        ps.setString(2, entity.getBiography());
                        ps.setLong(3, entity.getArtistID());
                        ps.executeUpdate();
                    }
                    conn.commit();
                }
            }
        }
    }

    @Override
    public void delete(Long id) throws SQLException {
        String sql = "DELETE FROM Artist WHERE ArtistID = ?";
        System.out.println("ArtistDaoImpl.delete: id=" + id + " thread=" + Thread.currentThread().getName());

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
                try (Connection conn = openConnection()) {
                    configureConnection(conn);
                    conn.setAutoCommit(false);
                    try (PreparedStatement ps = prepareStatementWithRetry(conn, sql, 6)) {
                        ps.setLong(1, id);
                        ps.executeUpdate();
                    }
                    conn.commit();
                }
            }
        }
    }

    @Override
    public Long findIdByName(String name) throws SQLException {
        String sql = "SELECT ArtistID FROM Artist WHERE Name = ? "
                + "AND lower(trim(Name)) NOT IN ('varios artistas', 'various artists')";
        System.out.println("ArtistDaoImpl.findIdByName: name=" + name + " thread=" + Thread.currentThread().getName());

        if (hasSharedConnection()) {
            try (PreparedStatement ps = prepareStatementWithRetry(sharedConnection(), sql, 6)) {
                configureConnection(sharedConnection());
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong("ArtistID") : null;
                }
            }
        } else {
            try (Connection conn = openConnection();
                 PreparedStatement ps = prepareStatementWithRetry(conn, sql, 6)) {
                configureConnection(conn);
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong("ArtistID") : null;
                }
            }
        }
    }

    @Override
    public void insertImage(long artistId, String type, byte[] data) throws SQLException {
        String sql = "INSERT INTO ArtistImage(ArtistID, ImageType, ImageData) VALUES(?, ?, ?)";
        System.out.println("ArtistDaoImpl.insertImage: artistId=" + artistId + " type=" + type + " thread=" + Thread.currentThread().getName());

        if (hasSharedConnection()) {
            synchronized (DB_WRITE_LOCK) {
                try (PreparedStatement ps = prepareStatementWithRetry(sharedConnection(), sql, 6)) {
                    configureConnection(sharedConnection());
                    ps.setLong(1, artistId);
                    ps.setString(2, type);
                    ps.setBytes(3, data);
                    ps.executeUpdate();
                }
            }
        } else {
            synchronized (DB_WRITE_LOCK) {
                try (Connection conn = openConnection()) {
                    configureConnection(conn);
                    conn.setAutoCommit(false);
                    try (PreparedStatement ps = prepareStatementWithRetry(conn, sql, 6)) {
                        ps.setLong(1, artistId);
                        ps.setString(2, type);
                        ps.setBytes(3, data);
                        ps.executeUpdate();
                    }
                    conn.commit();
                }
            }
        }
    }

    @Override
    public boolean imageExists(long artistId, String type) throws SQLException {
        String sql = "SELECT 1 FROM ArtistImage WHERE ArtistID = ? AND ImageType = ?";
        System.out.println("ArtistDaoImpl.imageExists: artistId=" + artistId + " type=" + type + " thread=" + Thread.currentThread().getName());

        if (hasSharedConnection()) {
            try (PreparedStatement ps = prepareStatementWithRetry(sharedConnection(), sql, 6)) {
                configureConnection(sharedConnection());
                ps.setLong(1, artistId);
                ps.setString(2, type);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        } else {
            try (Connection conn = openConnection();
                 PreparedStatement ps = prepareStatementWithRetry(conn, sql, 6)) {
                configureConnection(conn);
                ps.setLong(1, artistId);
                ps.setString(2, type);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        }
    }

    @Override
    public void insertArtistsAndImages(List<DeezerApiMetaData> metas) throws SQLException {
        if (metas == null || metas.isEmpty()) return;
        String sql = "INSERT OR IGNORE INTO Artist(ArtistID, Name, Biography) VALUES(?, ?, NULL)";
        System.out.println("ArtistDaoImpl.insertArtistsAndImages: start metaCount=" + metas.size());

        // If we have a sharedConnection(), use it and assume transaction management is external.
        if (hasSharedConnection()) {
            synchronized (DB_WRITE_LOCK) {
                configureConnection(sharedConnection());
                try (PreparedStatement ps = prepareStatementWithRetry(sharedConnection(), sql, 6)) {
                    for (DeezerApiMetaData m : metas) {
                        List<Long> albumIds = m.getAlbumArtistIds();
                        List<String> albumNames = m.getAlbumArtistNames();
                        List<List<byte[]>> albumPortraits = m.getAlbumArtistsPortraitBytes();
                        for (int i = 0; i < albumIds.size(); i++) {
                            long apiId = albumIds.get(i);
                            String name = albumNames.get(i);
                            if (ArtistIdentity.isVariousArtists(name)) continue;
                            try {
                                ps.setLong(1, apiId);
                                ps.setString(2, name);
                                ps.executeUpdate();
                            } catch (SQLException ex) {
                                String msg = Optional.ofNullable(ex.getMessage()).orElse("").toLowerCase();
                                System.out.println("ArtistDaoImpl.insertArtistsAndImages: insert artist failed -> " + msg);
                                System.out.println("SQLState=" + ex.getSQLState() + " code=" + ex.getErrorCode());
                                if (!msg.contains("unique") && !msg.contains("constraint") && !msg.contains("busy") && !msg.contains("locked"))
                                    throw ex;
                            }
                            List<byte[]> portraits = (albumPortraits != null && i < albumPortraits.size()) ? albumPortraits.get(i) : null;
                            long persistedArtistId = resolvePersistedArtistId(sharedConnection(), name);
                            if (persistedArtistId > 0) insertArtistImageBlobs(sharedConnection(), persistedArtistId, portraits);
                        }

                        List<Long> contribIds = m.getSongContributorIds();
                        List<String> contribNames = m.getSongContributorNames();
                        List<List<byte[]>> contribPortraits = m.getSongContributorsPortraitBytes();
                        for (int i = 0; i < contribIds.size(); i++) {
                            long apiId = contribIds.get(i);
                            String name = contribNames.get(i);
                            if (ArtistIdentity.isVariousArtists(name)) continue;
                            try {
                                ps.setLong(1, apiId);
                                ps.setString(2, name);
                                ps.executeUpdate();
                            } catch (SQLException ex) {
                                String msg = Optional.ofNullable(ex.getMessage()).orElse("").toLowerCase();
                                System.out.println("ArtistDaoImpl.insertArtistsAndImages: insert contributor failed -> " + msg);
                                System.out.println("SQLState=" + ex.getSQLState() + " code=" + ex.getErrorCode());
                                if (!msg.contains("unique") && !msg.contains("constraint") && !msg.contains("busy") && !msg.contains("locked"))
                                    throw ex;
                            }
                            List<byte[]> portraits = (contribPortraits != null && i < contribPortraits.size()) ? contribPortraits.get(i) : null;
                            long persistedArtistId = resolvePersistedArtistId(sharedConnection(), name);
                            if (persistedArtistId > 0) insertArtistImageBlobs(sharedConnection(), persistedArtistId, portraits);
                        }
                    }
                }
            }
        } else {
            // No sharedConnection(): manage transaction locally and short-lived connection
            synchronized (DB_WRITE_LOCK) {
                try (Connection conn = openConnection()) {
                    configureConnection(conn);
                    conn.setAutoCommit(false);
                    try (PreparedStatement ps = prepareStatementWithRetry(conn, sql, 6)) {
                        for (DeezerApiMetaData m : metas) {
                            List<Long> albumIds = m.getAlbumArtistIds();
                            List<String> albumNames = m.getAlbumArtistNames();
                            List<List<byte[]>> albumPortraits = m.getAlbumArtistsPortraitBytes();
                            for (int i = 0; i < albumIds.size(); i++) {
                                long apiId = albumIds.get(i);
                                String name = albumNames.get(i);
                                if (ArtistIdentity.isVariousArtists(name)) continue;
                                try {
                                    ps.setLong(1, apiId);
                                    ps.setString(2, name);
                                    ps.executeUpdate();
                                } catch (SQLException ex) {
                                    String msg = Optional.ofNullable(ex.getMessage()).orElse("").toLowerCase();
                                    System.out.println("ArtistDaoImpl.insertArtistsAndImages: insert artist failed -> " + msg);
                                    System.out.println("SQLState=" + ex.getSQLState() + " code=" + ex.getErrorCode());
                                    if (!msg.contains("unique") && !msg.contains("constraint") && !msg.contains("busy") && !msg.contains("locked"))
                                        throw ex;
                                }
                                List<byte[]> portraits = (albumPortraits != null && i < albumPortraits.size()) ? albumPortraits.get(i) : null;
                                long persistedArtistId = resolvePersistedArtistId(conn, name);
                                if (persistedArtistId > 0) insertArtistImageBlobs(conn, persistedArtistId, portraits);
                            }

                            List<Long> contribIds = m.getSongContributorIds();
                            List<String> contribNames = m.getSongContributorNames();
                            List<List<byte[]>> contribPortraits = m.getSongContributorsPortraitBytes();
                            for (int i = 0; i < contribIds.size(); i++) {
                                long apiId = contribIds.get(i);
                                String name = contribNames.get(i);
                                if (ArtistIdentity.isVariousArtists(name)) continue;
                                try {
                                    ps.setLong(1, apiId);
                                    ps.setString(2, name);
                                    ps.executeUpdate();
                                } catch (SQLException ex) {
                                    String msg = Optional.ofNullable(ex.getMessage()).orElse("").toLowerCase();
                                    System.out.println("ArtistDaoImpl.insertArtistsAndImages: insert contributor failed -> " + msg);
                                    System.out.println("SQLState=" + ex.getSQLState() + " code=" + ex.getErrorCode());
                                    if (!msg.contains("unique") && !msg.contains("constraint") && !msg.contains("busy") && !msg.contains("locked"))
                                        throw ex;
                                }
                                List<byte[]> portraits = (contribPortraits != null && i < contribPortraits.size()) ? contribPortraits.get(i) : null;
                                long persistedArtistId = resolvePersistedArtistId(conn, name);
                                if (persistedArtistId > 0) insertArtistImageBlobs(conn, persistedArtistId, portraits);
                            }
                        }
                        conn.commit();
                    } catch (SQLException e) {
                        try {
                            conn.rollback();
                        } catch (SQLException ignore) {
                        }
                        throw e;
                    } finally {
                        try {
                            conn.setAutoCommit(true);
                        } catch (SQLException ignore) {
                        }
                    }
                }
            }
        }
    }

    private static long resolvePersistedArtistId(Connection conn, String name) throws SQLException {
        if (conn == null || name == null || name.isBlank()) return 0L;
        try (PreparedStatement statement = conn.prepareStatement(
                "SELECT ArtistID FROM Artist WHERE Name = ? LIMIT 1"
        )) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        }
    }

    @Override
    public void updateBiography(long artistId, String biography) throws SQLException {
        String sql = "UPDATE Artist SET Biography = ? WHERE ArtistID = ?";
        System.out.println("ArtistDaoImpl.updateBiography: artistId=" + artistId + " thread=" + Thread.currentThread().getName());

        if (hasSharedConnection()) {
            synchronized (DB_WRITE_LOCK) {
                try (PreparedStatement ps = prepareStatementWithRetry(sharedConnection(), sql, 6)) {
                    configureConnection(sharedConnection());
                    if (biography != null && !biography.isBlank()) ps.setString(1, biography);
                    else ps.setNull(1, Types.VARCHAR);
                    ps.setLong(2, artistId);
                    ps.executeUpdate();
                }
            }
        } else {
            synchronized (DB_WRITE_LOCK) {
                try (Connection conn = openConnection()) {
                    configureConnection(conn);
                    conn.setAutoCommit(false);
                    try (PreparedStatement ps = prepareStatementWithRetry(conn, sql, 6)) {
                        if (biography != null && !biography.isBlank()) ps.setString(1, biography);
                        else ps.setNull(1, Types.VARCHAR);
                        ps.setLong(2, artistId);
                        ps.executeUpdate();
                    }
                    conn.commit();
                }
            }
        }
    }

    @Override
    public void deleteArtistsWithoutAlbums() throws SQLException {
        String sql = "DELETE FROM Artist WHERE ArtistID NOT IN (SELECT DISTINCT ArtistID FROM AlbumArtist)";
        System.out.println("ArtistDaoImpl.deleteArtistsWithoutAlbums: thread=" + Thread.currentThread().getName());

        if (hasSharedConnection()) {
            synchronized (DB_WRITE_LOCK) {
                try (PreparedStatement ps = prepareStatementWithRetry(sharedConnection(), sql, 6)) {
                    configureConnection(sharedConnection());
                    ps.executeUpdate();
                }
            }
        } else {
            synchronized (DB_WRITE_LOCK) {
                try (Connection conn = openConnection()) {
                    configureConnection(conn);
                    conn.setAutoCommit(false);
                    try (PreparedStatement ps = prepareStatementWithRetry(conn, sql, 6)) {
                        ps.executeUpdate();
                    }
                    conn.commit();
                }
            }
        }
    }

    @Override
    public void deleteArtistsWithoutReferences() throws SQLException {
        String sql = """
                DELETE FROM Artist
                 WHERE ArtistID NOT IN (SELECT ArtistID FROM AlbumArtist)
                   AND ArtistID NOT IN (SELECT ArtistID FROM SongArtist)
                """;
        System.out.println("ArtistDaoImpl.deleteArtistsWithoutReferences: thread=" + Thread.currentThread().getName());

        if (hasSharedConnection()) {
            synchronized (DB_WRITE_LOCK) {
                try (PreparedStatement ps = prepareStatementWithRetry(sharedConnection(), sql, 6)) {
                    configureConnection(sharedConnection());
                    ps.executeUpdate();
                }
            }
        } else {
            synchronized (DB_WRITE_LOCK) {
                try (Connection conn = openConnection()) {
                    configureConnection(conn);
                    conn.setAutoCommit(false);
                    try (PreparedStatement ps = prepareStatementWithRetry(conn, sql, 6)) {
                        ps.executeUpdate();
                    }
                    conn.commit();
                }
            }
        }
    }

    /**
     * Inserts portrait BLOBs using the provided connection.
     * JdbcDaoOperations.insertBlobs is designed to use the Connection passed to it.
     */
    public void insertArtistImageBlobs(Connection conn, long artistId, List<byte[]> portraits) throws SQLException {
        if (portraits == null || portraits.isEmpty()) return;
        String sql = "INSERT INTO ArtistImage(ArtistID, ImageType, ImageData) VALUES(?, ?, ?)";
        String[] types = {"small", "medium", "big"};
        System.out.println("ArtistDaoImpl.insertArtistImageBlobs: artistId=" + artistId + " count=" + portraits.size());

        // conn may be sharedConnection() or a local connection; JdbcDaoOperations must use this conn
        JdbcDaoOperations.insertBlobs(
                conn,
                sql,
                artistId,
                portraits,
                types,
                (c, p) -> {
                    try {
                        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM ArtistImage WHERE ArtistID = ? AND ImageType = ?")) {
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

    @Override
    public List<Artist> findByAlbumId(long albumId) throws SQLException {
        String sql = "SELECT a.ArtistID, a.Name, a.Biography " +
                "FROM Artist a " +
                "INNER JOIN AlbumArtist aa ON a.ArtistID = aa.ArtistID " +
                "WHERE aa.AlbumID = ?";

        List<Artist> result = new ArrayList<>();
        System.out.println("ArtistDaoImpl.findByAlbumId: albumId=" + albumId + " thread=" + Thread.currentThread().getName());

        if (hasSharedConnection()) {
            try (PreparedStatement ps = prepareStatementWithRetry(sharedConnection(), sql, 6)) {
                configureConnection(sharedConnection());
                ps.setLong(1, albumId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long artistId = rs.getLong("ArtistID");
                        String name = rs.getString("Name");
                        String bio = rs.getString("Biography");
                        result.add(new Artist(artistId, name, bio, new ArrayList<>()));
                    }
                }
            }
        } else {
            try (Connection conn = openConnection();
                 PreparedStatement ps = prepareStatementWithRetry(conn, sql, 6)) {
                configureConnection(conn);
                ps.setLong(1, albumId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long artistId = rs.getLong("ArtistID");
                        String name = rs.getString("Name");
                        String bio = rs.getString("Biography");
                        result.add(new Artist(artistId, name, bio, new ArrayList<>()));
                    }
                }
            }
        }
        return result;
    }


    private void applyPragmas(Connection c) {
        try (Statement st = c.createStatement()) {
            try { st.execute("PRAGMA busy_timeout = 5000"); } catch (Exception ignored) {}
        } catch (SQLException e) {
            System.out.println("ArtistDaoImpl.applyPragmas: " + e.getMessage());
        }
    }

    @Override
    public void create(String name) throws SQLException {
        if (name == null) return;
        String n = name.strip();
        if (n.isEmpty()) return;
        if (ArtistIdentity.isVariousArtists(n)) return;

        String sql = "INSERT OR IGNORE INTO Artist(Name) VALUES(?)";
        Connection conn = null;
        boolean closeConn = false;
        try {
            if (sharedConnection() != null && !sharedConnection().isClosed()) {
                conn = sharedConnection();
            } else {
                conn = openConnection();
                closeConn = true;
                applyPragmas(conn);
            }

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, n);
                ps.executeUpdate();
            }
        } finally {
            if (closeConn && conn != null) {
                try { conn.close(); } catch (SQLException ignored) {}
            }
        }
    }
}

