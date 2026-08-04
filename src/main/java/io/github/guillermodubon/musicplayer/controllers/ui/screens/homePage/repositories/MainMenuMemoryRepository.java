package io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.repositories;

import io.github.guillermodubon.musicplayer.models.*;
import io.github.guillermodubon.musicplayer.utils.LibraryModelDeduplicator;
import io.github.guillermodubon.musicplayer.utils.LocalSongVerifier;
import io.github.guillermodubon.musicplayer.services.manifest.ManifestService;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class MainMenuMemoryRepository {

    private final StartUpService svc;
    private final Object snapshotLock = new Object();
    private volatile LibrarySnapshot librarySnapshot;

    public MainMenuMemoryRepository(StartUpService svc) {
        this.svc = svc;
    }

    public List<Album> albums() {
        return snapshot().albums();
    }

    public List<Song> songs() {
        return snapshot().songs();
    }

    public List<Playlist> playlists() {
        if (svc == null || svc.getPlaylists() == null) return List.of();
        return LibraryModelDeduplicator.playlists(svc.getPlaylists());
    }

    public List<Artist> artists() {
        if (svc == null || svc.getArtists() == null) return List.of();
        return LibraryModelDeduplicator.artists(svc.getArtists());
    }

    public List<Genre> genres() {
        if (svc == null || svc.getGenres() == null) return List.of();
        return new ArrayList<>(svc.getGenres());
    }

    public List<Song> singles() {
        return snapshot().singles();
    }

    public List<Album> fullAlbums() {
        return snapshot().albums().stream()
                .filter(Objects::nonNull)
                .filter(a -> {
                    try {
                        if (a.getNumberOfTracks() > 1) return true;
                        return a.getSongList() != null && a.getSongList().size() > 1;
                    } catch (Exception e) {
                        return false;
                    }
                })
                .collect(Collectors.toList());
    }


    public void rebuildSnapshots() {
        librarySnapshot = null;
    }

    private LibrarySnapshot snapshot() {
        LibrarySnapshot current = librarySnapshot;
        if (current != null) return current;

        synchronized (snapshotLock) {
            current = librarySnapshot;
            if (current != null) return current;

            if (svc == null || svc.getSongs() == null || svc.getAlbums() == null) {
                current = LibrarySnapshot.EMPTY;
            } else {
                Map<String, ManifestEntry> manifest = LocalSongVerifier.loadManifest(svc);
                List<Song> localSongs = List.copyOf(LocalSongVerifier.verifiedLocalSongs(svc.getSongs(), manifest));
                List<Album> localAlbums = LibraryModelDeduplicator.albums(svc.getAlbums())
                        .stream()
                        .filter(album -> LocalSongVerifier.albumHasVerifiedLocalSong(album, localSongs, manifest))
                        .toList();
                List<Song> singles = localSongs.stream()
                        .filter(this::isSingle)
                        .toList();

                current = new LibrarySnapshot(localSongs, localAlbums, singles);
            }

            librarySnapshot = current;
            return current;
        }
    }

    private boolean isSingle(Song song) {
        if (song == null || song.getAlbum() == null) return false;
        Album album = song.getAlbum();
        try {
            if (album.getNumberOfTracks() > 0) return album.getNumberOfTracks() == 1;
            return album.getSongList() != null && album.getSongList().size() == 1;
        } catch (Exception ignored) {
            return false;
        }
    }

    private record LibrarySnapshot(List<Song> songs, List<Album> albums, List<Song> singles) {
        private static final LibrarySnapshot EMPTY = new LibrarySnapshot(List.of(), List.of(), List.of());
    }
}


