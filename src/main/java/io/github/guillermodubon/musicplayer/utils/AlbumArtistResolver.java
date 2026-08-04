package io.github.guillermodubon.musicplayer.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Extracts the artists that own an album or single from a Deezer resource.
 *
 * <p>Album resources can expose a primary {@code artist}, an {@code artists}
 * collection, or a {@code contributors} collection. The latter is filtered
 * by role so track-only collaborators are not displayed as album creators.</p>
 */
public final class AlbumArtistResolver {

    private AlbumArtistResolver() {
    }

    public static List<ArtistReference> resolve(JsonObject resource) {
        if (resource == null) return List.of();

        Map<String, ArtistReference> artists = new LinkedHashMap<>();
        addArtist(artists, resource.has("artist") && resource.get("artist").isJsonObject()
                ? resource.getAsJsonObject("artist") : null);

        addArtistsArray(artists, resource, "artists");

        if (resource.has("contributors") && resource.get("contributors").isJsonArray()) {
            for (JsonElement element : resource.getAsJsonArray("contributors")) {
                if (!element.isJsonObject() || !isMainAlbumArtist(element.getAsJsonObject())) continue;
                addArtist(artists, element.getAsJsonObject());
            }
        }

        return List.copyOf(artists.values());
    }

    public static List<String> names(JsonObject resource) {
        List<String> names = new ArrayList<>();
        for (ArtistReference artist : resolve(resource)) {
            if (artist.name() != null && !artist.name().isBlank()) names.add(artist.name());
        }
        return List.copyOf(names);
    }

    public static List<Long> ids(JsonObject resource) {
        List<Long> ids = new ArrayList<>();
        for (ArtistReference artist : resolve(resource)) {
            if (artist.id() > 0) ids.add(artist.id());
        }
        return List.copyOf(ids);
    }

    public static boolean hasExplicitOwnerCollection(JsonObject resource) {
        if (resource == null) return false;
        return hasArray(resource, "contributors") || hasArray(resource, "artists");
    }

    private static boolean hasArray(JsonObject resource, String field) {
        return resource.has(field)
                && resource.get(field).isJsonArray()
                && !resource.getAsJsonArray(field).isEmpty();
    }

    private static void addArtistsArray(Map<String, ArtistReference> target,
                                        JsonObject resource,
                                        String field) {
        if (!resource.has(field) || !resource.get(field).isJsonArray()) return;
        for (JsonElement element : resource.getAsJsonArray(field)) {
            if (element.isJsonObject()) addArtist(target, element.getAsJsonObject());
        }
    }

    private static void addArtist(Map<String, ArtistReference> target, JsonObject object) {
        if (object == null) return;
        long id = safeLong(object, "id");
        String name = firstText(object, "name", "title");
        if (id <= 0 && (name == null || name.isBlank())) return;

        String key = id > 0
                ? "id:" + id
                : "name:" + name.trim().toLowerCase(Locale.ROOT);
        target.putIfAbsent(key, new ArtistReference(id, name == null ? "" : name.trim()));
    }

    private static boolean isMainAlbumArtist(JsonObject object) {
        String role = firstText(object, "role");
        return role == null || role.isBlank() || role.toLowerCase(Locale.ROOT).contains("main");
    }

    private static long safeLong(JsonObject object, String field) {
        try {
            return object.has(field) && !object.get(field).isJsonNull()
                    ? object.get(field).getAsLong()
                    : -1L;
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private static String firstText(JsonObject object, String... fields) {
        for (String field : fields) {
            try {
                if (object.has(field) && !object.get(field).isJsonNull()) {
                    String value = object.get(field).getAsString();
                    if (value != null && !value.isBlank()) return value.trim();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    public record ArtistReference(long id, String name) {
    }
}
