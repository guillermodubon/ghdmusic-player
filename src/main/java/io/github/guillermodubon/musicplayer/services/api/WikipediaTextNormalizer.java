package io.github.guillermodubon.musicplayer.services.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Normalizes Wikipedia response text, identifiers, and query encodings. */
final class WikipediaTextNormalizer {

    private WikipediaTextNormalizer() {
    }

    static String firstSentence(String extract) {
        if (extract == null) {
            return "";
        }
        int dot = extract.indexOf('.');
        return dot > 0 ? extract.substring(0, dot + 1) : extract;
    }

    static String stringField(JsonObject object, String field) {
        if (object == null || field == null || !object.has(field) || object.get(field).isJsonNull()) {
            return "";
        }
        try {
            return object.get(field).getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    static String elementString(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        try {
            return element.getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    static String stripHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("<[^>]+>", " ")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ")
                .trim();
    }

    static String candidateKey(String value) {
        return stripHtml(value)
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    static String cacheKey(String value) {
        return stripHtml(value)
                .replaceAll("\\([^)]*\\)", " ")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    static String compactKey(String value) {
        return stripHtml(value)
                .replaceAll("\\([^)]*\\)", " ")
                .replaceAll("[^\\p{L}\\p{N}]+", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    static String stylizedCompactKey(String value) {
        return stripHtml(value)
                .toLowerCase(Locale.ROOT)
                .replace("$", "s")
                .replace("!", "i")
                .replace("@", "a")
                .replaceAll("\\([^)]*\\)", " ")
                .replaceAll("[^\\p{L}\\p{N}]+", "")
                .trim();
    }

    static String encodeQuery(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    static String encodePath(String value) {
        return encodeQuery(value).replace("+", "%20");
    }

    static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
