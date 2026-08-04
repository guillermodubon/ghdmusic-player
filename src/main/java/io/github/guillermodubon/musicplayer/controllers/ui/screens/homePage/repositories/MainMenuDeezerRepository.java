package io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.repositories;

import com.google.gson.JsonObject;
import io.github.guillermodubon.musicplayer.services.api.DeezerJsonCache;

import java.io.IOException;

public class MainMenuDeezerRepository {

    public JsonObject getJson(String urlStr) throws IOException {
        return DeezerJsonCache.getInstance().getJsonObject(urlStr);
    }
}
