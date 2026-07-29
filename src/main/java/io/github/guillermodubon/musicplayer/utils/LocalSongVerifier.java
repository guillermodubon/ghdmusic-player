package io.github.guillermodubon.musicplayer.utils;

import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.LocalManifestLookup;
import io.github.guillermodubon.musicplayer.models.ManifestEntry;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.manifest.ManifestService;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class LocalSongVerifier {

    private LocalSongVerifier() {
    }

    public static Map<String, ManifestEntry> loadManifest(StartUpService service) {
        if (service == null || service.getManifestService() == null) return Collections.emptyMap();
        try {
            Map<String, ManifestEntry> manifest = service.getManifestService().load();
            return manifest == null ? Collections.emptyMap() : manifest;
        } catch (Exception ignored) {
            return Collections.emptyMap();
        }
    }

    public static List<Song> verifiedLocalSongs(
            Collection<Song> songs,
            Map<String, ManifestEntry> manifest
    ) {
        if (songs == null || songs.isEmpty()) return List.of();
        LocalManifestLookup lookup = LocalManifestLookup.of(manifest);
        return LibraryModelDeduplicator.songs(songs)
                .stream()
                .filter(lookup::matches)
                .collect(Collectors.toList());
    }

    public static boolean isVerifiedLocalSong(
            Song song,
            Map<String, ManifestEntry> manifest
    ) {
        return LocalManifestLookup.of(manifest).matches(song);
    }

    public static List<Song> verifiedPlayableLocalSongs(
            Collection<Song> songs,
            StartUpService service,
            Map<String, ManifestEntry> manifest
    ) {
        if (songs == null || songs.isEmpty() || service == null || manifest == null || manifest.isEmpty()) {
            return List.of();
        }

        LocalManifestLookup lookup = LocalManifestLookup.of(manifest);
        return LibraryModelDeduplicator.songs(songs)
                .stream()
                .filter(lookup::matches)
                .filter(song -> {
                    try {
                        return service.resolvePathForSong(song)
                                .map(LocalSongVerifier::isReadableAudioFile)
                                .orElse(false);
                    } catch (Exception ignored) {
                        return false;
                    }
                })
                .collect(Collectors.toList());
    }

    private static boolean isReadableAudioFile(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            Path path = Path.of(value);
            return Files.isRegularFile(path)
                    && Files.isReadable(path)
                    && Files.size(path) > 0L;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean albumHasVerifiedLocalSong(
            Album album,
            Collection<Song> librarySongs,
            Map<String, ManifestEntry> manifest
    ) {
        if (album == null) return false;

        List<Song> albumSongs = album.getSongList();
        if (albumSongs != null) {
            for (Song song : albumSongs) {
                if (isVerifiedLocalSong(song, manifest)) return true;
            }
        }

        long albumId = album.getAlbumID();
        if (albumId <= 0 || librarySongs == null) return false;

        for (Song song : librarySongs) {
            if (song == null || song.getAlbum() == null) continue;
            if (song.getAlbum().getAlbumID() != albumId) continue;
            if (isVerifiedLocalSong(song, manifest)) return true;
        }

        return false;
    }
}
