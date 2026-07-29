package io.github.guillermodubon.musicplayer.repository.dao.song;

import io.github.guillermodubon.musicplayer.repository.dao.album.AlbumDao;
import io.github.guillermodubon.musicplayer.repository.dao.artist.ArtistDao;
import io.github.guillermodubon.musicplayer.repository.dao.Dao;
import io.github.guillermodubon.musicplayer.models.DeezerApiMetaData;
import io.github.guillermodubon.musicplayer.models.Song;

import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface SongDao extends Dao<Song, Long> {

    void linkArtist(long songId, long artistId) throws SQLException;
    List<Song> findByAlbum(long albumId) throws SQLException;
    boolean isEmpty() throws SQLException;
    void deleteByIds(List<Long> songIds) throws SQLException;
    void insertSongsAndArtists(List<DeezerApiMetaData> metas, AlbumDao albumDao, ArtistDao artistDao) throws SQLException;
    void cleanEmptyPlaylists() throws SQLException;
    Long insertAndGetId(Song s) throws SQLException;
    List<Long> findIdsNotIn(Set<String> titles) throws SQLException;
    Set<String> findAllTitles() throws SQLException;
    boolean  existsByAlbumAndTrackOrder(long albumId, int trackOrder) throws SQLException;
    void insertVisualSong(String title, long albumId, int trackOrder) throws SQLException;
    void deleteByAlbum(long albumId) throws SQLException;
    void insertLocalSongInAlbum(String title, long albumId, int trackOrder, String filePath) throws SQLException;
    Optional<Long> findLocalSongByTitleAndArtists(String title, List<String> artistNames) throws SQLException;
    void insertWithId(Song song) throws SQLException;
    void insertOrUpdateAllWithIds(Collection<Song> songs) throws SQLException;
    void updateAlbumAndOrderIfNeeded(long songId, long albumId, int trackOrder) throws SQLException;
    void deleteVisualDuplicates() throws SQLException;
    Set<Long> findAllLocalIds() throws SQLException;
}
