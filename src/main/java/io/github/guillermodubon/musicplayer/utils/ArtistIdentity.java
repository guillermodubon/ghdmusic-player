package io.github.guillermodubon.musicplayer.utils;

import io.github.guillermodubon.musicplayer.models.Artist;

import java.util.Locale;

/** Shared identity rules for artist names that are supplied by Deezer. */
public final class ArtistIdentity {

    private ArtistIdentity() {
    }

    /** Deezer's aggregate artist is metadata, not a navigable artist entity. */
    public static boolean isVariousArtists(Artist artist) {
        return artist != null && isVariousArtists(artist.getName());
    }

    public static boolean isVariousArtists(String name) {
        if (name == null || name.isBlank()) return false;

        String normalized = name.strip()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
        return normalized.equals("varios artistas")
                || normalized.equals("various artists");
    }

    /** Stable user-facing label for Deezer's aggregate artist. */
    public static String displayName(String name) {
        if (isVariousArtists(name)) return "Various Artists";
        return name == null || name.isBlank() ? "Unknown" : name.trim();
    }
}
