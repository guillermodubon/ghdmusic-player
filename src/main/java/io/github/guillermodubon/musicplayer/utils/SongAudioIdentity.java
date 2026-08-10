package io.github.guillermodubon.musicplayer.utils;

import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.DeezerApiMetaData;
import io.github.guillermodubon.musicplayer.models.Song;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Defines when two independently identified Deezer tracks may safely reuse
 * the same local audio file.
 *
 * <p>Album editions can expose one recording through different track IDs and
 * covers. A title alone is never enough to connect them: the complete set of
 * credited artists must match as well. Missing or placeholder artist data is
 * intentionally treated as non-matchable rather than risking a wrong file.</p>
 */
public final class SongAudioIdentity {

    private static final String KEY_PREFIX = "audio:v1:";

    private SongAudioIdentity() {
    }

    public static Optional<String> keyFor(Song song) {
        if (song == null) {
            return Optional.empty();
        }
        return keyFor(song.getTitle(), participantNames(song));
    }

    public static Optional<String> keyFor(DeezerApiMetaData metadata) {
        if (metadata == null) {
            return Optional.empty();
        }
        return keyFor(metadata.getSongName(), metadataArtistNames(metadata));
    }

    public static Optional<String> keyFor(String title, Collection<String> artists) {
        String normalizedTitle = normalize(title);
        TreeSet<String> normalizedArtists = normalizedArtists(artists);
        if (normalizedTitle.isBlank() || normalizedArtists.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(KEY_PREFIX + normalizedTitle + "\u0000" + String.join("\u0001", normalizedArtists));
    }

    public static boolean matches(Song first, Song second) {
        Optional<String> firstKey = keyFor(first);
        return firstKey.isPresent() && firstKey.equals(keyFor(second));
    }

    public static boolean matches(Song song, DeezerApiMetaData metadata) {
        Optional<String> songKey = keyFor(song);
        return songKey.isPresent() && songKey.equals(keyFor(metadata));
    }

    public static List<String> metadataArtistNames(DeezerApiMetaData metadata) {
        if (metadata == null) {
            return List.of();
        }

        TreeSet<String> artists = normalizedArtists(metadata.getAlbumArtistNames());
        artists.addAll(normalizedArtists(metadata.getSongContributorNames()));
        return List.copyOf(artists);
    }

    /**
     * Song contributors and album owners are hydrated through different
     * relations depending on the Deezer endpoint. Both contribute to the
     * participant list rendered by the UI, so both must take part in the
     * cross-edition audio identity as well.
     */
    private static List<String> participantNames(Song song) {
        TreeSet<String> participants = new TreeSet<>();
        addArtistNames(participants, song.getArtist());
        if (song.getAlbum() != null) {
            addArtistNames(participants, song.getAlbum().getArtist());
        }
        return List.copyOf(participants);
    }

    private static void addArtistNames(Collection<String> target, List<Artist> artists) {
        if (target == null || artists == null) {
            return;
        }
        for (Artist artist : artists) {
            if (artist != null) {
                String name = artist.getName();
                if (name != null && !name.isBlank()) {
                    target.add(name);
                }
            }
        }
    }

    private static TreeSet<String> normalizedArtists(Collection<String> artists) {
        TreeSet<String> normalized = new TreeSet<>();
        if (artists == null) {
            return normalized;
        }
        for (String artist : artists) {
            String value = normalize(artist);
            if (!value.isBlank() && !isPlaceholderArtist(value)) {
                normalized.add(value);
            }
        }
        return normalized;
    }

    private static boolean isPlaceholderArtist(String value) {
        return value.equals("unknown")
                || value.equals("unknown artist")
                || value.equals("desconocido");
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
