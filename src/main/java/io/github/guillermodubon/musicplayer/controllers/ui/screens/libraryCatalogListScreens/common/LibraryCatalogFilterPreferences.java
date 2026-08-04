package io.github.guillermodubon.musicplayer.controllers.ui.screens.libraryCatalogListScreens.common;

import java.util.prefs.Preferences;

/** Stores the last selected sort independently for each library catalog. */
public final class LibraryCatalogFilterPreferences {

    private static final Preferences PREFERENCES =
            Preferences.userNodeForPackage(LibraryCatalogFilterPreferences.class);
    private static final String MUSIC_PREFIX = "musicSort.";
    private static final String ARTIST_SORT_KEY = "artistSort";

    private LibraryCatalogFilterPreferences() {
    }

    public static String loadMusicSort(CatalogType type) {
        if (type == null) return null;
        return PREFERENCES.get(MUSIC_PREFIX + type.name(), null);
    }

    public static void saveMusicSort(CatalogType type, String sortId) {
        if (type == null || sortId == null || sortId.isBlank()) return;
        PREFERENCES.put(MUSIC_PREFIX + type.name(), sortId);
    }

    public static String loadArtistSort() {
        return PREFERENCES.get(ARTIST_SORT_KEY, null);
    }

    public static void saveArtistSort(String sortId) {
        if (sortId == null || sortId.isBlank()) return;
        PREFERENCES.put(ARTIST_SORT_KEY, sortId);
    }
}
