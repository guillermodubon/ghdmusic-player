package io.github.guillermodubon.musicplayer.repository.dao.genre;

import io.github.guillermodubon.musicplayer.repository.dao.Dao;
import io.github.guillermodubon.musicplayer.models.DeezerApiMetaData;
import io.github.guillermodubon.musicplayer.models.Genre;

import java.sql.SQLException;
import java.util.List;

public interface GenreDao extends Dao<Genre,Integer> {
    int findIdByName(String name) throws SQLException;
    int create(String name) throws SQLException;
    void upsertAll(List<DeezerApiMetaData> metas) throws SQLException;
    void deleteWithoutAlbums() throws SQLException;
}
