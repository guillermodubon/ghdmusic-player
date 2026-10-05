package io.github.guillermodubon.musicplayer.services.startup.orchestration;

import io.github.guillermodubon.musicplayer.repository.schema.DatabaseSchemaTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryInitializationPolicyTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void onlyAnActuallyEmptyDatabaseUsesFirstImport() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + temporaryDirectory.resolve("startup.db").toAbsolutePath())) {
            DatabaseSchemaTestSupport.initializeFresh(connection);
            assertTrue(LibraryInitializationPolicy.isGenuinelyEmpty(connection));

            execute(connection, "INSERT INTO Genre(GenreID, Name) VALUES(1, 'Unknown')");
            execute(connection, "INSERT INTO Album(AlbumID, GenreID, Name) VALUES(1, 1, 'Saved remote release')");
            execute(connection, "INSERT INTO Song(SongID, Title, Album, IsLocal) VALUES(1, 'Remote track', 1, 0)");
            execute(connection, "INSERT INTO SavedRelease(AlbumID, SavedAt) VALUES(1, 100)");
            assertFalse(LibraryInitializationPolicy.isGenuinelyEmpty(connection));
        }
    }

    private void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
    }
}
