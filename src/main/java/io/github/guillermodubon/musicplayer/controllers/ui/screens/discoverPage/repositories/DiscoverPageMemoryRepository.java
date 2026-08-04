package io.github.guillermodubon.musicplayer.controllers.ui.screens.discoverPage.repositories;

import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Genre;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.utils.LibraryModelDeduplicator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class DiscoverPageMemoryRepository {

    private final StartUpService svc;

    public DiscoverPageMemoryRepository(StartUpService svc) {
        this.svc = svc;
    }

    public List<Genre> genres() {
        return svc == null || svc.getGenres() == null ? List.of() : new ArrayList<>(svc.getGenres());
    }

    public List<Artist> artists() {
        return svc == null || svc.getArtists() == null ? List.of() : LibraryModelDeduplicator.artists(svc.getArtists());
    }

    public List<Song> songs() {
        return svc == null || svc.getSongs() == null ? List.of() : LibraryModelDeduplicator.songs(svc.getSongs());
    }

    public List<Album> albums() {
        return svc == null || svc.getAlbums() == null ? List.of() : LibraryModelDeduplicator.albums(svc.getAlbums());
    }

}
