package io.github.guillermodubon.musicplayer.services.startup.orchestration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

final class LibraryInitializationPolicy {

    private LibraryInitializationPolicy() {
    }

    static boolean isGenuinelyEmpty(Connection connection) throws SQLException {
        if (connection == null) return true;
        try (Statement statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT NOT EXISTS (SELECT 1 FROM Song)
                        AND NOT EXISTS (SELECT 1 FROM Album)
                        AND NOT EXISTS (SELECT 1 FROM Playlist)
                        AND NOT EXISTS (SELECT 1 FROM SavedSong)
                        AND NOT EXISTS (SELECT 1 FROM SavedRelease)
                     """)) {
            return result.next() && result.getInt(1) == 1;
        }
    }
}
