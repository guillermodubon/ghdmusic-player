package io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.providers.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.context.ArtistPageRenderContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.services.ArtistPageService;
import io.github.guillermodubon.musicplayer.utils.AlbumArtistResolver;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Shared, short-lived release catalog for the artist page. Album and single providers consume
 * the same result, so opening an artist never duplicates the expensive Deezer releases request.
 */
public final class ArtistReleaseCatalog {
    private static final long CACHE_TTL_MILLIS = 5 * 60 * 1000L;
    private static final int MAX_CACHE_ENTRIES = 72;
    private static final int MAX_DETAIL_LOOKUPS_PER_CATALOG = 20;

    private static final ConcurrentMap<String, CachedReleases> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, CompletableFuture<List<Release>>> IN_FLIGHT = new ConcurrentHashMap<>();

    private ArtistReleaseCatalog() {
    }

    public static List<Release> releases(ArtistPageService service, ArtistPageRenderContext renderContext) {
        if (service == null || renderContext == null || renderContext.artist() == null) return List.of();
        if (!renderContext.isAlive()) return List.of();

        Artist artist = renderContext.artist();
        long artistId = service.resolveArtistId(artist);
        if (artistId <= 0) return List.of();

        String key = artistKey(artistId, artist.getName());
        CachedReleases cached = CACHE.get(key);
        if (cached != null && !cached.expired()) return cached.releases();

        CompletableFuture<List<Release>> created = new CompletableFuture<>();
        CompletableFuture<List<Release>> active = IN_FLIGHT.putIfAbsent(key, created);
        if (active != null) {
            try {
                return active.join();
            } catch (Exception ignored) {
                return List.of();
            }
        }

        try {
            List<Release> loaded = fetchReleases(service, artist, artistId, renderContext);
            List<Release> immutable = List.copyOf(loaded);
            CACHE.put(key, new CachedReleases(immutable, System.currentTimeMillis() + CACHE_TTL_MILLIS));
            trimCache();
            created.complete(immutable);
            return immutable;
        } catch (Exception ex) {
            created.complete(List.of());
            return List.of();
        } finally {
            IN_FLIGHT.remove(key, created);
        }
    }

    /**
     * Deezer's artist-albums feed can omit singles for some artists. Only in that case we issue
     * the cached search fallback, preserving the normal album feed as the primary source.
     */
    public static List<Release> singles(ArtistPageService service, ArtistPageRenderContext renderContext) {
        List<Release> releases = releases(service, renderContext);
        boolean hasSingles = releases.stream().anyMatch(Release::single);
        if (hasSingles || service == null || renderContext == null || !renderContext.isAlive()) return releases;

        Artist artist = renderContext.artist();
        if (artist == null || artist.getName() == null || artist.getName().isBlank()) return releases;

        String key = "artist-single-search:" + artistKey(artist.getArtistID(), artist.getName());
        CachedReleases cached = CACHE.get(key);
        if (cached != null && !cached.expired()) return cached.releases();

        List<Release> searched = parse(
                service,
                service.searchAlbumsJson(artist.getName()),
                artist,
                true,
                renderContext
        );
        List<Release> immutable = List.copyOf(searched);
        CACHE.put(key, new CachedReleases(immutable, System.currentTimeMillis() + CACHE_TTL_MILLIS));
        trimCache();
        return immutable;
    }

    private static List<Release> fetchReleases(ArtistPageService service,
                                                Artist artist,
                                                long artistId,
                                                ArtistPageRenderContext renderContext) {
        JsonArray source = service.artistAlbumsJson(artistId);
        if (source == null || source.isEmpty()) {
            source = service.searchAlbumsJson(artist.getName());
            return parse(service, source, artist, true, renderContext);
        }
        return parse(service, source, artist, false, renderContext);
    }

    private static List<Release> parse(ArtistPageService service,
                                       JsonArray source,
                                       Artist artist,
                                       boolean onlyMatchingArtist,
                                       ArtistPageRenderContext renderContext) {
        if (source == null || source.isEmpty()) return List.of();

        Map<Long, Release> byId = new LinkedHashMap<>();
        int detailLookups = 0;
        for (JsonElement element : source) {
            if (!renderContext.isAlive()) return List.of();
            if (element == null || !element.isJsonObject()) continue;

            JsonObject json = element.getAsJsonObject();
            if (onlyMatchingArtist && !matchesArtist(json, artist)) continue;

            long id = DeezerApiService.safeGetLong(json, "id", -1L);
            if (id <= 0 || byId.containsKey(id)) continue;

            String title = DeezerApiService.extractTitle(json);
            String coverUrl = DeezerApiService.extractHighResolutionCoverUrl(json);
            boolean single = isSingle(json);
            List<String> artists = artistNames(json, artist == null ? null : artist.getName());
            if (detailLookups < MAX_DETAIL_LOOKUPS_PER_CATALOG
                    && !AlbumArtistResolver.hasExplicitOwnerCollection(json)
                    && artists.size() <= 1
                    && id > 0) {
                detailLookups++;
                try {
                    JsonObject detail = service.albumByIdJson(id);
                    List<String> detailedArtists = AlbumArtistResolver.names(detail);
                    if (!detailedArtists.isEmpty()) artists = detailedArtists;
                } catch (Exception ignored) {
                }
            }
            byId.put(id, new Release(id, title, coverUrl, single, artists));
        }
        return new ArrayList<>(byId.values());
    }

    private static boolean isSingle(JsonObject release) {
        if (release == null) return false;
        try {
            if (release.has("record_type") && !release.get("record_type").isJsonNull()) {
                if ("single".equalsIgnoreCase(release.get("record_type").getAsString())) return true;
            }
            return release.has("nb_tracks")
                    && !release.get("nb_tracks").isJsonNull()
                    && release.get("nb_tracks").getAsInt() == 1;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean matchesArtist(JsonObject release, Artist artist) {
        if (release == null || artist == null) return false;
        if (release.has("artist") && release.get("artist").isJsonObject()) {
            JsonObject releaseArtist = release.getAsJsonObject("artist");
            long id = DeezerApiService.safeGetLong(releaseArtist, "id", -1L);
            if (artist.getArtistID() > 0) return id == artist.getArtistID();

            String name = stringValue(releaseArtist, "name");
            if (name != null && artist.getName() != null && name.equalsIgnoreCase(artist.getName())) return true;
        }
        return false;
    }

    private static List<String> artistNames(JsonObject release, String fallback) {
        LinkedHashSet<String> names = new LinkedHashSet<>(AlbumArtistResolver.names(release));
        if (names.isEmpty() && fallback != null && !fallback.isBlank()) names.add(fallback.trim());
        return names.isEmpty() ? List.of("Unknown") : List.copyOf(names);
    }

    private static String artistKey(long artistId, String name) {
        if (artistId > 0) return "id:" + artistId;
        return "name:" + (name == null ? "" : name.trim().toLowerCase(Locale.ROOT));
    }

    private static String stringValue(JsonObject object, String member) {
        try {
            return object.has(member) && !object.get(member).isJsonNull()
                    ? object.get(member).getAsString()
                    : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void trimCache() {
        if (CACHE.size() <= MAX_CACHE_ENTRIES) return;
        long now = System.currentTimeMillis();
        CACHE.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().expiresAt() <= now);
        while (CACHE.size() > MAX_CACHE_ENTRIES) {
            String key = CACHE.keySet().stream().findFirst().orElse(null);
            if (key == null) return;
            CACHE.remove(key);
        }
    }

    public record Release(long id, String title, String coverUrl, boolean single, List<String> artists) {
        public Release {
            title = title == null || title.isBlank() ? "Unknown" : title;
            artists = artists == null ? List.of("Unknown") : List.copyOf(artists);
        }
    }

    private record CachedReleases(List<Release> releases, long expiresAt) {
        boolean expired() {
            return System.currentTimeMillis() >= expiresAt;
        }
    }
}
