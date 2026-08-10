package io.github.guillermodubon.musicplayer.services.scanning;

import java.nio.file.Files;
import java.nio.file.Path;

/** Immutable evidence collected during one local-library scan. */
public record ScannedAudioFile(Path path, String fileName, long lastModified, long fileSize) {

    public boolean isReadable() {
        try {
            return path != null
                    && Files.isRegularFile(path)
                    && Files.isReadable(path)
                    && Files.size(path) > 0L;
        } catch (Exception ignored) {
            return false;
        }
    }
}
