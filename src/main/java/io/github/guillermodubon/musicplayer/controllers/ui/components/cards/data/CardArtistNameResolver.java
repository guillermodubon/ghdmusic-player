package io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data;

import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.utils.ArtistIdentity;

import java.util.LinkedHashSet;
import java.util.List;

public final class CardArtistNameResolver {

    private CardArtistNameResolver() {}

    public static List<String> fromSong(Song song) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        addArtists(names, song == null ? null : song.getArtist());
        addArtists(names, song == null || song.getAlbum() == null ? null : song.getAlbum().getArtist());
        return toList(names);
    }

    public static List<String> fromAlbum(Album album) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        addArtists(names, album == null ? null : album.getArtist());
        if (names.isEmpty() && isCompilation(album)) {
            return List.of(MusicCardData.VARIOUS_ARTISTS_LABEL);
        }
        return toList(names);
    }

    /**
     * Resolves a single as a release first. This keeps track-only collaborators
     * out of single cards while retaining a safe song fallback for incomplete data.
     */
    public static List<String> fromSingle(Song song) {
        if (song == null) return toList(new LinkedHashSet<>());
        Album album = song.getAlbum();
        if (album != null && album.getArtist() != null && !album.getArtist().isEmpty()) {
            return fromAlbum(album);
        }
        return fromSong(song);
    }

    private static boolean isCompilation(Album album) {
        if (album == null) return false;
        if (album.getNumberOfTracks() > 1) return true;
        return album.getSongList() != null && album.getSongList().size() > 1;
    }

    public static List<String> fromArtists(List<Artist> artists) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        addArtists(names, artists);
        return toList(names);
    }

    private static void addArtists(LinkedHashSet<String> target, List<Artist> artists) {
        if (target == null || artists == null) return;
        for (Artist artist : artists) {
            if (artist == null || artist.getName() == null) continue;
            String name = artist.getName().trim();
            if (name.isBlank()) continue;
            target.add(ArtistIdentity.displayName(name));
        }
    }

    private static List<String> toList(LinkedHashSet<String> names) {
        return names == null || names.isEmpty()
                ? List.of(MusicCardData.UNKNOWN_ARTIST_LABEL)
                : List.copyOf(names);
    }
}
