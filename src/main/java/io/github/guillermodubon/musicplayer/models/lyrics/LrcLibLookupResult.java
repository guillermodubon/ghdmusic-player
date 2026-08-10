package io.github.guillermodubon.musicplayer.models.lyrics;

/** Internal, normalized LRCLIB response independent from the HTTP client. */
public record LrcLibLookupResult(
        SongLyrics lyrics,
        long retryAfterMillis
) {

    public LrcLibLookupResult {
        lyrics = lyrics == null ? SongLyrics.empty() : lyrics;
        retryAfterMillis = Math.max(0L, retryAfterMillis);
    }
}
