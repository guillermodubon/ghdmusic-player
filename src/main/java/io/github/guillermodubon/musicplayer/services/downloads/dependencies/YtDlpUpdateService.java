package io.github.guillermodubon.musicplayer.services.downloads.dependencies;

import io.github.guillermodubon.musicplayer.services.downloads.logging.DownloadLog;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Checks for yt-dlp updates using only the executable bundled with the app.
 * A failed or timed-out update must never block the application startup.
 */
public final class YtDlpUpdateService {

    private static final long UPDATE_TIMEOUT_SECONDS = 15;

    public UpdateResult updateBundledYtDlp() {
        Process process = null;
        try {
            ProcessBuilder builder = BundledMediaTools.ytDlpUpdateProcessBuilder();
            builder.redirectErrorStream(true);
            process = builder.start();

            Process activeProcess = process;
            Thread outputReader = new Thread(
                    () -> readProcessOutput(activeProcess),
                    "yt-dlp-update-output"
            );
            outputReader.setDaemon(true);
            outputReader.start();

            boolean finished = process.waitFor(UPDATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                outputReader.join(TimeUnit.SECONDS.toMillis(1));
                DownloadLog.warn("YtDlpUpdateService", "yt-dlp update check timed out.");
                return UpdateResult.timeoutResult();
            }

            outputReader.join(TimeUnit.SECONDS.toMillis(1));
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                DownloadLog.info("YtDlpUpdateService", "yt-dlp update check completed.");
            } else {
                DownloadLog.warn("YtDlpUpdateService", "yt-dlp update check finished with exit code " + exitCode + ".");
            }
            return UpdateResult.completed(exitCode);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            DownloadLog.warn("YtDlpUpdateService", "yt-dlp update check was interrupted.");
            return UpdateResult.interruptedResult();
        } catch (IOException ex) {
            DownloadLog.warn("YtDlpUpdateService", "Unable to check for yt-dlp updates: " + ex.getMessage());
            return UpdateResult.failed();
        }
    }

    private void readProcessOutput(Process process) {
        try (BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                DownloadLog.processOutput("yt-dlp-update", line);
            }
        } catch (IOException ex) {
            DownloadLog.warn("YtDlpUpdateService", "Could not read yt-dlp update output: " + ex.getMessage());
        }
    }

    public record UpdateResult(int exitCode, boolean timedOut, boolean interrupted) {
        private static UpdateResult completed(int exitCode) {
            return new UpdateResult(exitCode, false, false);
        }

        private static UpdateResult timeoutResult() {
            return new UpdateResult(-1, true, false);
        }

        private static UpdateResult interruptedResult() {
            return new UpdateResult(-1, false, true);
        }

        private static UpdateResult failed() {
            return new UpdateResult(-1, false, false);
        }
    }
}
