package io.github.guillermodubon.musicplayer.models.lyrics;

/** A persisted lyrics row together with the duration used to validate reuse. */
public record LyricsTrackMatch(String trackKey, int durationSeconds, SongLyrics lyrics) {

    public LyricsTrackMatch {
        trackKey = trackKey == null ? "" : trackKey;
        durationSeconds = Math.max(0, durationSeconds);
        lyrics = lyrics == null ? SongLyrics.empty() : lyrics;
    }
}
