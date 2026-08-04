package io.github.guillermodubon.musicplayer.services.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.List;

/** Maps optional Deezer JSON fields without coupling cards to response variants. */
final class DeezerJsonFields {

    private DeezerJsonFields() {
    }

    static String title(JsonObject object) {
        return firstNonBlank(object, "title", "name");
    }

    static String artwork(JsonObject object) {
        return firstNonBlank(object,
                "cover_xl", "cover_big", "cover_medium", "cover_small", "cover",
                "picture_xl", "picture_big", "picture_medium", "picture_small", "picture");
    }

    static String highResolutionCover(JsonObject object) {
        return firstNonBlank(object,
                "cover_xl", "cover_big", "cover_medium", "cover_small", "cover",
                "picture_xl", "picture_big", "picture_medium", "picture_small", "picture");
    }

    static String highResolutionPicture(JsonObject object) {
        return firstNonBlank(object,
                "picture_xl", "picture_big", "picture_medium", "picture_small", "picture",
                "cover_xl", "cover_big", "cover_medium", "cover_small", "cover");
    }

    static String firstNonBlank(JsonObject object, String... keys) {
        if (object == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            try {
                if (key == null || !object.has(key) || object.get(key).isJsonNull()) {
                    continue;
                }
                String value = object.get(key).getAsString();
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    static long longValue(JsonObject object, String key, long fallback) {
        try {
            if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
                return fallback;
            }
            JsonElement element = object.get(key);
            if (element.isJsonPrimitive()) {
                JsonPrimitive primitive = element.getAsJsonPrimitive();
                if (primitive.isNumber()) {
                    return primitive.getAsLong();
                }
                if (primitive.isString()) {
                    return Long.parseLong(primitive.getAsString());
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    static int integerValue(JsonObject object, String key, int fallback) {
        try {
            if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
                return fallback;
            }
            JsonElement element = object.get(key);
            if (element.isJsonPrimitive()) {
                JsonPrimitive primitive = element.getAsJsonPrimitive();
                if (primitive.isNumber()) {
                    return primitive.getAsInt();
                }
                if (primitive.isString()) {
                    return Integer.parseInt(primitive.getAsString());
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    static int indexOfName(List<String> names, String expectedName) {
        if (names == null || expectedName == null) {
            return -1;
        }
        String expected = expectedName.strip();
        for (int index = 0; index < names.size(); index++) {
            String current = names.get(index);
            if (current != null && current.strip().equalsIgnoreCase(expected)) {
                return index;
            }
        }
        return -1;
    }
}
