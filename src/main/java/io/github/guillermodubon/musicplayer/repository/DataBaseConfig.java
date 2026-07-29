package io.github.guillermodubon.musicplayer.repository;

import io.github.guillermodubon.musicplayer.repository.schema.DatabaseSchemaManager;
import io.github.guillermodubon.musicplayer.repository.userData.UserDataPaths;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Backwards-compatible repository entry point for database initialization.
 * Schema ownership lives in {@link DatabaseSchemaManager}.
 */
public final class DataBaseConfig {

    public static final String DB_FILE = UserDataPaths.databaseFile().toString();

    private DataBaseConfig() {
    }

    public static void initializeDatabase() {
        DatabaseSchemaManager.initialize(DB_FILE);
    }

    public static Connection getConnection() throws SQLException {
        return DbConnectionManager.getInstance().openConnection();
    }
}
