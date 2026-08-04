package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.header;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.image.Image;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.repository.dao.playlist.PlaylistDao;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.utils.ImageUtils;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Coordinates playlist persistence and the startup cache used by PlayerMenu.
 * Database statements remain inside PlaylistDao; this class only maps the
 * screen model to repository operations and keeps the observable cache in sync.
 */
public final class PlayerMenuPlaylistPersistence {

    private StartUpService startupService;
    private PlaylistDao configuredDao;

    public void bind(StartUpService startupService, PlaylistDao playlistDao) {
        this.startupService = startupService;
        this.configuredDao = playlistDao;
    }

    public PlaylistDao resolveDao() {
        if (configuredDao != null) {
            return configuredDao;
        }
        return startupService == null ? null : startupService.getPlaylistDao();
    }

    public boolean isUserLocal(Playlist playlist) {
        if (playlist == null || startupService == null || startupService.getPlaylists() == null) {
            return false;
        }

        long playlistId = playlist.getId();
        return startupService.getPlaylists().stream()
                .filter(Objects::nonNull)
                .anyMatch(saved -> saved.getId() == playlistId
                        && Optional.ofNullable(saved.getAuthorName())
                        .orElse("")
                        .equalsIgnoreCase("User"));
    }

    public boolean existsLocally(Playlist playlist) {
        if (playlist == null) {
            return false;
        }

        if (startupService != null && startupService.getPlaylists() != null) {
            boolean cached = startupService.getPlaylists().stream()
                    .filter(Objects::nonNull)
                    .anyMatch(saved -> samePlaylist(saved, playlist));
            if (cached) {
                return true;
            }
        }

        PlaylistDao dao = resolveDao();
        if (dao == null || playlist.getTitle() == null || playlist.getTitle().isBlank()) {
            return false;
        }

        try {
            Long id = dao.findIdByTitle(playlist.getTitle());
            return id != null && id > 0;
        } catch (SQLException ignored) {
            return false;
        }
    }

    public Playlist saveRemotePlaylist(Playlist remotePlaylist) throws SQLException {
        PlaylistDao dao = resolveDao();
        if (remotePlaylist == null || dao == null) {
            throw new IllegalArgumentException("Playlist and PlaylistDao are mandatory");
        }

        String title = Optional.ofNullable(remotePlaylist.getTitle())
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .orElse("Untitled");
        String author = Optional.ofNullable(remotePlaylist.getAuthorName())
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .orElse("Unknown");
        String description = Optional.ofNullable(remotePlaylist.getDescription()).orElse("");

        byte[] coverBytes = resolveCoverBytes(remotePlaylist);
        Image coverImage = resolveCoverImage(remotePlaylist);

        Playlist databaseShortcut = new Playlist(
                remotePlaylist.getId(),
                title,
                author,
                description,
                remotePlaylist.getDate(),
                coverImage,
                FXCollections.observableArrayList()
        );
        databaseShortcut.setCoverUrl(remotePlaylist.getCoverUrl());

        long remoteId = remotePlaylist.getId();
        if (remoteId > 0) {
            dao.upsertRemotePlaylistPreservingId(databaseShortcut, coverBytes, remoteId);
        } else {
            dao.createPlaylist(databaseShortcut, coverBytes);
            Long generatedId = dao.findIdByTitle(title);
            if (generatedId != null && generatedId > 0) {
                databaseShortcut.setId(generatedId);
            }
        }

        if (databaseShortcut.getId() > 0) {
            MediaImageResolver.invalidatePlaylistCover(databaseShortcut.getId());
        }

        ObservableList<Song> visibleSongs = FXCollections.observableArrayList();
        if (remotePlaylist.getSongList() != null) {
            remotePlaylist.getSongList().stream()
                    .filter(Objects::nonNull)
                    .forEach(visibleSongs::add);
        }

        Playlist cachedShortcut = new Playlist(
                databaseShortcut.getId(),
                title,
                author,
                description,
                remotePlaylist.getDate(),
                coverImage,
                visibleSongs
        );
        cachedShortcut.setCoverUrl(remotePlaylist.getCoverUrl());
        return cachedShortcut;
    }

    public DeletedPlaylist deleteRemotePlaylist(Playlist playlist) throws SQLException {
        PlaylistDao dao = resolveDao();
        if (playlist == null || dao == null) {
            throw new IllegalArgumentException("Playlist y PlaylistDao son obligatorios.");
        }

        String title = Optional.ofNullable(playlist.getTitle()).orElse("");
        Long playlistId = null;
        if (playlist.getId() > 0 && existsLocallyById(playlist.getId())) {
            playlistId = playlist.getId();
        }
        if ((playlistId == null || playlistId <= 0) && !title.isBlank()) {
            playlistId = dao.findIdByTitle(title);
        }
        if (playlistId == null || playlistId <= 0) {
            return new DeletedPlaylist(-1L, title);
        }

        dao.delete(playlistId);
        return new DeletedPlaylist(playlistId, title);
    }

    public void addSavedPlaylistToCacheOnFxThread(Playlist playlist) {
        runOnFxThreadAndWait(() -> addSavedPlaylistToCache(playlist));
    }

    public void removeSavedPlaylistFromCacheOnFxThread(long playlistId, String playlistTitle) {
        runOnFxThreadAndWait(() -> removeSavedPlaylistFromCache(playlistId, playlistTitle));
    }

    private boolean existsLocallyById(long playlistId) {
        if (playlistId <= 0 || startupService == null || startupService.getPlaylists() == null) {
            return false;
        }
        return startupService.getPlaylists().stream()
                .filter(Objects::nonNull)
                .anyMatch(playlist -> playlist.getId() == playlistId);
    }

    private void addSavedPlaylistToCache(Playlist playlist) {
        if (playlist == null || startupService == null || startupService.getPlaylists() == null) {
            return;
        }

        boolean alreadySaved = startupService.getPlaylists().stream()
                .filter(Objects::nonNull)
                .anyMatch(existing -> existing.getId() == playlist.getId()
                        || sameTitle(existing.getTitle(), playlist.getTitle()));
        if (!alreadySaved) {
            startupService.getPlaylists().add(0, playlist);
        }
    }

    private void removeSavedPlaylistFromCache(long playlistId, String playlistTitle) {
        if (startupService == null || startupService.getPlaylists() == null) {
            return;
        }
        startupService.getPlaylists().removeIf(playlist -> playlist != null
                && (playlist.getId() == playlistId
                || sameTitle(playlist.getTitle(), playlistTitle)));
    }

    private byte[] resolveCoverBytes(Playlist playlist) {
        Image image = resolveCoverImage(playlist);
        if (!isUsableCoverImage(image)) {
            return null;
        }
        try {
            byte[] bytes = ImageUtils.toByteArray(image);
            return bytes == null || bytes.length == 0 ? null : bytes;
        } catch (Exception error) {
            System.err.println("No se pudo convertir la portada resuelta: " + error.getMessage());
            return null;
        }
    }

    private Image resolveCoverImage(Playlist playlist) {
        if (playlist == null) {
            return null;
        }

        try {
            Image resolved = MediaImageResolver.playlistCover(playlist, 400, 400);
            if (isUsableCoverImage(resolved)) {
                return resolved;
            }
        } catch (Exception ignored) {
        }

        String coverUrl = playlist.getCoverUrl();
        if (coverUrl == null || coverUrl.isBlank()) {
            return null;
        }

        try {
            Image loaded = new Image(coverUrl, 400, 400, false, true, false);
            return isUsableCoverImage(loaded) ? loaded : null;
        } catch (Exception error) {
            System.err.println("Could not load the remote playlist cover: " + error.getMessage());
            return null;
        }
    }

    private boolean isUsableCoverImage(Image image) {
        return image != null && !image.isError() && image.getWidth() > 0 && image.getHeight() > 0;
    }

    private boolean samePlaylist(Playlist first, Playlist second) {
        if (first == null || second == null) {
            return false;
        }
        if (first.getId() > 0 && second.getId() > 0 && first.getId() == second.getId()) {
            return true;
        }
        return sameTitle(first.getTitle(), second.getTitle());
    }

    private boolean sameTitle(String first, String second) {
        if (first == null || second == null) {
            return false;
        }
        return first.trim().equalsIgnoreCase(second.trim());
    }

    private void runOnFxThreadAndWait(Runnable action) {
        if (action == null) {
            return;
        }
        if (Platform.isFxApplicationThread()) {
            action.run();
            return;
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                action.run();
                future.complete(null);
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });
        future.join();
    }

    public record DeletedPlaylist(long playlistId, String title) {
    }
}
