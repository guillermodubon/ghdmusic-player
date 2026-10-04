package io.github.guillermodubon.musicplayer.services.downloads.dependencies;

import io.github.guillermodubon.musicplayer.services.downloads.logging.DownloadLog;
import io.github.guillermodubon.musicplayer.services.downloads.provider.YtDlpFailureKind;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Performs bounded, local-only checks of the media tools without delaying startup. */
public final class MediaToolsDiagnosticService {

    private static final MediaToolsDiagnosticService INSTANCE = new MediaToolsDiagnosticService();
    private static final long PROCESS_TIMEOUT_SECONDS = 5;
    private static final int MAX_OUTPUT_LENGTH = 2_048;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "media-tools-diagnostics");
        thread.setDaemon(true);
        return thread;
    });
    private volatile CompletableFuture<DiagnosticReport> reportFuture;

    private MediaToolsDiagnosticService() {
    }

    public static MediaToolsDiagnosticService getInstance() {
        return INSTANCE;
    }

    public CompletableFuture<DiagnosticReport> inspectAsync() {
        CompletableFuture<DiagnosticReport> current = reportFuture;
        if (current != null) return current;
        synchronized (this) {
            if (reportFuture == null) {
                reportFuture = CompletableFuture.supplyAsync(this::inspect, executor);
            }
            return reportFuture;
        }
    }

    public void reportRuntimeFailure(YtDlpFailureKind failureKind) {
        if (failureKind == YtDlpFailureKind.JAVASCRIPT_RUNTIME_REQUIRED) {
            DownloadLog.warn("MediaToolsDiagnosticService",
                    "yt-dlp explicitly reported missing JavaScript/EJS runtime support.");
        } else if (failureKind == YtDlpFailureKind.PROCESS_START_FAILURE
                || failureKind == YtDlpFailureKind.TOOL_CONFIGURATION) {
            DownloadLog.warn("MediaToolsDiagnosticService",
                    "A bundled media tool could not be started or resolved.");
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private DiagnosticReport inspect() {
        List<String> warnings = new ArrayList<>();
        try {
            BundledMediaTools.ToolPaths tools = BundledMediaTools.resolve();
            Path ytDlp = tools.ytDlpExecutable();
            Path ffmpeg = tools.ffmpegBinDirectory().resolve("ffmpeg.exe");
            Path ffprobe = tools.ffmpegBinDirectory().resolve("ffprobe.exe");
            String ytDlpVersion = readVersion(List.of(ytDlp.toString(), "--version"));
            String ffmpegVersion = readVersion(List.of(ffmpeg.toString(), "-version"));
            String ffprobeVersion = readVersion(List.of(ffprobe.toString(), "-version"));
            if (ytDlpVersion.isBlank()) warnings.add("yt-dlp version could not be read");
            if (ffmpegVersion.isBlank()) warnings.add("FFmpeg could not be started");
            if (ffprobeVersion.isBlank()) warnings.add("ffprobe could not be started");
            DiagnosticReport report = new DiagnosticReport(
                    Files.isRegularFile(ytDlp), ytDlpVersion,
                    Files.isRegularFile(ffmpeg), ffmpegVersion,
                    Files.isRegularFile(ffprobe), ffprobeVersion,
                    List.copyOf(warnings), null
            );
            DownloadLog.info("MediaToolsDiagnosticService", report.summary());
            return report;
        } catch (IOException error) {
            DownloadLog.warn("MediaToolsDiagnosticService",
                    "Local media-tool diagnostics failed: " + error.getMessage());
            return new DiagnosticReport(false, "", false, "", false, "",
                    List.copyOf(warnings), error.getMessage());
        }
    }

    private String readVersion(List<String> command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Process activeProcess = process;
            CompletableFuture<String> output = new CompletableFuture<>();
            Thread reader = new Thread(() -> {
                try {
                    byte[] buffer = new byte[512];
                    StringBuilder captured = new StringBuilder();
                    int count;
                    try (var input = activeProcess.getInputStream()) {
                        while ((count = input.read(buffer)) >= 0) {
                            if (captured.length() < MAX_OUTPUT_LENGTH) {
                                int kept = Math.min(count, MAX_OUTPUT_LENGTH - captured.length());
                                captured.append(new String(buffer, 0, kept, StandardCharsets.UTF_8));
                            }
                        }
                    }
                    output.complete(captured.toString().trim());
                } catch (IOException error) {
                    output.complete("");
                }
            }, "media-tool-version-output");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            reader.join(TimeUnit.SECONDS.toMillis(1));
            return output.getNow("");
        } catch (Exception error) {
            if (process != null && process.isAlive()) process.destroyForcibly();
            return "";
        }
    }

    public record DiagnosticReport(
            boolean ytDlpFound,
            String ytDlpVersion,
            boolean ffmpegFound,
            String ffmpegVersion,
            boolean ffprobeFound,
            String ffprobeVersion,
            List<String> warnings,
            String failure
    ) {
        public DiagnosticReport {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public boolean isReady() {
            return ytDlpFound && ffmpegFound && ffprobeFound
                    && warnings.isEmpty() && failure == null;
        }

        public String summary() {
            return "yt-dlp=" + (ytDlpVersion.isBlank() ? "unavailable" : ytDlpVersion)
                    + ", ffmpeg=" + (ffmpegVersion.isBlank() ? "unavailable" : "available")
                    + ", ffprobe=" + (ffprobeVersion.isBlank() ? "unavailable" : "available")
                    + (warnings.isEmpty() ? "" : ", warnings=" + String.join("; ", warnings));
        }
    }
}
