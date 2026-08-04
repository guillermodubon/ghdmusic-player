package io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.providers.base;

import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.context.SearchResultsPageRenderContext;

import java.io.IOException;

public interface SearchResultsPageSectionProvider {
    void render(SearchResultsPageRenderContext renderContext) throws IOException;
    default void dispose() {}
}
