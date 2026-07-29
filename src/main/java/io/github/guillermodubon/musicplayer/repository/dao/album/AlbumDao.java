package io.github.guillermodubon.musicplayer.repository.dao.album;

import io.github.guillermodubon.musicplayer.repository.dao.artist.ArtistDao;
import io.github.guillermodubon.musicplayer.repository.dao.Dao;
import io.github.guillermodubon.musicplayer.repository.dao.genre.GenreDao;
import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.DeezerApiMetaData;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public interface AlbumDao extends Dao<Album,Long> {

    Long findIdByName(String name) throws SQLException;
    void insertImage(long albumId, String type, byte[] data) throws SQLException;
    boolean imageExists(long albumId, String type) throws SQLException;
    void linkArtist(long albumId, long artistId) throws SQLException;
    Long create(String name, int genreId, String recordType, String releaseDate, int numberOfTracks) throws SQLException;
    void insertImages(long albumId, List<byte[]> covers) throws SQLException;

    void upsertAll(Connection conn,
                   List<DeezerApiMetaData> metas,
                   GenreDao genreDao,
                   ArtistDao artistDao) throws SQLException;

    void upsertAll(List<DeezerApiMetaData> metas,
                   GenreDao genreDao,
                   ArtistDao artistDao) throws SQLException;

    void deleteWithoutSongs() throws SQLException;
    void deleteAlbumArtists(long albumId) throws SQLException;
    void deleteAlbumImages(long albumId) throws SQLException;
    boolean existsById(long albumId) throws SQLException;
    void upsertFromMeta(DeezerApiMetaData meta) throws SQLException;
    Connection getConn() throws SQLException;

}
