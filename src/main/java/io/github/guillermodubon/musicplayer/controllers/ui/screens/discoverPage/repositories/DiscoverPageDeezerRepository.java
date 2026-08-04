package io.github.guillermodubon.musicplayer.controllers.ui.screens.discoverPage.repositories;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.guillermodubon.musicplayer.services.api.DeezerJsonCache;

import java.io.IOException;

public class DiscoverPageDeezerRepository {

    public JsonObject fetchJsonObject(String urlStr) throws IOException {
        JsonElement je = fetchJsonElement(urlStr);
        return (je != null && je.isJsonObject()) ? je.getAsJsonObject() : null;
    }

    public JsonElement fetchJsonElement(String urlStr) throws IOException {
        return DeezerJsonCache.getInstance().getJsonElement(urlStr);
    }

    public JsonObject getJson(String urlStr) throws IOException {
        return DeezerJsonCache.getInstance().getJsonObject(urlStr);
    }
}
