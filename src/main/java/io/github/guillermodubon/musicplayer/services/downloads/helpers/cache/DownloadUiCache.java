package io.github.guillermodubon.musicplayer.services.downloads.helpers.cache;

import javafx.scene.image.Image;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class DownloadUiCache {

    private static final ConcurrentMap<String, String> fetchedTitles = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, String> cleanTitles = new ConcurrentHashMap<>();

    private DownloadUiCache() {}

    public static boolean hasThumbnail(String query) {
        return false;
    }

    public static Image getThumbnail(String query) {
        return null;
    }

    public static boolean hasFetchedTitle(String query) {
        return query != null && fetchedTitles.containsKey(query);
    }

    public static String getFetchedTitle(String query) {
        return query == null ? null : fetchedTitles.get(query);
    }

    public static void putFetchedTitle(String query, String title) {
        if (query != null && title != null) fetchedTitles.put(query, title);
    }

    public static boolean hasCleanTitle(String query) {
        return query != null && cleanTitles.containsKey(query);
    }

    public static String getCleanTitle(String query) {
        return query == null ? null : cleanTitles.get(query);
    }

    public static void putCleanTitle(String query, String title) {
        if (query != null && title != null) cleanTitles.put(query, title);
    }

    public static void putAll(String query, String fetchedTitle, String cleanTitle, Image thumbnail) {
        if (query == null) return;
        if (fetchedTitle != null) fetchedTitles.put(query, fetchedTitle);
        if (cleanTitle != null) cleanTitles.put(query, cleanTitle);
    }
}
