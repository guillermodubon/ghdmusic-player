package io.github.guillermodubon.musicplayer.models.lyrics;

/** Plain, non-timed lyrics kept as the fallback representation. */
public record PlainLyrics(String text) {

    public PlainLyrics {
        text = text == null ? "" : text;
    }

    public boolean isAvailable() {
        return !text.isBlank();
    }
}
