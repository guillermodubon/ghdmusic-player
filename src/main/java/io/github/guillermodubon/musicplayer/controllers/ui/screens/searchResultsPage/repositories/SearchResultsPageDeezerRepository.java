package io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.repositories;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.guillermodubon.musicplayer.utils.MusicCardHelper;
import io.github.guillermodubon.musicplayer.utils.DeezerEndpoints;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

public class SearchResultsPageDeezerRepository {

    private final DeezerEndpoints.SearchResultsEndpoints endpoints;
    private static final Map<String, JsonArray> ALBUMS_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, JsonArray> TRACKS_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, JsonArray> PLAYLISTS_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, JsonArray> ARTISTS_CACHE = new ConcurrentHashMap<>();
    private static final Map<Long, JsonObject> TRACK_DETAILS_CACHE = new ConcurrentHashMap<>();
    private static final Map<Long, JsonObject> ALBUM_DETAILS_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<JsonArray>> ALBUMS_IN_FLIGHT = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<JsonArray>> TRACKS_IN_FLIGHT = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<JsonArray>> PLAYLISTS_IN_FLIGHT = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<JsonArray>> ARTISTS_IN_FLIGHT = new ConcurrentHashMap<>();
    private static final Map<Long, CompletableFuture<JsonObject>> TRACK_DETAILS_IN_FLIGHT = new ConcurrentHashMap<>();
    private static final Map<Long, CompletableFuture<JsonObject>> ALBUM_DETAILS_IN_FLIGHT = new ConcurrentHashMap<>();

    public SearchResultsPageDeezerRepository(DeezerEndpoints.SearchResultsEndpoints endpoints) {
        this.endpoints = endpoints;
    }

    public JsonArray searchAlbums(String query) throws IOException {
        return cachedArray(ALBUMS_CACHE, ALBUMS_IN_FLIGHT, query, endpoints.searchAlbums(encode(query)));
    }

    public JsonArray searchTracks(String query) throws IOException {
        return cachedArray(TRACKS_CACHE, TRACKS_IN_FLIGHT, query, endpoints.searchTracks(encode(query)));
    }

    public JsonArray searchPlaylists(String query) throws IOException {
        return cachedArray(PLAYLISTS_CACHE, PLAYLISTS_IN_FLIGHT, query, endpoints.searchPlaylists(encode(query)));
    }

    public JsonArray searchArtists(String query) throws IOException {
        return cachedArray(ARTISTS_CACHE, ARTISTS_IN_FLIGHT, query, endpoints.searchArtists(encode(query)));
    }

    public JsonObject trackById(long id) throws IOException {
        if (id <= 0) return null;
        return cachedObject(TRACK_DETAILS_CACHE, TRACK_DETAILS_IN_FLIGHT, id, () ->
                MusicCardHelper.fetchJsonObject(endpoints.trackById(id))
        );
    }

    public JsonObject albumById(long id) throws IOException {
        if (id <= 0) return null;
        return cachedObject(ALBUM_DETAILS_CACHE, ALBUM_DETAILS_IN_FLIGHT, id, () ->
                MusicCardHelper.fetchJsonObject(endpoints.albumById(id))
        );
    }

    private JsonArray cachedArray(Map<String, JsonArray> cache,
                                  Map<String, CompletableFuture<JsonArray>> inFlight,
                                  String query,
                                  String url) throws IOException {
        String key = key(query);
        JsonArray cached = cache.get(key);
        if (cached != null) return cached;

        CompletableFuture<JsonArray> created = new CompletableFuture<>();
        CompletableFuture<JsonArray> active = inFlight.putIfAbsent(key, created);
        if (active != null) return await(active);

        try {
            JsonArray loaded = fetchArray(url);
            if (loaded != null && loaded.size() > 0) cache.put(key, loaded);
            created.complete(loaded == null ? new JsonArray() : loaded);
            return loaded == null ? new JsonArray() : loaded;
        } catch (IOException ex) {
            created.completeExceptionally(ex);
            throw ex;
        } finally {
            inFlight.remove(key, created);
        }
    }

    private <K> JsonObject cachedObject(Map<K, JsonObject> cache,
                                        Map<K, CompletableFuture<JsonObject>> inFlight,
                                        K key,
                                        JsonObjectLoader loader) throws IOException {
        JsonObject cached = cache.get(key);
        if (cached != null) return cached;

        CompletableFuture<JsonObject> created = new CompletableFuture<>();
        CompletableFuture<JsonObject> active = inFlight.putIfAbsent(key, created);
        if (active != null) return await(active);

        try {
            JsonObject loaded = loader.load();
            if (loaded != null && !loaded.has("error")) cache.put(key, loaded);
            created.complete(loaded);
            return loaded;
        } catch (IOException ex) {
            created.completeExceptionally(ex);
            throw ex;
        } finally {
            inFlight.remove(key, created);
        }
    }

    private <T> T await(CompletableFuture<T> future) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Search request interrupted", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof IOException ioException) throw ioException;
            throw new IOException("Search request failed", cause);
        }
    }

    @FunctionalInterface
    private interface JsonObjectLoader {
        JsonObject load() throws IOException;
    }

    private JsonArray fetchArray(String url) throws IOException {
        JsonObject obj = MusicCardHelper.fetchJsonObject(url);
        if (obj == null || obj.has("error")) {
            throw new IOException("Deezer search response could not be loaded");
        }
        if (!obj.has("data") || !obj.get("data").isJsonArray()) {
            throw new IOException("Deezer search response is missing result data");
        }
        return obj.getAsJsonArray("data");
    }

    private String encode(String query) {
        return URLEncoder.encode(query == null ? "" : query.trim(), StandardCharsets.UTF_8);
    }

    private String key(String query) {
        return query == null ? "" : query.trim().toLowerCase();
    }
}
