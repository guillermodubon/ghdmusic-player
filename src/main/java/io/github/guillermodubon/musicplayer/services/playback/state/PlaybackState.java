package io.github.guillermodubon.musicplayer.services.playback.state;

import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext;
import io.github.guillermodubon.musicplayer.models.Song;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class PlaybackState {

    private final Object lock = new Object();

    private List<Song> currentSongList = new ArrayList<>();
    private List<Song> sourceSongList = new ArrayList<>();
    private List<Song> shuffleList = new ArrayList<>();
    private final Deque<Song> queue = new ArrayDeque<>();

    private int currentIndex = 0;
    private boolean randomMode = false;
    private boolean replayMode = false;
    private double lastVolume = 1.0;

    private String originSource;
    private Song lastPlayedSong;
    private boolean lastPlayedFromQueue;

    private long currentPlaylistPlayingId = -1L;
    private long currentPlaylistInViewId = -1L;
    private PlayerMenuContext.ContentType currentContentTypePlaying;

    public Object getLock() {
        return lock;
    }

    public List<Song> getCurrentSongListCopy() {
        synchronized (lock) {
            return new ArrayList<>(currentSongList);
        }
    }

    public void setCurrentSongList(List<Song> songs) {
        synchronized (lock) {
            currentSongList = songs == null ? new ArrayList<>() : new ArrayList<>(songs);
        }
    }

    public List<Song> getSourceSongListCopy() {
        synchronized (lock) {
            return new ArrayList<>(sourceSongList);
        }
    }

    public void setSourceSongList(List<Song> songs) {
        synchronized (lock) {
            sourceSongList = songs == null ? new ArrayList<>() : new ArrayList<>(songs);
        }
    }

    public List<Song> getShuffleListCopy() {
        synchronized (lock) {
            return new ArrayList<>(shuffleList);
        }
    }

    public void setShuffleList(List<Song> songs) {
        synchronized (lock) {
            shuffleList = songs == null ? new ArrayList<>() : new ArrayList<>(songs);
        }
    }

    public Deque<Song> getQueueCopy() {
        synchronized (lock) {
            return new ArrayDeque<>(queue);
        }
    }

    public void clearQueue() {
        synchronized (lock) {
            queue.clear();
        }
    }

    public void enqueueLast(Song song) {
        synchronized (lock) {
            queue.addLast(song);
        }
    }

    public void enqueueFirst(Song song) {
        synchronized (lock) {
            queue.addFirst(song);
        }
    }

    public boolean replaceQueueOrder(List<Song> orderedSongs) {
        return replaceQueueOrder(null, orderedSongs);
    }

    public boolean replaceQueueOrder(List<Song> expectedSongs, List<Song> orderedSongs) {
        if ((expectedSongs != null && expectedSongs.stream().anyMatch(song -> song == null))
                || orderedSongs == null
                || orderedSongs.stream().anyMatch(song -> song == null)) {
            return false;
        }

        synchronized (lock) {
            List<Song> current = new ArrayList<>(queue);
            if (expectedSongs != null && !sameSongSequence(current, expectedSongs)) {
                return false;
            }
            if (sameSongSequence(current, orderedSongs)) {
                return false;
            }

            queue.clear();
            queue.addAll(orderedSongs);
            return true;
        }
    }

    public boolean replaceCurrentRemainderOrder(List<Song> orderedSongs) {
        return replaceCurrentRemainderOrder(null, orderedSongs);
    }

    public boolean replaceCurrentRemainderOrder(
            List<Song> expectedSongs,
            List<Song> orderedSongs
    ) {
        if ((expectedSongs != null && expectedSongs.stream().anyMatch(song -> song == null))
                || orderedSongs == null
                || orderedSongs.stream().anyMatch(song -> song == null)) {
            return false;
        }

        synchronized (lock) {
            int start = Math.max(0, Math.min(currentIndex + 1, currentSongList.size()));
            int remainderSize = currentSongList.size() - start;
            List<Song> currentRemainder = new ArrayList<>(
                    currentSongList.subList(start, currentSongList.size())
            );
            if (expectedSongs != null && !sameSongSequence(currentRemainder, expectedSongs)) {
                return false;
            }
            if (remainderSize != orderedSongs.size()) {
                return false;
            }

            List<Song> reordered = new ArrayList<>(currentSongList);
            if (sameSongSequence(currentRemainder, orderedSongs)) {
                return false;
            }

            reordered.subList(start, reordered.size()).clear();
            reordered.addAll(orderedSongs);
            currentSongList = reordered;

            if (randomMode) {
                shuffleList = new ArrayList<>(reordered);
            } else {
                sourceSongList = new ArrayList<>(reordered);
            }
            return true;
        }
    }

    public Song pollFirstQueue() {
        synchronized (lock) {
            return queue.pollFirst();
        }
    }

    public boolean removeFromQueue(Song song) {
        synchronized (lock) {
            return queue.removeIf(s -> s != null && s.equals(song));
        }
    }

    public int getQueueSize() {
        synchronized (lock) {
            return queue.size();
        }
    }

    public int getCurrentIndex() {
        synchronized (lock) {
            return currentIndex;
        }
    }

    public void setCurrentIndex(int index) {
        synchronized (lock) {
            currentIndex = index;
        }
    }

    public boolean isRandomMode() {
        synchronized (lock) {
            return randomMode;
        }
    }

    public void setRandomMode(boolean randomMode) {
        synchronized (lock) {
            this.randomMode = randomMode;
        }
    }

    public boolean isReplayMode() {
        synchronized (lock) {
            return replayMode;
        }
    }

    public void setReplayMode(boolean replayMode) {
        synchronized (lock) {
            this.replayMode = replayMode;
        }
    }

    public double getLastVolume() {
        synchronized (lock) {
            return lastVolume;
        }
    }

    public void setLastVolume(double lastVolume) {
        synchronized (lock) {
            this.lastVolume = lastVolume;
        }
    }

    public String getOriginSource() {
        synchronized (lock) {
            return originSource;
        }
    }

    public void setOriginSource(String originSource) {
        synchronized (lock) {
            this.originSource = originSource;
        }
    }

    public Song getLastPlayedSong() {
        synchronized (lock) {
            return lastPlayedSong;
        }
    }

    public void setLastPlayedSong(Song lastPlayedSong) {
        synchronized (lock) {
            this.lastPlayedSong = lastPlayedSong;
        }
    }

    public boolean isLastPlayedFromQueue() {
        synchronized (lock) {
            return lastPlayedFromQueue;
        }
    }

    public void setLastPlayedFromQueue(boolean lastPlayedFromQueue) {
        synchronized (lock) {
            this.lastPlayedFromQueue = lastPlayedFromQueue;
        }
    }

    public long getCurrentPlaylistPlayingId() {
        synchronized (lock) {
            return currentPlaylistPlayingId;
        }
    }

    public void setCurrentPlaylistPlayingId(long id) {
        synchronized (lock) {
            this.currentPlaylistPlayingId = id;
        }
    }

    public long getCurrentPlaylistInViewId() {
        synchronized (lock) {
            return currentPlaylistInViewId;
        }
    }

    public void setCurrentPlaylistInViewId(long id) {
        synchronized (lock) {
            this.currentPlaylistInViewId = id;
        }
    }

    public PlayerMenuContext.ContentType getCurrentContentTypePlaying() {
        synchronized (lock) {
            return currentContentTypePlaying;
        }
    }

    public void setCurrentContentTypePlaying(PlayerMenuContext.ContentType type) {
        synchronized (lock) {
            this.currentContentTypePlaying = type;
        }
    }

    public Song getCurrentSongAtCurrentIndex() {
        synchronized (lock) {
            if (currentSongList.isEmpty()) return null;
            if (currentIndex < 0 || currentIndex >= currentSongList.size()) return null;
            return currentSongList.get(currentIndex);
        }
    }

    public int sizeOfCurrentSongList() {
        synchronized (lock) {
            return currentSongList.size();
        }
    }

    /**
     * Removes every occurrence of an unavailable local song from the active
     * source, shuffle and queue structures. The current index is moved back so
     * the following call to next() selects the song immediately after it.
     */
    public boolean removeUnavailableSong(Song target) {
        if (target == null) return false;

        synchronized (lock) {
            int previousIndex = currentIndex;
            int removedAtOrBeforeCurrent = countMatchesThrough(currentSongList, target, previousIndex);
            boolean changed = removeMatches(currentSongList, target);
            changed |= removeMatches(sourceSongList, target);
            changed |= removeMatches(shuffleList, target);
            changed |= queue.removeIf(song -> sameSong(song, target));

            if (changed && removedAtOrBeforeCurrent > 0) {
                currentIndex = Math.max(-1, previousIndex - removedAtOrBeforeCurrent);
            }

            if (currentSongList.isEmpty()) {
                currentIndex = 0;
            } else if (currentIndex >= currentSongList.size()) {
                currentIndex = currentSongList.size() - 1;
            }

            if (sameSong(lastPlayedSong, target)) {
                lastPlayedSong = null;
                lastPlayedFromQueue = false;
            }

            return changed;
        }
    }

    private boolean removeMatches(List<Song> songs, Song target) {
        return songs != null && songs.removeIf(song -> sameSong(song, target));
    }

    private int countMatchesThrough(List<Song> songs, Song target, int index) {
        if (songs == null || songs.isEmpty() || index < 0) return 0;

        int count = 0;
        for (int i = 0; i <= Math.min(index, songs.size() - 1); i++) {
            if (sameSong(songs.get(i), target)) count++;
        }
        return count;
    }

    private boolean sameSong(Song left, Song right) {
        if (left == null || right == null) return false;
        if (left.getSongID() > 0 && right.getSongID() > 0) {
            return left.getSongID() == right.getSongID();
        }
        return left.equals(right);
    }

    private boolean sameSongSequence(List<Song> left, List<Song> right) {
        if (left == right) return true;
        if (left == null || right == null || left.size() != right.size()) return false;

        for (int index = 0; index < left.size(); index++) {
            if (!sameSong(left.get(index), right.get(index))) return false;
        }
        return true;
    }

    public void replaceSongReferences(long oldSongId, Song newSong) {
        synchronized (lock) {
            if (newSong == null) return;

            for (int i = 0; i < sourceSongList.size(); i++) {
                Song s = sourceSongList.get(i);
                if (s != null && s.getSongID() == oldSongId) sourceSongList.set(i, newSong);
            }

            for (int i = 0; i < currentSongList.size(); i++) {
                Song s = currentSongList.get(i);
                if (s != null && s.getSongID() == oldSongId) currentSongList.set(i, newSong);
            }

            Deque<Song> rebuilt = new ArrayDeque<>(queue.size());
            for (Song s : queue) {
                rebuilt.addLast(s != null && s.getSongID() == oldSongId ? newSong : s);
            }
            queue.clear();
            queue.addAll(rebuilt);

            if (lastPlayedSong != null && lastPlayedSong.getSongID() == oldSongId) {
                lastPlayedSong = newSong;
            }
        }
    }
}
