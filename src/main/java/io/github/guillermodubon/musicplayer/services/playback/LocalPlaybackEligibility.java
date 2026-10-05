package io.github.guillermodubon.musicplayer.services.playback;

import io.github.guillermodubon.musicplayer.models.Song;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** Keeps current playback entry points local-only while the hybrid engine is deferred. */
public final class LocalPlaybackEligibility {

    public boolean isLocalSong(Song song) {
        return song != null && song.isLocal();
    }

    public List<Song> playableSongs(List<Song> source, Predicate<Song> canPlayNow) {
        if (source == null || source.isEmpty() || canPlayNow == null) return List.of();
        return source.stream()
                .filter(this::isLocalSong)
                .filter(canPlayNow)
                .toList();
    }

    public List<Song> buildLocalSource(List<Song> source,
                                      Song selected,
                                      Predicate<Song> canPlayNow,
                                      BiPredicate<Song, Song> sameSong) {
        if (source == null || source.isEmpty() || selected == null || !isLocalSong(selected)) return List.of();
        Objects.requireNonNull(canPlayNow, "canPlayNow");
        Objects.requireNonNull(sameSong, "sameSong");

        List<Song> playable = new ArrayList<>();
        for (Song candidate : source) {
            if (candidate == null || !isLocalSong(candidate)) continue;
            if (candidate == selected) {
                playable.add(candidate);
                continue;
            }
            if (canPlayNow.test(candidate) && playable.stream().noneMatch(existing -> sameSong.test(existing, candidate))) {
                playable.add(candidate);
            }
        }
        return List.copyOf(playable);
    }
}
