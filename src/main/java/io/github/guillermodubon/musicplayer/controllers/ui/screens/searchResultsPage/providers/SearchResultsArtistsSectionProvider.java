package io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.scene.Node;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.ArtistCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.factory.CardFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.SearchResultsPageController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.context.SearchResultsPageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.context.SearchResultsPageRenderContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.providers.base.BaseSearchResultsPagePageSectionProvider;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;
import io.github.guillermodubon.musicplayer.services.api.DeezerArtistMetadataResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class SearchResultsArtistsSectionProvider extends BaseSearchResultsPagePageSectionProvider {

    private static final int MAX_ARTISTS = 5;

    private record ArtistResult(long id, String name, String pictureUrl) {}

    public SearchResultsArtistsSectionProvider(SearchResultsPageContext context, SearchResultsPageController.UiBindings ui) {
        super(context, ui);
    }

    @Override
    public void render(SearchResultsPageRenderContext rc) {
        if (!isCurrent(rc)) return;
        prepareSectionSlot();

        final String query = rc.query() == null ? "" : rc.query().trim();
        loadAsync(rc, () -> buildArtists(query), results ->
                showSectionBatched(rc, "Artists", results, this::createCard)
        );
    }

    private List<ArtistResult> buildArtists(String query) throws Exception {
        if (query == null || query.isBlank()) return List.of();
        JsonArray remoteArtists = service.remoteArtists(query);
        Set<Long> localArtistIds = service.snapshotSongs().stream()
                .filter(song -> song != null && song.isLocal())
                .map(Song::getArtist)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .map(Artist::getArtistID)
                .filter(id -> id > 0)
                .collect(Collectors.toSet());

        Set<Long> seenArtistIds = ConcurrentHashMap.newKeySet();
        List<ArtistResult> results = new ArrayList<>();

        for (JsonElement el : remoteArtists) {
            if (results.size() >= MAX_ARTISTS) break;
            if (!el.isJsonObject()) continue;

            JsonObject artistJson = el.getAsJsonObject();
            long id = DeezerApiService.safeGetLong(artistJson, "id", -1L);
            String name = DeezerApiService.extractTitle(artistJson);
            if (id <= 0 || name == null || name.isBlank() || localArtistIds.contains(id) || !seenArtistIds.add(id)) continue;

            String picture = extractPicture(artistJson);
            results.add(new ArtistResult(id, name, picture));
        }

        return results;
    }

    private Node createCard(ArtistResult result) {
        if (result == null) return null;
        try {
            Artist artist = new Artist(result.id(), result.name(), null, new ArrayList<>());
            artist.setPortraitUrl(result.pictureUrl());
            return CardFactory.createArtistCard(
                    new ArtistCardData(artist, context.artistActions().artistClick(null))
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private String extractPicture(JsonObject artistJson) {
        return DeezerArtistMetadataResolver.pictureUrl(artistJson);
    }
}
