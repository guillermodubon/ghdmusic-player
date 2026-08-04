package io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.services;

import javafx.collections.FXCollections;
import javafx.scene.image.Image;
import io.github.guillermodubon.musicplayer.repository.DataBaseConfig;
import io.github.guillermodubon.musicplayer.repository.dao.playlist.PlaylistDao;
import io.github.guillermodubon.musicplayer.repository.dao.playlist.PlaylistDaoImpl;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

public class PlaylistDialogService {

    public Playlist createPlaylist(String title, String description, Image uiImage, byte[] coverBytes) throws SQLException {
        try (Connection conn = DataBaseConfig.getConnection()) {
            conn.setAutoCommit(false);

            PlaylistDao dao = new PlaylistDaoImpl(conn);

            Long existing = dao.findIdByTitle(title);
            if (existing != null) {
                conn.rollback();
                throw new IllegalStateException("Ya existe una playlist con ese título.");
            }

            Playlist provisional = new Playlist(
                    -1L,
                    title,
                    "User",
                    description,
                    null,
                    null,
                    FXCollections.observableArrayList()
            );

            dao.createPlaylist(provisional, coverBytes);
            conn.commit();

            return dao.findById(provisional.getId()).orElse(provisional);
        }
    }

    public void updatePlaylist(Playlist playlist, byte[] coverBytes, File selectedImage) throws SQLException, IOException {
        updatePlaylist(playlist, coverBytes, selectedImage, false);
    }

    public void updatePlaylist(Playlist playlist,
                               byte[] coverBytes,
                               File selectedImage,
                               boolean removeCover) throws SQLException, IOException {
        if (playlist == null) {
            throw new IllegalArgumentException("Playlist no disponible.");
        }

        try (Connection conn = DataBaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try {
                PlaylistDao dao = new PlaylistDaoImpl(conn);
                Long existingId = playlist.getTitle() == null || playlist.getTitle().isBlank()
                        ? null
                        : dao.findIdByTitle(playlist.getTitle());
                if (existingId != null && existingId != playlist.getId()) {
                    throw new IllegalStateException("Ya existe una playlist con ese titulo.");
                }

                updatePlaylistMetadata(conn, playlist, coverBytes, selectedImage != null || removeCover);
                conn.commit();
            } catch (SQLException | RuntimeException ex) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                }
                throw ex;
            }
        }
    }

    private void updatePlaylistMetadata(Connection conn,
                                        Playlist playlist,
                                        byte[] coverBytes,
                                        boolean updateCover) throws SQLException {
        String sql = updateCover
                ? "UPDATE Playlist SET Title = ?, Description = ?, CoverImage = ? WHERE PlaylistID = ?"
                : "UPDATE Playlist SET Title = ?, Description = ? WHERE PlaylistID = ?";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playlist.getTitle());
            if (playlist.getDescription() == null) {
                ps.setNull(2, Types.VARCHAR);
            } else {
                ps.setString(2, playlist.getDescription());
            }

            if (updateCover) {
                if (coverBytes == null || coverBytes.length == 0) {
                    ps.setNull(3, Types.BLOB);
                } else {
                    ps.setBytes(3, coverBytes);
                }
                ps.setLong(4, playlist.getId());
            } else {
                ps.setLong(3, playlist.getId());
            }

            ps.executeUpdate();
        }
    }

    public void addSongToPlaylist(long playlistId, Song song) throws SQLException {
        try (Connection conn = DataBaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try {
                PlaylistDaoImpl dao = new PlaylistDaoImpl(conn);
                dao.addSongToPlaylist(conn, playlistId, song);
                conn.commit();
            } catch (SQLException ex) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                throw ex;
            }
        }
    }

    public void removeSongFromPlaylist(long playlistId, long songId) throws SQLException {
        try (Connection conn = DataBaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try {
                PlaylistDaoImpl dao = new PlaylistDaoImpl(conn);
                dao.removeSongFromPlaylist(conn, playlistId, songId);
                conn.commit();
            } catch (SQLException ex) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                throw ex;
            }
        }
    }

    public void addSongsToPlaylist(long playlistId, List<Song> songs) throws SQLException {
        if (songs == null || songs.isEmpty()) return;

        try (Connection conn = DataBaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try {
                PlaylistDaoImpl dao = new PlaylistDaoImpl(conn);
                dao.addSongsToPlaylist(conn, playlistId, songs);
                conn.commit();
            } catch (SQLException ex) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                throw ex;
            }
        }
    }

    public void removeSongsFromPlaylist(long playlistId, List<Song> songs) throws SQLException {
        if (songs == null || songs.isEmpty()) return;

        try (Connection conn = DataBaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try {
                PlaylistDaoImpl dao = new PlaylistDaoImpl(conn);
                for (Song song : songs) {
                    if (song != null && song.getSongID() > 0) {
                        dao.removeSongFromPlaylist(conn, playlistId, song.getSongID());
                    }
                }
                conn.commit();
            } catch (SQLException ex) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                throw ex;
            }
        }
    }
}
