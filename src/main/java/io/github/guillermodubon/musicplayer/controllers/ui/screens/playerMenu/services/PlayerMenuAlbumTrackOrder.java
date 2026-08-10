package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services;

import io.github.guillermodubon.musicplayer.models.Song;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps album tracks in Deezer's track order independently of the cell type
 * used to render each row.
 */
public final class PlayerMenuAlbumTrackOrder {

    private PlayerMenuAlbumTrackOrder() {
    }

    public static List<Song> order(List<Song> songs) {
        if (songs == null || songs.size() < 2) {
            return songs == null ? List.of() : new ArrayList<>(songs);
        }

        /*
         * TrackOrder belongs to the album view, not to the local file that
         * may replace a row after a download. As long as every row exposes a
         * unique Deezer position, it is the authoritative source of order
         * even for a mixed remote/local collection.
         *
         * If an incomplete response has not supplied every position yet, do
         * not guess: keeping the original source sequence is the only safe
         * fallback until Deezer's complete album data is available.
         */
        if (!hasCompleteUniqueTrackOrder(songs)) {
            return new ArrayList<>(songs);
        }

        // The sort is stable, so an unexpected tie cannot scramble rows.
        List<Song> ordered = new ArrayList<>(songs);
        ordered.sort(Comparator.comparingInt(PlayerMenuAlbumTrackOrder::sortTrackOrder));
        return ordered;
    }

    private static int sortTrackOrder(Song song) {
        if (song == null || song.getTrackOrder() <= 0) {
            return Integer.MAX_VALUE;
        }
        return song.getTrackOrder();
    }

    private static boolean hasCompleteUniqueTrackOrder(List<Song> songs) {
        Set<Integer> orders = new HashSet<>();
        for (Song song : songs) {
            if (song == null || song.getTrackOrder() <= 0 || !orders.add(song.getTrackOrder())) {
                return false;
            }
        }
        return true;
    }
}
