package io.github.guillermodubon.musicplayer.repository.dao.genre;

import io.github.guillermodubon.musicplayer.repository.DbConnectionManager;
import io.github.guillermodubon.musicplayer.models.DeezerApiMetaData;
import io.github.guillermodubon.musicplayer.models.Genre;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class GenreDaoImpl implements GenreDao {

    private static final Object DB_WRITE_LOCK = new Object();
    private final DbConnectionManager mgr = DbConnectionManager.getInstance();
    private final Connection sharedConn;

    public GenreDaoImpl(Connection connection) {
        this.sharedConn = connection;
    }

    private boolean hasSharedConn() {
        return this.sharedConn != null;
    }


    private void ensurePragmas(Connection conn) {
        if (conn == null) return;
        try (Statement st = conn.createStatement()) {
            try { st.execute("PRAGMA busy_timeout = 5000"); } catch (Exception ignored) {}
        } catch (SQLException e) {
            System.out.println("GenreDaoImpl: warning setting pragmas -> " + Optional.ofNullable(e.getMessage()).orElse("null"));
        }
    }

    private PreparedStatement prepareStatementWithRetry(Connection conn, String sql, int maxAttempts) throws SQLException {
        int attempt = 0;
        long baseSleep = 120;
        while (true) {
            attempt++;
            try {
                return conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            } catch (SQLException ex) {
                String msg = Optional.ofNullable(ex.getMessage()).orElse("").toLowerCase();
                boolean locked = msg.contains("database is locked") || msg.contains("busy");
                try {
                    if (!locked && ex.getClass().getName().contains("sqlite")) {
                        try {
                            Integer ec = (Integer) ex.getClass().getMethod("getErrorCode").invoke(ex);
                            if (ec != null && ec == 5) locked = true;
                        } catch (Exception ignore) {}
                    }
                } catch (Exception ignore) {}
                if (!locked || attempt >= maxAttempts) throw ex;
                try {
                    long sleep = Math.min(baseSleep * (1L << (attempt - 1)), 5000);
                    Thread.sleep(sleep);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Interrupted while waiting to retry DB operation", ie);
                }
            }
        }
    }

    // helper that uses a provided connection (avoids nested open/close when calling from batch ops)
    private int findIdByName(Connection conn, String name) throws SQLException {
        String sql = "SELECT GenreID FROM Genre WHERE Name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("GenreID") : 0;
            }
        }
    }

    private boolean existsById(Connection conn, int id) throws SQLException {
        String sql = "SELECT 1 FROM Genre WHERE GenreID = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }


    @Override
    public Optional<Genre> findById(Integer id) throws SQLException {
        System.out.println("GenreDaoImpl.findById: id=" + id);
        String sql = "SELECT GenreID, Name FROM Genre WHERE GenreID = ?";
        if (hasSharedConn()) {
            ensurePragmas(sharedConn);
            try (PreparedStatement ps = sharedConn.prepareStatement(sql)) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return Optional.of(new Genre(rs.getInt("GenreID"), rs.getString("Name")));
                }
            }
            return Optional.empty();
        } else {
            try (Connection conn = mgr.openConnection();
                 PreparedStatement ps = prepareStatementWithRetry(conn, sql, 6)) {
                ensurePragmas(conn);
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return Optional.of(new Genre(rs.getInt("GenreID"), rs.getString("Name")));
                }
            }
            return Optional.empty();
        }
    }

    @Override
    public List<Genre> findAll() throws SQLException {
        System.out.println("GenreDaoImpl.findAll: start");
        String sql = "SELECT GenreID, Name FROM Genre";
        List<Genre> list = new ArrayList<>();
        if (hasSharedConn()) {
            ensurePragmas(sharedConn);
            try (Statement stmt = sharedConn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) list.add(new Genre(rs.getInt("GenreID"), rs.getString("Name")));
            }
        } else {
            try (Connection conn = mgr.openConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                ensurePragmas(conn);
                while (rs.next()) list.add(new Genre(rs.getInt("GenreID"), rs.getString("Name")));
            }
        }
        System.out.println("GenreDaoImpl.findAll: found=" + list.size());
        return list;
    }

    @Override
    public void insert(Genre genre) throws SQLException {
        System.out.println("GenreDaoImpl.insert: name='" + genre.getName() + "' id=" + genre.getGenreID());
        // if genre has explicit ID, insert with it; otherwise insert name-only and read generated key.
        String sqlWithId = "INSERT OR IGNORE INTO Genre(GenreID, Name) VALUES(?, ?)";
        String sqlNoId = "INSERT OR IGNORE INTO Genre(Name) VALUES(?)";

        if (hasSharedConn()) {
            synchronized (DB_WRITE_LOCK) {
                ensurePragmas(sharedConn);
                if (genre.getGenreID() > 0) {
                    try (PreparedStatement ps = sharedConn.prepareStatement(sqlWithId)) {
                        ps.setInt(1, genre.getGenreID());
                        ps.setString(2, genre.getName());
                        ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = sharedConn.prepareStatement(sqlNoId, Statement.RETURN_GENERATED_KEYS)) {
                        ps.setString(1, genre.getName());
                        ps.executeUpdate();
                        try (ResultSet rs = ps.getGeneratedKeys()) {
                            if (rs.next()) genre.setGenreID(rs.getInt(1));
                        }
                    }
                }
            }
        } else {
            synchronized (DB_WRITE_LOCK) {
                try (Connection conn = mgr.openConnection()) {
                    ensurePragmas(conn);
                    conn.setAutoCommit(false);
                    try {
                        if (genre.getGenreID() > 0) {
                            try (PreparedStatement ps = prepareStatementWithRetry(conn, sqlWithId, 6)) {
                                ps.setInt(1, genre.getGenreID());
                                ps.setString(2, genre.getName());
                                ps.executeUpdate();
                            }
                        } else {
                            try (PreparedStatement ps = prepareStatementWithRetry(conn, sqlNoId, 6)) {
                                ps.setString(1, genre.getName());
                                ps.executeUpdate();
                                try (ResultSet rs = ps.getGeneratedKeys()) {
                                    if (rs.next()) genre.setGenreID(rs.getInt(1));
                                }
                            }
                        }
                        conn.commit();
                    } catch (SQLException e) {
                        try { conn.rollback(); } catch (SQLException ignore) {}
                        throw e;
                    } finally {
                        try { conn.setAutoCommit(true); } catch (SQLException ignore) {}
                    }
                }
            }
        }
    }

    @Override
    public void update(Genre entity) throws SQLException {
        System.out.println("GenreDaoImpl.update: id=" + entity.getGenreID());
        String sql = "UPDATE Genre SET Name = ? WHERE GenreID = ?";
        if (hasSharedConn()) {
            synchronized (DB_WRITE_LOCK) {
                ensurePragmas(sharedConn);
                try (PreparedStatement ps = sharedConn.prepareStatement(sql)) {
                    ps.setString(1, entity.getName());
                    ps.setInt(2, entity.getGenreID());
                    ps.executeUpdate();
                }
            }
        } else {
            synchronized (DB_WRITE_LOCK) {
                try (Connection conn = mgr.openConnection()) {
                    ensurePragmas(conn);
                    conn.setAutoCommit(false);
                    try (PreparedStatement ps = prepareStatementWithRetry(conn, sql, 6)) {
                        ps.setString(1, entity.getName());
                        ps.setInt(2, entity.getGenreID());
                        ps.executeUpdate();
                    }
                    conn.commit();
                }
            }
        }
    }

    @Override
    public void delete(Integer id) throws SQLException {
        System.out.println("GenreDaoImpl.delete: id=" + id);
        String sql = "DELETE FROM Genre WHERE GenreID = ?";
        if (hasSharedConn()) {
            synchronized (DB_WRITE_LOCK) {
                ensurePragmas(sharedConn);
                try (PreparedStatement ps = sharedConn.prepareStatement(sql)) {
                    ps.setInt(1, id);
                    ps.executeUpdate();
                }
            }
        } else {
            synchronized (DB_WRITE_LOCK) {
                try (Connection conn = mgr.openConnection()) {
                    ensurePragmas(conn);
                    conn.setAutoCommit(false);
                    try (PreparedStatement ps = prepareStatementWithRetry(conn, sql, 6)) {
                        ps.setInt(1, id);
                        ps.executeUpdate();
                    }
                    conn.commit();
                }
            }
        }
    }

    @Override
    public int findIdByName(String name) throws SQLException {
        System.out.println("GenreDaoImpl.findIdByName: name='" + name + "'");
        if (hasSharedConn()) {
            return findIdByName(sharedConn, name);
        } else {
            try (Connection conn = mgr.openConnection()) {
                ensurePragmas(conn);
                return findIdByName(conn, name);
            }
        }
    }

    @Override
    public int create(String name) throws SQLException {
        System.out.println("GenreDaoImpl.create: name='" + name + "'");
        String sql = "INSERT INTO Genre(Name) VALUES(?)";
        if (hasSharedConn()) {
            synchronized (DB_WRITE_LOCK) {
                ensurePragmas(sharedConn);
                try (PreparedStatement ps = sharedConn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, name);
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (rs.next()) return rs.getInt(1);
                    }
                }
            }
            return 0;
        } else {
            synchronized (DB_WRITE_LOCK) {
                try (Connection conn = mgr.openConnection()) {
                    ensurePragmas(conn);
                    conn.setAutoCommit(false);
                    try (PreparedStatement ps = prepareStatementWithRetry(conn, sql, 6)) {
                        ps.setString(1, name);
                        ps.executeUpdate();
                        try (ResultSet rs = ps.getGeneratedKeys()) {
                            if (rs.next()) return rs.getInt(1);
                        }
                    }
                    conn.commit();
                }
            }
            return 0;
        }
    }

    /**
     * Upsert genres from Deezer metas.
     * If a meta exposes a numeric genre id (via reflection), insert using that explicit GenreID.
     * Otherwise fall back to insert-by-name (as before).
     *
     * Logic:
     *  - If meta has genreId > 0:
     *      * If a row with that GenreID exists -> optionally update Name if different
     *      * Else if a row with the same Name exists but different id -> try to update its GenreID to the Deezer id
     *         only if the Deezer id is not already used by another row.
     *      * Else insert with explicit GenreID.
     *  - If meta has no numeric id -> insert by name if missing.
     */
    @Override
    public void upsertAll(List<DeezerApiMetaData> metas) throws SQLException {
        System.out.println("GenreDaoImpl.upsertAll: metas=" + (metas == null ? 0 : metas.size()));
        if (metas == null || metas.isEmpty()) return;

        String insertWithIdSql = "INSERT OR IGNORE INTO Genre(GenreID, Name) VALUES(?, ?)";
        String insertNameOnlySql = "INSERT OR IGNORE INTO Genre(Name) VALUES(?)";

        if (hasSharedConn()) {
            synchronized (DB_WRITE_LOCK) {
                ensurePragmas(sharedConn);
                boolean prevAuto = sharedConn.getAutoCommit();
                try {
                    if (prevAuto) sharedConn.setAutoCommit(false);
                    try (PreparedStatement psWithId = sharedConn.prepareStatement(insertWithIdSql);
                         PreparedStatement psNameOnly = sharedConn.prepareStatement(insertNameOnlySql)) {

                        for (DeezerApiMetaData meta : metas) {
                            if (meta == null) continue;
                            String genreName = meta.getGenre();
                            if (genreName == null || genreName.isBlank()) continue;

                            int gid = meta.getAlbumGenreId();
                            if (gid > 0) {
                                // if the row by name does not exist and the ID does not exist either -> insert with ID
                                if (findIdByName(sharedConn, genreName) == 0 && !existsById(sharedConn, gid)) {
                                    psWithId.setInt(1, gid);
                                    psWithId.setString(2, genreName);
                                    psWithId.executeUpdate();
                                } else if (findIdByName(sharedConn, genreName) == 0 && existsById(sharedConn, gid)) {
                                    // a row with the ID exists but has a different name: update name if empty or different
                                    try (PreparedStatement up = sharedConn.prepareStatement("UPDATE Genre SET Name = ? WHERE GenreID = ?")) {
                                        up.setString(1, genreName);
                                        up.setInt(2, gid);
                                        up.executeUpdate();
                                    }
                                }
                                // if findIdByName != 0, leave the row as is (due to existing name)
                            } else {
                                // no ID available -> insert by name if it doesn't exist
                                if (findIdByName(sharedConn, genreName) == 0) {
                                    psNameOnly.setString(1, genreName);
                                    psNameOnly.executeUpdate();
                                }
                            }
                        }
                    }
                    sharedConn.commit();
                } catch (SQLException e) {
                    try { sharedConn.rollback(); } catch (SQLException ignore) {}
                    throw e;
                } finally {
                    try { sharedConn.setAutoCommit(prevAuto); } catch (SQLException ignore) {}
                }
            }
        } else {
            synchronized (DB_WRITE_LOCK) {
                try (Connection conn = mgr.openConnection()) {
                    ensurePragmas(conn);
                    conn.setAutoCommit(false);
                    try (PreparedStatement psWithId = prepareStatementWithRetry(conn, insertWithIdSql, 6);
                         PreparedStatement psNameOnly = prepareStatementWithRetry(conn, insertNameOnlySql, 6)) {

                        for (DeezerApiMetaData meta : metas) {
                            if (meta == null) continue;
                            String genreName = meta.getGenre();
                            if (genreName == null || genreName.isBlank()) continue;

                            int gid = meta.getAlbumGenreId();
                            if (gid > 0) {
                                if (findIdByName(conn, genreName) == 0 && !existsById(conn, gid)) {
                                    psWithId.setInt(1, gid);
                                    psWithId.setString(2, genreName);
                                    psWithId.executeUpdate();
                                } else if (findIdByName(conn, genreName) == 0 && existsById(conn, gid)) {
                                    try (PreparedStatement up = conn.prepareStatement("UPDATE Genre SET Name = ? WHERE GenreID = ?")) {
                                        up.setString(1, genreName);
                                        up.setInt(2, gid);
                                        up.executeUpdate();
                                    }
                                }
                            } else {
                                if (findIdByName(conn, genreName) == 0) {
                                    psNameOnly.setString(1, genreName);
                                    psNameOnly.executeUpdate();
                                }
                            }
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
    public void deleteWithoutAlbums() throws SQLException {
        System.out.println("GenreDaoImpl.deleteWithoutAlbums: start");
        String sql = "DELETE FROM Genre WHERE GenreID NOT IN (SELECT DISTINCT GenreID FROM Album)";
        if (hasSharedConn()) {
            synchronized (DB_WRITE_LOCK) {
                ensurePragmas(sharedConn);
                try (PreparedStatement ps = sharedConn.prepareStatement(sql)) {
                    ps.executeUpdate();
                }
            }
        } else {
            synchronized (DB_WRITE_LOCK) {
                try (Connection conn = mgr.openConnection()) {
                    ensurePragmas(conn);
                    conn.setAutoCommit(false);
                    try (PreparedStatement ps = prepareStatementWithRetry(conn, sql, 6)) {
                        ps.executeUpdate();
                    }
                    conn.commit();
                }
            }
        }
    }

}
