package io.github.guillermodubon.musicplayer.repository.dao.lyrics;

import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.models.lyrics.LyricsLookupCandidate;
import io.github.guillermodubon.musicplayer.models.lyrics.LyricsTrackMatch;
import io.github.guillermodubon.musicplayer.models.lyrics.LyricsTrackIdentity;
import io.github.guillermodubon.musicplayer.models.lyrics.SongLyrics;

import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persistence boundary for offline LRCLIB lyrics. */
public interface LyricsDao {

    Optional<SongLyrics> findBySourceKey(String sourceKey) throws SQLException;

    Optional<SongLyrics> findByTrackIdentity(LyricsLookupCandidate candidate) throws SQLException;

    List<LyricsTrackMatch> findByTrackKeys(Collection<String> trackKeys) throws SQLException;

    Map<Long, SongLyrics> findBySongIds(Collection<Long> songIds) throws SQLException;

    List<LyricsLookupCandidate> findPendingCandidates(int limit, long nowMillis) throws SQLException;

    Optional<Long> findSongIdByFilePath(String filePath) throws SQLException;

    void upsert(LyricsLookupCandidate candidate, SongLyrics lyrics) throws SQLException;

    default void hydrateSongs(Collection<Song> songs) throws SQLException {
        if (songs == null || songs.isEmpty()) return;
        Map<Long, SongLyrics> lyricsBySong = findBySongIds(
                songs.stream().filter(song -> song != null && song.getSongID() > 0)
                        .map(Song::getSongID).toList()
        );
        Map<String, List<LyricsTrackMatch>> lyricsByTrack = findByTrackKeys(
                songs.stream()
                        .filter(song -> song != null
                                && (lyricsBySong.get(song.getSongID()) == null
                                || !lyricsBySong.get(song.getSongID()).isAvailable()))
                        .map(LyricsTrackIdentity::fromSong)
                        .filter(key -> key != null && !key.isBlank())
                        .distinct()
                        .toList()
        ).stream().collect(java.util.stream.Collectors.groupingBy(LyricsTrackMatch::trackKey));
        for (Song song : songs) {
            if (song == null) continue;
            SongLyrics lyrics = lyricsBySong.get(song.getSongID());
            SongLyrics sharedLyrics = bestTrackMatch(
                    lyricsByTrack.get(LyricsTrackIdentity.fromSong(song)),
                    song.getDurationSeconds()
            );
            SongLyrics resolved = SongLyrics.prefer(lyrics, sharedLyrics);
            if (resolved != null) song.setLyrics(resolved);
        }
    }

    private static SongLyrics bestTrackMatch(List<LyricsTrackMatch> matches, int durationSeconds) {
        if (matches == null || matches.isEmpty()) return null;
        LyricsTrackMatch best = null;
        int bestScore = Integer.MAX_VALUE;
        for (LyricsTrackMatch match : matches) {
            if (match == null) continue;
            int storedDuration = match.durationSeconds();
            if (durationSeconds > 0 && storedDuration > 0
                    && Math.abs(durationSeconds - storedDuration) > 4) continue;
            int availabilityPenalty = match.lyrics().hasSyncedLyrics()
                    ? 0
                    : match.lyrics().hasPlainLyrics() ? 1_000 : 10_000;
            int durationPenalty = durationSeconds <= 0 || storedDuration <= 0
                    ? 100
                    : Math.abs(durationSeconds - storedDuration);
            int score = availabilityPenalty + durationPenalty;
            if (score < bestScore) {
                best = match;
                bestScore = score;
            }
        }
        return best == null ? null : best.lyrics();
    }
}
