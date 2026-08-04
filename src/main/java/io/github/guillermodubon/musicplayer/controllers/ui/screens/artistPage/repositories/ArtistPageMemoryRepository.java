package io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.repositories;

import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.utils.LibraryModelDeduplicator;

import java.util.*;
import java.util.stream.Collectors;

public class ArtistPageMemoryRepository {

    public List<Album> snapshotAlbums(StartUpService svc) {
        if (svc == null || svc.getAlbums() == null) return List.of();
        return LibraryModelDeduplicator.albums(svc.getAlbums());
    }

    public List<Song> snapshotSongs(StartUpService svc) {
        if (svc == null || svc.getSongs() == null) return List.of();
        return LibraryModelDeduplicator.songs(svc.getSongs());
    }

}
