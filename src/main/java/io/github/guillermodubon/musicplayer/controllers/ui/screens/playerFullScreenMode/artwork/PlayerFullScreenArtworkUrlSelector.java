package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.artwork;

import com.google.gson.JsonObject;

import java.util.List;

/** Selects the best artwork URL exposed by a Deezer response. */
final class PlayerFullScreenArtworkUrlSelector {

    private static final List<String> ARTWORK_KEYS = List.of(
            "cover_xl",
            "cover_big",
            "cover_medium",
            "cover",
            "picture_xl",
            "picture_big",
            "picture_medium",
            "picture"
    );

    private PlayerFullScreenArtworkUrlSelector() {
    }

    static String bestCover(JsonObject source) {
        if (source == null) {
            return null;
        }
        for (String key : ARTWORK_KEYS) {
            try {
                if (source.has(key) && !source.get(key).isJsonNull()) {
                    String value = source.get(key).getAsString();
                    if (value != null && !value.isBlank()) {
                        return value;
                    }
                }
            } catch (Exception ignored) {
                // Try the next known artwork field.
            }
        }
        return null;
    }

    static String bestArtistPortrait(JsonObject source) {
        if (source == null) {
            return null;
        }

        // Artist responses expose portraits through picture_* fields. Keep
        // this separate from album/track cover selection so a lower-quality
        // generic field can never win over Deezer's XL portrait.
        for (String key : List.of(
                "picture_xl",
                "picture_big",
                "picture_medium",
                "picture_small",
                "picture"
        )) {
            try {
                if (source.has(key) && !source.get(key).isJsonNull()) {
                    String value = source.get(key).getAsString();
                    if (value != null && !value.isBlank()) {
                        return value;
                    }
                }
            } catch (Exception ignored) {
                // Try the next portrait size.
            }
        }
        return null;
    }
}
