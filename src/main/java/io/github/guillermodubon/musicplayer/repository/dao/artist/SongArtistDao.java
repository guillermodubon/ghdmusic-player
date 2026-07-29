package io.github.guillermodubon.musicplayer.repository.dao.artist;

import io.github.guillermodubon.musicplayer.models.Artist;

import java.sql.SQLException;
import java.util.List;

/** Persistence boundary for the SongArtist relationship. */
public interface SongArtistDao {
    List<Artist> findBySongId(long songId) throws SQLException;

    void persistArtistsForSong(long songId, List<Artist> artists) throws SQLException;

    void refreshBiographies(List<Artist> artists) throws SQLException;
}
