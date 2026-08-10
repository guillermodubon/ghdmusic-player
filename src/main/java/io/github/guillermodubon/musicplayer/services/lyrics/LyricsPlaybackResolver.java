package io.github.guillermodubon.musicplayer.services.lyrics;

import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.models.lyrics.LyricsLookupCandidate;
import io.github.guillermodubon.musicplayer.models.lyrics.SongLyrics;
import io.github.guillermodubon.musicplayer.repository.DbConnectionManager;
import io.github.guillermodubon.musicplayer.repository.dao.lyrics.LyricsDaoImpl;
import io.github.guillermodubon.musicplayer.services.downloads.services.DownloadPipelineExecutors;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves the current track's persisted lyrics without touching the FX thread. */
public final class LyricsPlaybackResolver {

    private static final LyricsPlaybackResolver INSTANCE = new LyricsPlaybackResolver();

    private final Map<String, CompletableFuture<SongLyrics>> inFlight = new ConcurrentHashMap<>();

    private LyricsPlaybackResolver() {
    }

    public static LyricsPlaybackResolver getInstance() {
        return INSTANCE;
    }

    public CompletableFuture<SongLyrics> resolve(Song song) {
        if (song == null) return CompletableFuture.completedFuture(SongLyrics.empty());

        SongLyrics inMemory = song.getLyrics();
        if (inMemory != null && inMemory.hasSyncedLyrics()) {
            return CompletableFuture.completedFuture(inMemory);
        }

        LyricsLookupCandidate candidate = LyricsCandidateFactory.fromSong(song);
        String sourceKey = candidate.sourceKey();
        if (sourceKey.isBlank()) return CompletableFuture.completedFuture(SongLyrics.empty());
        String requestKey = candidate.trackKey().isBlank() ? sourceKey : "track-key:" + candidate.trackKey();

        return inFlight.computeIfAbsent(requestKey, ignored -> load(song, candidate)
                .whenComplete((lyrics, error) -> inFlight.remove(requestKey)));
    }

    private CompletableFuture<SongLyrics> load(Song song, LyricsLookupCandidate candidate) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return DbConnectionManager.getInstance().runWithRetries(connection -> {
                    try {
                        LyricsDaoImpl dao = new LyricsDaoImpl(connection);
                        Optional<SongLyrics> sourceLyrics = song.getSongID() > 0
                                ? dao.findBySongIds(List.of(song.getSongID())).values().stream().findFirst()
                                : dao.findBySourceKey(candidate.sourceKey());
                        Optional<SongLyrics> sharedLyrics = candidate.hasLookupIdentity()
                                ? dao.findByTrackIdentity(candidate)
                                : Optional.empty();
                        SongLyrics resolved = SongLyrics.prefer(
                                sourceLyrics.orElse(SongLyrics.empty()),
                                sharedLyrics.orElse(inMemoryFallback(song))
                        );
                        song.setLyrics(resolved);
                        return resolved;
                    } catch (SQLException error) {
                        throw new RuntimeException(error);
                    }
                });
            } catch (SQLException | RuntimeException ignored) {
                return SongLyrics.empty();
            }
        }, DownloadPipelineExecutors.completion());
    }

    private static SongLyrics inMemoryFallback(Song song) {
        return song == null || song.getLyrics() == null ? SongLyrics.empty() : song.getLyrics();
    }
}
