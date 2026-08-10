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
            if (isReadableMediaFile(fp)) {
                return Optional.of(fp);
            }
            // The file can have been moved while the app is open. Continue to
            // the strict recovery path instead of treating the old location as
            // a definitive deletion.
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

                Optional<String> recovered = svc.recoverMovedAudioPath(song);
                if (recovered.isPresent() && isReadableMediaFile(recovered.get())) {
                    String path = recovered.get();
                    song.setLocal(true);
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
