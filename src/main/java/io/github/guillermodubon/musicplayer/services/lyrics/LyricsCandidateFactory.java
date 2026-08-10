package io.github.guillermodubon.musicplayer.services.lyrics;

import io.github.guillermodubon.musicplayer.models.DeezerApiMetaData;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.models.lyrics.LyricsLookupCandidate;
import io.github.guillermodubon.musicplayer.models.lyrics.LyricsTrackIdentity;
import javafx.util.Pair;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Converts local library and Deezer metadata into a stable LRCLIB signature. */
final class LyricsCandidateFactory {

    private LyricsCandidateFactory() {
    }

    static LyricsLookupCandidate fromDownloadedMetadata(
            DeezerApiMetaData metadata,
            File file,
            long persistedSongId
    ) {
        long songId = persistedSongId > 0 ? persistedSongId
                : metadata == null ? 0L : Math.max(0L, metadata.getTrackId());
        String path = file == null ? "" : file.getAbsolutePath();
        List<String> allArtists = allArtists(metadata);
        return new LyricsLookupCandidate(
                songId,
                sourceKey(songId, path),
                metadata == null ? "" : metadata.getSongName(),
                firstRealArtist(metadata == null ? List.of() : metadata.getSongContributorNames(),
                        metadata == null ? List.of() : metadata.getAlbumArtistNames()),
                allArtists,
                metadata == null ? "" : metadata.getAlbumName(),
                metadata == null ? 0 : metadata.getDurationSeconds()
        );
    }

    static LyricsLookupCandidate fromUnmatchedLocalSong(Pair<String, String> entry) {
        String fileName = entry == null ? "" : entry.getKey();
        String path = entry == null ? "" : entry.getValue();
        String normalized = removeExtension(fileName);
        int separator = normalized.indexOf(" - ");
        String artist = separator > 0 ? normalized.substring(0, separator).trim() : "";
        String title = separator > 0 ? normalized.substring(separator + 3).trim() : normalized.trim();
        return new LyricsLookupCandidate(0L, sourceKey(0L, path), title, artist, List.of(artist), "", 0);
    }

    static LyricsLookupCandidate fromSong(Song song) {
        if (song == null) return new LyricsLookupCandidate(0L, "", "", "", List.of(), "", 0);
        long songId = Math.max(0L, song.getSongID());
        String path = song.getFilePath();
        String album = song.getAlbum() == null ? "" : song.getAlbum().getName();
        return new LyricsLookupCandidate(
                songId,
                sourceKey(songId, path),
                song.getTitle(),
                LyricsTrackIdentity.artistName(song),
                LyricsTrackIdentity.artistNames(song),
                album,
                song.getDurationSeconds()
        );
    }

    private static List<String> allArtists(DeezerApiMetaData metadata) {
        if (metadata == null) return List.of();
        Set<String> unique = new LinkedHashSet<>();
        if (metadata.getAlbumArtistNames() != null) unique.addAll(metadata.getAlbumArtistNames());
        if (metadata.getSongContributorNames() != null) unique.addAll(metadata.getSongContributorNames());
        List<String> result = new ArrayList<>(unique);
        result.removeIf(name -> name == null || name.isBlank());
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(result);
    }

    private static String firstRealArtist(List<String> primary, List<String> fallback) {
        String fromPrimary = firstRealArtist(primary);
        return fromPrimary.isBlank() ? firstRealArtist(fallback) : fromPrimary;
    }

    private static String firstRealArtist(List<String> artists) {
        if (artists == null) return "";
        for (String artist : artists) {
            if (artist == null || artist.isBlank()) continue;
            String normalized = artist.trim().toLowerCase(Locale.ROOT);
            if (!normalized.equals("unknown")
                    && !normalized.equals("unknown artist")
                    && !normalized.equals("desconocido")
                    && !normalized.equals("various artists")
                    && !normalized.equals("varios artistas")) {
                return artist.trim();
            }
        }
        return "";
    }

    private static String removeExtension(String value) {
        if (value == null) return "";
        int lastSeparator = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        int extension = value.lastIndexOf('.');
        return extension > lastSeparator ? value.substring(0, extension) : value;
    }

    private static String sourceKey(long songId, String path) {
        if (songId > 0) return "track:" + songId;
        return "file:" + (path == null ? "" : path.trim().toLowerCase(Locale.ROOT));
    }
}
