package io.github.guillermodubon.musicplayer.models.lyrics;

import java.util.Locale;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal identity required to request and persist lyrics safely. */
public record LyricsLookupCandidate(
        long songId,
        String sourceKey,
        String trackName,
        String artistName,
        List<String> artistNames,
        String albumName,
        int durationSeconds
) {

    public LyricsLookupCandidate {
        songId = Math.max(0L, songId);
        sourceKey = sourceKey == null ? "" : sourceKey.trim();
        trackName = trackName == null ? "" : trackName.trim();
        artistName = artistName == null ? "" : artistName.trim();
        artistNames = normalizeArtists(artistNames, artistName);
        albumName = albumName == null ? "" : albumName.trim();
        durationSeconds = Math.max(0, durationSeconds);
    }

    public boolean hasLookupIdentity() {
        return !trackName.isBlank() && !artistName.isBlank();
    }

    /**
     * Identity shared by one track across album editions. The album is
     * intentionally excluded because deluxe, extended and single releases
     * can use different album metadata and covers for the same audio.
     */
    public String trackKey() {
        if (!hasLookupIdentity()) return "";
        String artists = artistNames.isEmpty()
                ? normalize(artistName)
                : String.join("\u001E", artistNames.stream().map(LyricsLookupCandidate::normalize).toList());
        return normalize(trackName) + "\u001F" + artists;
    }

    private static List<String> normalizeArtists(List<String> artists, String fallback) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (artists != null) {
            for (String artist : artists) {
                if (artist != null && !artist.isBlank()) {
                    String cleanName = artist.trim();
                    normalized.putIfAbsent(normalize(cleanName), cleanName);
                }
            }
        }
        if (normalized.isEmpty() && fallback != null && !fallback.isBlank()) {
            String cleanFallback = fallback.trim();
            normalized.put(normalize(cleanFallback), cleanFallback);
        }
        List<String> result = new ArrayList<>(normalized.values());
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(result);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
