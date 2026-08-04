package io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.repositories;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.guillermodubon.musicplayer.services.api.DeezerJsonCache;

public class GenrePageDeezerRepository {

    public JsonArray getArray(String url) {
        try {
            JsonElement element = DeezerJsonCache.getInstance().getJsonElement(url);
            if (element != null && element.isJsonArray()) return element.getAsJsonArray();
            if (element != null && element.isJsonObject()
                    && element.getAsJsonObject().has("data")
                    && element.getAsJsonObject().get("data").isJsonArray()) {
                return element.getAsJsonObject().getAsJsonArray("data");
            }
        } catch (Exception ignored) {
        }
        return new JsonArray();
    }

    public JsonObject getObject(String url) {
        try {
            return DeezerJsonCache.getInstance().getJsonObject(url);
        } catch (Exception ignored) {
            return null;
        }
    }
}
