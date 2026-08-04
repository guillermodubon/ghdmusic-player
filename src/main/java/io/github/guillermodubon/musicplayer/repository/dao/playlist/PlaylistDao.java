package io.github.guillermodubon.musicplayer.repository.dao.playlist;

import io.github.guillermodubon.musicplayer.repository.dao.Dao;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;

import java.sql.SQLException;
import java.util.List;

public interface PlaylistDao extends Dao<Playlist, Long> {

    List<String> findAllTitles() throws SQLException;
    Long findIdByTitle(String title) throws SQLException;
    List<String> findSongTitlesInPlaylist(long playlistId) throws SQLException;
    void createPlaylist(Playlist p, byte[] coverBytes) throws SQLException;
    void upsertRemotePlaylistPreservingId(Playlist p, byte[] coverBytes, long remoteId) throws SQLException;
    void addSongToPlaylist(long playlistId, Song song) throws SQLException;
    void removeSongFromPlaylist(long playlistId, long songId) throws SQLException;
    void reorderSongs(long playlistId, List<Long> orderedSongIds) throws SQLException;
    void persistSongOrders(long playlistId,
                           List<Long> orderedSongIds,
                           List<Long> customOrderedSongIds) throws SQLException;
    List<Long> findSongIdsByCustomOrder(long playlistId) throws SQLException;
    List<Long> findSongIdsByRecentlyAdded(long playlistId) throws SQLException;
}
