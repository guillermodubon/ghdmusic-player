package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services;

import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane.QueueController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.downloads.services.IntegratedDownloadResult;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;

import java.io.File;
import java.util.List;

/** Keeps playback and queue state consistent after downloaded songs integrate. */
final class PlayerMenuDownloadPlaybackCoordinator {

    private final PlayerMenuContext context;
    private final PlaybackManager playbackManager;
    private final PlayerMenuDownloadViewCoordinator viewCoordinator;

    PlayerMenuDownloadPlaybackCoordinator(PlayerMenuContext context,
                                           PlaybackManager playbackManager,
                                           PlayerMenuDownloadViewCoordinator viewCoordinator) {
        this.context = context;
        this.playbackManager = playbackManager;
        this.viewCoordinator = viewCoordinator;
    }

    Long resolveCurrentSourceId() {
        try {
            long id = context.getCurrentPlaylistInViewId();
            return id > 0 ? id : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    boolean integrateIntoPlaybackFlow(Song remoteSong, Song localSong, Long sourceId) {
        if (localSong == null || !PlayerMenuDownloadSongMatcher.isPlayableLocalSong(localSong)) {
            return false;
        }
        Song viewSong = viewCoordinator.findSongInCurrentView(remoteSong, localSong);
        Song localViewSong = PlayerMenuDownloadSongMatcher.copyForView(viewSong, localSong);
        if (!PlayerMenuDownloadSongMatcher.isPlayableLocalSong(localViewSong)) {
            localViewSong = localSong;
        }
        try {
            playbackManager.integrateDownloadedSongIntoCurrentFlow(
                    remoteSong != null ? remoteSong : localSong,
                    localViewSong,
                    sourceId
            );
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    void reloadCurrentMediaIfDownloaded(long trackId) {
        if (trackId <= 0) {
            return;
        }
        try {
            Song currentSong = playbackManager.getCurrentSong();
            if (currentSong != null && currentSong.getSongID() == trackId) {
                playbackManager.reloadCurrentMediaIfNeeded();
            }
        } catch (Exception ignored) {
        }
    }

    boolean syncPlaybackSourceIfCurrentView(List<IntegratedDownloadResult> completedDownloads) {
        if (!isCurrentViewActivePlaybackSource()) {
            return false;
        }

        List<Song> updatedSource = viewCoordinator.buildPlayableSourceFromCurrentView();
        if (completedDownloads != null) {
            for (IntegratedDownloadResult result : completedDownloads) {
                if (result == null || result.localSong() == null) {
                    continue;
                }

                Song remoteSong = result.remoteSong();
                Song localSong = PlayerMenuDownloadSongMatcher.makePlayableLocalSong(
                        result.localSong(), result.downloadedFile());
                if (!PlayerMenuDownloadSongMatcher.isPlayableLocalSong(localSong)
                        || !viewCoordinator.downloadBelongsToCurrentView(remoteSong, localSong)) {
                    continue;
                }

                Song viewReference = viewCoordinator.findSongInCurrentView(remoteSong, localSong);
                Song localViewSong = PlayerMenuDownloadSongMatcher.copyForView(viewReference, localSong);
                upsertSongKeepingViewOrder(updatedSource, remoteSong, localViewSong);
            }
        }

        if (updatedSource.isEmpty()) {
            return false;
        }
        context.setCurrentSongList(updatedSource);

        List<Song> existingSource;
        try {
            existingSource = playbackManager.getSourceSongList();
        } catch (Exception ignored) {
            existingSource = List.of();
        }

        boolean sourceChanged = !PlayerMenuDownloadSongMatcher.sameSongSequence(existingSource, updatedSource);
        try {
            if (sourceChanged) {
                playbackManager.syncCurrentSourceSongs(updatedSource);
            }
            QueueController.notifyPlaybackFlowChanged();
            return sourceChanged;
        } catch (Exception error) {
            error.printStackTrace();
            QueueController.notifyPlaybackFlowChanged();
            return false;
        }
    }

    boolean syncPlaybackSourceForCompletedDownloads(List<IntegratedDownloadResult> results) {
        if (results == null || results.isEmpty() || !isCurrentViewActivePlaybackSource()) {
            return false;
        }

        boolean downloadedSongBelongsToView = false;
        for (IntegratedDownloadResult result : results) {
            if (result == null || result.localSong() == null) {
                continue;
            }

            Song remoteSong = result.remoteSong();
            Song localSong = PlayerMenuDownloadSongMatcher.makePlayableLocalSong(
                    result.localSong(), result.downloadedFile());
            int masterIndex = viewCoordinator.indexInCurrentView(remoteSong, localSong);
            if (masterIndex < 0) {
                continue;
            }
            downloadedSongBelongsToView = true;

            Song currentViewSong = context.getMasterSongList().get(masterIndex);
            Song localViewSong = PlayerMenuDownloadSongMatcher.copyForView(currentViewSong, localSong);
            Song replacement = localViewSong == null ? localSong : localViewSong;
            context.getMasterSongList().set(masterIndex, replacement);
            viewCoordinator.replaceInVisibleLists(remoteSong, replacement);
        }

        if (!downloadedSongBelongsToView) {
            return false;
        }

        List<Song> updatedSource = viewCoordinator.buildPlayableSourceFromCurrentView();
        if (updatedSource.isEmpty()) {
            return false;
        }
        context.setCurrentSongList(updatedSource);

        List<Song> existingSource;
        try {
            existingSource = playbackManager.getSourceSongList();
        } catch (Exception ignored) {
            existingSource = List.of();
        }

        boolean sourceChanged = !PlayerMenuDownloadSongMatcher.sameSongSequence(existingSource, updatedSource);
        try {
            if (sourceChanged) {
                playbackManager.syncCurrentSourceSongs(updatedSource);
            }
            QueueController.notifyPlaybackFlowChanged();
            return sourceChanged;
        } catch (Exception error) {
            error.printStackTrace();
            QueueController.notifyPlaybackFlowChanged();
            return false;
        }
    }

    private boolean isCurrentViewActivePlaybackSource() {
        if (!java.util.Objects.equals(
                playbackManager.getCurrentContentTypePlaying(),
                context.getCurrentContentTypeInView())) {
            return false;
        }

        long playingSourceId;
        try {
            playingSourceId = playbackManager.getCurrentPlaylistPlayingId();
        } catch (Exception ignored) {
            playingSourceId = -1L;
        }

        long viewSourceId;
        try {
            viewSourceId = context.getCurrentPlaylistInViewId();
        } catch (Exception ignored) {
            viewSourceId = -1L;
        }

        if (playingSourceId > 0 && viewSourceId > 0) {
            return playingSourceId == viewSourceId;
        }

        Song currentSong;
        try {
            currentSong = playbackManager.getCurrentSong();
        } catch (Exception ignored) {
            currentSong = null;
        }
        if (currentSong == null || context.getMasterSongList() == null) {
            return false;
        }

        for (Song song : context.getMasterSongList()) {
            if (PlayerMenuDownloadSongMatcher.matchesSong(song, currentSong)) {
                return true;
            }
        }
        return false;
    }

    private boolean upsertSongKeepingViewOrder(List<Song> updatedSource,
                                               Song remoteSong,
                                               Song localSong) {
        if (updatedSource == null || localSong == null) {
            return false;
        }

        for (int index = 0; index < updatedSource.size(); index++) {
            Song existing = updatedSource.get(index);
            if (PlayerMenuDownloadSongMatcher.matchesSong(existing, remoteSong)
                    || PlayerMenuDownloadSongMatcher.matchesSong(existing, localSong)) {
                if (!PlayerMenuDownloadSongMatcher.samePlayableSongReference(existing, localSong)) {
                    updatedSource.set(index, localSong);
                    return true;
                }
                return false;
            }
        }

        int targetMasterIndex = viewCoordinator.indexInCurrentView(remoteSong, localSong);
        if (targetMasterIndex < 0) {
            return false;
        }

        int insertIndex = updatedSource.size();
        for (int index = 0; index < updatedSource.size(); index++) {
            int existingMasterIndex = viewCoordinator.indexInCurrentView(
                    updatedSource.get(index), updatedSource.get(index));
            if (existingMasterIndex > targetMasterIndex) {
                insertIndex = index;
                break;
            }
        }
        updatedSource.add(insertIndex, localSong);
        return true;
    }
}
