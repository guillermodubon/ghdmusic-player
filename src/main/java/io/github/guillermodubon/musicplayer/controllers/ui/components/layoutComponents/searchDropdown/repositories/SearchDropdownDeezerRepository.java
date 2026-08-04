package io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.searchDropdown.repositories;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.guillermodubon.musicplayer.utils.MusicCardHelper;
import io.github.guillermodubon.musicplayer.utils.DeezerEndpoints;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class SearchDropdownDeezerRepository {

    private final DeezerEndpoints.SearchDropdownEndpoints endpoints;

    public SearchDropdownDeezerRepository(DeezerEndpoints.SearchDropdownEndpoints endpoints) {
        this.endpoints = endpoints;
    }

    public JsonObject searchAll(String query) throws IOException {
        return MusicCardHelper.fetchJsonObject(endpoints.searchAll(encode(query)));
    }

    public JsonArray searchAlbums(String query) throws IOException {
        return fetchArray(endpoints.searchAlbums(encode(query)));
    }

    public JsonArray searchTracks(String query) throws IOException {
        return fetchArray(endpoints.searchTracks(encode(query)));
    }

    public JsonArray searchPlaylists(String query) throws IOException {
        return fetchArray(endpoints.searchPlaylists(encode(query)));
    }

    public JsonArray searchArtists(String query) throws IOException {
        return fetchArray(endpoints.searchArtists(encode(query)));
    }

    private JsonArray fetchArray(String url) throws IOException {
        JsonObject obj = MusicCardHelper.fetchJsonObject(url);
        return obj != null && obj.has("data") && obj.get("data").isJsonArray()
                ? obj.getAsJsonArray("data")
                : new JsonArray();
    }

    private String encode(String query) {
        return URLEncoder.encode(query == null ? "" : query.trim(), StandardCharsets.UTF_8);
    }
}
