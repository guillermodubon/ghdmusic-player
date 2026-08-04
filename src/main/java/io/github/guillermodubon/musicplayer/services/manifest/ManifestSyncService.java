package io.github.guillermodubon.musicplayer.services.manifest;

import io.github.guillermodubon.musicplayer.models.ManifestEntry;

import io.github.guillermodubon.musicplayer.models.DeezerApiMetaData;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.manifest.ManifestService;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ManifestSyncService {

    private final ManifestService manifestService;
    private final ExecutorService MANIFEST_EXECUTOR;

    public ManifestSyncService() {
        this.manifestService = new ManifestService();
        this.MANIFEST_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "manifest-sync");
            t.setDaemon(true);
            return t;
        });
    }

    public Map<String, ManifestEntry> load() {
        return manifestService.load();
    }

    public void save(Map<String, ManifestEntry> manifest) {
        manifestService.save(manifest);
    }

    public boolean equals(Map<String, ManifestEntry> a, Map<String, ManifestEntry> b) {
        return manifestService.equals(a, b);
    }

    public String buildManifestKey(DeezerApiMetaData meta, File file) {
        if (file != null) {
            return cleanManifestFileName(file.getName());
        }

        // Prefer metadata if available (include artist first), fallback to file path/name
        if (meta != null) {

            String artist = null;
            try {
                if (meta.getAlbumArtistNames() != null && !meta.getAlbumArtistNames().isEmpty()) {
                    artist = meta.getAlbumArtistNames().get(0);
                } else if (meta.getSongContributorNames() != null && !meta.getSongContributorNames().isEmpty()) {
                    artist = meta.getSongContributorNames().get(0);
                }
            } catch (Throwable ignore) {}
            if (artist == null || artist.isBlank()) artist = "Unknown";
            String title = meta.getSongName() != null ? meta.getSongName().trim() : (meta.getSongFileName() != null ? meta.getSongFileName().trim() : "Unknown");
            if (title.isBlank()) title = "Unknown";

            // Consistent format: "Artist - Title" (no ID, since the ID goes in the value)
            return String.format("%s - %s", artist.replaceAll(":", "").trim(), title.replaceAll(":", "").trim());

        } else if (file != null) {
            String fname = file.getName();
            fname = fname.replaceAll("[\\\\/:*?\"<>|]", "").replaceAll("\\s+", " ").trim();
            if (fname.length() > 200) fname = fname.substring(0, 200).trim();
            return String.format("%s:path:%s", fname, file.getAbsolutePath());
        } else {
            return "unknown:" + System.nanoTime();
        }
    }

    public CompletableFuture<Void> updateManifestEntryAsync(DeezerApiMetaData meta, File file, Long timestamp) {
        CompletableFuture<Void> completion = new CompletableFuture<>();

        // serialized single-threaded updates to avoid races on manifest.json
        MANIFEST_EXECUTOR.execute(() -> {
            try {
                Map<String, ManifestEntry> manifest = manifestService.load();
                if (manifest == null) manifest = new HashMap<>();
                String key = buildManifestKey(meta, file);
                long tid = (meta != null) ? (meta.getTrackId() > 0 ? meta.getTrackId() : 0L) : 0L;
                long ts = (timestamp != null) ? timestamp : System.currentTimeMillis();
                ManifestEntry entry = new ManifestEntry(tid, ts);
                String normalizedKey = normalizeManifestKey(key);
                manifest.entrySet().removeIf(existing -> {
                    if (existing == null || existing.getKey() == null || existing.getKey().equals(key)) return false;
                    ManifestEntry existingEntry = existing.getValue();
                    if (tid > 0 && existingEntry != null && existingEntry.getDeezerId() == tid) return true;
                    return normalizeManifestKey(existing.getKey()).equals(normalizedKey);
                });
                manifest.put(key, entry);
                manifestService.save(manifest);
                completion.complete(null);
            } catch (Throwable t) {
                System.err.println("updateManifestEntryAsync failed: " + Optional.ofNullable(t.getMessage()).orElse("null"));
                t.printStackTrace();
                completion.completeExceptionally(t);
            }
        });

        return completion;
    }

    /** Removes stale local-media evidence after a file is removed externally. */
    public void removeManifestEntryAsync(Song song, String missingPath) {
        if (song == null) return;

        MANIFEST_EXECUTOR.submit(() -> {
            try {
                Map<String, ManifestEntry> manifest = manifestService.load();
                if (manifest == null || manifest.isEmpty()) return;

                long songId = song.getSongID();
                String normalizedTitle = normalizeManifestKey(song.getTitle());
                String normalizedFileName = "";
                if (missingPath != null && !missingPath.isBlank()) {
                    normalizedFileName = normalizeManifestKey(new File(missingPath).getName());
                }

                final String fileName = normalizedFileName;
                boolean changed = manifest.entrySet().removeIf(entry -> {
                    if (entry == null || entry.getKey() == null) return false;

                    ManifestEntry value = entry.getValue();
                    if (songId > 0 && value != null && value.getDeezerId() == songId) return true;

                    String key = normalizeManifestKey(entry.getKey());
                    return (!fileName.isBlank() && key.equals(fileName))
                            || (!normalizedTitle.isBlank() && key.equals(normalizedTitle));
                });

                if (changed) manifestService.save(manifest);
            } catch (Throwable error) {
                System.err.println("removeManifestEntryAsync failed: "
                        + Optional.ofNullable(error.getMessage()).orElse("null"));
            }
        });
    }

    private static String cleanManifestFileName(String name) {
        if (name == null || name.isBlank()) return "unknown";
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        base = base.replaceAll("[\\\\/:*?\"<>|]", "").replaceAll("\\s+", " ").trim();
        if (base.length() > 200) base = base.substring(0, 200).trim();
        return base.isBlank() ? "unknown" : base;
    }

    private static String normalizeManifestKey(String key) {
        if (key == null) return "";
        int sep = key.lastIndexOf(" | id:");
        String left = sep > 0 ? key.substring(0, sep) : key;
        int pathSep = left.indexOf(":path:");
        if (pathSep > 0) left = left.substring(0, pathSep);
        return cleanManifestFileName(left)
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(java.util.Locale.ROOT);
    }
}


