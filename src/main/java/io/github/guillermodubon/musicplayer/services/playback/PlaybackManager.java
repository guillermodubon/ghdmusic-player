package io.github.guillermodubon.musicplayer.services.playback;

import javafx.scene.media.MediaPlayer;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.PlayerMenuBarController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.PlayerMenuController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.playback.events.PlaybackEventBus;
import io.github.guillermodubon.musicplayer.services.playback.managers.PlaybackMediaResolver;
import io.github.guillermodubon.musicplayer.services.playback.services.PlaybackMediaService;
import io.github.guillermodubon.musicplayer.services.playback.services.PlaybackSequenceService;
import io.github.guillermodubon.musicplayer.services.playback.state.PlaybackState;
import java.util.List;
import java.util.concurrent.Executors;
import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Artist;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;

public class PlaybackManager {

    private static PlaybackManager instance;

    private final PlaybackState state = new PlaybackState();
    private final PlaybackEventBus events = new PlaybackEventBus();
    private final PlaybackMediaResolver resolver = new PlaybackMediaResolver();

    private final ScheduledExecutorService playbackExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "playback-executor");
        t.setDaemon(true);
        return t;
    });

    private final PlaybackMediaService mediaService;
    private final PlaybackSequenceService sequenceService;

    private PlaybackManager() {
        mediaService = new PlaybackMediaService(state, events, resolver, playbackExecutor);
        sequenceService = new PlaybackSequenceService(state, events, mediaService, playbackExecutor);
    }

    public static synchronized PlaybackManager getInstance() {
        if (instance == null) instance = new PlaybackManager();
        return instance;
    }

    public void addTrackChangeListener(Runnable listener) {
        events.addTrackChangeListener(listener);
    }

    public void removeTrackChangeListener(Runnable listener) {
        events.removeTrackChangeListener(listener);
    }

    public void notifyTrackChanged() {
        events.notifyTrackChanged();
    }

    public void setOriginSource(String src) {
        state.setOriginSource(src);
    }

    public String getOriginSource() {
        return state.getOriginSource();
    }

    public void setControllers(PlayerMenuController menuCtrl, PlayerMenuBarController barCtrl) {
        mediaService.setControllers(menuCtrl, barCtrl);
    }

    public void setMenuController(PlayerMenuController menuCtrl) {
        setControllers(menuCtrl, getPlayerMenuBarController());
    }

    public void setBarController(PlayerMenuBarController barCtrl) {
        setControllers(getMenuController(), barCtrl);
    }

    public PlayerMenuController getMenuController() {
        return mediaService.getMenuController();
    }

    public PlayerMenuBarController getPlayerMenuBarController() {
        return mediaService.getBarController();
    }

    public void setRandomMode(boolean on) {
        sequenceService.setRandomMode(on);
    }

    public boolean isRandomMode() {
        return sequenceService.isRandomMode();
    }

    public void setReplayMode(boolean r) {
        sequenceService.setReplayMode(r);
    }

    public void playSongs(List<Song> songs, int startIndex, long playlistId, PlayerMenuContext.ContentType type) {
        state.setCurrentPlaylistPlayingId(playlistId);
        state.setCurrentContentTypePlaying(type);
        sequenceService.playSongs(songs, startIndex, playlistId, type);
    }

    /**
     * Starts a collection with an explicit shuffle policy. The policy belongs
     * to the collection that is being started and prevents a previous flow's
     * global toggle from changing the new collection unexpectedly.
     */
    public void playSongs(List<Song> songs,
                          int startIndex,
                          long playlistId,
                          PlayerMenuContext.ContentType type,
                          boolean randomMode) {
        state.setCurrentPlaylistPlayingId(playlistId);
        state.setCurrentContentTypePlaying(type);
        sequenceService.playSongs(songs, startIndex, playlistId, type, randomMode);
    }

    public void next() {
        sequenceService.next();
    }

    public void previous() {
        sequenceService.previous();
    }

    public void togglePlayPause() {
        mediaService.togglePlayPause();
    }

    public void setLastVolume(double v) {
        state.setLastVolume(v);
    }

    public double getLastVolume() {
        return state.getLastVolume();
    }

    public MediaPlayer getCurrentPlayer() {
        return mediaService.getCurrentPlayer();
    }

    public long getCurrentPlaylistPlayingId() {
        return state.getCurrentPlaylistPlayingId();
    }

    public PlayerMenuContext.ContentType getCurrentContentTypePlaying() {
        return state.getCurrentContentTypePlaying();
    }

    public long getCurrentPlaylistInViewId() {
        return state.getCurrentPlaylistInViewId();
    }

    public void setCurrentPlaylistInViewId(long id) {
        state.setCurrentPlaylistInViewId(id);
    }

    public List<Song> getQueue() {
        return sequenceService.getQueue();
    }

    public void enqueue(Song s) {
        sequenceService.enqueue(s);
    }

    public void clearQueue() {
        sequenceService.clearQueue();
    }

    public boolean reorderQueue(List<Song> orderedSongs) {
        return sequenceService.reorderQueue(orderedSongs);
    }

    public boolean reorderQueue(List<Song> expectedSongs, List<Song> orderedSongs) {
        return sequenceService.reorderQueue(expectedSongs, orderedSongs);
    }

    public boolean reorderRemainder(List<Song> orderedSongs) {
        return sequenceService.reorderRemainder(orderedSongs);
    }

    public boolean reorderRemainder(List<Song> expectedSongs, List<Song> orderedSongs) {
        return sequenceService.reorderRemainder(expectedSongs, orderedSongs);
    }

    public void enqueueAndPlayNext(Song s) {
        sequenceService.enqueueAndPlayNext(s);
    }

    public List<Song> getRemainder() {
        return sequenceService.getRemainder();
    }

    public List<Song> getSourceSongList() {
        return state.getSourceSongListCopy();
    }

    public Song getCurrentSong() {
        return state.getLastPlayedSong();
    }

    public boolean isCurrentSongFromQueue() {
        return state.isLastPlayedFromQueue();
    }

    public void removeFromQueue(Song song) {
        state.removeFromQueue(song);
        notifyTrackChanged();
    }

    public void addSongToCurrentPlaylist(Song song) {
        sequenceService.addSongToCurrentPlaylist(song);
    }

    public void setCurrentSongList(List<Song> currentSongList) {
        state.setCurrentSongList(currentSongList);
    }

    public void syncCurrentSourceSongs(List<Song> songs) {
        sequenceService.syncCurrentSourceSongs(songs);
    }

    public void removeSongFromCurrentPlaylist(Song song) {
        sequenceService.removeSongFromCurrentPlaylist(song);
    }

    public void reloadCurrentMediaIfNeeded() {
        mediaService.reloadCurrentMediaIfNeeded();
    }

    /**
     * Replaces every playback reference to an old/remote song with the new local/canonical song.
     *
     * This is used after a download has been integrated into DB/cache/manifest so that:
     * - queue points to the playable local song
     * - source song list points to the playable local song
     * - current song list points to the playable local song
     * - last played/current song can be reloaded if needed
     */
    public synchronized void replaceSongReferences(long oldSongId, Song newSong) {
        if (oldSongId <= 0 || newSong == null) {
            return;
        }

        sequenceService.replaceSongReferences(oldSongId, newSong);
    }

    /**
     * Main integration hook for downloaded songs.
     *
     * This method should be called after the download pipeline has completed:
     * file finalized -> metadata resolved -> DB/cache/manifest updated -> local Song available.
     *
     * It updates the current playback flow without forcing the user to restart playback.
     */
    public synchronized void integrateDownloadedSongIntoCurrentFlow(
            Song remoteSong,
            Song localSong,
            Long sourcePlaylistOrAlbumId
    ) {
        if (remoteSong == null || localSong == null) {
            return;
        }

        ensureLocalSongIsPlayable(localSong);

        boolean changed = false;

        long remoteId = remoteSong.getSongID();
        long localId = localSong.getSongID();

        if (remoteId > 0) {
            sequenceService.replaceSongReferences(remoteId, localSong);
            changed = true;
        }

        if (localId > 0 && localId != remoteId) {
            sequenceService.replaceSongReferences(localId, localSong);
            changed = true;
        }

        List<Song> sourceSongs = state.getSourceSongListCopy();

        boolean sourceContainsDownloadedSong = containsMatchingSong(sourceSongs, remoteSong, localSong);
        boolean samePlayingSource = isSamePlayingSource(sourcePlaylistOrAlbumId);
        boolean sameViewSource = isSameViewSource(sourcePlaylistOrAlbumId);

        /*
         * If the song belongs to the album/playlist currently playing or currently in view,
         * replace it inside the active source list so next/previous/remainder keep working
         * with the newly downloaded local version.
         */
        if (sourceContainsDownloadedSong || samePlayingSource) {
            List<Song> updatedSource =
                    replaceDownloadedSongInCopy(
                            sourceSongs,
                            remoteSong,
                            localSong
                    );


            if (samePlayingSource
                    && !containsMatchingSong(updatedSource, remoteSong, localSong)) {
                updatedSource =
                        insertDownloadedSongIntoSource(
                                updatedSource,
                                remoteSong,
                                localSong
                        );
            }

            if (!updatedSource.isEmpty()) {
                sequenceService.syncCurrentSourceSongs(updatedSource);
                changed = true;
            }
        }

        /*
         * If the current media is the song that was just downloaded,
         * reload it so JavaFX MediaPlayer uses the local file instead of
         * the old preview/remote/non-local reference.
         */
        if (isCurrentSongAffected(remoteSong, localSong)) {
            mediaService.reloadCurrentMediaIfNeeded();
            changed = true;
        }

        if (changed) {
            events.notifyTrackChanged();
        }
    }

    private void ensureLocalSongIsPlayable(Song localSong) {
        if (localSong == null) {
            return;
        }

        if (!localSong.isLocal()) {
            localSong.setLocal(true);
        }
    }

    private boolean isSamePlayingSource(Long sourcePlaylistOrAlbumId) {
        return sourcePlaylistOrAlbumId != null
                && sourcePlaylistOrAlbumId > 0
                && state.getCurrentPlaylistPlayingId() == sourcePlaylistOrAlbumId;
    }

    private boolean isSameViewSource(Long sourcePlaylistOrAlbumId) {
        return sourcePlaylistOrAlbumId != null
                && sourcePlaylistOrAlbumId > 0
                && state.getCurrentPlaylistInViewId() == sourcePlaylistOrAlbumId;
    }

    private boolean containsMatchingSong(List<Song> songs, Song remoteSong, Song localSong) {
        if (songs == null || songs.isEmpty()) {
            return false;
        }

        for (Song song : songs) {
            if (matchesDownloadedSong(song, remoteSong, localSong)) {
                return true;
            }
        }

        return false;
    }

    private List<Song> replaceDownloadedSongInCopy(List<Song> sourceSongs,
                                                   Song remoteSong,
                                                   Song localSong) {
        if (sourceSongs == null || sourceSongs.isEmpty()) {
            return new ArrayList<>();
        }

        List<Song> updated = new ArrayList<>(sourceSongs.size());

        for (Song existing : sourceSongs) {
            if (matchesDownloadedSong(existing, remoteSong, localSong)) {
                preservePlaybackOrderingData(existing, localSong);
                updated.add(localSong);
            } else {
                updated.add(existing);
            }
        }

        return updated;
    }

    private void preservePlaybackOrderingData(Song existing, Song localSong) {
        if (existing == null || localSong == null) {
            return;
        }

        try {
            if (existing.getTrackOrder() > 0 && localSong.getTrackOrder() <= 0) {
                localSong.setTrackOrder(existing.getTrackOrder());
            }
        } catch (Exception ignored) {
        }

        try {
            if ((localSong.getAlbum() == null || localSong.getAlbum().getAlbumID() <= 0)
                    && existing.getAlbum() != null) {
                localSong.setAlbum(existing.getAlbum());
            }
        } catch (Exception ignored) {
        }

        try {
            if ((localSong.getArtist() == null || localSong.getArtist().isEmpty())
                    && existing.getArtist() != null) {
                localSong.setArtist(existing.getArtist());
            }
        } catch (Exception ignored) {
        }
    }

    private boolean isCurrentSongAffected(Song remoteSong, Song localSong) {
        Song current = state.getLastPlayedSong();

        return matchesDownloadedSong(current, remoteSong, localSong);
    }

    private boolean matchesDownloadedSong(Song current, Song remoteSong, Song localSong) {
        if (current == null) {
            return false;
        }

        if (matchesById(current, remoteSong) || matchesById(current, localSong)) {
            return true;
        }

        if (matchesByTitleAlbumAndArtist(current, remoteSong)) {
            return true;
        }

        return matchesByTitleAlbumAndArtist(current, localSong);
    }

    private boolean matchesById(Song current, Song candidate) {
        if (current == null || candidate == null) {
            return false;
        }

        long currentId = current.getSongID();
        long candidateId = candidate.getSongID();

        return currentId > 0 && candidateId > 0 && currentId == candidateId;
    }

    private boolean matchesByTitleAlbumAndArtist(Song current, Song candidate) {
        if (current == null || candidate == null) {
            return false;
        }

        String currentTitle = normalize(current.getTitle());
        String candidateTitle = normalize(candidate.getTitle());

        if (currentTitle.isBlank() || !currentTitle.equals(candidateTitle)) {
            return false;
        }

        if (!albumCompatible(current, candidate)) {
            return false;
        }

        return artistsCompatible(current, candidate);
    }

    private boolean albumCompatible(Song current, Song candidate) {
        Album currentAlbum = current.getAlbum();
        Album candidateAlbum = candidate.getAlbum();

        if (currentAlbum == null || candidateAlbum == null) {
            return true;
        }

        long currentAlbumId = currentAlbum.getAlbumID();
        long candidateAlbumId = candidateAlbum.getAlbumID();

        if (currentAlbumId > 0 && candidateAlbumId > 0) {
            return currentAlbumId == candidateAlbumId;
        }

        String currentAlbumName = normalize(currentAlbum.getName());
        String candidateAlbumName = normalize(candidateAlbum.getName());

        return currentAlbumName.isBlank()
                || candidateAlbumName.isBlank()
                || currentAlbumName.equals(candidateAlbumName);
    }

    private boolean artistsCompatible(Song current, Song candidate) {
        List<Artist> currentArtists = current.getArtist();
        List<Artist> candidateArtists = candidate.getArtist();

        if (currentArtists == null
                || currentArtists.isEmpty()
                || candidateArtists == null
                || candidateArtists.isEmpty()) {
            return true;
        }

        for (Artist currentArtist : currentArtists) {
            if (currentArtist == null) {
                continue;
            }

            long currentArtistId = currentArtist.getArtistID();
            String currentArtistName = normalize(currentArtist.getName());

            for (Artist candidateArtist : candidateArtists) {
                if (candidateArtist == null) {
                    continue;
                }

                long candidateArtistId = candidateArtist.getArtistID();
                String candidateArtistName = normalize(candidateArtist.getName());

                if (currentArtistId > 0 && candidateArtistId > 0 && currentArtistId == candidateArtistId) {
                    return true;
                }

                if (!currentArtistName.isBlank() && currentArtistName.equals(candidateArtistName)) {
                    return true;
                }
            }
        }

        return false;
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT)
                  .trim()
                  .replaceAll("\\s+", " ");
    }

    private List<Song> insertDownloadedSongIntoSource(
            List<Song> sourceSongs,
            Song remoteSong,
            Song localSong
    ) {
        List<Song> updated =
                new ArrayList<>(sourceSongs == null ? List.of() : sourceSongs);

        if (localSong == null) {
            return updated;
        }

        preservePlaybackOrderingData(remoteSong, localSong);

        int downloadedOrder =
                resolveTrackOrder(remoteSong, localSong);

        if (downloadedOrder <= 0) {
            updated.add(localSong);
            return updated;
        }

        int insertIndex = updated.size();

        for (int index = 0; index < updated.size(); index++) {
            Song existing = updated.get(index);
            int existingOrder =
                    resolveTrackOrder(existing, existing);

            if (existingOrder > downloadedOrder) {
                insertIndex = index;
                break;
            }
        }

        updated.add(insertIndex, localSong);
        return updated;
    }

    private int resolveTrackOrder(Song primary, Song fallback) {
        try {
            if (primary != null && primary.getTrackOrder() > 0) {
                return primary.getTrackOrder();
            }
        } catch (Exception ignored) {
        }

        try {
            if (fallback != null && fallback.getTrackOrder() > 0) {
                return fallback.getTrackOrder();
            }
        } catch (Exception ignored) {
        }

        return -1;
    }

}
