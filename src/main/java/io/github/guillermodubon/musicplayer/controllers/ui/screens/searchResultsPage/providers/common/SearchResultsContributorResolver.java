package io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.providers.common;

import com.google.gson.JsonObject;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.services.SearchResultsService;
import io.github.guillermodubon.musicplayer.utils.AlbumArtistResolver;
import io.github.guillermodubon.musicplayer.utils.MusicCardHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Resolves extended contributor metadata without serially blocking a whole search section.
 * Result entries that already expose multiple artists never require an extra Deezer request.
 */
public final class SearchResultsContributorResolver {
    private static final long CACHE_TTL_MILLIS = 15 * 60 * 1000L;
    private static final int MAX_CACHE_ENTRIES = 512;
    private static final ExecutorService DETAIL_POOL = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "search-results-details");
        thread.setDaemon(true);
        return thread;
    });

    private static final ConcurrentMap<String, CachedArtists> ARTISTS_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, CompletableFuture<List<String>>> IN_FLIGHT = new ConcurrentHashMap<>();

    private SearchResultsContributorResolver() {
    }

    public static Map<Long, List<String>> resolveAlbumArtists(SearchResultsService service,
                                                               List<ReleaseCandidate> candidates,
                                                               BooleanSupplier active) {
        return resolve("album", service, candidates, active, service::remoteAlbumById);
    }

    public static Map<Long, List<String>> resolveTrackArtists(SearchResultsService service,
                                                               List<ReleaseCandidate> candidates,
                                                               BooleanSupplier active) {
        return resolve("track", service, candidates, active, service::remoteTrackById);
    }

    private static Map<Long, List<String>> resolve(String type,
                                                    SearchResultsService service,
                                                    List<ReleaseCandidate> candidates,
                                                    BooleanSupplier active,
                                                    DetailLoader detailLoader) {
        if (service == null || candidates == null || candidates.isEmpty()) return Map.of();

        Map<Long, List<String>> resolved = new LinkedHashMap<>();
        List<DetailRequest> details = new ArrayList<>();

        for (ReleaseCandidate candidate : candidates) {
            if (!isActive(active) || candidate == null || candidate.id() <= 0) return Map.of();

            List<String> baseArtists = "album".equals(type)
                    ? AlbumArtistResolver.names(candidate.payload())
                    : artists(candidate.payload());
            if (baseArtists.size() > 1) {
                resolved.put(candidate.id(), baseArtists);
                continue;
            }

            CompletableFuture<List<String>> future = request(type, candidate.id(), service, detailLoader);
            details.add(new DetailRequest(candidate.id(), baseArtists, future));
        }

        for (DetailRequest detail : details) {
            if (!isActive(active)) return Map.of();
            List<String> artists = detail.baseArtists();
            try {
                List<String> enriched = detail.future().join();
                if (enriched != null && !enriched.isEmpty()) artists = merge(artists, enriched);
            } catch (Exception ignored) {
            }
            resolved.put(detail.id(), artists.isEmpty() ? List.of("Unknown") : artists);
        }

        return resolved;
    }

    private static CompletableFuture<List<String>> request(String type,
                                                            long id,
                                                            SearchResultsService service,
                                                            DetailLoader detailLoader) {
        String key = type + ':' + id;
        CachedArtists cached = ARTISTS_CACHE.get(key);
        if (cached != null && !cached.expired()) {
            return CompletableFuture.completedFuture(cached.artists());
        }

        CompletableFuture<List<String>> created = new CompletableFuture<>();
        CompletableFuture<List<String>> existing = IN_FLIGHT.putIfAbsent(key, created);
        if (existing != null) return existing;

        DETAIL_POOL.execute(() -> {
            try {
                JsonObject detail = detailLoader.load(id);
                List<String> artists = "album".equals(type)
                        ? AlbumArtistResolver.names(detail)
                        : artists(detail);
                if (artists.isEmpty()) artists = List.of("Unknown");
                List<String> immutable = List.copyOf(artists);
                ARTISTS_CACHE.put(key, new CachedArtists(immutable, System.currentTimeMillis() + CACHE_TTL_MILLIS));
                trimCache();
                created.complete(immutable);
            } catch (Exception ignored) {
                created.complete(List.of());
            } finally {
                IN_FLIGHT.remove(key, created);
            }
        });
        return created;
    }

    private static List<String> artists(JsonObject source) {
        if (source == null) return List.of();
        LinkedHashSet<String> names = new LinkedHashSet<>(MusicCardHelper.extractArtistNamesFromTrackJson(source));
        return names.isEmpty() ? List.of() : List.copyOf(names);
    }

    private static List<String> merge(List<String> primary, List<String> additional) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (primary != null) names.addAll(primary);
        if (additional != null) names.addAll(additional);
        return names.isEmpty() ? List.of() : List.copyOf(names);
    }

    private static boolean isActive(BooleanSupplier active) {
        return active == null || active.getAsBoolean();
    }

    private static void trimCache() {
        if (ARTISTS_CACHE.size() <= MAX_CACHE_ENTRIES) return;
        long now = System.currentTimeMillis();
        ARTISTS_CACHE.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().expiresAt() <= now);
        while (ARTISTS_CACHE.size() > MAX_CACHE_ENTRIES) {
            String key = ARTISTS_CACHE.keySet().stream().findFirst().orElse(null);
            if (key == null) return;
            ARTISTS_CACHE.remove(key);
        }
    }

    public record ReleaseCandidate(long id, JsonObject payload) {
    }

    private record DetailRequest(long id, List<String> baseArtists, CompletableFuture<List<String>> future) {
    }

    private record CachedArtists(List<String> artists, long expiresAt) {
        boolean expired() {
            return System.currentTimeMillis() >= expiresAt;
        }
    }

    @FunctionalInterface
    private interface DetailLoader {
        JsonObject load(long id) throws Exception;
    }
}
