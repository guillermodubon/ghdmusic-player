package io.github.guillermodubon.musicplayer.repository.dao.history;

import io.github.guillermodubon.musicplayer.repository.dao.Dao;
import io.github.guillermodubon.musicplayer.models.PlaybackHistory;

import java.sql.SQLException;
import java.util.List;

public interface PlaybackHistoryDao extends Dao<PlaybackHistory, Long> {

    List<PlaybackHistory> findRecent(int limit) throws SQLException;
    Long insertAndGetId(PlaybackHistory entry) throws SQLException;
    void deleteOlderThan(long epochMillis) throws SQLException;
}
