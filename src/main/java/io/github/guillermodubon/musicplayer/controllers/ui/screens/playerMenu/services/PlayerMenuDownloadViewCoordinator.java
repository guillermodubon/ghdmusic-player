package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services;

import javafx.scene.control.ListView;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext;
import io.github.guillermodubon.musicplayer.models.DeezerApiMetaData;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.downloads.services.IntegratedDownloadResult;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Updates the PlayerMenu models and virtualized lists after a download. */
final class PlayerMenuDownloadViewCoordinator {

    private final PlayerMenuContext context;
    private ListView<Song> songsToPlayView;
    private ListView<Song> songListView;
    private ListView<Song> recommendationsView;
    private StartUpService startUpService;

    PlayerMenuDownloadViewCoordinator(PlayerMenuContext context) {
        this.context = context;
    }

    void bindUi(ListView<Song> songsToPlayView,
                ListView<Song> songListView,
                ListView<Song> recommendationsView) {
        this.songsToPlayView = songsToPlayView;
        this.songListView = songListView;
        this.recommendationsView = recommendationsView;
    }

    void bindStartUpService(StartUpService service) {
        this.startUpService = service;
    }

    boolean replaceInCollectionState(Song remoteSong, Song localSong) {
        boolean changed = false;
        changed |= replaceSongInList(context.getMasterSongList(), remoteSong, localSong);
        changed |= replaceSongInList(context.getCurrentSongList(), remoteSong, localSong);
        return changed;
    }

    boolean replaceInVisibleLists(Song remoteSong, Song localSong) {
        boolean changed = replaceSongInListView(songsToPlayView, remoteSong, localSong);
        if (songListView != null && songListView != songsToPlayView) {
            changed |= replaceSongInListView(songListView, remoteSong, localSong);
        }
        return changed;
    }

    boolean replaceInRecommendations(Song remoteSong, Song localSong) {
        return replaceSongInListView(recommendationsView, remoteSong, localSong);
    }

    boolean updateCollectionState(DeezerApiMetaData metadata, File finalFile) {
        if (metadata == null) {
            return false;
        }
        boolean masterChanged = updateSongs(context.getMasterSongList(), metadata, finalFile);
        boolean currentChanged = updateSongs(context.getCurrentSongList(), metadata, finalFile);
        return masterChanged || currentChanged;
    }

    boolean updateVisibleLists(DeezerApiMetaData metadata, File finalFile) {
        if (metadata == null) {
            return false;
        }
        boolean changed = updateListViewItems(songsToPlayView, metadata, finalFile);
        if (songListView != null && songListView != songsToPlayView) {
            changed |= updateListViewItems(songListView, metadata, finalFile);
        }
        return changed;
    }

    boolean updateRecommendations(DeezerApiMetaData metadata, File finalFile) {
        return metadata != null && updateListViewItems(recommendationsView, metadata, finalFile);
    }

    Song resolvePlayableSong(DeezerApiMetaData metadata, File finalFile) {
        if (startUpService != null) {
            try {
                Song canonical = startUpService.findCanonicalDownloadedSong(metadata, finalFile).orElse(null);
                if (canonical != null) {
                    return canonical;
                }
            } catch (Exception ignored) {
            }
        }

        if (metadata != null && metadata.getTrackId() > 0) {
            Song byId = context.findCurrentSongById(metadata.getTrackId());
            if (byId != null) {
                return byId;
            }
        }

        List<Song> masterSongs = context.getMasterSongList();
        if (masterSongs == null) {
            return null;
        }
        for (Song song : masterSongs) {
            if (PlayerMenuDownloadSongMatcher.matchesDownloadedSong(song, metadata)) {
                return song;
            }
        }
        return null;
    }

    boolean rebuildCurrentPlayableListFromMaster() {
        List<Song> masterSongs = context.getMasterSongList();
        if (masterSongs == null) {
            return false;
        }

        List<Song> rebuiltPlayableSongs = new ArrayList<>(masterSongs.size());
        for (Song song : masterSongs) {
            if (!ensurePlayableLocalState(song)) {
                continue;
            }
            if (!PlayerMenuDownloadSongMatcher.containsMatchingSong(rebuiltPlayableSongs, song)) {
                rebuiltPlayableSongs.add(song);
            }
        }

        List<Song> currentSongs = context.getCurrentSongList();
        boolean changed = !PlayerMenuDownloadSongMatcher.sameSongSequence(currentSongs, rebuiltPlayableSongs);
        context.setCurrentSongList(rebuiltPlayableSongs);
        return changed;
    }

    boolean ensurePlayableLocalState(Song song) {
        if (PlayerMenuDownloadSongMatcher.isPlayableLocalSong(song)) {
            return true;
        }
        if (song == null || !song.isLocal() || startUpService == null) {
            return false;
        }
        try {
            var resolvedPath = startUpService.resolvePathForSong(song);
            if (resolvedPath.isPresent()) {
                File file = new File(resolvedPath.get());
                if (file.exists() && file.isFile() && file.canRead() && file.length() > 0L) {
                    song.setFilePath(file.getAbsolutePath());
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    boolean forceLocalSongIntoCurrentView(Song remoteSong, Song localSong, File downloadedFile) {
        List<Song> masterSongs = context.getMasterSongList();
        if (masterSongs == null || masterSongs.isEmpty() || localSong == null || downloadedFile == null) {
            return false;
        }

        String localPath = downloadedFile.getAbsolutePath();
        boolean changed = false;
        for (int index = 0; index < masterSongs.size(); index++) {
            Song existing = masterSongs.get(index);
            if (!PlayerMenuDownloadSongMatcher.matchesSong(existing, remoteSong)
                    && !PlayerMenuDownloadSongMatcher.matchesSong(existing, localSong)) {
                continue;
            }

            PlayerMenuDownloadSongMatcher.preserveViewSpecificData(existing, localSong);
            existing.setLocal(true);
            existing.setFilePath(localPath);
            try {
                masterSongs.set(index, existing);
            } catch (Exception ignored) {
            }
            changed = true;
        }
        return changed;
    }

    boolean resultBelongsToCurrentView(IntegratedDownloadResult result) {
        if (result == null || context.getMasterSongList() == null || context.getMasterSongList().isEmpty()) {
            return false;
        }
        for (Song viewSong : context.getMasterSongList()) {
            if (PlayerMenuDownloadSongMatcher.matchesSong(viewSong, result.remoteSong())
                    || PlayerMenuDownloadSongMatcher.matchesSong(viewSong, result.localSong())) {
                return true;
            }
        }
        return false;
    }

    boolean metadataBelongsToCurrentView(DeezerApiMetaData metadata) {
        if (metadata == null || context.getMasterSongList() == null || context.getMasterSongList().isEmpty()) {
            return false;
        }
        for (Song song : context.getMasterSongList()) {
            if (PlayerMenuDownloadSongMatcher.matchesDownloadedSong(song, metadata)) {
                return true;
            }
        }
        return false;
    }

    boolean downloadBelongsToCurrentView(Song remoteSong, Song localSong) {
        return findSongInCurrentView(remoteSong, localSong) != null;
    }

    Song findSongInCurrentView(Song remoteSong, Song localSong) {
        List<Song> masterSongs = context.getMasterSongList();
        if (masterSongs == null || masterSongs.isEmpty()) {
            return null;
        }
        for (Song candidate : masterSongs) {
            if (PlayerMenuDownloadSongMatcher.matchesSong(candidate, remoteSong)
                    || PlayerMenuDownloadSongMatcher.matchesSong(candidate, localSong)) {
                return candidate;
            }
        }
        return null;
    }

    int indexInCurrentView(Song remoteSong, Song localSong) {
        List<Song> masterSongs = context.getMasterSongList();
        if (masterSongs == null || masterSongs.isEmpty()) {
            return -1;
        }
        for (int index = 0; index < masterSongs.size(); index++) {
            Song candidate = masterSongs.get(index);
            if (PlayerMenuDownloadSongMatcher.matchesSong(candidate, remoteSong)
                    || PlayerMenuDownloadSongMatcher.matchesSong(candidate, localSong)) {
                return index;
            }
        }
        return -1;
    }

    List<Song> buildPlayableSourceFromCurrentView() {
        List<Song> updatedSource = new ArrayList<>();
        List<Song> masterSongs = context.getMasterSongList();
        if (masterSongs == null || masterSongs.isEmpty()) {
            return updatedSource;
        }
        for (Song song : masterSongs) {
            if (PlayerMenuDownloadSongMatcher.isPlayableLocalSong(song)
                    && !PlayerMenuDownloadSongMatcher.containsMatchingSong(updatedSource, song)) {
                updatedSource.add(song);
            }
        }
        return updatedSource;
    }

    private boolean replaceSongInListView(ListView<Song> listView, Song remoteSong, Song localSong) {
        if (listView == null || listView.getItems() == null) {
            return false;
        }
        if (listView.getItems() instanceof javafx.collections.transformation.FilteredList<?>) {
            refreshList(listView);
            return false;
        }
        try {
            return replaceSongInList(listView.getItems(), remoteSong, localSong);
        } catch (UnsupportedOperationException ignored) {
            refreshList(listView);
            return false;
        }
    }

    private boolean replaceSongInList(List<Song> songs, Song remoteSong, Song localSong) {
        if (songs == null || remoteSong == null || localSong == null) {
            return false;
        }
        boolean changed = false;
        for (int index = 0; index < songs.size(); index++) {
            Song existing = songs.get(index);
            if (!PlayerMenuDownloadSongMatcher.matchesSong(existing, remoteSong)
                    && !PlayerMenuDownloadSongMatcher.matchesSong(existing, localSong)) {
                continue;
            }
            Song localViewSong = PlayerMenuDownloadSongMatcher.copyForView(existing, localSong);
            songs.set(index, localViewSong == null ? localSong : localViewSong);
            changed = true;
        }
        return changed;
    }

    private boolean updateListViewItems(ListView<Song> listView, DeezerApiMetaData metadata, File finalFile) {
        return listView != null && listView.getItems() != null
                && updateSongs(listView.getItems(), metadata, finalFile);
    }

    private boolean updateSongs(List<Song> songs, DeezerApiMetaData metadata, File finalFile) {
        if (songs == null || metadata == null) {
            return false;
        }
        boolean changed = false;
        for (int index = 0; index < songs.size(); index++) {
            Song song = songs.get(index);
            if (!PlayerMenuDownloadSongMatcher.matchesDownloadedSong(song, metadata)) {
                continue;
            }
            boolean itemChanged = false;
            if (!song.isLocal()) {
                song.setLocal(true);
                itemChanged = true;
            }
            if (finalFile != null && finalFile.exists() && finalFile.isFile()
                    && !java.util.Objects.equals(song.getFilePath(), finalFile.getAbsolutePath())) {
                song.setFilePath(finalFile.getAbsolutePath());
                itemChanged = true;
            }
            if (itemChanged) {
                try {
                    songs.set(index, song);
                } catch (UnsupportedOperationException ignored) {
                }
                changed = true;
            }
        }
        return changed;
    }

    private void refreshList(ListView<Song> listView) {
        try {
            if (listView != null) {
                listView.refresh();
            }
        } catch (Exception ignored) {
        }
    }
}
