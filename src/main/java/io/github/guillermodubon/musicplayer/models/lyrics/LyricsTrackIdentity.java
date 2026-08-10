package io.github.guillermodubon.musicplayer.models.lyrics;

import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Song;

import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds the album-independent identity used to reuse lyrics safely. */
public final class LyricsTrackIdentity {

    private static final String SEPARATOR = "\u001F";

    private LyricsTrackIdentity() {
    }

    public static String fromSong(Song song) {
        if (song == null) return "";
        String title = normalize(song.getTitle());
        List<String> artists = artistNames(song);
        if (title.isBlank() || artists.isEmpty()) return "";
        return title + SEPARATOR + String.join("\u001E", artists.stream().map(LyricsTrackIdentity::normalize).toList());
    }

    /**
     * Keeps the same artist preference used by downloaded metadata: an
     * exclusive track contributor is preferred, then the album artist.
     * This also makes older persisted lyrics match after another album edition
     * is opened.
     */
    public static String artistName(Song song) {
        if (song == null || song.getArtist() == null) return "";
        List<Artist> albumArtists = song.getAlbum() == null
                ? List.of()
                : song.getAlbum().getArtist();
        List<Artist> contributors = song.getArtist().stream()
                .filter(artist -> artist != null && !isAlbumArtist(artist, albumArtists))
                .toList();
        String contributor = firstArtist(contributors);
        return contributor.isBlank() ? firstArtist(song.getArtist()) : contributor;
    }

    /** Returns the complete artist set attached to the song, independent of its album. */
    public static List<String> artistNames(Song song) {
        if (song == null || song.getArtist() == null) return List.of();
        Map<String, String> unique = new LinkedHashMap<>();
        for (Artist artist : song.getArtist()) {
            if (artist == null || artist.getName() == null || artist.getName().isBlank()) continue;
            String name = artist.getName().trim();
            unique.putIfAbsent(normalize(name), name);
        }
        List<String> result = new ArrayList<>(unique.values());
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(result);
    }

    private static String firstArtist(List<Artist> artists) {
        if (artists == null) return "";
        for (Artist artist : artists) {
            if (artist == null || artist.getName() == null || artist.getName().isBlank()) continue;
            String name = artist.getName().trim();
            String normalized = normalize(name);
            if (!normalized.equals("unknown")
                    && !normalized.equals("unknown artist")
                    && !normalized.equals("desconocido")
                    && !normalized.equals("various artists")
                    && !normalized.equals("varios artistas")) {
                return name;
            }
        }
        return "";
    }

    private static boolean isAlbumArtist(Artist candidate, List<Artist> albumArtists) {
        if (candidate == null || albumArtists == null) return false;
        return albumArtists.stream().anyMatch(albumArtist -> albumArtist != null
                && ((candidate.getArtistID() > 0 && candidate.getArtistID() == albumArtist.getArtistID())
                || (candidate.getName() != null && albumArtist.getName() != null
                && candidate.getName().equalsIgnoreCase(albumArtist.getName()))));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
