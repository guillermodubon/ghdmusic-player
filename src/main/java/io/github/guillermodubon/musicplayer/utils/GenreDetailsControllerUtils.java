package io.github.guillermodubon.musicplayer.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class GenreDetailsControllerUtils {

    private GenreDetailsControllerUtils() {}

    private static final ExecutorService IMAGE_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    public static Image defaultCover() {
        return MediaImageResolver.defaultCover();
    }


    public static String extractArtistDisplayName(JsonObject obj) {
        if (obj == null) return null;

        try {
            if (obj.has("name") && !obj.get("name").isJsonNull()) {
                String v = obj.get("name").getAsString();
                if (v != null && !v.isBlank()) return v.trim();
            }
        } catch (Exception ignored) {}

        try {
            if (obj.has("title") && !obj.get("title").isJsonNull()) {
                String v = obj.get("title").getAsString();
                if (v != null && !v.isBlank()) return v.trim();
            }
        } catch (Exception ignored) {}

        return null;
    }

    public static List<String> extractArtistNamesFromResource(JsonObject obj) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (obj == null) return List.of();

        try {
            if (obj.has("artist") && obj.get("artist").isJsonObject()) {
                String n = extractArtistDisplayName(obj.getAsJsonObject("artist"));
                if (n != null) names.add(n);
            }
        } catch (Exception ignored) {}

        try {
            if (obj.has("contributors") && obj.get("contributors").isJsonArray()) {
                for (JsonElement el : obj.getAsJsonArray("contributors")) {
                    if (el.isJsonObject()) {
                        String n = extractArtistDisplayName(el.getAsJsonObject());
                        if (n != null) names.add(n);
                    }
                }
            }
        } catch (Exception ignored) {}

        return List.copyOf(names);
    }

    public static List<Long> extractArtistIdsFromResource(JsonObject obj) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (obj == null) return List.of();

        try {
            if (obj.has("artist") && obj.get("artist").isJsonObject()) {
                long aid = safeGetLong(obj.getAsJsonObject("artist"), "id", -1L);
                if (aid > 0) ids.add(aid);
            }
        } catch (Exception ignored) {}

        try {
            if (obj.has("contributors") && obj.get("contributors").isJsonArray()) {
                for (JsonElement el : obj.getAsJsonArray("contributors")) {
                    if (el.isJsonObject()) {
                        long aid = safeGetLong(el.getAsJsonObject(), "id", -1L);
                        if (aid > 0) ids.add(aid);
                    }
                }
            }
        } catch (Exception ignored) {}

        return List.copyOf(ids);
    }

    /** Returns album/single owners only; track-only collaborators are excluded. */
    public static List<String> extractAlbumArtistNamesFromResource(JsonObject obj) {
        return AlbumArtistResolver.names(obj);
    }

    /** Returns the Deezer IDs of album/single owners only. */
    public static List<Long> extractAlbumArtistIdsFromResource(JsonObject obj) {
        return AlbumArtistResolver.ids(obj);
    }

    public static long safeGetLong(JsonObject obj, String key, long fallback) {
        try {
            if (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) {
                return obj.get(key).getAsLong();
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    public static String safeGetString(JsonObject obj, String key, String fallback) {
        try {
            if (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) {
                String v = obj.get(key).getAsString();
                if (v != null && !v.isBlank()) return v;
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    public static void loadImageAsync(String url, Consumer<Image> callback) {
        if (url == null || url.isBlank() || callback == null) return;

        IMAGE_EXECUTOR.submit(() -> {
            try {
                Image img = MediaImageResolver.remoteImage(url, 300, 0);
                if (img != null && !img.isError()) {
                    callback.accept(img);
                } else {
                    callback.accept(null);
                }
            } catch (Exception ex) {
                callback.accept(null);
            }
        });
    }

}
