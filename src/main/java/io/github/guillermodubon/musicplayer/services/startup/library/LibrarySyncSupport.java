package io.github.guillermodubon.musicplayer.services.startup.library;

import io.github.guillermodubon.musicplayer.models.ManifestEntry;

import javafx.util.Pair;
import io.github.guillermodubon.musicplayer.models.DeezerApiMetaData;
import io.github.guillermodubon.musicplayer.services.manifest.ManifestService;
import io.github.guillermodubon.musicplayer.utils.SongDataHelper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Shared SQL and key-matching operations used by incremental library synchronization. */
final class LibrarySyncSupport {

    private LibrarySyncSupport() {
    }
static void markSongsRemote(Connection conn, Collection<Long> songIds) throws SQLException {
    if (songIds == null || songIds.isEmpty()) return;
    for (Long songId : songIds) {
        markSongRemote(conn, songId);
    }
}

static void markSongRemote(Connection conn, Long songId) throws SQLException {
    if (conn == null || songId == null || songId <= 0) return;
    try (PreparedStatement ps = conn.prepareStatement("UPDATE Song SET IsLocal = 0, FilePath = NULL WHERE SongID = ?")) {
        ps.setLong(1, songId);
        ps.executeUpdate();
    } catch (SQLException missingFilePath) {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE Song SET IsLocal = 0 WHERE SongID = ?")) {
            ps.setLong(1, songId);
            ps.executeUpdate();
        }
    }
}

static void markSongLocal(Connection conn, long songId, String path) throws SQLException {
    if (conn == null || songId <= 0) return;
    try (PreparedStatement ps = conn.prepareStatement("UPDATE Song SET IsLocal = 1, FilePath = ? WHERE SongID = ?")) {
        ps.setString(1, path);
        ps.setLong(2, songId);
        ps.executeUpdate();
    } catch (SQLException missingFilePath) {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE Song SET IsLocal = 1 WHERE SongID = ?")) {
            ps.setLong(1, songId);
            ps.executeUpdate();
        }
    }
}

static boolean matchesMeta(DeezerApiMetaData meta, String comparisonName, String originalName) {
    if (meta == null) return false;
    Set<String> metaKeys = new HashSet<>();
    metaKeys.add(comparisonKey(meta.getSongFileName()));
    metaKeys.add(comparisonKey(meta.getSongName()));
    String fileName = meta.getSongFileName();
    String songName = meta.getSongName();
    if (fileName != null && songName != null) {
        metaKeys.add(comparisonKey(fileName + " " + songName));
    }
    String target = comparisonName == null ? "" : comparisonName;
    String original = comparisonKey(originalName);
    return metaKeys.stream().anyMatch(c -> !c.isBlank() && (c.equals(target) || c.equals(original)));
}

static String resolveManifestKeyAgainstScan(Connection conn,
                                                    String manifestKey,
                                                    ManifestEntry entry,
                                                    Map<String, Long> scanTsMap) {
    if (manifestKey == null) manifestKey = "";
    Set<String> scanKeys = scanTsMap == null ? Set.of() : scanTsMap.keySet();
    if (scanKeys == null || scanKeys.isEmpty()) return manifestKey;
    if (scanKeys.contains(manifestKey)) return manifestKey;

    if (entry != null) {
        String timestampMatch = null;
        for (Map.Entry<String, Long> scanEntry : scanTsMap.entrySet()) {
            if (scanEntry == null || scanEntry.getKey() == null || scanEntry.getValue() == null) continue;
            if (scanEntry.getValue() != entry.getLastModified()) continue;
            if (timestampMatch != null && !timestampMatch.equals(scanEntry.getKey())) {
                timestampMatch = null;
                break;
            }
            timestampMatch = scanEntry.getKey();
        }
        if (timestampMatch != null && !timestampMatch.isBlank()) {
            return timestampMatch;
        }
    }

    Set<String> aliases = new LinkedHashSet<>();
    if (!manifestKey.isBlank()) aliases.add(manifestKey);

    long deezerId = entry == null ? 0L : entry.getDeezerId();
    if (conn != null && deezerId > 0) {
        String title = null;
        List<String> albumArtists = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT Title FROM Song WHERE SongID = ? LIMIT 1")) {
            ps.setLong(1, deezerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) title = rs.getString("Title");
            }
        } catch (SQLException ignored) {
        }

        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT ar.Name
                  FROM Song s
                  JOIN AlbumArtist aa ON aa.AlbumID = s.Album
                  JOIN Artist ar ON ar.ArtistID = aa.ArtistID
                 WHERE s.SongID = ?
                 ORDER BY ar.Name
                """)) {
            ps.setLong(1, deezerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    if (name != null && !name.isBlank()) albumArtists.add(name);
                }
            }
        } catch (SQLException ignored) {
        }

        if (title != null && !title.isBlank()) {
            aliases.add(comparisonKey(title));
            for (String artist : albumArtists) {
                aliases.add(comparisonKey(artist + " " + title));
            }
            if (!albumArtists.isEmpty()) {
                aliases.add(comparisonKey(String.join(" ", albumArtists) + " " + title));
            }
        }
    }

    for (String alias : aliases) {
        if (scanKeys.contains(alias)) return alias;
    }

    String matched = null;
    for (String scan : scanKeys) {
        for (String alias : aliases) {
            if (alias == null || alias.isBlank() || scan == null || scan.isBlank()) continue;
            boolean compatible = scan.equals(alias)
                    || scan.endsWith(" " + alias)
                    || alias.endsWith(" " + scan);
            if (!compatible) continue;
            if (matched != null && !matched.equals(scan)) return manifestKey;
            matched = scan;
        }
    }
    return matched == null ? manifestKey : matched;
}

static String resolvePathForMeta(DeezerApiMetaData meta,
                                         Collection<String> fileKeys,
                                         Map<String, String> cleanedToOriginalKey,
                                         Map<String, String> fileNameToPath) {
    if (meta == null || fileKeys == null || fileNameToPath == null) return null;
    for (String key : fileKeys) {
        String original = cleanedToOriginalKey == null ? key : cleanedToOriginalKey.getOrDefault(key, key);
        if (matchesMeta(meta, key, original)) {
            String path = fileNameToPath.get(key);
            if (path != null) return path;
        }
    }
    return null;
}

static String findPathForTitle(String title, Map<String, String> normalizedTitleToPath) {
    if (title == null || normalizedTitleToPath == null) return null;
    String path = normalizedTitleToPath.get(title.toLowerCase(Locale.ROOT));
    if (path != null) return path;
    path = normalizedTitleToPath.get(SongDataHelper.sanitizeForFileKey(title).toLowerCase(Locale.ROOT));
    return path != null
            ? path
            : normalizedTitleToPath.get(SongDataHelper.fallbackKey(title).toLowerCase(Locale.ROOT));
}

static String manifestDisplayName(String manifestKey) {
    if (manifestKey == null) return "";
    int sep = manifestKey.lastIndexOf(" | id:");
    String left = sep > 0 ? manifestKey.substring(0, sep) : manifestKey;
    int pathSep = left.indexOf(":path:");
    if (pathSep > 0) left = left.substring(0, pathSep);
    return left;
}

static String cleanBaseName(String raw) {
    if (raw == null) return "";
    String fileName = raw;
    try {
        fileName = Paths.get(raw).getFileName().toString();
    } catch (Exception ignored) {
    }
    String cleaned = SongDataHelper.removeFileExtension(fileName)
            .replaceAll("[\\\\/:*?\"<>|]", "")
            .replaceAll("\\s+", " ")
            .trim();
    if (cleaned.length() > 200) cleaned = cleaned.substring(0, 200).trim();
    return cleaned;
}

static String comparisonKey(String raw) {
    return cleanBaseName(raw)
            .replace('_', ' ')
            .replace('-', ' ')
            .replaceAll("\\s+", " ")
            .trim()
            .toLowerCase(Locale.ROOT);
}

static String manifestFileKey(String pathOrName) {
    return cleanBaseName(pathOrName);
}

static void removeDuplicateManifestEntries(Map<String, ManifestEntry> manifest,
                                                   String manifestKey,
                                                   long deezerId) {
    if (manifest == null || manifestKey == null) return;
    String normalized = comparisonKey(manifestKey);
    manifest.entrySet().removeIf(entry -> {
        if (entry == null || entry.getKey() == null || entry.getKey().equals(manifestKey)) return false;
        ManifestEntry value = entry.getValue();
        if (deezerId > 0 && value != null && value.getDeezerId() == deezerId) return true;
        return comparisonKey(manifestDisplayName(entry.getKey())).equals(normalized);
    });
}


}


