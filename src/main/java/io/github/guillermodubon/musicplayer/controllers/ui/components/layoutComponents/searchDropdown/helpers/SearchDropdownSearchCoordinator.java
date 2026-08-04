package io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.searchDropdown.helpers;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.util.Duration;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.searchDropdown.services.SearchDropdownService;
import io.github.guillermodubon.musicplayer.models.SearchCandidate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/** Owns debounce, cancellation, caching and stale-result protection. */
public final class SearchDropdownSearchCoordinator {

    private static final long DEBOUNCE_MS = 180L;
    private static final int CACHE_SIZE = 32;

    private final Timeline debounceTimer = new Timeline();
    private final ExecutorService searchExecutor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "search-dropdown-search");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger searchSequence = new AtomicInteger();
    private final Map<String, List<SearchCandidate>> candidateCache =
            Collections.synchronizedMap(new LinkedHashMap<>(CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<String, List<SearchCandidate>> eldest
                ) {
                    return size() > CACHE_SIZE;
                }
            });

    private final Runnable clearResults;
    private final Runnable hidePopup;
    private final Runnable cancelImages;
    private final BiConsumer<String, Integer> showLoading;
    private final BiConsumer<List<SearchCandidate>, String> showCandidates;
    private final int maxResults;

    private SearchDropdownService searchService;
    private volatile Future<?> currentSearchFuture;
    private volatile List<SearchCandidate> latestCandidates;
    private volatile String latestQuery;
    private volatile int activeSearchToken;
    private volatile String activeInputText = "";

    public SearchDropdownSearchCoordinator(
            Runnable clearResults,
            Runnable hidePopup,
            Runnable cancelImages,
            BiConsumer<String, Integer> showLoading,
            BiConsumer<List<SearchCandidate>, String> showCandidates,
            int maxResults
    ) {
        this.clearResults = clearResults;
        this.hidePopup = hidePopup;
        this.cancelImages = cancelImages;
        this.showLoading = showLoading;
        this.showCandidates = showCandidates;
        this.maxResults = maxResults;
    }

    public void setSearchService(SearchDropdownService searchService) {
        this.searchService = searchService;
    }

    public void handleInput(String query) {
        debounceTimer.stop();
        if (query == null || query.isBlank()) {
            return;
        }

        activeInputText = query;
        int token = searchSequence.incrementAndGet();
        activeSearchToken = token;
        cancelOngoingSearches(false);

        List<SearchCandidate> cached = cachedCandidates(query);
        if (!cached.isEmpty()) {
            latestCandidates = cached;
            latestQuery = query;
            Platform.runLater(() -> showCandidates.accept(cached, query));
            return;
        }

        Platform.runLater(() -> showLoading.accept(query, token));
        debounceTimer.getKeyFrames().clear();
        debounceTimer.getKeyFrames().add(new KeyFrame(
                Duration.millis(DEBOUNCE_MS),
                event -> submitSearch(query, token)
        ));
        debounceTimer.play();
    }

    public void cancelAndClearResults() {
        cancelOngoingSearches(true);
    }

    public void cancelOngoingSearches(boolean clearResultsUi) {
        debounceTimer.stop();
        try {
            Future<?> future = currentSearchFuture;
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        } catch (Exception ignored) {
        }
        currentSearchFuture = null;

        if (cancelImages != null) {
            cancelImages.run();
        }

        if (clearResultsUi && clearResults != null) {
            Platform.runLater(clearResults);
        }
    }

    public List<SearchCandidate> latestCandidates() {
        List<SearchCandidate> candidates = latestCandidates;
        return candidates == null ? List.of() : candidates;
    }

    public String latestQuery() {
        return latestQuery;
    }

    public boolean hasLatestCandidatesFor(String query) {
        return query != null
                && !latestCandidates().isEmpty()
                && query.equals(latestQuery);
    }

    public String currentInputText() {
        return activeInputText;
    }

    public void clearInputState() {
        activeInputText = "";
        activeSearchToken = searchSequence.incrementAndGet();
    }

    public boolean isSearchCurrent(String query, int token) {
        if (query == null || query.isBlank()) {
            return false;
        }
        return token == activeSearchToken && query.equals(activeInputText);
    }

    public void shutdown() {
        cancelOngoingSearches(false);
        searchExecutor.shutdownNow();
    }

    private void submitSearch(String query, int token) {
        if (!isSearchCurrent(query, token)) {
            return;
        }

        currentSearchFuture = searchExecutor.submit(() -> {
            try {
                performSearchBackground(query, token);
            } catch (Throwable error) {
                error.printStackTrace();
                if (isSearchCurrent(query, token) && hidePopup != null) {
                    Platform.runLater(hidePopup);
                }
            }
        });
    }

    private void performSearchBackground(String query, int token) throws Exception {
        if (Thread.currentThread().isInterrupted() || !isSearchCurrent(query, token)) {
            return;
        }

        SearchDropdownService service = searchService;
        List<SearchCandidate> candidates = service == null
                ? List.of()
                : service.buildCandidates(query, maxResults);

        if (Thread.currentThread().isInterrupted() || !isSearchCurrent(query, token)) {
            return;
        }

        if (candidates == null || candidates.isEmpty()) {
            Platform.runLater(() -> {
                if (isSearchCurrent(query, token)) {
                    if (clearResults != null) {
                        clearResults.run();
                    }
                    if (hidePopup != null) {
                        hidePopup.run();
                    }
                }
            });
            return;
        }

        latestCandidates = candidates;
        latestQuery = query;
        candidateCache.put(normalizeQuery(query), List.copyOf(candidates));

        if (Thread.currentThread().isInterrupted() || !isSearchCurrent(query, token)) {
            return;
        }

        Platform.runLater(() -> {
            if (isSearchCurrent(query, token) && showCandidates != null) {
                showCandidates.accept(candidates, query);
            }
        });
    }

    private List<SearchCandidate> cachedCandidates(String query) {
        List<SearchCandidate> cached = candidateCache.get(normalizeQuery(query));
        return cached == null ? List.of() : cached;
    }

    private String normalizeQuery(String query) {
        return query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
