package io.github.guillermodubon.musicplayer.services.startup.library;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Performs the referential cleanup that follows removal of local media files.
 *
 * <p>All statements operate on the caller's transaction. Keeping the cleanup
 * set-based avoids one query/transaction round trip per album or artist while
 * preserving the existing rule: an album is removed only when it no longer
 * contains any local song.</p>
 */
final class LibraryDeletionCleanupService {

    private static final String ALBUMS_WITHOUT_LOCAL_SONGS = """
            SELECT album.AlbumID
              FROM Album album
             WHERE NOT EXISTS (
                 SELECT 1
                   FROM Song song
                  WHERE song.Album = album.AlbumID
                    AND song.IsLocal = 1
             )
            """;

    private static final String SONGS_IN_EMPTY_LOCAL_ALBUMS = """
            SELECT song.SongID
              FROM Song song
             WHERE song.Album IN (
            """ + ALBUMS_WITHOUT_LOCAL_SONGS + ")";

    private static final String DELETE_PLAYLIST_SONGS = """
            DELETE FROM SongsPlaylists
             WHERE SongID IN (
            """ + SONGS_IN_EMPTY_LOCAL_ALBUMS + ")";

    private static final String DELETE_SONG_ARTISTS_FOR_REMOVED_ALBUMS = """
            DELETE FROM SongArtist
             WHERE SongID IN (
            """ + SONGS_IN_EMPTY_LOCAL_ALBUMS + ")";

    private static final String DELETE_SONGS_FOR_REMOVED_ALBUMS = """
            DELETE FROM Song
             WHERE Album IN (
            """ + ALBUMS_WITHOUT_LOCAL_SONGS + ")";

    private static final String DELETE_ALBUM_IMAGES = """
            DELETE FROM AlbumImage
             WHERE AlbumID IN (
            """ + ALBUMS_WITHOUT_LOCAL_SONGS + ")";

    private static final String DELETE_ALBUM_ARTISTS = """
            DELETE FROM AlbumArtist
             WHERE AlbumID IN (
            """ + ALBUMS_WITHOUT_LOCAL_SONGS + ")";

    private static final String DELETE_ALBUMS = """
            DELETE FROM Album
             WHERE AlbumID IN (
            """ + ALBUMS_WITHOUT_LOCAL_SONGS + ")";

    private static final String DELETE_UNREFERENCED_REMOTE_SONG_ARTISTS = """
            DELETE FROM SongArtist AS relation
             WHERE EXISTS (
                 SELECT 1
                   FROM Song song
                  WHERE song.SongID = relation.SongID
                    AND song.IsLocal = 0
             )
               AND NOT EXISTS (
                 SELECT 1
                   FROM AlbumArtist albumArtist
                  WHERE albumArtist.ArtistID = relation.ArtistID
             )
               AND NOT EXISTS (
                 SELECT 1
                   FROM SongArtist localRelation
                   JOIN Song localSong ON localSong.SongID = localRelation.SongID
                  WHERE localRelation.ArtistID = relation.ArtistID
                    AND localSong.IsLocal = 1
             )
            """;

    private static final String DELETE_UNREFERENCED_ARTIST_IMAGES = """
            DELETE FROM ArtistImage
             WHERE NOT EXISTS (
                 SELECT 1
                   FROM AlbumArtist albumArtist
                  WHERE albumArtist.ArtistID = ArtistImage.ArtistID
             )
               AND NOT EXISTS (
                 SELECT 1
                   FROM SongArtist songArtist
                  WHERE songArtist.ArtistID = ArtistImage.ArtistID
             )
            """;

    private static final String DELETE_UNREFERENCED_ARTISTS = """
            DELETE FROM Artist
             WHERE NOT EXISTS (
                 SELECT 1
                   FROM AlbumArtist albumArtist
                  WHERE albumArtist.ArtistID = Artist.ArtistID
             )
               AND NOT EXISTS (
                 SELECT 1
                   FROM SongArtist songArtist
                  WHERE songArtist.ArtistID = Artist.ArtistID
             )
            """;

    private static final String DELETE_GENRES_WITHOUT_SONGS = """
            DELETE FROM Genre
             WHERE NOT EXISTS (
                 SELECT 1
                   FROM Album album
                   JOIN Song song ON song.Album = album.AlbumID
                  WHERE album.GenreID = Genre.GenreID
             )
            """;

    CleanupSummary cleanup(Connection connection) throws SQLException {
        if (connection == null) {
            return CleanupSummary.empty();
        }

        execute(connection, DELETE_PLAYLIST_SONGS);
        execute(connection, DELETE_SONG_ARTISTS_FOR_REMOVED_ALBUMS);
        int removedSongs = execute(connection, DELETE_SONGS_FOR_REMOVED_ALBUMS);
        execute(connection, DELETE_ALBUM_IMAGES);
        execute(connection, DELETE_ALBUM_ARTISTS);
        int removedAlbums = execute(connection, DELETE_ALBUMS);

        execute(connection, DELETE_UNREFERENCED_REMOTE_SONG_ARTISTS);
        execute(connection, DELETE_UNREFERENCED_ARTIST_IMAGES);
        int removedArtists = execute(connection, DELETE_UNREFERENCED_ARTISTS);
        int removedGenres = execute(connection, DELETE_GENRES_WITHOUT_SONGS);

        return new CleanupSummary(removedSongs, removedAlbums, removedArtists, removedGenres);
    }

    private int execute(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            return statement.executeUpdate();
        }
    }

    record CleanupSummary(int removedSongs,
                          int removedAlbums,
                          int removedArtists,
                          int removedGenres) {
        static CleanupSummary empty() {
            return new CleanupSummary(0, 0, 0, 0);
        }
    }
}
