package io.github.guillermodubon.musicplayer.services.playback.services;

import io.github.guillermodubon.musicplayer.services.downloads.dependencies.BundledMediaTools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Provides playback-compatible files for formats that JavaFX Media does not
 * reliably decode on every supported Windows environment.
 */
public final class PlaybackAudioCompatibilityService {

    private static final String CACHE_DIRECTORY_NAME = "ghdmusic-flac-cache";

    private final Object conversionLock = new Object();

    /**
     * Returns the original path for supported formats and a cached WAV path for
     * FLAC. The source FLAC file is never modified.
     */
    public Path resolvePlayablePath(Path source) throws IOException, InterruptedException {
        if (!isFlac(source)) {
            return source;
        }

        if (!isUsableFile(source)) {
            throw new IOException("FLAC source is not readable: " + source);
        }

        synchronized (conversionLock) {
            Path cached = cachedPath(source);
            if (isUsableFile(cached)) {
                return cached;
            }

            Files.createDirectories(cached.getParent());
            // Keep .wav as the final extension so FFmpeg can infer the output
            // container. A trailing .part would make FFmpeg reject the file.
            Path temporary = cached.resolveSibling(cached.getFileName() + ".part.wav");
            Files.deleteIfExists(temporary);

            try {
                convertToWav(source, temporary);
                moveIntoCache(temporary, cached);
                return cached;
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    /** Used to avoid reloading an already converted FLAC during playback sync. */
    public Path cachedPlayablePathIfPresent(Path source) {
        if (!isFlac(source) || !isUsableFile(source)) {
            return null;
        }

        try {
            Path cached = cachedPath(source);
            return isUsableFile(cached) ? cached : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    public boolean isFlac(Path path) {
        return path != null
                && path.getFileName() != null
                && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".flac");
    }

    private void convertToWav(Path source, Path destination)
            throws IOException, InterruptedException {
        Path ffmpeg = BundledMediaTools.resolve()
                .ffmpegBinDirectory()
                .resolve("ffmpeg.exe");

        ProcessBuilder processBuilder = new ProcessBuilder(
                ffmpeg.toString(),
                "-hide_banner",
                "-loglevel", "error",
                "-nostdin",
                "-y",
                "-i", source.toAbsolutePath().toString(),
                "-vn",
                "-c:a", "pcm_s16le",
                destination.toAbsolutePath().toString()
        );
        processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);

        Process process = processBuilder.start();
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw interrupted;
        }

        if (exitCode != 0 || !isUsableFile(destination)) {
            throw new IOException("Could not create a playback-compatible WAV from FLAC.");
        }
    }

    private void moveIntoCache(Path temporary, Path cached) throws IOException {
        try {
            Files.move(
                    temporary,
                    cached,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, cached, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path cachedPath(Path source) throws IOException {
        Path cacheDirectory = Path.of(
                System.getProperty("java.io.tmpdir"),
                CACHE_DIRECTORY_NAME
        );
        String fingerprint = source.toAbsolutePath().normalize()
                + "|" + Files.size(source)
                + "|" + Files.getLastModifiedTime(source).toMillis();

        return cacheDirectory.resolve("flac-" + sha256(fingerprint) + ".wav");
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private boolean isUsableFile(Path path) {
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
