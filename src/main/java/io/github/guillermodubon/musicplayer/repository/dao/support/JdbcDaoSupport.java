package io.github.guillermodubon.musicplayer.repository.dao.support;

import io.github.guillermodubon.musicplayer.repository.DbConnectionManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * Shared SQLite access primitives for DAO implementations.
 *
 * <p>DAO classes may receive a transaction-owned connection. When no shared
 * connection is available, this class opens and configures a short-lived one.
 * It also centralizes SQLite busy handling so repository implementations use
 * consistent retry behaviour.</p>
 */
public abstract class JdbcDaoSupport {

    protected static final int DEFAULT_RETRY_ATTEMPTS = 6;

    private final Connection sharedConnection;

    protected JdbcDaoSupport(Connection sharedConnection) {
        this.sharedConnection = sharedConnection;
    }

    protected final boolean hasSharedConnection() {
        try {
            return sharedConnection != null && !sharedConnection.isClosed();
        } catch (SQLException ignored) {
            return false;
        }
    }

    protected final Connection sharedConnection() {
        return sharedConnection;
    }

    protected final Connection openConnection() throws SQLException {
        if (hasSharedConnection()) {
            configureConnection(sharedConnection);
            return sharedConnection;
        }

        Connection connection = DbConnectionManager.getInstance().openConnection();
        configureConnection(connection);
        return connection;
    }

    protected final DbConnectionManager connectionManager() {
        return DbConnectionManager.getInstance();
    }

    protected final boolean closesConnection(Connection connection) {
        return connection != null && connection != sharedConnection;
    }

    protected final PreparedStatement prepareStatementWithRetry(
            Connection connection,
            String sql,
            int maxAttempts
    ) throws SQLException {
        return prepareStatementWithRetry(connection, sql, maxAttempts, false);
    }

    protected final PreparedStatement prepareStatementWithRetry(
            Connection connection,
            String sql,
            int maxAttempts,
            boolean returnGeneratedKeys
    ) throws SQLException {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return returnGeneratedKeys
                        ? connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
                        : connection.prepareStatement(sql);
            } catch (SQLException exception) {
                if (!isBusy(exception) || attempt >= maxAttempts) {
                    throw exception;
                }
                waitBeforeRetry(attempt, exception);
            }
        }
    }

    protected final void configureConnection(Connection connection) {
        if (connection == null) {
            return;
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA journal_mode = WAL");
        } catch (SQLException ignored) {
            // Connection creation remains usable even when a PRAGMA is unavailable.
        }
    }

    private boolean isBusy(SQLException exception) {
        String message = exception.getMessage() == null
                ? ""
                : exception.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("database is locked") || message.contains("busy");
    }

    private void waitBeforeRetry(int attempt, SQLException originalException) throws SQLException {
        long delay = Math.min(120L * (1L << (attempt - 1)), 5_000L);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting to retry database operation", originalException);
        }
    }
}
