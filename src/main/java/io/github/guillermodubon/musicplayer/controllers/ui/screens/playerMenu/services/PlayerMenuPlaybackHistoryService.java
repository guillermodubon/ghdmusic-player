package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services;

import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext.ContentType;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.PlaybackHistory;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.repository.dao.history.PlaybackHistoryDao;
import io.github.guillermodubon.musicplayer.repository.dao.history.PlaybackHistoryDaoImpl;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Persists PlayerMenu playback origins without blocking the JavaFX thread. */
public final class PlayerMenuPlaybackHistoryService {
    private static final ExecutorService HISTORY_IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "player-menu-history-io");
        thread.setDaemon(true);
        return thread;
    });

    public void persist(Song song, Playlist playlist, ContentType contentType) {
        PlaybackOrigin origin = resolveOrigin(song, playlist, contentType);
        if (origin == null) return;

        HISTORY_IO.execute(() -> {
            try {
                PlaybackHistoryDao dao = new PlaybackHistoryDaoImpl(null);
                dao.insertAndGetId(new PlaybackHistory(
                        origin.itemId(),
                        origin.itemType(),
                        origin.name(),
                        System.currentTimeMillis()
                ));
            } catch (Exception error) {
                error.printStackTrace();
            }
        });
    }

    private PlaybackOrigin resolveOrigin(Song song,
                                         Playlist playlist,
                                         ContentType contentType) {
        long itemId = playlist == null ? -1L : playlist.getId();
        String name = playlist == null ? null : playlist.getTitle();
        String itemType = normalizeType(contentType);

        if (itemId <= 0 && song != null) {
            if ("ALBUM".equals(itemType)
                    && song.getAlbum() != null
                    && song.getAlbum().getAlbumID() > 0) {
                itemId = song.getAlbum().getAlbumID();
                name = song.getAlbum().getName();
            } else {
                itemId = song.getSongID();
                name = song.getTitle();
                itemType = "SINGLE";
            }
        }

        if (itemId <= 0) return null;
        if (name == null || name.isBlank()) {
            name = song == null || song.getTitle() == null || song.getTitle().isBlank()
                    ? "Unknown"
                    : song.getTitle();
        }
        return new PlaybackOrigin(itemId, itemType, name);
    }

    private String normalizeType(ContentType type) {
        if (type == null) return "SINGLE";
        return switch (type) {
            case PLAYLIST -> "PLAYLIST";
            case SINGLE -> "SINGLE";
            case ALBUM, EPISODE -> "ALBUM";
        };
    }

    private record PlaybackOrigin(long itemId, String itemType, String name) {
    }
}
