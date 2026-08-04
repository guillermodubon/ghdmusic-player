package io.github.guillermodubon.musicplayer.services.scanning;

import io.github.guillermodubon.musicplayer.utils.SongDataHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

public class SongScannerService {

    private static final List<String> WINDOWS_AUDIO_FOLDERS = List.of("Desktop", "Downloads", "Music");
    private static final List<String> ONEDRIVE_ENVIRONMENT_VARIABLES = List.of(
            "OneDrive",
            "OneDriveConsumer",
            "OneDriveCommercial"
    );

    private final SongDataHelper songDataUtils = new SongDataHelper();

    /**
     * Scans a single directory for audio files,
     * adding unique entries to the provided map: songName -> filePath.
     */
    public void scanDirectoryParallel(Path directory, Map<String, String> uniqueSongs) {
        try (Stream<Path> paths = Files.walk(directory)) {
            // Files.walk is disk-bound. Parallelizing it inside another executor
            // oversubscribes disk I/O and is slower for large music folders.
            paths
                    .filter(path -> Files.isRegularFile(path) && songDataUtils.isAudioFile(path))
                    .forEach(path -> {
                        String songName = songDataUtils.removeFileExtension(path.getFileName().toString());
                        String filePath = path.toAbsolutePath().toString();
                        uniqueSongs.putIfAbsent(songName, filePath);
                    });
        } catch (IOException e) {
            System.err.println("Failed to access: " + directory + " - " + e.getMessage());
        }
    }

    /**
     * Scans predefined directories and returns a map of unique songs:
     * key = song name (without extension), value = absolute file path.
     */
    public Map<String, String> getAllSongsMapFromLocalDevice() {
        ConcurrentMap<String, String> uniqueSongs = new ConcurrentHashMap<>();

        List<Path> directoriesToScan = resolveWindowsAudioDirectories();

        int directoryWorkers = Math.max(1, Math.min(2, directoriesToScan.size()));
        ExecutorService exec = Executors.newFixedThreadPool(directoryWorkers, runnable -> {
            Thread thread = new Thread(runnable, "local-song-scanner");
            thread.setDaemon(true);
            return thread;
        });

        // Lanzamos una tarea por cada directorio
        List<? extends Future<?>> futures = directoriesToScan.stream()
                .map(dir -> exec.submit(() -> scanDirectoryParallel(dir, uniqueSongs)))
                .toList();

        // Esperamos a que todas terminen
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (InterruptedException | ExecutionException e) {
                System.err.println("Error scanning directory: " + e.getMessage());
            }
        }

        exec.shutdown();

        return uniqueSongs;
    }

    /**
     * Resolves Desktop, Downloads and Music for the current Windows profile.
     * OneDrive candidates cover Windows systems that redirect known folders there.
     */
    private List<Path> resolveWindowsAudioDirectories() {
        Set<Path> directories = new LinkedHashSet<>();
        addAudioDirectories(directories, Paths.get(System.getProperty("user.home")));

        String userProfile = System.getenv("USERPROFILE");
        if (userProfile != null && !userProfile.isBlank()) {
            addAudioDirectories(directories, Paths.get(userProfile));
        }

        for (String environmentVariable : ONEDRIVE_ENVIRONMENT_VARIABLES) {
            String oneDrivePath = System.getenv(environmentVariable);
            if (oneDrivePath != null && !oneDrivePath.isBlank()) {
                addAudioDirectories(directories, Paths.get(oneDrivePath));
            }
        }

        return directories.stream()
                .filter(Files::isDirectory)
                .filter(Files::isReadable)
                .toList();
    }

    private void addAudioDirectories(Set<Path> directories, Path root) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        for (String folder : WINDOWS_AUDIO_FOLDERS) {
            directories.add(normalizedRoot.resolve(folder).normalize());
        }
    }
}
