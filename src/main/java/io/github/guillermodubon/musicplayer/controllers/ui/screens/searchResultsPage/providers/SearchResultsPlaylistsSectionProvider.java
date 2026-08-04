package io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.MusicCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.SearchResultsPageController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.context.SearchResultsPageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.context.SearchResultsPageRenderContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.providers.base.BaseSearchResultsPagePageSectionProvider;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;
import io.github.guillermodubon.musicplayer.utils.SearchResultsCardFactory;
import io.github.guillermodubon.musicplayer.utils.SearchResultsImageCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class SearchResultsPlaylistsSectionProvider extends BaseSearchResultsPagePageSectionProvider {

    private record PlaylistResult(String id, String cover, String title, List<String> creators) {}

    public SearchResultsPlaylistsSectionProvider(SearchResultsPageContext context, SearchResultsPageController.UiBindings ui) {
        super(context, ui);
    }

    @Override
    public void render(SearchResultsPageRenderContext rc) {
        if (!isCurrent(rc)) return;
        prepareSectionSlot();

        final String query = rc.query() == null ? "" : rc.query().trim();
        loadAsync(rc, () -> buildPlaylists(query), results ->
                showSectionBatched(rc, "Playlists", results, result -> createCard(result, rc))
        );
    }

    private List<PlaylistResult> buildPlaylists(String query) throws Exception {
        if (query == null || query.isBlank()) return List.of();
        JsonArray remotePlaylists = service.remotePlaylists(query);
        Set<Long> localSongIds = service.snapshotSongs().stream()
                .filter(song -> song != null && song.isLocal())
                .map(Song::getSongID)
                .filter(id -> id > 0)
                .collect(Collectors.toSet());
        Set<Long> localPlaylistIds = service.snapshotPlaylists().stream()
                .filter(playlist -> containsLocalSong(playlist, localSongIds))
                .map(Playlist::getId)
                .filter(id -> id > 0)
                .collect(Collectors.toSet());

        Set<Long> seenPlaylistIds = ConcurrentHashMap.newKeySet();
        List<PlaylistResult> results = new ArrayList<>();

        for (JsonElement el : remotePlaylists) {
            if (results.size() >= MAX_CARDS_PER_SECTION) break;
            if (!el.isJsonObject()) continue;

            JsonObject playlistJson = el.getAsJsonObject();
            long id = DeezerApiService.safeGetLong(playlistJson, "id", -1L);
            if (id <= 0 || localPlaylistIds.contains(id) || !seenPlaylistIds.add(id)) continue;

            String title = DeezerApiService.extractTitle(playlistJson);
            List<String> creators = List.of(MusicCardData.REMOTE_PLAYLIST_CREATOR_LABEL);
            String cover = DeezerApiService.extractHighResolutionCoverUrl(playlistJson);

            results.add(new PlaylistResult(String.valueOf(id), cover, title, creators));
        }

        return results;
    }

    private Node createCard(PlaylistResult result, SearchResultsPageRenderContext rc) {
        if (result == null) return null;
        try {
            Node card = SearchResultsCardFactory.createCard(
                    result.id(),
                    result.cover(),
                    result.title(),
                    result.creators(),
                    id -> context.musicActions().playlistClick(null).accept(id),
                    name -> context.musicActions().artistNameClick(null).accept(name),
                    true
            );
            if (card instanceof javafx.scene.layout.StackPane stackPane) {
                SearchResultsImageCache.getInstance().load(result.cover(), stackPane, () -> isCurrent(rc));
            }
            return card;
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean containsLocalSong(Playlist playlist, Set<Long> localSongIds) {
        ObservableList<Song> songs = playlist == null ? null : playlist.getSongList();
        if (songs == null || songs.isEmpty() || localSongIds == null || localSongIds.isEmpty()) return false;
        for (Song song : songs) {
            if (song != null && song.getSongID() > 0 && localSongIds.contains(song.getSongID())) return true;
        }
        return false;
    }
}
