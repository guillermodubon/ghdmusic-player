package io.github.guillermodubon.musicplayer.models.lyrics;

/**
 * Offline lyrics associated with a song. Synced lyrics are preferred whenever
 * present; plain lyrics remain available as a reliable fallback.
 */
public record SongLyrics(
        long lrcLibId,
        PlainLyrics plainLyrics,
        SyncedLyrics syncedLyrics,
        boolean instrumental,
        LyricsStatus status,
        long fetchedAtMillis,
        long lastAttemptAtMillis,
        long nextRetryAtMillis
) {

    public SongLyrics {
        plainLyrics = plainLyrics == null ? new PlainLyrics("") : plainLyrics;
        syncedLyrics = syncedLyrics == null ? new SyncedLyrics("") : syncedLyrics;
        status = status == null ? LyricsStatus.PENDING : status;
        fetchedAtMillis = Math.max(0L, fetchedAtMillis);
        lastAttemptAtMillis = Math.max(0L, lastAttemptAtMillis);
        nextRetryAtMillis = Math.max(0L, nextRetryAtMillis);
    }

    public static SongLyrics empty() {
        return new SongLyrics(0L, new PlainLyrics(""), new SyncedLyrics(""), false,
                LyricsStatus.PENDING, 0L, 0L, 0L);
    }

    public boolean hasSyncedLyrics() {
        return syncedLyrics.isAvailable();
    }

    public boolean hasPlainLyrics() {
        return plainLyrics.isAvailable();
    }

    public boolean isAvailable() {
        return hasSyncedLyrics() || hasPlainLyrics();
    }

    /**
     * Selects the strongest representation available for the same track.
     * Timed lyrics always win over plain text, regardless of which album
     * edition supplied the row.
     */
    public static SongLyrics prefer(SongLyrics first, SongLyrics second) {
        SongLyrics left = first == null ? SongLyrics.empty() : first;
        SongLyrics right = second == null ? SongLyrics.empty() : second;
        if (left.hasSyncedLyrics()) return left;
        if (right.hasSyncedLyrics()) return right;
        if (left.hasPlainLyrics()) return left;
        if (right.hasPlainLyrics()) return right;
        return first != null ? first : right;
    }
}
