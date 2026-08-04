package io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.providers.common;

import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.SearchResultsPageController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.context.SearchResultsPageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.context.SearchResultsPageRenderContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.providers.*;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.providers.base.SearchResultsPageSectionProvider;

import java.io.IOException;
import java.util.List;

public class SearchResultsPageSectionRegistry {

    private final List<SearchResultsPageSectionProvider> providers;

    public SearchResultsPageSectionRegistry(SearchResultsPageContext context, SearchResultsPageController.UiBindings ui) {
        this.providers = List.of(
                new SearchResultsLocalSectionProvider(context, ui),
                new SearchResultsAlbumsSectionProvider(context, ui),
                new SearchResultsTracksSectionProvider(context, ui),
                new SearchResultsPlaylistsSectionProvider(context, ui),
                new SearchResultsArtistsSectionProvider(context, ui)
        );
    }

    public void renderAll(SearchResultsPageRenderContext renderContext) throws IOException {
        for (SearchResultsPageSectionProvider provider : providers) provider.render(renderContext);
    }

    public int providerCount() {
        return providers.size();
    }

    public void dispose() {
        for (SearchResultsPageSectionProvider provider : providers) {
            try { provider.dispose(); } catch (Exception ignored) {}
        }
    }
}
