package io.github.guillermodubon.musicplayer.repository.schema;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

public final class DatabaseSchemaTestSupport {

    private DatabaseSchemaTestSupport() {
    }

    public static void initializeFresh(Connection connection) throws SQLException {
        DatabaseSchemaManager.initializeConnection(connection, Path.of("schema-fixture.db"), () -> { });
    }
}
