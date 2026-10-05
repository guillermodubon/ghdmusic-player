package io.github.guillermodubon.musicplayer.repository.dao.album;

import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Genre;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.repository.dao.support.JdbcDaoSupport;
import io.github.guillermodubon.musicplayer.repository.identity.CanonicalMediaService;
import io.github.guillermodubon.musicplayer.repository.identity.CanonicalizationResult;
import io.github.guillermodubon.musicplayer.repository.identity.ExternalMediaId;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** JDBC implementation for remote-album playback hydration. */
public final class AlbumPlaybackDaoImpl extends JdbcDaoSupport implements AlbumPlaybackDao {

    public AlbumPlaybackDaoImpl() {
        this(null);
    }

    public AlbumPlaybackDaoImpl(Connection connection) {
        super(connection);
    }

    @Override
    public void persistRemoteSongs(long albumId, Collection<Song> songs) {
        if (albumId <= 0 || songs == null || songs.isEmpty()) return;

        Map<Long, Song> uniqueSongs = new LinkedHashMap<>();
        for (Song song : songs) {
            if (song != null && song.getSongID() > 0) uniqueSongs.putIfAbsent(song.getSongID(), song);
        }
        if (uniqueSongs.isEmpty()) return;

        try {
            connectionManager().runInTransaction(connection -> {
                try {
                    CanonicalMediaService media = new CanonicalMediaService();
                    for (Song song : uniqueSongs.values()) {
                        CanonicalMediaService.AlbumMetadata album = toAlbumMetadata(song.getAlbum());
                        CanonicalMediaService.SongMetadata track = new CanonicalMediaService.SongMetadata(
                                Objects.requireNonNullElse(song.getTitle(), "Unknown track"),
                                song.getTrackOrder(),
                                song.getDurationSeconds(),
                                artistNames(song.getArtist()),
                                album,
                                false,
                                null
                        );
                        CanonicalizationResult<CanonicalMediaService.CanonicalSong> result =
                                media.ensureCanonicalSong(
                                        connection,
                                        ExternalMediaId.deezer(song.getSongID()),
                                        ExternalMediaId.deezer(albumId),
                                        track
                                );
                        if (!result.isSuccess()) {
                            throw new SQLException("Remote track identity conflict: " + result.conflict().reason());
                        }
                    }
                    return null;
                } catch (SQLException error) {
                    throw new RuntimeException(error);
                }
            });
        } catch (Exception ignored) {
            // Remote view hydration remains available if optional persistence fails.
        }
    }

    @Override
    public void updateReleaseDate(long albumId, String albumName, String releaseDate, int numberOfTracks) {
        if (albumId <= 0 || releaseDate == null || releaseDate.isBlank()) return;

        try {
            connectionManager().runInTransaction(connection -> {
                try {
                    CanonicalMediaService media = new CanonicalMediaService();
                    CanonicalizationResult<Long> result = media.ensureCanonicalAlbum(
                            connection,
                            ExternalMediaId.deezer(albumId),
                            new CanonicalMediaService.AlbumMetadata(
                                    Objects.requireNonNullElse(albumName, "Unknown release"),
                                    "Unknown", "album", releaseDate, Math.max(0, numberOfTracks), List.of()
                            )
                    );
                    if (!result.isSuccess()) {
                        throw new SQLException("Remote release identity conflict: " + result.conflict().reason());
                    }
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE Album SET ReleaseDate = COALESCE(NULLIF(ReleaseDate, ''), ?) WHERE AlbumID = ?")) {
                        update.setString(1, releaseDate);
                        update.setLong(2, result.value());
                        update.executeUpdate();
                    }
                    return null;
                } catch (SQLException error) {
                    throw new RuntimeException(error);
                }
            });
        } catch (Exception ignored) {
            // The remote response is already available to the user.
        }
    }

    private static CanonicalMediaService.AlbumMetadata toAlbumMetadata(Album album) {
        if (album == null || album.getName() == null || album.getName().isBlank()) return null;
        Genre genre = album.getGenre();
        return new CanonicalMediaService.AlbumMetadata(
                album.getName(),
                genre == null ? "Unknown" : Objects.requireNonNullElse(genre.getName(), "Unknown"),
                Objects.requireNonNullElse(album.getRecordType(), "album"),
                album.getReleaseDate(),
                Math.max(0, album.getNumberOfTracks()),
                artistNames(album.getArtist())
        );
    }

    private static List<String> artistNames(Collection<Artist> artists) {
        if (artists == null || artists.isEmpty()) return List.of();
        return artists.stream().filter(Objects::nonNull).map(Artist::getName)
                .filter(name -> name != null && !name.isBlank()).map(String::trim).distinct().toList();
    }
}
