package io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.repositories;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.guillermodubon.musicplayer.utils.MusicCardHelper;
import io.github.guillermodubon.musicplayer.utils.DeezerEndpoints;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class ArtistPageDeezerRepository {
    private static final long COLLECTION_TTL_MILLIS = 5 * 60 * 1000L;
    private static final long SEARCH_TTL_MILLIS = 2 * 60 * 1000L;
    private static final int MAX_COLLECTION_CACHE_ENTRIES = 96;

    private static final ConcurrentMap<Long, JsonObject> ALBUM_DETAIL_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Long, JsonObject> TRACK_DETAIL_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, TimedJsonObject> OBJECT_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, TimedJsonArray> ARRAY_CACHE = new ConcurrentHashMap<>();

    public JsonArray fetchArtistAlbums(long artistId, DeezerEndpoints.ArtistPageEndpoints endpoints) {
        if (artistId <= 0 || endpoints == null) return null;
        String key = "artist-albums:" + artistId;
        JsonArray cached = cachedArray(key);
        if (cached != null) return cached;
        try {
            JsonObject root = MusicCardHelper.fetchJsonObject(withLimit(endpoints.artistAlbums(artistId), 24));
            if (root == null || !root.has("data") || !root.get("data").isJsonArray()) return null;
            JsonArray data = root.getAsJsonArray("data");
            cacheArray(key, data, COLLECTION_TTL_MILLIS);
            return data;
        } catch (Exception ignored) {}
        return null;
    }

    public JsonObject fetchTopTracks(long artistId, int limit, DeezerEndpoints.ArtistPageEndpoints endpoints) throws IOException {
        if (artistId <= 0 || endpoints == null) return null;
        String key = "artist-top:" + artistId + ':' + Math.max(1, limit);
        JsonObject cached = cachedObject(key);
        if (cached != null) return cached;
        JsonObject result = MusicCardHelper.fetchJsonObject(endpoints.artistTopTracks(artistId, limit));
        cacheObject(key, result, COLLECTION_TTL_MILLIS);
        return result;
    }

    public JsonObject searchPlaylists(String query, DeezerEndpoints.ArtistPageEndpoints endpoints) throws IOException {
        String safeQuery = query == null ? "" : query.trim();
        String encoded = URLEncoder.encode(safeQuery, StandardCharsets.UTF_8);
        if (endpoints == null) return null;
        String key = "search-playlists:" + safeQuery.toLowerCase();
        JsonObject cached = cachedObject(key);
        if (cached != null) return cached;
        JsonObject result = MusicCardHelper.fetchJsonObject(withLimit(endpoints.searchPlaylists(encoded), 8));
        cacheObject(key, result, SEARCH_TTL_MILLIS);
        return result;
    }

    public JsonObject searchTracks(String query) throws IOException {
        String safeQuery = query == null ? "" : query.trim();
        String encoded = URLEncoder.encode(safeQuery, StandardCharsets.UTF_8);
        String key = "search-tracks:" + safeQuery.toLowerCase();
        JsonObject cached = cachedObject(key);
        if (cached != null) return cached;
        JsonObject result = MusicCardHelper.fetchJsonObject(withLimit("https://api.deezer.com/search/track?q=" + encoded, 10));
        cacheObject(key, result, SEARCH_TTL_MILLIS);
        return result;
    }

    public JsonArray searchAlbums(String query) {
        try {
            String safeQuery = query == null ? "" : query.trim();
            String key = "search-albums:" + safeQuery.toLowerCase();
            JsonArray cached = cachedArray(key);
            if (cached != null) return cached;

            String encoded = URLEncoder.encode(safeQuery, StandardCharsets.UTF_8);
            JsonObject root = MusicCardHelper.fetchJsonObject(withLimit("https://api.deezer.com/search/album?q=" + encoded, 20));
            if (root != null && root.has("data") && root.get("data").isJsonArray()) {
                JsonArray data = root.getAsJsonArray("data");
                cacheArray(key, data, SEARCH_TTL_MILLIS);
                return data;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public JsonObject fetchAlbumById(long albumId, DeezerEndpoints.ArtistPageEndpoints endpoints) throws IOException {
        if (albumId <= 0) return null;
        JsonObject cached = ALBUM_DETAIL_CACHE.get(albumId);
        if (cached != null) return cached.deepCopy();
        JsonObject fetched = MusicCardHelper.fetchJsonObject(endpoints.albumById(albumId));
        if (fetched != null) ALBUM_DETAIL_CACHE.putIfAbsent(albumId, fetched.deepCopy());
        return fetched;
    }

    public JsonObject fetchTrackById(long trackId, DeezerEndpoints.ArtistPageEndpoints endpoints) throws IOException {
        if (trackId <= 0) return null;
        JsonObject cached = TRACK_DETAIL_CACHE.get(trackId);
        if (cached != null) return cached.deepCopy();
        JsonObject fetched = MusicCardHelper.fetchJsonObject(endpoints.trackById(trackId));
        if (fetched != null) TRACK_DETAIL_CACHE.putIfAbsent(trackId, fetched.deepCopy());
        return fetched;
    }

    private JsonObject cachedObject(String key) {
        TimedJsonObject entry = OBJECT_CACHE.get(key);
        if (entry == null || entry.isExpired()) {
            if (entry != null) OBJECT_CACHE.remove(key, entry);
            return null;
        }
        return entry.value().deepCopy();
    }

    private JsonArray cachedArray(String key) {
        TimedJsonArray entry = ARRAY_CACHE.get(key);
        if (entry == null || entry.isExpired()) {
            if (entry != null) ARRAY_CACHE.remove(key, entry);
            return null;
        }
        return entry.value().deepCopy();
    }

    private void cacheObject(String key, JsonObject value, long ttlMillis) {
        if (key == null || value == null) return;
        trimCaches();
        OBJECT_CACHE.put(key, new TimedJsonObject(value.deepCopy(), expiresAt(ttlMillis)));
    }

    private void cacheArray(String key, JsonArray value, long ttlMillis) {
        if (key == null || value == null) return;
        trimCaches();
        ARRAY_CACHE.put(key, new TimedJsonArray(value.deepCopy(), expiresAt(ttlMillis)));
    }

    private void trimCaches() {
        if (OBJECT_CACHE.size() + ARRAY_CACHE.size() < MAX_COLLECTION_CACHE_ENTRIES) return;
        long now = System.currentTimeMillis();
        OBJECT_CACHE.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().expiresAt() <= now);
        ARRAY_CACHE.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().expiresAt() <= now);

        while (OBJECT_CACHE.size() + ARRAY_CACHE.size() >= MAX_COLLECTION_CACHE_ENTRIES) {
            String objectKey = OBJECT_CACHE.keySet().stream().findFirst().orElse(null);
            if (objectKey != null) {
                OBJECT_CACHE.remove(objectKey);
                continue;
            }
            String arrayKey = ARRAY_CACHE.keySet().stream().findFirst().orElse(null);
            if (arrayKey == null) return;
            ARRAY_CACHE.remove(arrayKey);
        }
    }

    private long expiresAt(long ttlMillis) {
        return System.currentTimeMillis() + Math.max(1L, ttlMillis);
    }

    private record TimedJsonObject(JsonObject value, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() >= expiresAt; }
    }

    private record TimedJsonArray(JsonArray value, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() >= expiresAt; }
    }

    private String withLimit(String url, int limit) {
        if (url == null || url.isBlank()) return url;
        if (url.contains("limit=")) return url;
        return url + (url.contains("?") ? "&" : "?") + "limit=" + limit;
    }
}
