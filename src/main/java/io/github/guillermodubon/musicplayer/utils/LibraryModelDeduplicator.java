package io.github.guillermodubon.musicplayer.utils;

import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

public final class LibraryModelDeduplicator {

    private LibraryModelDeduplicator() {
    }

    public static List<Album> albums(Collection<Album> albums) {
        return distinct(albums, album -> album.getAlbumID() > 0
                ? "id:" + album.getAlbumID()
                : "name:" + normalize(album.getName()));
    }

    public static List<Song> songs(Collection<Song> songs) {
        return distinct(songs, song -> {
            if (song.getSongID() > 0) return "id:" + song.getSongID();
            if (song.getFilePath() != null && !song.getFilePath().isBlank()) {
                return "path:" + normalize(song.getFilePath());
            }
            long albumId = song.getAlbum() == null ? 0 : song.getAlbum().getAlbumID();
            return "title:" + albumId + ":" + normalize(song.getTitle());
        });
    }

    public static List<Playlist> playlists(Collection<Playlist> playlists) {
        return distinct(playlists, playlist -> playlist.getId() > 0
                ? "id:" + playlist.getId()
                : "name:" + normalize(playlist.getTitle()) + ":" + normalize(playlist.getAuthorName()));
    }

    public static List<Artist> artists(Collection<Artist> artists) {
        return distinct(artists, artist -> artist.getArtistID() > 0
                ? "id:" + artist.getArtistID()
                : "name:" + normalize(artist.getName()));
    }

    private static <T> List<T> distinct(Collection<T> values, Function<T, String> keyResolver) {
        if (values == null || values.isEmpty()) return List.of();

        LinkedHashMap<String, T> unique = new LinkedHashMap<>();
        for (T value : values) {
            if (value == null) continue;
            String key = keyResolver.apply(value);
            if (key == null || key.isBlank()) {
                key = "instance:" + System.identityHashCode(value);
            }
            unique.putIfAbsent(key, value);
        }
        return List.copyOf(unique.values());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
