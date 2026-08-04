package io.github.guillermodubon.musicplayer.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class DiscoverUtils {

    private static final long JSON_CACHE_TTL_MILLIS = 5 * 60 * 1000L;
    private static final ConcurrentMap<String, CachedJson> JSON_CACHE = new ConcurrentHashMap<>();

    private DiscoverUtils() {}

    public static Image defaultCover() {
        return loadDefaultCover();
    }

    public static Image remoteImage(String url) {
        if (url == null || url.isBlank()) return defaultCover();
        try {
            Image image = MediaImageResolver.remoteCardImage(url);
            return image == null ? defaultCover() : image;
        } catch (Exception e) {
            return defaultCover();
        }
    }

    public static Image loadDefaultCover() {
        return MediaImageResolver.defaultCover();
    }

    public static JsonElement fetchJsonElement(String urlStr) throws IOException {
        CachedJson cached = JSON_CACHE.get(urlStr);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.loadedAtMillis <= JSON_CACHE_TTL_MILLIS) {
            return cached.payload;
        }

        HttpURLConnection con = null;
        try {
            URL url = new URL(urlStr);
            con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.setConnectTimeout(9000);
            con.setReadTimeout(12000);

            int code = con.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
            if (is == null) return null;

            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                String body = sb.toString();
                if (body.isBlank()) return null;

                try (StringReader sr = new StringReader(body)) {
                    JsonReader jr = new JsonReader(sr);
                    jr.setLenient(true);
                    JsonElement parsed = JsonParser.parseReader(jr);
                    JSON_CACHE.put(urlStr, new CachedJson(parsed, now));
                    return parsed;
                } catch (Exception e) {
                    JsonElement parsed = JsonParser.parseString(body);
                    JSON_CACHE.put(urlStr, new CachedJson(parsed, now));
                    return parsed;
                }
            }
        } finally {
            if (con != null) con.disconnect();
        }
    }

    public static JsonObject fetchJsonObject(String urlStr) throws IOException {
        JsonElement el = fetchJsonElement(urlStr);
        return (el != null && el.isJsonObject()) ? el.getAsJsonObject() : null;
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

    public static LinkedHashSet<String> extractArtistNamesFromResource(JsonObject obj) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (obj == null) return names;

        try {
            if (obj.has("artist") && obj.get("artist").isJsonObject()) {
                String name = extractArtistDisplayName(obj.getAsJsonObject("artist"));
                if (name != null && !name.isBlank()) names.add(name);
            }
        } catch (Exception ignored) {}

        try {
            if (obj.has("contributors") && obj.get("contributors").isJsonArray()) {
                for (JsonElement el : obj.getAsJsonArray("contributors")) {
                    if (!el.isJsonObject()) continue;
                    String name = extractArtistDisplayName(el.getAsJsonObject());
                    if (name != null && !name.isBlank()) names.add(name);
                }
            }
        } catch (Exception ignored) {}

        return names;
    }

    public static LinkedHashSet<Long> extractArtistIdsFromResource(JsonObject obj) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (obj == null) return ids;

        try {
            if (obj.has("artist") && obj.get("artist").isJsonObject()) {
                long aid = safeGetLong(obj.getAsJsonObject("artist"), "id", -1L);
                if (aid > 0) ids.add(aid);
            }
        } catch (Exception ignored) {}

        try {
            if (obj.has("contributors") && obj.get("contributors").isJsonArray()) {
                for (JsonElement el : obj.getAsJsonArray("contributors")) {
                    if (!el.isJsonObject()) continue;
                    long aid = safeGetLong(el.getAsJsonObject(), "id", -1L);
                    if (aid > 0) ids.add(aid);
                }
            }
        } catch (Exception ignored) {}

        return ids;
    }

    /** Returns album/single owners only; track-only collaborators are excluded. */
    public static LinkedHashSet<String> extractAlbumArtistNamesFromResource(JsonObject obj) {
        return new LinkedHashSet<>(AlbumArtistResolver.names(obj));
    }

    /** Returns the Deezer IDs of album/single owners only. */
    public static LinkedHashSet<Long> extractAlbumArtistIdsFromResource(JsonObject obj) {
        return new LinkedHashSet<>(AlbumArtistResolver.ids(obj));
    }

    public static List<String> normalizeArtistNames(Collection<String> raw) {
        if (raw == null) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : raw) {
            if (s == null) continue;
            String v = s.trim();
            if (!v.isBlank()) out.add(v);
        }
        return List.copyOf(out);
    }

    public static List<String> resolveTrackArtistNames(DeezerEndpoints.DiscoverEndpoints endpoints, long trackId, JsonObject baseJson) {
        if (trackId <= 0) return List.of("Unknown");

        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.addAll(extractArtistNamesFromResource(baseJson));

        if (names.size() <= 1 && endpoints != null) {
            try {
                JsonObject detail = fetchJsonObject(endpoints.trackById(trackId));
                if (detail != null) names.addAll(extractArtistNamesFromResource(detail));
            } catch (Exception ignored) {}
        }

        List<String> out = normalizeArtistNames(names);
        return out.isEmpty() ? List.of("Unknown") : out;
    }

    public static List<Long> resolveTrackArtistIds(DeezerEndpoints.DiscoverEndpoints endpoints, long trackId, JsonObject baseJson) {
        if (trackId <= 0) return List.of();

        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        ids.addAll(extractArtistIdsFromResource(baseJson));

        if (ids.size() <= 1 && endpoints != null) {
            try {
                JsonObject detail = fetchJsonObject(endpoints.trackById(trackId));
                if (detail != null) ids.addAll(extractArtistIdsFromResource(detail));
            } catch (Exception ignored) {}
        }

        return ids.isEmpty() ? List.of() : List.copyOf(ids);
    }

    public static String resolveTrackCoverUrl(DeezerEndpoints.DiscoverEndpoints endpoints, long trackId, JsonObject baseJson) {
        if (trackId <= 0) return null;

        String cover = null;
        try {
            cover = DeezerApiService.extractHighResolutionCoverUrl(baseJson);
        } catch (Exception ignored) {}

        if ((cover == null || cover.isBlank()) && baseJson != null) {
            try {
                if (baseJson.has("album") && baseJson.get("album").isJsonObject()) {
                    cover = DeezerApiService.extractHighResolutionCoverUrl(baseJson.getAsJsonObject("album"));
                }
            } catch (Exception ignored) {}
        }

        if ((cover == null || cover.isBlank()) && endpoints != null) {
            try {
                JsonObject detail = fetchJsonObject(endpoints.trackById(trackId));
                if (detail != null) {
                    cover = DeezerApiService.extractHighResolutionCoverUrl(detail);
                    if ((cover == null || cover.isBlank()) && detail.has("album") && detail.get("album").isJsonObject()) {
                        cover = DeezerApiService.extractHighResolutionCoverUrl(detail.getAsJsonObject("album"));
                    }
                }
            } catch (Exception ignored) {}
        }

        return cover;
    }


    public static long safeGetLong(JsonObject obj, String key, long fallback) {
        try {
            if (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) {
                return obj.get(key).getAsLong();
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    public static void setImageOnCard(StackPane card, Image img) {
        if (card == null || img == null) return;
        Node iv = findFirstImageView(card);
        if (iv instanceof ImageView imageView) {
            imageView.setImage(img);
        } else {
            card.getProperties().put("resolvedImage", img);
        }
    }

    public static Node findFirstImageView(Node node) {
        if (node == null) return null;
        if (node instanceof ImageView) return node;
        if (node instanceof Parent p) {
            for (Node ch : p.getChildrenUnmodifiable()) {
                Node n = findFirstImageView(ch);
                if (n != null) return n;
            }
        }
        return null;
    }

    public static boolean matchesFilter(String text, Collection<String> extras, String filter) {
        if (filter == null || filter.isBlank()) return true;
        String f = filter.toLowerCase(Locale.ROOT);
        if (text != null && text.toLowerCase(Locale.ROOT).contains(f)) return true;
        if (extras == null) return false;
        for (String s : extras) {
            if (s != null && s.toLowerCase(Locale.ROOT).contains(f)) return true;
        }
        return false;
    }

    private record CachedJson(JsonElement payload, long loadedAtMillis) {}
}
