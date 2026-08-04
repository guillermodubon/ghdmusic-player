package io.github.guillermodubon.musicplayer.services.playback.services;

import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.playback.events.PlaybackEventBus;
import io.github.guillermodubon.musicplayer.services.playback.state.PlaybackState;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class PlaybackSequenceService {

    private final PlaybackState state;
    private final PlaybackEventBus events;
    private final PlaybackMediaService mediaService;
    private final ScheduledExecutorService executor;
    private final Random rnd = new Random();
    private final AtomicLong playbackVersion = new AtomicLong();
    private final AtomicLong playbackAttempt = new AtomicLong();
    private final AtomicLong unavailableRecoveryAttempt = new AtomicLong(-1L);
    private final Deque<Song> playbackHistory = new ArrayDeque<>();

    public PlaybackSequenceService(PlaybackState state,
                                   PlaybackEventBus events,
                                   PlaybackMediaService mediaService,
                                   ScheduledExecutorService executor) {
        this.state = state;
        this.events = events;
        this.mediaService = mediaService;
        this.executor = executor;
    }

    public void playSongs(List<Song> songs, int startIndex, long playlistId, PlayerMenuContext.ContentType type) {
        playSongs(songs, startIndex, playlistId, type, state.isRandomMode());
    }

    public void playSongs(List<Song> songs,
                          int startIndex,
                          long playlistId,
                          PlayerMenuContext.ContentType type,
                          boolean randomMode) {
        long version = playbackVersion.incrementAndGet();
        long activationAttempt = beginPlaybackAttempt();
        rememberCurrentSong();
        mediaService.stopCurrent();
        if (songs == null || songs.isEmpty()) return;

        List<Song> source = new ArrayList<>(songs);
        int selectedIndex = Math.max(0, Math.min(startIndex, source.size() - 1));
        Song selectedSong = source.get(selectedIndex);

        state.setCurrentPlaylistPlayingId(playlistId);
        state.setCurrentContentTypePlaying(type);
        state.setRandomMode(randomMode);
        state.setSourceSongList(source);
        state.clearQueue();

        if (state.isRandomMode()) {
            prepareShuffledFlow(source, selectedSong);
        } else {
            state.setShuffleList(List.of());
            state.setCurrentSongList(source);
            state.setCurrentIndex(selectedIndex);
        }

        executor.schedule(() -> {
            if (!isCurrentPlaybackAttempt(version, activationAttempt)) return;

            Song current = state.getCurrentSongAtCurrentIndex();
            state.setLastPlayedFromQueue(false);
            playWithAvailabilityGuard(current, version, activationAttempt);
        }, 0, TimeUnit.MILLISECONDS);
    }


    private void playWithAvailabilityGuard(Song song, long version, long expectedAttempt) {
        if (expectedAttempt >= 0L && !isCurrentPlaybackAttempt(version, expectedAttempt)) {
            return;
        }

        long attempt = beginPlaybackAttempt();
        mediaService.playSong(
                song,
                () -> advanceAfterMediaEnd(version, attempt),
                unavailable -> handleUnavailableSong(unavailable, version, attempt)
        );
    }

    /**
     * A missing local file is removed before advancing. This makes every
     * unavailable item fail at most once and prevents playback loops.
     */
    private void handleUnavailableSong(Song song, long expectedVersion, long expectedAttempt) {
        executor.execute(() -> {
            if (!isCurrentPlaybackAttempt(expectedVersion, expectedAttempt)) return;
            if (!unavailableRecoveryAttempt.compareAndSet(-1L, expectedAttempt)) return;

            try {
                boolean removed = state.removeUnavailableSong(song);
                events.notifyTrackChanged();

                if (removed && !state.isReplayMode()
                        && isCurrentPlaybackAttempt(expectedVersion, expectedAttempt)) {
                    playNextAvailableSong(expectedVersion, expectedAttempt);
                }
            } finally {
                unavailableRecoveryAttempt.compareAndSet(expectedAttempt, -1L);
            }
        });
    }

    /**
     * Advances after a media file disappears without applying the normal
     * end-of-flow behavior. Normal next() is intentionally cyclic, while a
     * missing file must never restart the sequence or create a new shuffle
     * order. It can only continue with the next queued song or the next song
     * that already exists after the removed item in the active flow.
     */
    private void playNextAvailableSong(long expectedVersion, long failedAttempt) {
        if (!isCurrentPlaybackAttempt(expectedVersion, failedAttempt)) return;

        long recoveryAttempt = beginPlaybackAttempt();
        if (!isCurrentPlaybackAttempt(expectedVersion, recoveryAttempt)) return;

        mediaService.stopCurrent();

        Song queued = state.pollFirstQueue();
        if (queued != null) {
            alignCurrentIndexToSource(queued);
            state.setLastPlayedFromQueue(true);
            playWithAvailabilityGuard(queued, expectedVersion, recoveryAttempt);
            return;
        }

        int nextIndex = state.getCurrentIndex() + 1;
        if (nextIndex < 0) {
            nextIndex = 0;
        }

        if (nextIndex >= state.sizeOfCurrentSongList()) {
            state.setLastPlayedFromQueue(false);
            events.notifyTrackChanged();
            return;
        }

        state.setCurrentIndex(nextIndex);
        Song nextSong = state.getCurrentSongAtCurrentIndex();
        if (nextSong == null) {
            state.setLastPlayedFromQueue(false);
            events.notifyTrackChanged();
            return;
        }

        state.setLastPlayedFromQueue(false);
        playWithAvailabilityGuard(nextSong, expectedVersion, recoveryAttempt);
    }

    /**
     * Builds a new shuffle flow without losing the song explicitly selected
     * by the user. The selected song is always played first; only the
     * remaining songs are randomized.
     */
    private void prepareShuffledFlow(List<Song> source, Song selectedSong) {
        List<Song> shuffled = new ArrayList<>(source == null ? List.of() : source);
        removeFirstMatchingSong(shuffled, selectedSong);
        Collections.shuffle(shuffled, rnd);

        if (selectedSong != null) {
            shuffled.add(0, selectedSong);
        }

        state.setShuffleList(shuffled);
        state.setCurrentSongList(shuffled);
        state.setCurrentIndex(0);
    }

    private void removeFirstMatchingSong(List<Song> songs, Song selectedSong) {
        if (songs == null || selectedSong == null) {
            return;
        }

        for (int index = 0; index < songs.size(); index++) {
            if (sameSong(songs.get(index), selectedSong)) {
                songs.remove(index);
                return;
            }
        }
    }

    public void next() {
        scheduleNext(playbackVersion.get(), beginPlaybackAttempt());
    }

    private void advanceAfterMediaEnd(long expectedVersion, long expectedAttempt) {
        if (!isCurrentPlaybackAttempt(expectedVersion, expectedAttempt)) return;
        scheduleNext(expectedVersion, beginPlaybackAttempt());
    }

    private void scheduleNext(long expectedVersion, long navigationAttempt) {
        executor.execute(() -> {
            if (!isCurrentPlaybackAttempt(expectedVersion, navigationAttempt)) return;

            mediaService.stopCurrent();
            rememberCurrentSong();

            Song queued = state.pollFirstQueue();
            if (queued != null) {
                alignCurrentIndexToSource(queued);
                state.setLastPlayedFromQueue(true);
                playWithAvailabilityGuard(queued, expectedVersion, navigationAttempt);
                return;
            }

            if (state.isReplayMode()) {
                Song current = state.getCurrentSongAtCurrentIndex();
                if (current != null) {
                    state.setLastPlayedFromQueue(false);
                    playWithAvailabilityGuard(current, expectedVersion, navigationAttempt);
                }
                return;
            }

            if (state.sizeOfCurrentSongList() == 0) {
                events.notifyTrackChanged();
                return;
            }

            if (state.isRandomMode()) {
                int index = state.getCurrentIndex();
                int size = state.sizeOfCurrentSongList();

                if (index < size - 1) {
                    state.setCurrentIndex(index + 1);
                } else {
                    reshuffleRandomList();
                    state.setCurrentIndex(0);
                }
            } else {
                int size = state.sizeOfCurrentSongList();
                state.setCurrentIndex((state.getCurrentIndex() + 1) % size);
            }

            Song current = state.getCurrentSongAtCurrentIndex();
            state.setLastPlayedFromQueue(false);
            playWithAvailabilityGuard(current, expectedVersion, navigationAttempt);
        });
    }

    public void previous() {
        long version = playbackVersion.get();
        long navigationAttempt = beginPlaybackAttempt();

        mediaService.stopCurrent();

        Song historical = popPreviousSong();
        if (historical != null) {
            alignCurrentIndexToSource(historical);
            state.setLastPlayedFromQueue(!containsSong(state.getSourceSongListCopy(), historical));
            playWithAvailabilityGuard(historical, version, navigationAttempt);
            return;
        }

        if (state.isReplayMode()) {
            Song current = state.getCurrentSongAtCurrentIndex();
            if (current != null) {
                state.setLastPlayedFromQueue(false);
                playWithAvailabilityGuard(current, version, navigationAttempt);
            }
            return;
        }

        if (state.isRandomMode()) {
            state.setCurrentIndex(pickRandomIndex());
        } else {
            int size = state.sizeOfCurrentSongList();
            if (size > 0) {
                state.setCurrentIndex((state.getCurrentIndex() - 1 + size) % size);
            }
        }

        Song current = state.getCurrentSongAtCurrentIndex();
        if (current != null) {
            state.setLastPlayedFromQueue(false);
            playWithAvailabilityGuard(current, version, navigationAttempt);
        }
    }

    public void setRandomMode(boolean on) {
        if (on == state.isRandomMode()) return;
        applyRandomMode(on);
        events.notifyTrackChanged();
    }

    public boolean isRandomMode() {
        return state.isRandomMode();
    }

    public void setReplayMode(boolean replay) {
        state.setReplayMode(replay);
    }

    public void enqueue(Song song) {
        state.enqueueLast(song);
    }

    public void clearQueue() {
        state.clearQueue();
    }

    public boolean reorderQueue(List<Song> orderedSongs) {
        return reorderQueue(null, orderedSongs);
    }

    public boolean reorderQueue(List<Song> expectedSongs, List<Song> orderedSongs) {
        boolean changed = state.replaceQueueOrder(expectedSongs, orderedSongs);
        if (changed) {
            events.notifyTrackChanged();
        }
        return changed;
    }

    public boolean reorderRemainder(List<Song> orderedSongs) {
        return reorderRemainder(null, orderedSongs);
    }

    public boolean reorderRemainder(List<Song> expectedSongs, List<Song> orderedSongs) {
        boolean changed = state.replaceCurrentRemainderOrder(expectedSongs, orderedSongs);
        if (changed) {
            events.notifyTrackChanged();
        }
        return changed;
    }

    public List<Song> getQueue() {
        return List.copyOf(state.getQueueCopy());
    }

    public void enqueueAndPlayNext(Song song) {
        state.enqueueFirst(song);
        long version = playbackVersion.get();
        long navigationAttempt = beginPlaybackAttempt();
        executor.schedule(() -> scheduleNext(version, navigationAttempt), 0, TimeUnit.MILLISECONDS);
    }

    public List<Song> getRemainder() {
        List<Song> base = state.isRandomMode() ? state.getCurrentSongListCopy() : state.getSourceSongListCopy();
        if (base.isEmpty()) return List.of();

        List<Song> tail = new ArrayList<>();
        int idx = state.getCurrentIndex();

        if (idx + 1 < base.size()) {
            tail.addAll(base.subList(idx + 1, base.size()));
        }

        return tail;
    }

    public void addSongToCurrentPlaylist(Song song) {
        if (song == null) return;

        List<Song> source = state.getSourceSongListCopy();
        source.add(song);
        state.setSourceSongList(source);

        if (!state.isRandomMode()) {
            List<Song> current = state.getCurrentSongListCopy();
            current.add(song);
            state.setCurrentSongList(current);
        } else {
            List<Song> shuffled = state.getCurrentSongListCopy();
            shuffled.add(song);
            Collections.shuffle(shuffled);
            state.setShuffleList(shuffled);
            state.setCurrentSongList(shuffled);

            Song last = state.getLastPlayedSong();
            int idx = shuffled.indexOf(last);
            state.setCurrentIndex(Math.max(0, idx));
        }

        events.notifyTrackChanged();
    }

    public void removeSongFromCurrentPlaylist(Song song) {
        if (song == null) return;

        List<Song> source = state.getSourceSongListCopy();
        source.remove(song);
        state.setSourceSongList(source);

        List<Song> current = state.getCurrentSongListCopy();
        current.remove(song);
        state.setCurrentSongList(current);

        events.notifyTrackChanged();
    }

    public void replaceSongReferences(long oldSongId, Song newSong) {
        state.replaceSongReferences(oldSongId, newSong);
        events.notifyTrackChanged();
    }


    public void syncCurrentSourceSongs(List<Song> songs) {
        if (songs == null || songs.isEmpty()) return;

        Song current = state.getLastPlayedSong();
        List<Song> refreshed = new ArrayList<>(songs);
        state.setSourceSongList(refreshed);

        if (!state.isRandomMode()) {
            state.setCurrentSongList(refreshed);
            alignCurrentIndexToCurrent(current);
        } else {
            List<Song> randomCurrent = state.getCurrentSongListCopy();
            for (Song song : refreshed) {
                if (song == null || containsSong(randomCurrent, song)) continue;
                randomCurrent.add(song);
            }
            randomCurrent.removeIf(song -> song != null && !containsSong(refreshed, song));
            state.setShuffleList(randomCurrent);
            state.setCurrentSongList(randomCurrent);
            alignCurrentIndexToCurrent(current);
        }

        events.notifyTrackChanged();
    }

    private void applyRandomMode(boolean on) {
        state.setRandomMode(on);

        if (on) {
            List<Song> source = state.getCurrentSongListCopy();
            state.setSourceSongList(source);

            List<Song> shuffled = new ArrayList<>(source);
            Collections.shuffle(shuffled);

            Song last = state.getLastPlayedSong();
            if (last != null) {
                shuffled.remove(last);
                shuffled.add(0, last);
            }

            state.setShuffleList(shuffled);
            state.setCurrentSongList(shuffled);
            state.setCurrentIndex(0);
        } else {
            List<Song> original = state.getSourceSongListCopy();
            if (original.isEmpty()) return;

            Song current = state.getLastPlayedSong();
            state.setCurrentSongList(original);

            int newIndex = 0;
            if (current != null) {
                long cid = current.getSongID();
                for (int i = 0; i < original.size(); i++) {
                    Song s = original.get(i);
                    if (s != null && s.getSongID() == cid) {
                        newIndex = i;
                        break;
                    }
                }
            }

            state.setCurrentIndex(Math.max(0, Math.min(newIndex, original.size() - 1)));
        }
    }

    private void reshuffleRandomList() {
        List<Song> shuffled = new ArrayList<>(state.getSourceSongListCopy());
        Collections.shuffle(shuffled);

        Song last = state.getLastPlayedSong();
        if (last != null) {
            shuffled.remove(last);
            shuffled.add(0, last);
        }

        state.setShuffleList(shuffled);
        state.setCurrentSongList(shuffled);
    }

    private boolean isCurrentVersion(long expectedVersion) {
        return expectedVersion == playbackVersion.get();
    }

    private long beginPlaybackAttempt() {
        return playbackAttempt.incrementAndGet();
    }

    private boolean isCurrentPlaybackAttempt(long expectedVersion, long expectedAttempt) {
        return isCurrentVersion(expectedVersion) && expectedAttempt == playbackAttempt.get();
    }

    private void rememberCurrentSong() {
        Song current = state.getLastPlayedSong();
        if (current == null) return;

        synchronized (playbackHistory) {
            Song last = playbackHistory.peekLast();
            if (!sameSong(last, current)) {
                playbackHistory.addLast(current);
            }
        }
    }

    private Song popPreviousSong() {
        synchronized (playbackHistory) {
            return playbackHistory.pollLast();
        }
    }

    private void alignCurrentIndexToSource(Song song) {
        if (song == null) return;

        List<Song> source = state.getSourceSongListCopy();
        for (int i = 0; i < source.size(); i++) {
            Song candidate = source.get(i);
            if (sameSong(candidate, song)) {
                state.setCurrentIndex(i);
                return;
            }
        }
    }

    private void alignCurrentIndexToCurrent(Song song) {
        if (song == null) return;

        List<Song> current = state.getCurrentSongListCopy();
        for (int i = 0; i < current.size(); i++) {
            Song candidate = current.get(i);
            if (sameSong(candidate, song)) {
                state.setCurrentIndex(i);
                return;
            }
        }
    }

    private boolean containsSong(List<Song> songs, Song target) {
        if (songs == null || target == null) return false;
        for (Song song : songs) {
            if (sameSong(song, target)) return true;
        }
        return false;
    }

    private boolean sameSong(Song a, Song b) {
        if (a == null || b == null) return false;
        if (a.getSongID() > 0 && b.getSongID() > 0) {
            return a.getSongID() == b.getSongID();
        }
        return a.equals(b);
    }

    private int pickRandomIndex() {
        int size = state.sizeOfCurrentSongList();
        if (size <= 1) return state.getCurrentIndex();

        int currentIndex = state.getCurrentIndex();
        int next;
        do {
            next = rnd.nextInt(size);
        } while (next == currentIndex);

        return next;
    }
}
