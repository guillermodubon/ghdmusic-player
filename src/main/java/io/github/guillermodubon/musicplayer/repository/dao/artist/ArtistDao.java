package io.github.guillermodubon.musicplayer.repository.dao.artist;

import io.github.guillermodubon.musicplayer.repository.dao.Dao;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.DeezerApiMetaData;

import java.sql.SQLException;
import java.util.List;

public interface ArtistDao extends Dao<Artist,Long> {
    Long findIdByName(String name) throws SQLException;
    void insertImage(long artistId, String type, byte[] data) throws SQLException;
    boolean imageExists(long artistId, String type) throws SQLException;
    void insertArtistsAndImages(List<DeezerApiMetaData> metas) throws SQLException;
    void updateBiography(long artistId, String biography) throws SQLException;
    void deleteArtistsWithoutAlbums() throws SQLException;
    void deleteArtistsWithoutReferences() throws SQLException;
    List<Artist> findByAlbumId(long albumId) throws SQLException;
    void create(String name) throws SQLException;
}
