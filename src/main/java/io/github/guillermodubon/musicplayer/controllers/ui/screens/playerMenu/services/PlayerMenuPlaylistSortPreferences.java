package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services;

import java.util.prefs.Preferences;

/** Persists the selected order independently for each local playlist. */
final class PlayerMenuPlaylistSortPreferences {

    private static final Preferences PREFERENCES =
            Preferences.userNodeForPackage(PlayerMenuPlaylistSortPreferences.class);
    private static final String SORT_PREFIX = "playlistSort.";

    private PlayerMenuPlaylistSortPreferences() {
    }

    static PlayerMenuPlaylistSort load(long playlistId) {
        if (playlistId <= 0) return PlayerMenuPlaylistSort.RECENTLY_ADDED;
        return PlayerMenuPlaylistSort.fromId(PREFERENCES.get(SORT_PREFIX + playlistId, null));
    }

    static void save(long playlistId, PlayerMenuPlaylistSort sort) {
        if (playlistId <= 0 || sort == null) return;
        PREFERENCES.put(SORT_PREFIX + playlistId, sort.name());
    }
}
