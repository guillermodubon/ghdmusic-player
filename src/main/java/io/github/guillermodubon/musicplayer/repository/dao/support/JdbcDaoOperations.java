package io.github.guillermodubon.musicplayer.repository.dao.support;

import javafx.util.Pair;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.function.BiFunction;

public final class JdbcDaoOperations {

    private JdbcDaoOperations() {}

    public static void insertBlobs(
            Connection conn,
            String sql,
            long entityId,
            List<byte[]> dataList,
            String[] types,
            BiFunction<Connection, Pair<Long,String>, Boolean> existsCheck
    ) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < dataList.size() && i < types.length; i++) {
                byte[] blob = dataList.get(i);
                if (blob == null) continue;
                String type = types[i];
                if (!existsCheck.apply(conn, new Pair<>(entityId, type))) {
                    ps.setLong(1, entityId);
                    ps.setString(2, type);
                    ps.setBytes(3, blob);
                    ps.executeUpdate();
                }
            }
        }
    }

}
