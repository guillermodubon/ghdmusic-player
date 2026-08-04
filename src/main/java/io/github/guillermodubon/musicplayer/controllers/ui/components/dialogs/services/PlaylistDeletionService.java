package io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.services;

import javafx.application.Platform;
import io.github.guillermodubon.musicplayer.repository.dao.playlist.PlaylistDao;
import io.github.guillermodubon.musicplayer.repository.dao.playlist.PlaylistDaoImpl;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.sql.SQLException;
import java.util.Optional;

public class PlaylistDeletionService {

    public void deleteFromDatabase(Playlist playlist,
                                   PlaylistDao configuredDao,
                                   StartUpService svc) throws SQLException {
        if (playlist == null) {
            throw new IllegalArgumentException("Playlist no disponible.");
        }
        if (playlist.getId() <= 0) {
            throw new IllegalArgumentException("Playlist invalida.");
        }

        PlaylistDao dao = resolveDao(configuredDao, svc);
        dao.delete(playlist.getId());
    }

    public void removeFromMemory(Playlist playlist, StartUpService svc) {
        if (playlist == null || svc == null || svc.getPlaylists() == null) return;

        Runnable action = () -> {
            long id = playlist.getId();
            String title = Optional.ofNullable(playlist.getTitle()).orElse("");
            svc.getPlaylists().removeIf(p ->
                    p != null
                            && (p.getId() == id
                            || (!title.isBlank()
                            && p.getTitle() != null
                            && p.getTitle().equalsIgnoreCase(title))));
        };

        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    private PlaylistDao resolveDao(PlaylistDao configuredDao, StartUpService svc) {
        if (configuredDao != null) return configuredDao;
        if (svc != null && svc.getPlaylistDao() != null) return svc.getPlaylistDao();
        return new PlaylistDaoImpl(null);
    }
}
