package io.github.guillermodubon.musicplayer.models;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Verifies locality from file evidence, never from a provider ID or title match. */
public final class LocalManifestLookup {

    private final Map<String, ManifestEntry> manifest;

    private LocalManifestLookup(Map<String, ManifestEntry> manifest) {
        if (manifest == null || manifest.isEmpty()) {
            this.manifest = Collections.emptyMap();
            return;
        }
        Map<String, ManifestEntry> snapshot = new HashMap<>();
        manifest.forEach((key, value) -> {
            if (key != null && value != null) snapshot.put(key, value);
        });
        this.manifest = Map.copyOf(snapshot);
    }

    public static LocalManifestLookup of(Map<String, ManifestEntry> manifest) {
        return new LocalManifestLookup(manifest);
    }

    public boolean matches(Song song) {
        return song != null && song.isLocal() && matches(song, song.getFilePath());
    }

    public boolean matches(Song song, String candidatePath) {
        if (song == null || !song.isLocal() || manifest.isEmpty()
                || candidatePath == null || candidatePath.isBlank()) return false;
        try {
            Path path = Path.of(candidatePath);
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) return false;
            String currentName = nameKey(path.getFileName() == null ? "" : path.getFileName().toString());
            long currentSize = Files.size(path);
            long currentModified = Files.getLastModifiedTime(path).toMillis();
            for (Map.Entry<String, ManifestEntry> entry : manifest.entrySet()) {
                if (entry == null || entry.getValue() == null) continue;
                ManifestEntry value = entry.getValue();
                String expectedName = nameKey(value.getFileName());
                if (expectedName.isBlank()) expectedName = nameKey(displayKey(entry.getKey()));
                if (expectedName.isBlank() || !expectedName.equals(currentName)) continue;
                if (value.getFileSize() > 0 && value.getFileSize() != currentSize) continue;
                if (value.getLastModified() > 0 && value.getLastModified() != currentModified) continue;
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static String displayKey(String value) {
        if (value == null) return "";
        int marker = value.indexOf(" | id:");
        String name = marker >= 0 ? value.substring(0, marker) : value;
        int pathMarker = name.indexOf(":path:");
        if (pathMarker >= 0) name = name.substring(0, pathMarker);
        try {
            Path path = Path.of(name);
            if (path.getFileName() != null) return path.getFileName().toString();
        } catch (Exception ignored) {
        }
        return name;
    }

    private static String nameKey(String value) {
        if (value == null || value.isBlank()) return "";
        String name = value;
        try {
            Path path = Path.of(value);
            if (path.getFileName() != null) name = path.getFileName().toString();
        } catch (Exception ignored) {
        }
        int extension = name.lastIndexOf('.');
        if (extension > 0) {
            String suffix = name.substring(extension + 1).toLowerCase(Locale.ROOT);
            if (SetOfAudioExtensions.contains(suffix)) name = name.substring(0, extension);
        }
        return Normalizer.normalize(name.trim(), Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private static final class SetOfAudioExtensions {
        private static boolean contains(String extension) {
            return switch (extension) {
                case "mp3", "m4a", "wav", "flac", "aac", "opus", "ogg", "wma" -> true;
                default -> false;
            };
        }
    }
}
