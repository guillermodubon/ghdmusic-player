package io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.repositories;

import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.utils.LibraryModelDeduplicator;

import java.util.List;

public class GenrePageMemoryRepository {

    private final StartUpService svc;

    public GenrePageMemoryRepository(StartUpService svc) {
        this.svc = svc;
    }

    /**
     * Produces a stable library view for one GenrePage render cycle.
     * The persisted IsLocal flag is the source of truth here: the manifest is
     * auxiliary state and must not hide models that were loaded correctly from
     * the cache or database.
     */
    public LibrarySnapshot snapshot() {
        if (svc == null) return LibrarySnapshot.empty();

        List<Song> allSongs = svc.getSongs() == null ? List.of() : List.copyOf(svc.getSongs());
        List<Song> localSongs = LibraryModelDeduplicator.songs(allSongs)
                .stream()
                .filter(song -> song != null && song.isLocal())
                .toList();
        List<Album> allAlbums = svc.getAlbums() == null ? List.of() : List.copyOf(svc.getAlbums());
        List<Album> localAlbums = LibraryModelDeduplicator.albums(allAlbums)
                .stream()
                .filter(album -> albumHasLocalSong(album, localSongs))
                .toList();

        return new LibrarySnapshot(localAlbums, localSongs);
    }

    private boolean albumHasLocalSong(Album album, List<Song> localSongs) {
        if (album == null) return false;

        if (album.getSongList() != null
                && album.getSongList().stream().anyMatch(song -> song != null && song.isLocal())) {
            return true;
        }

        long albumId = album.getAlbumID();
        return albumId > 0
                && localSongs != null
                && localSongs.stream().anyMatch(song -> song != null
                && song.getAlbum() != null
                && song.getAlbum().getAlbumID() == albumId);
    }

    public List<Album> albums() {
        return snapshot().albums();
    }

    public List<Song> songs() {
        return snapshot().songs();
    }

    public List<Artist> artists() {
        return svc == null || svc.getArtists() == null ? List.of() : LibraryModelDeduplicator.artists(svc.getArtists());
    }

    public List<Playlist> playlists() {
        return svc == null || svc.getPlaylists() == null ? List.of() : LibraryModelDeduplicator.playlists(svc.getPlaylists());
    }

    public record LibrarySnapshot(List<Album> albums, List<Song> songs) {
        public LibrarySnapshot {
            albums = albums == null ? List.of() : List.copyOf(albums);
            songs = songs == null ? List.of() : List.copyOf(songs);
        }

        public static LibrarySnapshot empty() {
            return new LibrarySnapshot(List.of(), List.of());
        }
    }
}
