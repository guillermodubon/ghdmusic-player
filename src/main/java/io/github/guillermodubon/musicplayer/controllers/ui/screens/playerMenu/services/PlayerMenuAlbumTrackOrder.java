package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services;

import io.github.guillermodubon.musicplayer.models.Song;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

        // If no track position is available, preserve the source sequence.
        // That sequence may already be the one returned by Deezer, and there
        // is no safe metadata-based order to derive from zero-valued tracks.
        boolean hasTrackOrder = songs.stream()
                .filter(song -> song != null)
                .anyMatch(song -> song.getTrackOrder() > 0);
        if (!hasTrackOrder) {
            return new ArrayList<>(songs);
        }

        // List.sort is stable: equal/unknown positions keep the source order
        // while known Deezer positions are placed before missing metadata.
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
}
