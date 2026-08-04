package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services;

import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.repository.dao.playlist.PlaylistDao;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Persists the latest requested order without blocking the JavaFX thread. */
final class PlayerMenuPlaylistOrderPersistenceService {

    private static final ExecutorService DB_WRITES = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "player-menu-playlist-order");
        thread.setDaemon(true);
        return thread;
    });
    private static final ConcurrentHashMap<Long, PendingOrder> PENDING_BY_PLAYLIST =
            new ConcurrentHashMap<>();
    private static final AtomicBoolean DRAIN_SCHEDULED = new AtomicBoolean(false);

    void request(StartUpService service, Playlist playlist, List<Song> orderedSongs) {
        requestInternal(service, playlist, orderedSongs, false);
    }

    void requestCustom(StartUpService service, Playlist playlist, List<Song> orderedSongs) {
        requestInternal(service, playlist, orderedSongs, true);
    }

    private void requestInternal(StartUpService service,
                                  Playlist playlist,
                                  List<Song> orderedSongs,
                                  boolean saveCustomOrder) {
        if (service == null || playlist == null || playlist.getId() <= 0
                || orderedSongs == null || orderedSongs.size() < 2) {
            return;
        }

        List<Long> orderedIds = new ArrayList<>(orderedSongs.size());
        Set<Long> seenIds = new HashSet<>();
        for (Song song : orderedSongs) {
            if (song == null || song.getSongID() <= 0 || !seenIds.add(song.getSongID())) {
                return;
            }
            orderedIds.add(song.getSongID());
        }

        PENDING_BY_PLAYLIST.compute(playlist.getId(), (playlistId, previous) ->
                new PendingOrder(
                        service,
                        playlistId,
                        List.copyOf(orderedIds),
                        saveCustomOrder
                                ? List.copyOf(orderedIds)
                                : previous == null ? null : previous.customOrderedSongIds()
                ));
        scheduleDrain();
    }

    private void scheduleDrain() {
        if (DRAIN_SCHEDULED.compareAndSet(false, true)) {
            DB_WRITES.execute(this::drainPendingOrders);
        }
    }

    private void drainPendingOrders() {
        try {
            while (!PENDING_BY_PLAYLIST.isEmpty()) {
                for (Long playlistId : new ArrayList<>(PENDING_BY_PLAYLIST.keySet())) {
                    PendingOrder pending = PENDING_BY_PLAYLIST.remove(playlistId);
                    if (pending != null) persistWithRetry(pending);
                }
            }
        } finally {
            DRAIN_SCHEDULED.set(false);
            if (!PENDING_BY_PLAYLIST.isEmpty()) scheduleDrain();
        }
    }

    private void persistWithRetry(PendingOrder pending) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                PlaylistDao dao = pending.service().getPlaylistDao();
                if (dao == null) return;
                dao.persistSongOrders(
                        pending.playlistId(),
                        pending.orderedSongIds(),
                        pending.customOrderedSongIds()
                );
                return;
            } catch (SQLException exception) {
                if (attempt == 2) {
                    System.err.println("Could not persist playlist order: " + exception.getMessage());
                    return;
                }
                try {
                    Thread.sleep(120L * (attempt + 1));
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } catch (RuntimeException exception) {
                System.err.println("Could not persist playlist order: " + exception.getMessage());
                return;
            }
        }
    }

    private record PendingOrder(
            StartUpService service,
            long playlistId,
            List<Long> orderedSongIds,
            List<Long> customOrderedSongIds
    ) {
    }
}
