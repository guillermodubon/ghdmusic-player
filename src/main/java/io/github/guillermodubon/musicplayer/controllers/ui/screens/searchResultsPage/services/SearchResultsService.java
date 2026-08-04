package io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.context.SearchResultsPageContext;
import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class SearchResultsService {

    private final SearchResultsPageContext context;

    public SearchResultsService(SearchResultsPageContext context) {
        this.context = context;
    }

    public List<Album> snapshotAlbums() { return context.memory().snapshotAlbums(context.svc()); }
    public List<Song> snapshotSongs() { return context.memory().snapshotSongs(context.svc()); }
    public List<Playlist> snapshotPlaylists() { return context.memory().snapshotPlaylists(context.svc()); }
    public List<Artist> snapshotArtists() { return context.memory().snapshotArtists(context.svc()); }
    public JsonArray remoteAlbums(String query) throws IOException { return context.deezer().searchAlbums(query); }
    public JsonArray remoteTracks(String query) throws IOException { return context.deezer().searchTracks(query); }
    public JsonArray remotePlaylists(String query) throws IOException { return context.deezer().searchPlaylists(query); }
    public JsonArray remoteArtists(String query) throws IOException { return context.deezer().searchArtists(query); }
    public JsonObject remoteTrackById(long id) throws IOException { return context.deezer().trackById(id); }
    public JsonObject remoteAlbumById(long id) throws IOException { return context.deezer().albumById(id); }

    public String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

}
