package io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.managers;

import javafx.scene.image.ImageView;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext.ContentType;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.PlayerMenuController;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;

public final class PlayerMenuBarNavigationHandler {

    public void openCurrentPlayingContext(PlaybackManager pm,
                                          MusicCardActionManager musicActions,
                                          ImageView anchor) {
        if (pm == null || musicActions == null || anchor == null) return;

        NavigationTarget target = resolveTarget(pm);
        if (target == null || target.type() == null || target.id() <= 0) return;
        if (isAlreadyViewingTarget(pm, target)) return;

        String idStr = String.valueOf(target.id());
        switch (target.type()) {
            case PLAYLIST -> musicActions.playlistClick(anchor).accept(idStr);
            case ALBUM, EPISODE -> musicActions.albumClick(anchor).accept(idStr);
            case SINGLE -> musicActions.songClick(anchor).accept(idStr);
        }
    }

    private NavigationTarget resolveTarget(PlaybackManager pm) {
        Song playing = pm.getCurrentSong();
        if (playing != null && pm.isCurrentSongFromQueue()) {
            return targetForSongOwnContext(playing);
        }

        NavigationTarget sourceTarget = targetForPlaybackSource(pm, playing);
        if (sourceTarget != null) return sourceTarget;

        return targetForSongOwnContext(playing);
    }

    private NavigationTarget targetForPlaybackSource(PlaybackManager pm, Song playing) {
        ContentType playingType = pm.getCurrentContentTypePlaying();
        if (playingType == null) return null;

        long id = pm.getCurrentPlaylistPlayingId();

        return switch (playingType) {
            case PLAYLIST -> id > 0
                    ? new NavigationTarget(ContentType.PLAYLIST, id)
                    : targetForSongOwnContext(playing);
            case ALBUM, EPISODE -> {
                if (id <= 0 && playing != null && playing.getAlbum() != null) {
                    id = playing.getAlbum().getAlbumID();
                }
                yield id > 0 ? new NavigationTarget(playingType, id) : null;
            }
            case SINGLE -> {
                if (id <= 0 && playing != null) {
                    id = playing.getSongID();
                }
                yield id > 0 ? new NavigationTarget(ContentType.SINGLE, id) : null;
            }
        };
    }

    private NavigationTarget targetForSongOwnContext(Song song) {
        if (song == null) return null;

        Album album = song.getAlbum();
        if (shouldOpenAlbum(album)) {
            return new NavigationTarget(ContentType.ALBUM, album.getAlbumID());
        }

        return song.getSongID() > 0
                ? new NavigationTarget(ContentType.SINGLE, song.getSongID())
                : null;
    }

    private boolean shouldOpenAlbum(Album album) {
        if (album == null || album.getAlbumID() <= 0) return false;

        String recordType = album.getRecordType();
        if (recordType != null && recordType.equalsIgnoreCase("single")) return false;

        if (album.getNumberOfTracks() > 0) return album.getNumberOfTracks() > 1;
        if (album.getSongList() != null && !album.getSongList().isEmpty()) return album.getSongList().size() > 1;

        return true;
    }

    private boolean isAlreadyViewingTarget(PlaybackManager pm, NavigationTarget target) {
        PlayerMenuController menuController = pm.getMenuController();
        if (menuController == null || !menuController.isCurrentCenterViewVisible()) return false;
        return menuController.getCurrentPlaylistInViewId() == target.id()
                && menuController.getCurrentContentTypeInView() == target.type();
    }

    private record NavigationTarget(ContentType type, long id) {}
}
