package io.github.guillermodubon.musicplayer.services.playback.managers;

import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class PlaybackMediaResolver {

    public Optional<String> resolvePlayablePath(Song song) {
        if (song == null || !song.isLocal()) return Optional.empty();

        String fp = song.getFilePath();
        if (fp != null && !fp.isBlank()) {
            return isReadableMediaFile(fp) ? Optional.of(fp) : Optional.empty();
        }

        try {
            StartUpService svc = StartUpService.getInstance();
            if (svc != null) {
                Optional<String> candidate = svc.resolvePathForSong(song);
                if (candidate.isPresent() && isReadableMediaFile(candidate.get())) {
                    String path = candidate.get();
                    song.setFilePath(path);
                    return Optional.of(path);
                }
            }
        } catch (Exception ignored) {
        }

        return Optional.empty();
    }

    private boolean isReadableMediaFile(String candidate) {
        if (candidate == null || candidate.isBlank()) return false;

        try {
            Path path = Path.of(candidate);
            return Files.isRegularFile(path) && Files.isReadable(path) && Files.size(path) > 0L;
        } catch (Exception ignored) {
            return false;
        }
    }

    public void markSongUnavailable(Song song) {
        if (song == null) return;

        try {
            StartUpService service = StartUpService.getInstance();
            if (service != null) {
                service.markSongAsUnavailable(song);
                return;
            }
        } catch (Exception ignored) {
        }

        song.setLocal(false);
        song.setFilePath(null);
    }
}
