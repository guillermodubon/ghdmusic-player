
package io.github.guillermodubon.musicplayer.services.startup.locality;

import io.github.guillermodubon.musicplayer.repository.DbConnectionManager;
import io.github.guillermodubon.musicplayer.services.manifest.ManifestSyncService;
import io.github.guillermodubon.musicplayer.models.DeezerApiMetaData;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.io.File;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SongLocalityService {

    private final StartUpService owner;
    private final ManifestSyncService manifestSyncService;
    private final ExecutorService localityExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "local-song-availability");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<String> pendingUnavailableSongs = ConcurrentHashMap.newKeySet();

    public SongLocalityService(StartUpService owner, ManifestSyncService manifestSyncService) {
        this.owner = java.util.Objects.requireNonNull(owner, "owner");
        this.manifestSyncService = java.util.Objects.requireNonNull(manifestSyncService, "manifestSyncService");
    }

    public void markSongAsLocal(long tid, String absolutePath) {
        if (tid <= 0) return;

        synchronized (owner.getDbLock()) {
            try {
                DbConnectionManager.getInstance().runInTransaction(conn -> {
                    try {
                        try (PreparedStatement upd = conn.prepareStatement("UPDATE Song SET IsLocal = 1, FilePath = ? WHERE SongID = ?")) {
                            upd.setString(1, absolutePath);
                            upd.setLong(2, tid);
                            upd.executeUpdate();
                        }
                    } catch (SQLException e) {
                        try (PreparedStatement upd2 = conn.prepareStatement("UPDATE Song SET IsLocal = 1 WHERE SongID = ?")) {
                            upd2.setLong(1, tid);
                            upd2.executeUpdate();
                        } catch (SQLException ex) {
                            throw new RuntimeException(ex);
                        }
                    }
                    return null;
                });
            } catch (Throwable t) {
                System.out.println("markSongAsLocal: DB update failed -> " + Optional.ofNullable(t.getMessage()).orElse("null"));
                t.printStackTrace();
            }
        }

        synchronized (owner.getSongs()) {
            for (Song s : owner.getSongs()) {
                if (s != null && s.getSongID() == tid) {
                    s.setLocal(true);
                    if (absolutePath != null) s.setFilePath(absolutePath);
                }
            }
        }

        if (absolutePath != null) {
            try {
                Optional<Song> so = owner.getSongs().stream().filter(s -> s != null && s.getSongID() == tid).findFirst();
                if (so.isPresent() && so.get().getTitle() != null) {
                    owner.putTitleToPath(so.get().getTitle(), absolutePath);
                    DeezerApiMetaData pseudo = new DeezerApiMetaData();
                    pseudo.setSongName(so.get().getTitle());
                    pseudo.setTrackId(so.get().getSongID());
                    manifestSyncService.updateManifestEntryAsync(pseudo, new File(absolutePath), System.currentTimeMillis());
                }
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Marks the model unavailable immediately and persists the change away
     * from the FX and playback threads.
     */
    public void markSongAsUnavailable(Song song) {
        if (song == null) return;

        String missingPath = song.getFilePath();
        String invalidationKey = unavailableSongKey(song, missingPath);
        owner.markSongUnavailableInMemory(song, missingPath);

        if (!pendingUnavailableSongs.add(invalidationKey)) {
            return;
        }

        localityExecutor.execute(() -> {
            try {
                long songId = song.getSongID();
                if (songId > 0) {
                    synchronized (owner.getDbLock()) {
                        try {
                            DbConnectionManager.getInstance().runInTransaction(conn -> {
                                try (PreparedStatement update = conn.prepareStatement(
                                        "UPDATE Song SET IsLocal = 0, FilePath = NULL WHERE SongID = ?")) {
                                    update.setLong(1, songId);
                                    update.executeUpdate();
                                } catch (SQLException error) {
                                    throw new RuntimeException(error);
                                }
                                return null;
                            });
                        } catch (Throwable error) {
                            System.err.println("markSongAsUnavailable: DB update failed -> "
                                    + Optional.ofNullable(error.getMessage()).orElse("null"));
                        }
                    }
                }

                manifestSyncService.removeManifestEntryAsync(song, missingPath);
            } finally {
                pendingUnavailableSongs.remove(invalidationKey);
            }
        });
    }

    private String unavailableSongKey(Song song, String missingPath) {
        if (song.getSongID() > 0) return "song:" + song.getSongID();
        if (missingPath != null && !missingPath.isBlank()) return "path:" + missingPath.trim().toLowerCase();
        String title = song.getTitle();
        return "title:" + (title == null ? "" : title.trim().toLowerCase());
    }
}
