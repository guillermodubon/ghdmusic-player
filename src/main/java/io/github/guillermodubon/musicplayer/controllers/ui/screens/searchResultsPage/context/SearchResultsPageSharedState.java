package io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.context;

import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.searchDropdown.models.SearchCandidate;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class SearchResultsPageSharedState {
    private final AtomicInteger searchRunId = new AtomicInteger(0);
    private final AtomicBoolean snapshotConsumed = new AtomicBoolean(true);
    private final AtomicReference<List<SearchCandidate>> latestCandidates = new AtomicReference<>(null);
    private final AtomicReference<String> latestQuery = new AtomicReference<>(null);

    public AtomicInteger searchRunId() { return searchRunId; }

    public void clearTransient() {
        searchRunId.incrementAndGet();
        snapshotConsumed.set(true);
        latestCandidates.set(null);
        latestQuery.set(null);
    }
}
