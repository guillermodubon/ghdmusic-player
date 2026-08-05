package io.github.guillermodubon.musicplayer.repository.dao.history;

import io.github.guillermodubon.musicplayer.repository.DbConnectionManager;
import io.github.guillermodubon.musicplayer.models.PlaybackHistory;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;


public class PlaybackHistoryDaoImpl implements PlaybackHistoryDao {

    private static final Object DB_WRITE_LOCK = new Object();
    private static final int MAX_RETRY_ATTEMPTS = 6;

    private final DbConnectionManager mgr = DbConnectionManager.getInstance();
    private final Connection sharedConn;

    public PlaybackHistoryDaoImpl(Connection sharedConnection) {
        this.sharedConn = sharedConnection;
    }

    private Connection openConnection() throws SQLException {
        if (sharedConn != null && !sharedConn.isClosed()) {
            return sharedConn;
        }
        Connection c = mgr.openConnection();
        applyPragmas(c);
        return c;
    }

    private boolean shouldCloseConnection(Connection c) {
        return c != null && c != sharedConn;
    }

    private void applyPragmas(Connection c) {
        try (Statement st = c.createStatement()) {
            try { st.execute("PRAGMA busy_timeout = 5000"); } catch (Exception ignored) {}
        } catch (SQLException e) {
            System.out.println("PlaybackHistoryDaoImpl.applyPragmas: warning -> " + e.getMessage());
        }
    }

    private PreparedStatement prepareStatementWithRetry(Connection conn, String sql, int maxAttempts) throws SQLException {
        int attempt = 0;
        long baseSleep = 120L;
        while (true) {
            attempt++;
            try {
                return conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            } catch (SQLException ex) {
                String msg = Optional.ofNullable(ex.getMessage()).orElse("").toLowerCase();
                boolean locked = msg.contains("database is locked") || msg.contains("busy");
                if (!locked && ex.getClass().getName().toLowerCase().contains("sqlite")) {
                    try {
                        Integer ec = (Integer) ex.getClass().getMethod("getErrorCode").invoke(ex);
                        if (ec != null && ec == 5) locked = true;
                    } catch (Exception ignore) { }
                }
                if (!locked || attempt >= maxAttempts) throw ex;
                try {
                    long sleep = baseSleep * (1L << (attempt - 1));
                    Thread.sleep(Math.min(sleep, 5000L));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Interrupted while waiting to retry DB operation", ie);
                }
            }
        }
    }

    private PlaybackHistory mapRow(ResultSet rs) throws SQLException {
        long historyId = rs.getLong("HistoryID");
        long itemId = rs.getLong("ItemID");
        String itemType = rs.getString("ItemType");
        String name = rs.getString("Name");
        long playedAt = rs.getLong("PlayedAt");
        return new PlaybackHistory(historyId, itemId, itemType, name, playedAt);
    }

    @Override
    public Optional<PlaybackHistory> findById(Long k) throws SQLException {
        String sql = "SELECT HistoryID, ItemID, ItemType, Name, PlayedAt FROM PlaybackHistory WHERE HistoryID = ? LIMIT 1";
        Connection conn = null;
        boolean close = false;
        try {
            conn = openConnection();
            close = shouldCloseConnection(conn);
            try (PreparedStatement ps = prepareStatementWithRetry(conn, sql, MAX_RETRY_ATTEMPTS)) {
                ps.setLong(1, k);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    return Optional.of(mapRow(rs));
                }
            }
        } finally {
            if (close) try { conn.close(); } catch (SQLException ignore) {}
        }
    }

    @Override
    public List<PlaybackHistory> findAll() throws SQLException {
        String sql = "SELECT HistoryID, ItemID, ItemType, Name, PlayedAt FROM PlaybackHistory ORDER BY PlayedAt DESC";
        Connection conn = null;
        boolean close = false;
        List<PlaybackHistory> out = new ArrayList<>();
        try {
            conn = openConnection();
            close = shouldCloseConnection(conn);
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) out.add(mapRow(rs));
            }
            return out;
        } finally {
            if (close) try { conn.close(); } catch (SQLException ignore) {}
        }
    }

    @Override
    public void insert(PlaybackHistory entity) throws SQLException {
        // delegate to insertAndGetId to set returned id on entity
        Long generated = insertAndGetId(entity);
        if (generated != null) entity.setHistoryId(generated);
    }

    @Override
    public Long insertAndGetId(PlaybackHistory entry) throws SQLException {
        String sql = "INSERT INTO PlaybackHistory(ItemID, ItemType, Name, PlayedAt) VALUES (?, ?, ?, ?)";
        synchronized (DB_WRITE_LOCK) {
            Connection conn = null;
            boolean close = false;
            try {
                conn = openConnection();
                close = shouldCloseConnection(conn);
                boolean manageTx = (sharedConn == null);
                if (manageTx) conn.setAutoCommit(false);
                try (PreparedStatement ps = prepareStatementWithRetry(conn, sql, MAX_RETRY_ATTEMPTS)) {
                    ps.setLong(1, entry.getItemId());
                    ps.setString(2, entry.getItemType());
                    ps.setString(3, entry.getName());
                    ps.setLong(4, entry.getPlayedAt());
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (rs.next()) {
                            long id = rs.getLong(1);
                            if (manageTx) conn.commit();
                            entry.setHistoryId(id);
                            return id;
                        }
                    }
                    if (manageTx) conn.commit();
                    return null;
                } catch (SQLException ex) {
                    if (manageTx) try { conn.rollback(); } catch (SQLException ignore) {}
                    throw ex;
                } finally {
                    if (manageTx) try { conn.setAutoCommit(true); } catch (SQLException ignore) {}
                }
            } finally {
                if (close) try { conn.close(); } catch (SQLException ignore) {}
            }
        }
    }

    @Override
    public void update(PlaybackHistory entity) throws SQLException {
        String sql = "UPDATE PlaybackHistory SET ItemID = ?, ItemType = ?, Name = ?, PlayedAt = ? WHERE HistoryID = ?";
        synchronized (DB_WRITE_LOCK) {
            Connection conn = null;
            boolean close = false;
            try {
                conn = openConnection();
                close = shouldCloseConnection(conn);
                try (PreparedStatement ps = prepareStatementWithRetry(conn, sql, MAX_RETRY_ATTEMPTS)) {
                    ps.setLong(1, entity.getItemId());
                    ps.setString(2, entity.getItemType());
                    ps.setString(3, entity.getName());
                    ps.setLong(4, entity.getPlayedAt());
                    ps.setLong(5, entity.getHistoryId());
                    ps.executeUpdate();
                }
            } finally {
                if (close) try { conn.close(); } catch (SQLException ignore) {}
            }
        }
    }

    @Override
    public void delete(Long k) throws SQLException {
        String sql = "DELETE FROM PlaybackHistory WHERE HistoryID = ?";
        synchronized (DB_WRITE_LOCK) {
            Connection conn = null;
            boolean close = false;
            try {
                conn = openConnection();
                close = shouldCloseConnection(conn);
                try (PreparedStatement ps = prepareStatementWithRetry(conn, sql, MAX_RETRY_ATTEMPTS)) {
                    ps.setLong(1, k);
                    ps.executeUpdate();
                }
            } finally {
                if (close) try { conn.close(); } catch (SQLException ignore) {}
            }
        }
    }

    @Override
    public List<PlaybackHistory> findRecent(int limit) throws SQLException {
        if (limit <= 0) return Collections.emptyList();
        String sql = "SELECT HistoryID, ItemID, ItemType, Name, PlayedAt FROM PlaybackHistory ORDER BY PlayedAt DESC LIMIT ?";
        Connection conn = null;
        boolean close = false;
        List<PlaybackHistory> out = new ArrayList<>();
        try {
            conn = openConnection();
            close = shouldCloseConnection(conn);
            try (PreparedStatement ps = prepareStatementWithRetry(conn, sql, MAX_RETRY_ATTEMPTS)) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(mapRow(rs));
                }
            }
            return out;
        } finally {
            if (close) try { conn.close(); } catch (SQLException ignore) {}
        }
    }

    @Override
    public void deleteOlderThan(long epochMillis) throws SQLException {
        String sql = "DELETE FROM PlaybackHistory WHERE PlayedAt < ?";
        synchronized (DB_WRITE_LOCK) {
            Connection conn = null;
            boolean close = false;
            try {
                conn = openConnection();
                close = shouldCloseConnection(conn);
                try (PreparedStatement ps = prepareStatementWithRetry(conn, sql, MAX_RETRY_ATTEMPTS)) {
                    ps.setLong(1, epochMillis);
                    ps.executeUpdate();
                }
            } finally {
                if (close) try { conn.close(); } catch (SQLException ignore) {}
            }
        }
    }
}
