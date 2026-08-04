package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services;

import java.util.Locale;

/** Supported orders for songs in a user-created local playlist. */
public enum PlayerMenuPlaylistSort {
    TITLE("Title"),
    ARTIST("Artist"),
    ALBUM("Album"),
    RECENTLY_ADDED("Recently added"),
    CUSTOM("Custom");

    private final String label;

    PlayerMenuPlaylistSort(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static PlayerMenuPlaylistSort fromId(String id) {
        if (id == null || id.isBlank()) return RECENTLY_ADDED;
        try {
            return valueOf(id.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return RECENTLY_ADDED;
        }
    }
}
