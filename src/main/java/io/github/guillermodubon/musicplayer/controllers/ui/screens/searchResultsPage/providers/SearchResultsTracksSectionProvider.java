package io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.scene.Node;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.SearchResultsPageController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.context.SearchResultsPageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.context.SearchResultsPageRenderContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.providers.base.BaseSearchResultsPagePageSectionProvider;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.providers.common.SearchResultsContributorResolver;
import io.github.guillermodubon.musicplayer.utils.SearchResultsCardFactory;
import io.github.guillermodubon.musicplayer.utils.SearchResultsImageCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class SearchResultsTracksSectionProvider extends BaseSearchResultsPagePageSectionProvider {

    private record TrackResult(String id, String cover, String title, List<String> artists) {}

    public SearchResultsTracksSectionProvider(SearchResultsPageContext context, SearchResultsPageController.UiBindings ui) {
        super(context, ui);
    }

    @Override
    public void render(SearchResultsPageRenderContext rc) {
        if (!isCurrent(rc)) return;
        prepareSectionSlot();

        final String query = rc.query() == null ? "" : rc.query().trim();
        loadAsync(rc, () -> buildTracks(query, rc), results ->
                showSectionBatched(rc, "Songs", results, result -> createCard(result, rc))
        );
    }

    private List<TrackResult> buildTracks(String query, SearchResultsPageRenderContext rc) throws Exception {
        if (query == null || query.isBlank()) return List.of();
        JsonArray remoteTracks = service.remoteTracks(query);
        Set<Long> localSongIds = service.snapshotSongs().stream()
                .filter(song -> song != null && song.isLocal())
                .map(Song::getSongID)
                .filter(id -> id > 0)
                .collect(Collectors.toSet());

        Set<Long> seenTrackIds = ConcurrentHashMap.newKeySet();
        List<TrackCandidate> candidates = new ArrayList<>();

        for (JsonElement el : remoteTracks) {
            if (!isCurrent(rc)) return List.of();
            if (candidates.size() >= MAX_CARDS_PER_SECTION) break;
            if (!el.isJsonObject()) continue;

            JsonObject trackJson = el.getAsJsonObject();
            long id = DeezerApiService.safeGetLong(trackJson, "id", -1L);
            if (id <= 0 || localSongIds.contains(id) || !seenTrackIds.add(id)) continue;

            String title = DeezerApiService.extractTitle(trackJson);

            String cover = null;
            if (trackJson.has("album") && trackJson.get("album").isJsonObject()) {
                cover = DeezerApiService.extractHighResolutionCoverUrl(trackJson.getAsJsonObject("album"));
            }

            candidates.add(new TrackCandidate(id, cover, title, trackJson));
        }

        Map<Long, List<String>> artistsByTrack = SearchResultsContributorResolver.resolveTrackArtists(
                service,
                candidates.stream()
                        .map(candidate -> new SearchResultsContributorResolver.ReleaseCandidate(candidate.id(), candidate.payload()))
                        .toList(),
                () -> isCurrent(rc)
        );

        List<TrackResult> results = new ArrayList<>(candidates.size());
        for (TrackCandidate candidate : candidates) {
            if (!isCurrent(rc)) return List.of();
            results.add(new TrackResult(
                    String.valueOf(candidate.id()),
                    candidate.cover(),
                    candidate.title(),
                    artistsByTrack.getOrDefault(candidate.id(), List.of("Unknown"))
            ));
        }
        return results;
    }

    private Node createCard(TrackResult result, SearchResultsPageRenderContext rc) {
        if (result == null) return null;
        try {
            Node card = SearchResultsCardFactory.createCard(
                    result.id(),
                    result.cover(),
                    result.title(),
                    result.artists(),
                    id -> context.musicActions().songClick(null).accept(id),
                    name -> context.musicActions().artistNameClick(null).accept(name),
                    false
            );
            if (card instanceof javafx.scene.layout.StackPane stackPane) {
                SearchResultsImageCache.getInstance().load(result.cover(), stackPane, () -> isCurrent(rc));
            }
            return card;
        } catch (Exception ignored) {
            return null;
        }
    }

    private record TrackCandidate(long id, String cover, String title, JsonObject payload) {
    }
}
