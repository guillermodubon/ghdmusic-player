package io.github.guillermodubon.musicplayer.models.lyrics;

/** Durable outcome of a LRCLIB lookup. */
public enum LyricsStatus {
    PENDING,
    FOUND,
    INSTRUMENTAL,
    NOT_FOUND,
    INSUFFICIENT_METADATA,
    RETRYABLE_ERROR
}
