package io.github.guillermodubon.musicplayer.repository;

import io.github.guillermodubon.musicplayer.repository.userData.UserDataPaths;

import java.sql.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

public class DbConnectionManager {
    private static final String DEFAULT_DB_FILE = UserDataPaths.databaseFile().toString();
    private static final String JDBC_PREFIX = "jdbc:sqlite:";
    private static final int DEFAULT_BUSY_TIMEOUT_MS = 5000;
    private static final int MAX_RETRIES = 6;
    private static volatile DbConnectionManager instance;
    private final String dbUrl;
    private final Object transactionLock = new Object();
    private final AtomicBoolean walModeConfigured = new AtomicBoolean(false);

    private DbConnectionManager(String dbFile) {
        this.dbUrl = JDBC_PREFIX + dbFile;
    }

    public static synchronized DbConnectionManager init(String dbFile) {
        if (instance == null) instance = new DbConnectionManager(dbFile == null ? DEFAULT_DB_FILE : dbFile);
        return instance;
    }

    public static DbConnectionManager getInstance() {
        if (instance == null) throw new IllegalStateException("DbConnectionManager not initialized. Call init(...) first.");
        return instance;
    }

    public String getDbUrl() { return dbUrl; }

    /** Open a new connection and configure PRAGMAs. Caller must close. */
    public Connection openConnection() throws SQLException {
        Connection c = DriverManager.getConnection(dbUrl);
        configureConnection(c);
        System.out.println("DbConnectionManager.openConnection: opened conn identity=" + System.identityHashCode(c) + " thread=" + Thread.currentThread().getName());
        return c;
    }

    /** Configure PRAGMAs on a freshly opened connection (idempotent). */
    public void configureConnection(Connection conn) {
        if (conn == null) return;
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA synchronous = NORMAL"); // trade durability for speed
            st.execute("PRAGMA busy_timeout = " + DEFAULT_BUSY_TIMEOUT_MS);
        } catch (SQLException ex) {
            System.err.println("DbConnectionManager.configureConnection: warn - could not set connection PRAGMAs -> " + ex.getMessage());
        }

        /*
         * journal_mode is persistent for the database file. Reapplying it on
         * every connection can itself request a locking transition, especially
         * while another screen is writing. Configure it once and never from a
         * connection that is already inside a transaction.
         */
        try {
            if (conn.getAutoCommit() && walModeConfigured.compareAndSet(false, true)) {
                try (Statement st = conn.createStatement()) {
                    st.execute("PRAGMA journal_mode = WAL");
                } catch (SQLException ex) {
                    walModeConfigured.set(false);
                    System.err.println("DbConnectionManager.configureConnection: warn - could not enable WAL -> " + ex.getMessage());
                }
            }
        } catch (SQLException ignored) {
        }
    }

    /**
     * Execute function with retry on SQLITE_BUSY/locked. Function receives an open connection (must not close it).
     * Attempts will open/close a fresh connection for each try; implementors should commit/rollback inside function when needed.
     */
    public <T> T runWithRetries(Function<Connection,T> fn) throws SQLException {
        int attempt = 0;
        long base = 120L;
        SQLException lastEx = null;
        while (++attempt <= MAX_RETRIES) {
            try (Connection c = openConnection()) {
                return fn.apply(c);
            } catch (SQLException ex) {
                lastEx = ex;
                if (shouldRetry(ex, attempt, base)) continue;
                throw ex;
            } catch (RuntimeException ex) {
                /*
                 * Function cannot declare checked SQLExceptions, so
                 * transactional callers commonly wrap them in RuntimeException.
                 * Unwrap those here so SQLITE_BUSY receives the same retry path.
                 */
                SQLException sqlException = findSQLException(ex);
                if (sqlException == null) throw ex;

                lastEx = sqlException;
                if (shouldRetry(sqlException, attempt, base)) continue;
                throw sqlException;
            }
        }
        throw lastEx != null ? lastEx : new SQLException("Unknown DB error after retries");
    }

    private boolean shouldRetry(SQLException ex, int attempt, long base) throws SQLException {
        String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
        System.out.println("DbConnectionManager.runWithRetries: attempt " + attempt + " failed -> " + msg);
        if (!(msg.contains("database is locked") || msg.contains("busy") || isSqliteBusy(ex))) {
            return false;
        }
        if (attempt >= MAX_RETRIES) return false;

        try {
            long sleep = Math.min(base * (1L << (attempt - 1)), 5000);
            Thread.sleep(sleep);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while retrying DB operation", interrupted);
        }
        return true;
    }

    private SQLException findSQLException(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sqlException) return sqlException;
            current = current.getCause();
        }
        return null;
    }

    private boolean isSqliteBusy(SQLException ex) {
        try {
            Integer ec = ex.getErrorCode();
            return ec != null && ec == 5; // SQLITE_BUSY is error code 5 in many drivers
        } catch (Exception ignore) { return false; }
    }

    /** Helper to run a transactional unit of work with commit/rollback and retries on busy. */
    public <T> T runInTransaction(Function<Connection,T> unit) throws SQLException {
        synchronized (transactionLock) {
            return runWithRetries(conn -> {
                try {
                    conn.setAutoCommit(false);
                    T result = unit.apply(conn);
                    conn.commit();
                    return result;
                } catch (RuntimeException error) {
                    try { conn.rollback(); } catch (Exception ignored) {}
                    throw error;
                } catch (SQLException error) {
                    try { conn.rollback(); } catch (Exception ignored) {}
                    throw new RuntimeException(error);
                } finally {
                    try { conn.setAutoCommit(true); } catch (Exception ignored) {}
                }
            });
        }
    }

}
