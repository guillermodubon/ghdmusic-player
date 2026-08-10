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
        if (uniqueSongs == null) return;
        for (ScannedAudioFile file : scanDirectory(directory)) {
            String songName = songDataUtils.removeFileExtension(file.fileName());
            uniqueSongs.putIfAbsent(songName, file.path().toString());
        }
    }

    private List<ScannedAudioFile> scanDirectory(Path directory) {
        List<ScannedAudioFile> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(directory)) {
            // Files.walk is disk-bound. Parallelizing it inside another executor
            // oversubscribes disk I/O and is slower for large music folders.
            paths
                    .filter(path -> Files.isRegularFile(path) && songDataUtils.isAudioFile(path))
                    .forEach(path -> {
                        try {
                            Path absolute = path.toAbsolutePath().normalize();
                            files.add(new ScannedAudioFile(
                                    absolute,
                                    absolute.getFileName().toString(),
                                    Files.getLastModifiedTime(absolute).toMillis(),
                                    Files.size(absolute)
                            ));
                        } catch (IOException ignored) {
                            // The file moved during the scan. A later scan can recover it.
                        }
                    });
        } catch (IOException e) {
            System.err.println("Failed to access: " + directory + " - " + e.getMessage());
        }
        return files;
    }

    /**
     * Scans predefined directories and returns a map of unique songs:
     * key = song name (without extension), value = absolute file path.
     */
    public Map<String, String> getAllSongsMapFromLocalDevice() {
        return toUniqueSongMap(scanLocalAudioFiles());
    }

    /**
     * Scans every configured music directory while retaining duplicate display
     * titles. Path recovery uses this rich result to avoid title-only matches.
     */
    public List<ScannedAudioFile> scanLocalAudioFiles() {
        ConcurrentLinkedQueue<ScannedAudioFile> discovered = new ConcurrentLinkedQueue<>();
        List<Path> directoriesToScan = resolveWindowsAudioDirectories();

        int directoryWorkers = Math.max(1, Math.min(2, directoriesToScan.size()));
        ExecutorService exec = Executors.newFixedThreadPool(directoryWorkers, runnable -> {
            Thread thread = new Thread(runnable, "local-song-scanner");
            thread.setDaemon(true);
            return thread;
        });

        List<? extends Future<?>> futures = directoriesToScan.stream()
                .map(dir -> exec.submit(() -> discovered.addAll(scanDirectory(dir))))
                .toList();

        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException e) {
                System.err.println("Error scanning directory: " + e.getMessage());
            }
        }
        exec.shutdown();

        List<ScannedAudioFile> files = new ArrayList<>(discovered);
        files.sort(Comparator.comparing(file -> file.path().toString(), String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(files);
    }

    public Map<String, String> toUniqueSongMap(Collection<ScannedAudioFile> files) {
        ConcurrentMap<String, String> uniqueSongs = new ConcurrentHashMap<>();
        if (files == null) return uniqueSongs;
        for (ScannedAudioFile file : files) {
            if (file == null || file.path() == null || file.fileName() == null) continue;
            String songName = songDataUtils.removeFileExtension(file.fileName());
            uniqueSongs.putIfAbsent(songName, file.path().toString());
        }

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

        Set<Path> resolvedDirectories = new LinkedHashSet<>();
        for (Path directory : directories) {
            try {
                if (!Files.isDirectory(directory) || !Files.isReadable(directory)) continue;
                // Windows can expose the same known folder through USERPROFILE
                // and OneDrive. Resolve its real location once so a redirected
                // Downloads/Desktop/Music folder is never walked twice.
                resolvedDirectories.add(directory.toRealPath());
            } catch (IOException ignored) {
                // A folder may disappear while the application is starting.
            }
        }
        return List.copyOf(resolvedDirectories);
    }

    private void addAudioDirectories(Set<Path> directories, Path root) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        for (String folder : WINDOWS_AUDIO_FOLDERS) {
            directories.add(normalizedRoot.resolve(folder).normalize());
        }
    }
}
