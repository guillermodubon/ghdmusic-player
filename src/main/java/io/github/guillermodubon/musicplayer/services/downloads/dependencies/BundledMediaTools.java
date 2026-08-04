package io.github.guillermodubon.musicplayer.services.downloads.dependencies;

import io.github.guillermodubon.musicplayer.repository.userData.UserDataPaths;
import io.github.guillermodubon.musicplayer.services.downloads.logging.DownloadLog;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Resolves the media tools bundled with the application without relying on PATH.
 * Resources are used directly in development and extracted to a private runtime
 * directory when the application is packaged inside a JAR/runtime image.
 */
public final class BundledMediaTools {

    private static final String RESOURCE_ROOT = "/io/github/guillermodubon/musicplayer/dependencies/";
    private static final String YT_DLP_RESOURCE = RESOURCE_ROOT + "yt-dlp.exe";
    private static final String FFMPEG_BIN_RESOURCE = RESOURCE_ROOT
            + "ffmpeg-2026-04-22-git-162ad61486-essentials_build/bin/";
    private static final String FFMPEG_RESOURCE = FFMPEG_BIN_RESOURCE + "ffmpeg.exe";
    private static final String FFPROBE_RESOURCE = FFMPEG_BIN_RESOURCE + "ffprobe.exe";
    private static final String RUNTIME_BUNDLE_DIRECTORY = "media-tools-2026-04-22";

    private static final Object RESOLUTION_LOCK = new Object();
    private static volatile ToolPaths resolvedPaths;

    private BundledMediaTools() {
    }

    public static ToolPaths resolve() throws IOException {
        ToolPaths current = resolvedPaths;
        if (current != null && current.isUsable()) {
            return current;
        }

        synchronized (RESOLUTION_LOCK) {
            current = resolvedPaths;
            if (current != null && current.isUsable()) {
                return current;
            }

            ensureWindows();

            Path ytDlp = resolveExecutable(YT_DLP_RESOURCE, "yt-dlp.exe", true);
            Path ffmpeg = resolveExecutable(FFMPEG_RESOURCE, "ffmpeg/bin/ffmpeg.exe", false);
            Path ffprobe = resolveExecutable(FFPROBE_RESOURCE, "ffmpeg/bin/ffprobe.exe", false);
            Path ffmpegBin = ffmpeg.getParent();

            if (ffmpegBin == null || !Files.isRegularFile(ffprobe)) {
                throw new IOException("Bundled FFmpeg installation is incomplete.");
            }

            current = new ToolPaths(ytDlp, ffmpegBin);
            resolvedPaths = current;
            DownloadLog.info(
                    "BundledMediaTools",
                    "Resolved yt-dlp=" + ytDlp + " | ffmpegBin=" + ffmpegBin
            );
            return current;
        }
    }

    public static ProcessBuilder ytDlpProcessBuilder(List<String> arguments, File workingDirectory)
            throws IOException {
        ToolPaths tools = resolve();
        List<String> command = new ArrayList<>();
        command.add(tools.ytDlpExecutable().toString());
        // Ignore user-level yt-dlp configuration so it cannot redirect the
        // process to a system-installed executable or external FFmpeg path.
        command.add("--ignore-config");
        command.add("--no-config-locations");
        command.add("--ffmpeg-location");
        command.add(tools.ffmpegBinDirectory().toString());
        if (arguments != null) {
            command.addAll(arguments);
        }

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            processBuilder.directory(workingDirectory);
        }

        configureBundledEnvironment(processBuilder, tools);
        DownloadLog.command("BundledMediaTools", command, workingDirectory);
        return processBuilder;
    }

    /**
     * Builds the self-update command for the same bundled yt-dlp executable
     * used by the download pipeline. It intentionally does not rely on a
     * system installation or user-level configuration.
     */
    public static ProcessBuilder ytDlpUpdateProcessBuilder() throws IOException {
        ToolPaths tools = resolve();
        List<String> command = List.of(tools.ytDlpExecutable().toString(), "-U");
        File workingDirectory = tools.ytDlpExecutable().getParent().toFile();
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workingDirectory);

        configureBundledEnvironment(processBuilder, tools);
        DownloadLog.command("BundledMediaTools", command, workingDirectory);
        return processBuilder;
    }

    private static void configureBundledEnvironment(ProcessBuilder processBuilder, ToolPaths tools) {
        // Do not inherit a user-configured PATH. yt-dlp and its FFmpeg child
        // processes can resolve only the executable files bundled by the app.
        String bundledPath = tools.ffmpegBinDirectory().toString();
        processBuilder.environment().put("PATH", bundledPath);

        // yt-dlp.exe embeds Python. Force UTF-8 instead of the active Windows
        // ANSI code page so Unicode artist/title characters survive intact.
        processBuilder.environment().put("PYTHONUTF8", "1");
        processBuilder.environment().put("PYTHONIOENCODING", "utf-8");
        processBuilder.environment().put("PYTHONLEGACYWINDOWSFSENCODING", "0");
    }

    private static Path resolveExecutable(
            String resourcePath,
            String extractedRelativePath,
            boolean preserveExistingRuntimeCopy
    ) throws IOException {
        URL resource = BundledMediaTools.class.getResource(resourcePath);
        if (resource == null) {
            throw new IOException("Bundled dependency not found: " + resourcePath);
        }

        if ("file".equalsIgnoreCase(resource.getProtocol())) {
            try {
                Path directPath = Path.of(resource.toURI()).toAbsolutePath().normalize();
                validateExecutable(directPath, resourcePath);
                DownloadLog.info("BundledMediaTools", "Using bundled resource directly: " + directPath);
                return directPath;
            } catch (URISyntaxException ex) {
                throw new IOException("Invalid bundled dependency URI: " + resourcePath, ex);
            }
        }

        Path destination = runtimeDependencyDirectory().resolve(extractedRelativePath).normalize();
        extractResource(resourcePath, resource, destination, preserveExistingRuntimeCopy);
        validateExecutable(destination, resourcePath);
        return destination;
    }

    private static void extractResource(
            String resourcePath,
            URL resource,
            Path destination,
            boolean preserveExistingRuntimeCopy
    ) throws IOException {
        Files.createDirectories(destination.getParent());
        long expectedSize = resourceSize(resource);
        if (Files.isRegularFile(destination)
                && Files.size(destination) > 0
                && (preserveExistingRuntimeCopy
                || expectedSize <= 0
                || Files.size(destination) == expectedSize)) {
            // A packaged application runs from this private copy. Keep a
            // successfully self-updated yt-dlp executable, while FFmpeg still
            // refreshes when the bundled resource changes.
            destination.toFile().setExecutable(true, false);
            DownloadLog.info("BundledMediaTools", "Using previously extracted dependency: " + destination);
            return;
        }

        DownloadLog.info("BundledMediaTools", "Extracting " + resourcePath + " to " + destination);
        Path temporary = destination.resolveSibling(destination.getFileName() + ".part");
        try (InputStream input = BundledMediaTools.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IOException("Bundled dependency stream not found: " + resourcePath);
            }
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
        }

        try {
            Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }

        destination.toFile().setExecutable(true, false);
    }

    private static long resourceSize(URL resource) {
        try {
            URLConnection connection = resource.openConnection();
            connection.setUseCaches(false);
            return connection.getContentLengthLong();
        } catch (IOException ignored) {
            return -1L;
        }
    }

    private static Path runtimeDependencyDirectory() throws IOException {
        Path directory = UserDataPaths.runtimeDependenciesDirectory()
                .resolve(RUNTIME_BUNDLE_DIRECTORY);
        Files.createDirectories(directory);
        return directory.toAbsolutePath().normalize();
    }

    private static void validateExecutable(Path executable, String resourcePath) throws IOException {
        if (executable == null || !Files.isRegularFile(executable) || Files.size(executable) <= 0) {
            throw new IOException("Bundled dependency is not executable or is empty: " + resourcePath);
        }
    }

    private static void ensureWindows() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            throw new IOException("The bundled yt-dlp and FFmpeg executables currently support Windows only.");
        }
    }

    public record ToolPaths(Path ytDlpExecutable, Path ffmpegBinDirectory) {
        private boolean isUsable() {
            return ytDlpExecutable != null
                    && ffmpegBinDirectory != null
                    && Files.isRegularFile(ytDlpExecutable)
                    && Files.isRegularFile(ffmpegBinDirectory.resolve("ffmpeg.exe"))
                    && Files.isRegularFile(ffmpegBinDirectory.resolve("ffprobe.exe"));
        }
    }
}
