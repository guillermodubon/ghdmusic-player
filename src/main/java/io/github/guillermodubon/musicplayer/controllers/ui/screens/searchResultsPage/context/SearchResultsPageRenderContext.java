package io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.context;

import java.util.function.Supplier;

public record SearchResultsPageRenderContext(
        String query,
        SearchResultsPageContext context,
        SearchResultsPageSharedState shared,
        int generation,
        Supplier<Boolean> alive,
        SearchResultsPageLoadTracker loadTracker
) {
    public boolean isAlive() {
        return alive != null && alive.get();
    }

    public boolean isCurrent() {
        return isAlive()
                && shared != null
                && shared.searchRunId().get() == generation;
    }
}
