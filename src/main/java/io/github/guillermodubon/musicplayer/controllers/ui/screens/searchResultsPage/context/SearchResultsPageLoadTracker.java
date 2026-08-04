package io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.context;

import java.util.Objects;
import java.util.function.Consumer;

/** Aggregates one asynchronous result from every search-results section. */
public final class SearchResultsPageLoadTracker {
    private final int expectedSections;
    private final Consumer<Summary> completionListener;
    private final Object lock = new Object();

    private int completedSections;
    private int remoteSections;
    private int failedRemoteSections;
    private boolean hasResults;
    private boolean dispatched;

    public SearchResultsPageLoadTracker(int expectedSections,
                                        Consumer<Summary> completionListener) {
        this.expectedSections = Math.max(0, expectedSections);
        this.completionListener = Objects.requireNonNull(completionListener, "completionListener");
    }

    public void record(boolean remoteSection, boolean sectionHasResults, boolean failed) {
        Summary summary = null;

        synchronized (lock) {
            if (dispatched) return;

            completedSections++;
            hasResults |= sectionHasResults;

            if (remoteSection) {
                remoteSections++;
                if (failed) failedRemoteSections++;
            }

            if (completedSections >= expectedSections) {
                dispatched = true;
                boolean apiUnavailable = remoteSections > 0
                        && failedRemoteSections == remoteSections;
                summary = new Summary(hasResults, apiUnavailable);
            }
        }

        if (summary != null) completionListener.accept(summary);
    }

    public record Summary(boolean hasResults, boolean apiUnavailable) {
    }
}
