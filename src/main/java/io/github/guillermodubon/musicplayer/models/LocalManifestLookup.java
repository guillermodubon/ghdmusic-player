package io.github.guillermodubon.musicplayer.models;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Indexed manifest lookup used to verify local songs efficiently. */
public final class LocalManifestLookup {
    private final Map<String, ManifestEntry> manifest;
    private final Set<Long> deezerIds;
    private final Set<String> normalizedKeys;

    private LocalManifestLookup(
            Map<String, ManifestEntry> manifest,
            Set<Long> deezerIds,
            Set<String> normalizedKeys
    ) {
        this.manifest = manifest == null ? Collections.emptyMap() : manifest;
        this.deezerIds = deezerIds;
        this.normalizedKeys = normalizedKeys;
    }

    public static LocalManifestLookup of(Map<String, ManifestEntry> manifest) {
        if (manifest == null || manifest.isEmpty()) {
            return new LocalManifestLookup(Collections.emptyMap(), Set.of(), Set.of());
        }

        Set<Long> ids = new LinkedHashSet<>();
        Set<String> keys = new LinkedHashSet<>();
        for (Map.Entry<String, ManifestEntry> entry : manifest.entrySet()) {
            if (entry == null || entry.getValue() == null) continue;
            long id = entry.getValue().getDeezerId();
            if (id > 0) ids.add(id);

            String key = normalizeKey(entry.getKey());
            if (!key.isBlank()) keys.add(key);
        }
        return new LocalManifestLookup(
                manifest,
                Collections.unmodifiableSet(ids),
                Collections.unmodifiableSet(keys)
        );
    }

    public boolean matches(Song song) {
        if (song == null || !song.isLocal() || manifest.isEmpty()) return false;
        if (song.getSongID() > 0 && deezerIds.contains(song.getSongID())) return true;

        Set<String> candidates = buildManifestCandidates(song);
        for (String candidate : candidates) {
            if (normalizedKeys.contains(normalizeKey(candidate))) return true;
        }

        // Keep the tolerant legacy comparison for descriptive manifest keys.
        for (Map.Entry<String, ManifestEntry> entry : manifest.entrySet()) {
            if (entry == null || entry.getValue() == null) continue;
            if (song.getSongID() > 0 && entry.getValue().getDeezerId() == song.getSongID()) {
                return true;
            }

            String manifestKey = normalizeKey(entry.getKey());
            if (manifestKey.isBlank()) continue;
            for (String candidate : candidates) {
                String normalizedCandidate = normalizeKey(candidate);
                if (normalizedCandidate.isBlank()) continue;
                if (manifestKey.equals(normalizedCandidate)
                        || manifestKey.contains(normalizedCandidate)
                        || normalizedCandidate.contains(manifestKey)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<String> buildManifestCandidates(Song song) {
        Set<String> candidates = new LinkedHashSet<>();
        if (song == null) return candidates;

        addCandidate(candidates, song.getTitle());

        List<String> artistNames = artistNames(song);
        String title = trim(song.getTitle());
        if (!title.isBlank()) {
            for (String artist : artistNames) {
                String name = trim(artist);
                if (name.isBlank()) continue;
                addCandidate(candidates, name + " " + title);
                addCandidate(candidates, name + " - " + title);
            }
        }

        String path = song.getFilePath();
        if (path != null && !path.isBlank()) {
            try {
                File file = new File(path);
                addCandidate(candidates, file.getAbsolutePath());
                addCandidate(candidates, file.getName());
                addCandidate(candidates, stripExtension(file.getName()));
            } catch (Exception ignored) {
            }
        }
        return candidates;
    }

    private static List<String> artistNames(Song song) {
        List<String> names = new ArrayList<>();
        if (song == null) return names;

        if (song.getArtist() != null) {
            for (Artist artist : song.getArtist()) {
                if (artist == null) continue;
                String name = trim(artist.getName());
                if (!name.isBlank()) names.add(name);
            }
        }

        if (names.isEmpty() && song.getAlbum() != null && song.getAlbum().getArtist() != null) {
            for (Artist artist : song.getAlbum().getArtist()) {
                if (artist == null) continue;
                String name = trim(artist.getName());
                if (!name.isBlank()) names.add(name);
            }
        }
        return names;
    }

    private static void addCandidate(Set<String> output, String value) {
        String normalized = trim(value);
        if (!normalized.isBlank()) output.add(normalized);
    }

    private static String normalizeKey(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        normalized = stripExtension(normalized);
        normalized = normalized.replace('\\', '/');
        normalized = normalized.replaceAll("[_\\-]+", " ");
        normalized = normalized.replaceAll("\\s+", " ");
        return normalized.trim();
    }

    private static String stripExtension(String value) {
        if (value == null) return "";
        int dot = value.lastIndexOf('.');
        if (dot <= 0) return value;
        String extension = value.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (Objects.equals(extension, "mp3")
                || Objects.equals(extension, "m4a")
                || Objects.equals(extension, "wav")
                || Objects.equals(extension, "flac")
                || Objects.equals(extension, "aac")
                || Objects.equals(extension, "opus")) {
            return value.substring(0, dot);
        }
        return value;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
