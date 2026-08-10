
package io.github.guillermodubon.musicplayer.services.startup.locality;

import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.utils.SongAudioIdentity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class SongPathResolver {

    public Optional<String> resolvePathForSong(Song s, Map<String, String> audioIdentityToPath) {
        if (s == null) return Optional.empty();

        String path = s.getFilePath();
        try {
            if (path != null && !path.isBlank() && Files.exists(Path.of(path))) {
                return Optional.of(path);
            }
        } catch (Exception ignored) {
        }

        if (audioIdentityToPath == null || audioIdentityToPath.isEmpty()) {
            return Optional.empty();
        }

        Optional<String> identityKey = SongAudioIdentity.keyFor(s);
        if (identityKey.isEmpty()) {
            return Optional.empty();
        }

        String candidatePath = audioIdentityToPath.get(identityKey.get());
        if (candidatePath == null || candidatePath.isBlank()) {
            return Optional.empty();
        }
        try {
            if (Files.exists(Path.of(candidatePath))) {
                s.setFilePath(candidatePath);
                return Optional.of(candidatePath);
            }
        } catch (Exception ignored) {
        }

        return Optional.empty();
    }
}
