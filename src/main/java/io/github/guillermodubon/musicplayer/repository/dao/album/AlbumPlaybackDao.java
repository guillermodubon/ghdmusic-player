package io.github.guillermodubon.musicplayer.repository.dao.album;

import io.github.guillermodubon.musicplayer.models.Song;

import java.util.Collection;

/**
 * Persistence operations needed when a remote album is opened for playback.
 *
 * <p>This boundary keeps playback coordinators free from SQL while retaining
 * the small, album-specific writes required to hydrate the local model.</p>
 */
public interface AlbumPlaybackDao {

    void persistRemoteSongs(long albumId, Collection<Song> songs);

    void updateReleaseDate(long albumId, String albumName, String releaseDate, int numberOfTracks);
}
