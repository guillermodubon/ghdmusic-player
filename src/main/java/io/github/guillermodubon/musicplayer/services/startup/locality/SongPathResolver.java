
package io.github.guillermodubon.musicplayer.services.startup.locality;

import io.github.guillermodubon.musicplayer.utils.SongDataHelper;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Song;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class SongPathResolver {

    public Optional<String> resolvePathForSong(Song s, Map<String, String> titleToPath) {
        if (s == null || titleToPath == null || titleToPath.isEmpty()) return Optional.empty();

        String path = s.getFilePath();
        try {
            if (path != null && !path.isBlank() && Files.exists(Path.of(path))) {
                return Optional.of(path);
            }
        } catch (Exception ignored) {
        }

        // A persisted path identifies the exact local file for this song. If
        // it no longer exists, do not replace it with a similarly named track.
        if (path != null && !path.isBlank()) {
            return Optional.empty();
        }

        String title = Optional.ofNullable(s.getTitle()).orElse("").trim();
        List<String> artistNames = s.getArtist() == null ? List.of() :
                s.getArtist().stream().map(Artist::getName).filter(Objects::nonNull).toList();

        List<String> candidates = new ArrayList<>();
        if (!title.isBlank()) {
            candidates.add(title);
            candidates.add(SongDataHelper.sanitizeForFileKey(title));
            candidates.add(SongDataHelper.fallbackKey(title));
            candidates.add(title.toLowerCase(Locale.ROOT));
        }
        for (String an : artistNames) {
            if (an == null || an.isBlank()) continue;
            String combo = an + " " + title;
            candidates.add(combo);
            candidates.add(SongDataHelper.sanitizeForFileKey(combo));
            candidates.add(combo.toLowerCase(Locale.ROOT));
        }

        for (String key : candidates) {
            if (key == null) continue;
            String p = titleToPath.get(key);
            if (p != null) {
                try {
                    if (Files.exists(Path.of(p))) return Optional.of(p);
                } catch (Exception ignored) {
                }
            }
        }

        return Optional.empty();
    }
}
