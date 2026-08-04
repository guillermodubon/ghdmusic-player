package io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.guillermodubon.musicplayer.repository.DbConnectionManager;
import io.github.guillermodubon.musicplayer.repository.dao.album.AlbumDaoImpl;
import io.github.guillermodubon.musicplayer.repository.dao.artist.ArtistDaoImpl;
import io.github.guillermodubon.musicplayer.repository.dao.genre.GenreDaoImpl;
import io.github.guillermodubon.musicplayer.repository.dao.song.SongDaoImpl;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.context.ArtistPageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.repositories.ArtistPageDeezerRepository;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.repositories.ArtistPageMemoryRepository;
import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public class ArtistPageService {

    private final StartUpService svc;
    private final ArtistPageMemoryRepository memory;
    private final ArtistPageDeezerRepository deezer;
    private final ArtistPageContext context;

    private final SongDaoImpl songDao = new SongDaoImpl(null);
    private final AlbumDaoImpl albumDao = new AlbumDaoImpl(null);
    private final ArtistDaoImpl artistDao = new ArtistDaoImpl(null);
    private final GenreDaoImpl genreDao = new GenreDaoImpl(null);

    public ArtistPageService(ArtistPageContext context) {
        this.context = context;
        this.svc = context.svc();
        this.memory = context.memory();
        this.deezer = context.deezer();
    }

    public List<Album> snapshotAlbums() {
        return memory.snapshotAlbums(svc);
    }

    public List<Song> snapshotSongs() {
        return memory.snapshotSongs(svc);
    }

    public long resolveArtistId(Artist artist) {
        if (artist == null) return -1L;
        if (artist.getArtistID() > 0) return artist.getArtistID();
        if (artist.getName() == null || artist.getName().isBlank()) return -1L;
        try {
            Long id = artistDao.findIdByName(artist.getName());
            return id == null ? -1L : id;
        } catch (Exception ignored) {
            return -1L;
        }
    }

    public JsonObject topTracksJson(long artistId) throws IOException {
        return deezer.fetchTopTracks(artistId, 10, context.endpoints());
    }

    public JsonObject searchTracksJson(String query) throws IOException {
        return deezer.searchTracks(query);
    }


    public Predicate<Artist> artistMatches(Artist target) {
        return ar -> {
            if (ar == null || target == null) return false;
            if (ar.getArtistID() > 0 && target.getArtistID() > 0) {
                return ar.getArtistID() == target.getArtistID();
            }
            if (ar.getArtistID() > 0 || target.getArtistID() > 0) return false;
            return ar.getName() != null
                    && target.getName() != null
                    && ar.getName().equalsIgnoreCase(target.getName());
        };
    }

    public JsonArray artistAlbumsJson(long artistId) {
        return deezer.fetchArtistAlbums(artistId, context.endpoints());
    }

    public JsonArray searchAlbumsJson(String query) {
        return deezer.searchAlbums(query);
    }

    public JsonObject albumByIdJson(long albumId) throws IOException {
        return deezer.fetchAlbumById(albumId, context.endpoints());
    }

    public JsonObject trackByIdJson(long trackId) throws IOException {
        return deezer.fetchTrackById(trackId, context.endpoints());
    }

    public JsonObject searchPlaylistsJson(String query) throws IOException {
        return deezer.searchPlaylists(query, context.endpoints());
    }

    public List<Album> findAlbumsByArtistId(long artistId) throws SQLException {
        if (artistId <= 0) return List.of();

        String sql = """
                SELECT DISTINCT AlbumID
                  FROM AlbumArtist
                 WHERE ArtistID = ?
                """;
        List<Album> result = new ArrayList<>();
        try (Connection connection = DbConnectionManager.getInstance().openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, artistId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    long albumId = rows.getLong(1);
                    albumDao.findById(albumId).ifPresent(result::add);
                }
            }
        }
        return result;
    }

    public List<Song> findSongsByAlbum(long albumId) throws SQLException {
        return songDao.findByAlbum(albumId);
    }
}
