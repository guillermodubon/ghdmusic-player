package io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.repositories;

import io.github.guillermodubon.musicplayer.models.ManifestEntry;


import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.utils.LocalSongVerifier;
import io.github.guillermodubon.musicplayer.services.manifest.ManifestService;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.utils.MusicCardHelper;
import io.github.guillermodubon.musicplayer.utils.LibraryModelDeduplicator;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SearchResultsPageMemoryRepository {

    public List<Album> snapshotAlbums(StartUpService svc) {
        if (svc == null || svc.getAlbums() == null) return List.of();
        Map<String, ManifestEntry> manifest = LocalSongVerifier.loadManifest(svc);
        List<Song> localSongs = LocalSongVerifier.verifiedLocalSongs(svc.getSongs(), manifest);
        return LibraryModelDeduplicator.albums(svc.getAlbums())
                .stream()
                .filter(album -> LocalSongVerifier.albumHasVerifiedLocalSong(album, localSongs, manifest))
                .collect(Collectors.toList());
    }

    public List<Song> snapshotSongs(StartUpService svc) {
        return svc == null || svc.getSongs() == null
                ? List.of()
                : LocalSongVerifier.verifiedLocalSongs(
                        svc.getSongs(),
                        LocalSongVerifier.loadManifest(svc)
                );
    }

    public List<Playlist> snapshotPlaylists(StartUpService svc) {
        return svc == null || svc.getPlaylists() == null ? List.of() : LibraryModelDeduplicator.playlists(svc.getPlaylists());
    }

    public List<Artist> snapshotArtists(StartUpService svc) {
        return svc == null || svc.getArtists() == null ? List.of() : LibraryModelDeduplicator.artists(svc.getArtists());
    }
}


