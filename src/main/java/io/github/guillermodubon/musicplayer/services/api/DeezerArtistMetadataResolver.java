package io.github.guillermodubon.musicplayer.services.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.guillermodubon.musicplayer.utils.DeezerEndpoints;
import io.github.guillermodubon.musicplayer.utils.MusicCardHelper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Resolves canonical artist metadata from Deezer without accepting unrelated
 * partial search results as an artist match.
 */
public final class DeezerArtistMetadataResolver {

    private static final String EMPTY_ARTIST_IMAGE_HASH =
            "d41d8cd98f00b204e9800998ecf8427e";
    private static final String ARTIST_SEARCH_ENDPOINT =
            "https://api.deezer.com/search/artist?q=%s";

    private static final ConcurrentMap<Long, Optional<JsonObject>> ARTIST_BY_ID_CACHE =
            new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Optional<JsonObject>> EXACT_ARTIST_BY_NAME_CACHE =
            new ConcurrentHashMap<>();

    private DeezerArtistMetadataResolver() {
    }

    /**
     * Resolves an artist using its Deezer identity.
     *
     * A valid ID is authoritative. Falling back to a name search after an ID
     * lookup is unsafe because Deezer can contain several artists with the
     * same name.
     */
    public static JsonObject resolve(long artistId, String artistName) {
        String requestedName = normalizeName(artistName);

        if (artistId > 0) {
            JsonObject byId = findById(artistId);
            return isMatchingArtist(byId, requestedName) ? byId : null;
        }

        return findExactByName(artistName);
    }

    /**
     * Resolves only the portrait URL while preserving a valid portrait already
     * attached to the artist model. This is important for track contributor
     * objects: Deezer can return an empty portrait for a duplicate artist
     * record even though the track payload contains the real artist image.
     */
    public static String resolvePictureUrl(long artistId,
                                           String artistName,
                                           String fallbackPictureUrl) {
        String requestedName = normalizeName(artistName);
        if (artistId > 0) {
            JsonObject byId = findById(artistId);
            if (isMatchingArtist(byId, requestedName) && hasUsablePicture(byId)) {
                return pictureUrl(byId);
            }

            // Keep a portrait already attached to this exact artist, but
            // never use a same-name search result as a substitute.
            return isUsableArtistPictureUrl(fallbackPictureUrl)
                    ? fallbackPictureUrl.trim()
                    : null;
        }

        return pictureUrl(findExactByName(artistName));
    }

    /** Returns the canonical Deezer artist object for an exact name match. */
    public static JsonObject findExactByName(String artistName) {
        String key = normalizeName(artistName);
        if (key.isBlank()) {
            return null;
        }

        Optional<JsonObject> cached = EXACT_ARTIST_BY_NAME_CACHE.get(key);
        if (cached != null) {
            return cached.orElse(null);
        }

        JsonObject resolved = fetchExactByName(artistName, key);
        if (resolved != null) {
            EXACT_ARTIST_BY_NAME_CACHE.putIfAbsent(key, Optional.of(resolved));
        }
        return resolved;
    }

    /** Returns a canonical artist object only when Deezer confirms the ID. */
    public static JsonObject findById(long artistId) {
        if (artistId <= 0) {
            return null;
        }

        Optional<JsonObject> cached = ARTIST_BY_ID_CACHE.get(artistId);
        if (cached != null) {
            return cached.orElse(null);
        }

        JsonObject resolved = null;
        try {
            JsonObject candidate = MusicCardHelper.fetchJsonObject(
                    DeezerEndpoints.artistById(artistId)
            );
            if (safeGetLong(candidate, "id", -1L) == artistId
                    && hasText(stringValue(candidate, "name"))) {
                resolved = candidate;
            }
        } catch (Exception ignored) {
            // A failed request must not prevent the existing local fallback.
        }

        if (resolved != null) {
            ARTIST_BY_ID_CACHE.putIfAbsent(artistId, Optional.of(resolved));
        }
        return resolved;
    }

    /** Returns the highest-resolution, non-placeholder artist picture. */
    public static String pictureUrl(JsonObject artistJson) {
        if (artistJson == null) {
            return null;
        }
        for (String key : new String[]{"picture_xl", "picture_big", "picture_medium", "picture_small"}) {
            String value = stringValue(artistJson, key);
            if (isUsableArtistPictureUrl(value)) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * Artist images must come from Deezer's artist image namespace. This
     * prevents album/single covers and Deezer's empty-image hash from entering
     * artist headers as if they were valid portraits.
     */
    public static boolean isUsableArtistPictureUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String normalized = url.trim().toLowerCase(Locale.ROOT);
        if (!normalized.contains("/images/artist/")) {
            return false;
        }
        if (normalized.contains("/images/artist//")
                || normalized.contains(EMPTY_ARTIST_IMAGE_HASH)
                || normalized.contains("/images/artist/00000000000000000000000000000000/")) {
            return false;
        }
        return !normalized.contains("/images/cover/");
    }

    public static long artistId(JsonObject artistJson) {
        return safeGetLong(artistJson, "id", 0L);
    }

    public static String artistName(JsonObject artistJson) {
        return stringValue(artistJson, "name");
    }

    public static String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return normalized.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static JsonObject fetchExactByName(String artistName, String normalizedName) {
        try {
            String query = URLEncoder.encode(artistName.trim(), StandardCharsets.UTF_8);
            JsonObject response = MusicCardHelper.fetchJsonObject(
                    String.format(ARTIST_SEARCH_ENDPOINT, query)
            );
            if (response == null || !response.has("data")
                    || !response.get("data").isJsonArray()) {
                return null;
            }

            JsonArray artists = response.getAsJsonArray("data");
            JsonObject best = null;
            for (JsonElement element : artists) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject candidate = element.getAsJsonObject();
                if (safeGetLong(candidate, "id", -1L) > 0
                        && namesMatch(normalizedName, stringValue(candidate, "name"))) {
                    if (best == null || compareCandidates(candidate, best) > 0) {
                        best = candidate;
                    }
                }
            }
            return best;
        } catch (Exception ignored) {
            // Keep the current fallback behavior when Deezer is unavailable.
        }
        return null;
    }

    private static boolean namesMatch(String normalizedRequestedName, String candidateName) {
        return !normalizedRequestedName.isBlank()
                && normalizedRequestedName.equals(normalizeName(candidateName));
    }

    private static boolean isMatchingArtist(JsonObject artistJson, String normalizedRequestedName) {
        return artistJson != null
                && safeGetLong(artistJson, "id", 0L) > 0
                && (normalizedRequestedName.isBlank()
                || namesMatch(normalizedRequestedName, stringValue(artistJson, "name")));
    }

    private static boolean hasUsablePicture(JsonObject artistJson) {
        return isUsableArtistPictureUrl(pictureUrl(artistJson));
    }

    private static int compareCandidates(JsonObject left, JsonObject right) {
        int pictureComparison = Boolean.compare(hasUsablePicture(left), hasUsablePicture(right));
        if (pictureComparison != 0) {
            return pictureComparison;
        }

        int fanComparison = Long.compare(
                safeGetLong(left, "nb_fan", 0L),
                safeGetLong(right, "nb_fan", 0L)
        );
        if (fanComparison != 0) {
            return fanComparison;
        }

        return Long.compare(
                safeGetLong(left, "nb_album", 0L),
                safeGetLong(right, "nb_album", 0L)
        );
    }

    private static String stringValue(JsonObject object, String key) {
        try {
            if (object != null && key != null && object.has(key)
                    && !object.get(key).isJsonNull()) {
                return object.get(key).getAsString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static long safeGetLong(JsonObject object, String key, long fallback) {
        try {
            if (object == null || key == null || !object.has(key)
                    || object.get(key).isJsonNull()) {
                return fallback;
            }
            return object.get(key).getAsLong();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
