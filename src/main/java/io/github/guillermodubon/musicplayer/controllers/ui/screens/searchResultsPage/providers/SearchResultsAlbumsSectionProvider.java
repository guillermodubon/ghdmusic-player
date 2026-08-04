package io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.scene.Node;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.SearchResultsPageController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.context.SearchResultsPageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.context.SearchResultsPageRenderContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.providers.base.BaseSearchResultsPagePageSectionProvider;
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

public class SearchResultsAlbumsSectionProvider extends BaseSearchResultsPagePageSectionProvider {

    private record AlbumResult(String id, String cover, String title, List<String> artists) {}

    public SearchResultsAlbumsSectionProvider(SearchResultsPageContext context, SearchResultsPageController.UiBindings ui) {
        super(context, ui);
    }

    @Override
    public void render(SearchResultsPageRenderContext rc) {
        if (!isCurrent(rc)) return;
        prepareSectionSlot();

        final String query = rc.query() == null ? "" : rc.query().trim();
        loadAsync(rc, () -> buildAlbums(query, rc), results ->
                showSectionBatched(rc, "Albums", results, result -> createCard(result, rc))
        );
    }

    private List<AlbumResult> buildAlbums(String query, SearchResultsPageRenderContext rc) throws Exception {
        if (query == null || query.isBlank()) return List.of();
        JsonArray remoteAlbums = service.remoteAlbums(query);
        Set<Long> localAlbumIds = service.snapshotSongs().stream()
                .filter(song -> song != null && song.isLocal() && song.getAlbum() != null)
                .map(song -> song.getAlbum().getAlbumID())
                .filter(id -> id > 0)
                .collect(Collectors.toSet());

        Set<Long> seenAlbumIds = ConcurrentHashMap.newKeySet();
        List<AlbumCandidate> candidates = new ArrayList<>();

        for (JsonElement el : remoteAlbums) {
            if (!isCurrent(rc)) return List.of();
            if (candidates.size() >= MAX_CARDS_PER_SECTION) break;
            if (!el.isJsonObject()) continue;

            JsonObject albumJson = el.getAsJsonObject();
            long id = DeezerApiService.safeGetLong(albumJson, "id", -1L);
            if (id <= 0 || localAlbumIds.contains(id) || !seenAlbumIds.add(id)) continue;

            String title = DeezerApiService.extractTitle(albumJson);
            String cover = DeezerApiService.extractHighResolutionCoverUrl(albumJson);

            candidates.add(new AlbumCandidate(id, cover, title, albumJson));
        }

        Map<Long, List<String>> artistsByAlbum = SearchResultsContributorResolver.resolveAlbumArtists(
                service,
                candidates.stream()
                        .map(candidate -> new SearchResultsContributorResolver.ReleaseCandidate(candidate.id(), candidate.payload()))
                        .toList(),
                () -> isCurrent(rc)
        );

        List<AlbumResult> results = new ArrayList<>(candidates.size());
        for (AlbumCandidate candidate : candidates) {
            if (!isCurrent(rc)) return List.of();
            results.add(new AlbumResult(
                    String.valueOf(candidate.id()),
                    candidate.cover(),
                    candidate.title(),
                    artistsByAlbum.getOrDefault(candidate.id(), List.of("Unknown"))
            ));
        }
        return results;
    }

    private Node createCard(AlbumResult result, SearchResultsPageRenderContext rc) {
        if (result == null) return null;
        try {
            Node card = SearchResultsCardFactory.createCard(
                    result.id(),
                    result.cover(),
                    result.title(),
                    result.artists(),
                    id -> context.musicActions().albumClick(null).accept(id),
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

    private record AlbumCandidate(long id, String cover, String title, JsonObject payload) {
    }
}
