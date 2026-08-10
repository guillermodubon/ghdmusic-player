package io.github.guillermodubon.musicplayer.models.lyrics;

/** One timed line parsed from LRCLIB's LRC representation. */
public record LyricLine(long timestampMillis, String text) {

    public LyricLine {
        timestampMillis = Math.max(0L, timestampMillis);
        text = text == null ? "" : text;
    }
}
