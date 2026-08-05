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

        /*
         * A partially downloaded album can contain local songs beside remote
         * visual songs. Local metadata may carry a normalized/default track
         * position, so sorting that mixed snapshot can move only the playable
         * rows even though the source list is already in Deezer's order.
         * Keep that source sequence stable while the album has both states.
         */
        boolean hasLocalSongs = songs.stream().anyMatch(song -> song != null && song.isLocal());
        boolean hasRemoteSongs = songs.stream().anyMatch(song -> song != null && !song.isLocal());
        if (hasLocalSongs && hasRemoteSongs) {
            return new ArrayList<>(songs);
        }

        /*
         * A download can enrich only part of an album with track positions.
         * Sorting at that point would move the enriched tracks ahead of the
         * remaining visual tracks and would destroy Deezer's original order.
         * Until every row has a valid position, the source sequence is the
         * only complete and stable order available.
         */
        boolean hasCompleteTrackOrder = songs.stream()
                .allMatch(song -> song != null && song.getTrackOrder() > 0);
        if (!hasCompleteTrackOrder) {
            return new ArrayList<>(songs);
        }

        // Once the complete album metadata is available, use Deezer's track
        // positions. List.sort remains stable if an upstream source repeats a
        // position, preserving the existing sequence for that tie.
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
