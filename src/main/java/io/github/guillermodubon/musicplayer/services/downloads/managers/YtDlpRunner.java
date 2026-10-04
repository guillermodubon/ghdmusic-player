package io.github.guillermodubon.musicplayer.services.downloads.managers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.guillermodubon.musicplayer.services.downloads.context.DownloadTaskContext;
import io.github.guillermodubon.musicplayer.services.downloads.dependencies.BundledMediaTools;
import io.github.guillermodubon.musicplayer.services.downloads.dependencies.MediaToolsDiagnosticService;
import io.github.guillermodubon.musicplayer.services.downloads.helpers.DownloadFileNameHelper;
import io.github.guillermodubon.musicplayer.services.downloads.helpers.YTDLPApiHelpers.YtDlpCommandBuilder;
import io.github.guillermodubon.musicplayer.services.downloads.logging.DownloadLog;
import io.github.guillermodubon.musicplayer.services.downloads.provider.ProviderCooldownState;
import io.github.guillermodubon.musicplayer.services.downloads.provider.ProviderOperation;
import io.github.guillermodubon.musicplayer.services.downloads.provider.YouTubeRequestCoordinator;
import io.github.guillermodubon.musicplayer.services.downloads.provider.YtDlpFailureClassifier;
import io.github.guillermodubon.musicplayer.services.downloads.provider.YtDlpFailureKind;
import io.github.guillermodubon.musicplayer.services.downloads.provider.YtDlpProcessGuard;

import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.regex.Pattern;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public class YtDlpRunner {

    @FunctionalInterface
    public interface ProcessStarter {
        Process start(List<String> arguments, File workingDirectory) throws IOException;
    }

    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    public static final class AttemptResult {
        private final boolean cancelled;
        private final boolean exists;
        private final boolean fatal;
        private final int exitCode;
        private final File createdTmp;
        private final IOException ioException;
        private final boolean networkFailure;
        private final YtDlpFailureKind failureKind;
        private final ProviderCooldownState cooldownState;

        public AttemptResult(boolean cancelled,
                             boolean exists,
                             boolean fatal,
                             int exitCode,
                             File createdTmp,
                             IOException ioException,
                             boolean networkFailure) {
            this(cancelled, exists, fatal, exitCode, createdTmp, ioException, networkFailure,
                    networkFailure ? YtDlpFailureKind.TRANSIENT_NETWORK
                            : fatal ? YtDlpFailureKind.UNKNOWN : YtDlpFailureKind.NONE,
                    null);
        }

        public AttemptResult(boolean cancelled,
                             boolean exists,
                             boolean fatal,
                             int exitCode,
                             File createdTmp,
                             IOException ioException,
                             boolean networkFailure,
                             YtDlpFailureKind failureKind,
                             ProviderCooldownState cooldownState) {
            this.cancelled = cancelled;
            this.exists = exists;
            this.fatal = fatal;
            this.exitCode = exitCode;
            this.createdTmp = createdTmp;
            this.ioException = ioException;
            this.networkFailure = networkFailure;
            this.failureKind = failureKind == null ? YtDlpFailureKind.UNKNOWN : failureKind;
            this.cooldownState = cooldownState;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public boolean isExists() {
            return exists;
        }

        public boolean isFatal() {
            return fatal;
        }

        public int getExitCode() {
            return exitCode;
        }

        public File getCreatedTmp() {
            return createdTmp;
        }

        public IOException getIoException() {
            return ioException;
        }

        public boolean isNetworkFailure() {
            return networkFailure;
        }

        public YtDlpFailureKind getFailureKind() {
            return failureKind;
        }

        public ProviderCooldownState getCooldownState() {
            return cooldownState;
        }

        public boolean isSuccessfulCandidate() {
            return !cancelled
                    && !exists
                    && exitCode == 0
                    && createdTmp != null
                    && createdTmp.exists()
                    && failureKind == YtDlpFailureKind.NONE
                    && !fatal;
        }
    }

    private static final int MAX_DIAGNOSTIC_TAIL_LENGTH = 8_192;
    private final YouTubeRequestCoordinator coordinator;
    private final YtDlpFailureClassifier failureClassifier;
    private final ProcessStarter processStarter;
    private final Runnable diagnosticsCheck;
    private final ThreadLocal<Integer> currentAttemptNumber = ThreadLocal.withInitial(() -> 0);

    public YtDlpRunner() {
        this(YouTubeRequestCoordinator.getInstance(), new YtDlpFailureClassifier(),
                (arguments, directory) -> BundledMediaTools.ytDlpProcessBuilder(arguments, directory)
                        .redirectErrorStream(true)
                        .start(),
                () -> MediaToolsDiagnosticService.getInstance().inspectAsync());
    }

    public YtDlpRunner(YouTubeRequestCoordinator coordinator,
                       YtDlpFailureClassifier failureClassifier) {
        this(coordinator, failureClassifier,
                (arguments, directory) -> BundledMediaTools.ytDlpProcessBuilder(arguments, directory)
                        .redirectErrorStream(true)
                        .start(),
                () -> MediaToolsDiagnosticService.getInstance().inspectAsync());
    }

    public YtDlpRunner(YouTubeRequestCoordinator coordinator,
                       YtDlpFailureClassifier failureClassifier,
                       ProcessStarter processStarter) {
        this(coordinator, failureClassifier, processStarter,
                () -> MediaToolsDiagnosticService.getInstance().inspectAsync());
    }

    public YtDlpRunner(YouTubeRequestCoordinator coordinator,
                       YtDlpFailureClassifier failureClassifier,
                       ProcessStarter processStarter,
                       Runnable diagnosticsCheck) {
        this.coordinator = coordinator == null
                ? YouTubeRequestCoordinator.getInstance()
                : coordinator;
        this.failureClassifier = failureClassifier == null
                ? new YtDlpFailureClassifier()
                : failureClassifier;
        this.processStarter = processStarter == null
                ? (arguments, directory) -> BundledMediaTools.ytDlpProcessBuilder(arguments, directory)
                        .redirectErrorStream(true)
                        .start()
                : processStarter;
        this.diagnosticsCheck = diagnosticsCheck == null ? () -> { } : diagnosticsCheck;
    }

    public AttemptResult executeAttempt(
            DownloadTaskContext context,
            int candidateIndex,
            BooleanSupplier cancelledSupplier,
            IntConsumer progressConsumer,
            Consumer<String> messageConsumer
    ) throws IOException, InterruptedException {
        return executeAttempt(
                context,
                context == null ? "" : context.getQuery(),
                candidateIndex,
                cancelledSupplier,
                progressConsumer,
                messageConsumer
        );
    }

    public AttemptResult executeAttempt(
            DownloadTaskContext context,
            String searchQuery,
            int candidateIndex,
            BooleanSupplier cancelledSupplier,
            IntConsumer progressConsumer,
            Consumer<String> messageConsumer
    ) throws IOException, InterruptedException {

        if (context == null || context.getTargetDir() == null) {
            throw new IOException("Invalid download context.");
        }

        ensureTargetDir(context.getTargetDir());
        diagnosticsCheck.run();

        IOException lastIoEx = null;
        Process p = null;
        YtDlpProcessGuard processGuard = null;
        ProviderOperation operation = context.isBulkDownload()
                ? ProviderOperation.DOWNLOAD_BULK
                : ProviderOperation.DOWNLOAD_SINGLE;
        YouTubeRequestCoordinator.Acquisition acquisition = coordinator.acquire(operation, cancelledSupplier);
        if (acquisition.cancelled()) {
            return new AttemptResult(true, false, false, -1, null, null, false,
                    YtDlpFailureKind.NONE, null);
        }
        if (!acquisition.granted()) {
            return new AttemptResult(false, false, true, -1, null, null, false,
                    acquisition.failureKind(), acquisition.cooldownState());
        }

        YouTubeRequestCoordinator.Lease lease = acquisition.lease();
        StringBuilder diagnosticTail = new StringBuilder();
        YtDlpFailureKind observedFailure = YtDlpFailureKind.NONE;

        /*
         * Important:
         * YtDlpRunner emits raw yt-dlp progress from 0 to 100.
         * The global app progress mapping is handled by DownloadTask:
         *
         * yt-dlp raw 0..100 -> DownloadTask maps it to 0..75.
         *
         * Do NOT map to 75 here, otherwise progress would be scaled twice.
         */
        AtomicInteger lastEmittedProgress = new AtomicInteger(-1);

        try {
            List<String> baseArgs = YtDlpCommandBuilder.buildBaseArgs(
                    searchQuery,
                    context.getTargetDir(),
                    candidateIndex,
                    context.getDownloadToken(),
                    context.getAudioPreset(),
                    context.isBulkDownload(),
                    coordinator.policy()
            );

            DownloadLog.info(
                    "YtDlpRunner",
                    "Starting " + operation + " attempt=" + currentAttemptNumber.get()
                            + ", candidate=" + candidateIndex
                            + ", session=" + context.getBulkSessionId()
            );

            p = processStarter.start(baseArgs, context.getTargetDir());
            Thread executionThread = Thread.currentThread();
            processGuard = YtDlpProcessGuard.watch(p,
                    () -> executionThread.isInterrupted()
                            || (cancelledSupplier != null && cancelledSupplier.getAsBoolean()));

            DownloadLog.info("YtDlpRunner", "yt-dlp process started, pid=" + p.pid());

            boolean sawExists = false;
            boolean sawFatal = false;
            boolean sawNetworkFailure = false;

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {

                String line;

                while ((line = reader.readLine()) != null) {
                    if (cancelledSupplier != null && cancelledSupplier.getAsBoolean()) {
                        try {
                            p.destroyForcibly();
                        } catch (Exception ignored) {
                        }

                        if (messageConsumer != null) {
                            messageConsumer.accept("Cancelled");
                        }

                        if (progressConsumer != null) {
                            progressConsumer.accept(0);
                        }

                        return new AttemptResult(true, false, false, -1, null, null, false);
                    }

                    line = line.trim();

                    if (line.isEmpty()) {
                        continue;
                    }

                    DownloadLog.processOutput("yt-dlp", line);
                    appendDiagnostic(diagnosticTail, line);

                    YtDlpFailureKind lineFailure = failureClassifier.classifyLine(line);
                    observedFailure = preferFailure(observedFailure, lineFailure);
                    if (lineFailure.isProviderRejection()) {
                        lease.reportFailure(lineFailure);
                    }

                    emitDownloadProgressIfPresent(line, progressConsumer, lastEmittedProgress);

                    String ll = line.toLowerCase(Locale.ROOT);

                    if (isNetworkFailureMessage(ll)) {
                        sawNetworkFailure = true;
                    }

                    if (ll.contains("has already been downloaded")
                            || ll.contains("already been downloaded")
                            || ll.contains("already downloaded")
                            || ll.contains("file is already downloaded")
                            || ll.contains("file already exists")) {
                        sawExists = true;
                        continue;
                    }

                    /*
                     * Known yt-dlp warnings that should not be treated as fatal.
                     */
                    if (line.startsWith("WARNING:")
                            || ll.contains("signature extraction failed")
                            || ll.contains("some web_safari client")
                            || ll.contains("sabr streaming")
                            || ll.contains("some web client https formats have been skipped")
                            || ll.contains("please report this issue")) {
                        continue;
                    }

                    if (ll.contains("traceback")
                            || ll.contains("exception")
                            || ll.contains("[error]")
                            || line.startsWith("ERROR:")
                            || ll.matches(".*\\berror\\b.*\\bexception\\b.*")) {
                        sawFatal = true;
                    }
                }
            } catch (IOException io) {
                lastIoEx = io;
                sawFatal = true;

                sawNetworkFailure = isNetworkFailureMessage(
                        io.getMessage() == null
                                ? ""
                                : io.getMessage().toLowerCase(Locale.ROOT)
                );
            }

            int exitCode = p.waitFor();
            YtDlpFailureKind finalFailure = failureClassifier.classify(
                    diagnosticTail.toString(),
                    exitCode,
                    lastIoEx
            );
            observedFailure = preferFailure(observedFailure, finalFailure);
            if (sawFatal && observedFailure == YtDlpFailureKind.NONE) {
                observedFailure = YtDlpFailureKind.UNKNOWN;
            }
            if (observedFailure.isProviderRejection()) {
                lease.reportFailure(observedFailure);
            }
            if (lease.cooldownState() != null) {
                failureClassifier.retryAfter(diagnosticTail.toString())
                        .ifPresent(lease::reportRetryAfter);
            }
            MediaToolsDiagnosticService.getInstance().reportRuntimeFailure(observedFailure);
            DownloadLog.info("YtDlpRunner", "Attempt finished: classification=" + observedFailure
                    + ", exitCode=" + exitCode
                    + ", attempt=" + currentAttemptNumber.get()
                    + ", candidate=" + candidateIndex
                    + ", session=" + context.getBulkSessionId());

            File createdTmp = DownloadFileNameHelper.findLatestTmpFile(
                    context.getTargetDir(),
                    context.getDownloadToken()
            );

            DownloadLog.info(
                    "YtDlpRunner",
                    "yt-dlp process finished: exitCode=" + exitCode
                            + ", temporaryFile=" + DownloadLog.pathOf(createdTmp)
            );

            if (sawExists && observedFailure == YtDlpFailureKind.NONE) {
                lease.complete(YtDlpFailureKind.NONE);
                return new AttemptResult(false, true, false, exitCode, createdTmp, lastIoEx, false,
                        YtDlpFailureKind.NONE, null);
            }

            boolean success = exitCode == 0
                    && createdTmp != null
                    && createdTmp.exists()
                    && observedFailure == YtDlpFailureKind.NONE
                    && !sawFatal;
            if (success) {
                lease.complete(YtDlpFailureKind.NONE);
            } else {
                lease.complete(observedFailure);
            }
            return new AttemptResult(false, false, sawFatal || observedFailure != YtDlpFailureKind.NONE,
                    exitCode, createdTmp, lastIoEx,
                    observedFailure == YtDlpFailureKind.TRANSIENT_NETWORK || sawNetworkFailure,
                    observedFailure, lease.cooldownState());

        } catch (IOException io) {
            DownloadLog.error("YtDlpRunner", "Bundled yt-dlp failed to start", io);
            YtDlpFailureKind failure = failureClassifier.classify(
                    diagnosticTail.toString(), -1, io
            );
            if (failure == YtDlpFailureKind.UNKNOWN) {
                failure = YtDlpFailureKind.PROCESS_START_FAILURE;
            }
            MediaToolsDiagnosticService.getInstance().reportRuntimeFailure(failure);
            lease.complete(failure);
            return new AttemptResult(false, false, true, -1, null, io, false,
                    failure, lease.cooldownState());
        } finally {
            if (p != null && p.isAlive()) {
                try {
                    p.destroyForcibly();
                } catch (Exception ignored) {
                }
            }
            lease.close();
        }
    }

    public AttemptResult executeAttempt(
            DownloadTaskContext context,
            String searchQuery,
            int candidateIndex,
            int attemptNumber,
            BooleanSupplier cancelledSupplier,
            IntConsumer progressConsumer,
            Consumer<String> messageConsumer
    ) throws IOException, InterruptedException {
        currentAttemptNumber.set(Math.max(0, attemptNumber));
        try {
            return executeAttempt(context, searchQuery, candidateIndex,
                    cancelledSupplier, progressConsumer, messageConsumer);
        } finally {
            currentAttemptNumber.remove();
        }
    }

    public Optional<String> probeBestAudioFormat(String videoUrl, File targetDir) {
        if (videoUrl == null || videoUrl.isBlank()) {
            return Optional.empty();
        }

        YouTubeRequestCoordinator.Acquisition acquisition;
        try {
            acquisition = coordinator.acquire(ProviderOperation.FORMAT_PROBE, () -> false);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        if (!acquisition.granted()) return Optional.empty();

        YouTubeRequestCoordinator.Lease lease = acquisition.lease();
        StringBuilder diagnostic = new StringBuilder();
        Process p = null;
        YtDlpProcessGuard processGuard = null;

        try {
            DownloadLog.info("YtDlpRunner", "Probing best audio format for " + videoUrl);

            List<String> args = new ArrayList<>();
            args.add("-J");
            args.add("--no-warnings");
            args.add(videoUrl);

            p = processStarter.start(args, targetDir);
            Thread executionThread = Thread.currentThread();
            processGuard = YtDlpProcessGuard.watch(p, executionThread::isInterrupted);

            DownloadLog.info("YtDlpRunner", "Format probe process started, pid=" + p.pid());

            StringBuilder out = new StringBuilder();

            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;

                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                    appendDiagnostic(diagnostic, line);
                    YtDlpFailureKind lineFailure = failureClassifier.classifyLine(line);
                    if (lineFailure.isProviderRejection()) lease.reportFailure(lineFailure);
                }
            }

            int exit = p.waitFor();
            YtDlpFailureKind failure = failureClassifier.classify(diagnostic.toString(), exit, null);
            if (failure.isProviderRejection()) lease.reportFailure(failure);
            lease.complete(exit == 0 && failure == YtDlpFailureKind.NONE
                    ? YtDlpFailureKind.NONE : failure == YtDlpFailureKind.NONE
                    ? YtDlpFailureKind.UNKNOWN : failure);

            DownloadLog.info("YtDlpRunner", "Format probe finished with exitCode=" + exit);

            if (exit != 0 || out.isEmpty()) {
                return Optional.empty();
            }

            JsonElement je = JsonParser.parseString(out.toString());

            if (!je.isJsonObject()) {
                return Optional.empty();
            }

            JsonObject root = je.getAsJsonObject();

            if (!root.has("formats") || !root.get("formats").isJsonArray()) {
                return Optional.empty();
            }

            JsonArray formats = root.getAsJsonArray("formats");

            List<String> preferredExt = List.of("m4a", "mp4", "webm", "mp3", "aac", "opus");
            List<JsonObject> candidates = new ArrayList<>();

            for (JsonElement fe : formats) {
                if (!fe.isJsonObject()) {
                    continue;
                }

                JsonObject fo = fe.getAsJsonObject();

                String acodec = fo.has("acodec") && !fo.get("acodec").isJsonNull()
                        ? fo.get("acodec").getAsString()
                        : "none";

                if ("none".equalsIgnoreCase(acodec)) {
                    continue;
                }

                candidates.add(fo);
            }

            candidates.sort((a, b) -> {
                String ea = a.has("ext") && !a.get("ext").isJsonNull()
                        ? a.get("ext").getAsString()
                        : "";

                String eb = b.has("ext") && !b.get("ext").isJsonNull()
                        ? b.get("ext").getAsString()
                        : "";

                int ia = preferredExt.indexOf(ea.toLowerCase(Locale.ROOT));
                int ib = preferredExt.indexOf(eb.toLowerCase(Locale.ROOT));

                if (ia != ib) {
                    return Integer.compare(
                            ia < 0 ? Integer.MAX_VALUE : ia,
                            ib < 0 ? Integer.MAX_VALUE : ib
                    );
                }

                int abra = a.has("abr") && !a.get("abr").isJsonNull()
                        ? a.get("abr").getAsInt()
                        : 0;

                int abrb = b.has("abr") && !b.get("abr").isJsonNull()
                        ? b.get("abr").getAsInt()
                        : 0;

                if (abra != abrb) {
                    return Integer.compare(abrb, abra);
                }

                long fsa = a.has("filesize") && !a.get("filesize").isJsonNull()
                        ? a.get("filesize").getAsLong()
                        : 0L;

                long fsb = b.has("filesize") && !b.get("filesize").isJsonNull()
                        ? b.get("filesize").getAsLong()
                        : 0L;

                return Long.compare(fsb, fsa);
            });

            for (JsonObject cand : candidates) {
                if (cand.has("format_id")) {
                    String fid = cand.get("format_id").getAsString();

                    if (fid != null && !fid.isBlank()) {
                        DownloadLog.info("YtDlpRunner", "Selected audio format id=" + fid);
                        return Optional.of(fid);
                }
            }
            if (processGuard != null) processGuard.close();
        }
        } catch (Throwable error) {
            lease.complete(failureClassifier.classify(diagnostic.toString(), -1, error));
            DownloadLog.error("YtDlpRunner", "Could not probe audio format", error);
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
            if (processGuard != null) processGuard.close();
            lease.close();
        }

        return Optional.empty();
    }

    private void ensureTargetDir(File dir) throws IOException {
        if (dir.exists()) {
            return;
        }

        if (!dir.mkdirs() && !dir.exists()) {
            throw new IOException("Could not create target directory: " + dir.getAbsolutePath());
        }
    }

    private void emitDownloadProgressIfPresent(String line,
                                               IntConsumer progressConsumer,
                                               AtomicInteger lastEmittedProgress) {
        if (line == null || progressConsumer == null || lastEmittedProgress == null) {
            return;
        }

        /*
         * yt-dlp progress lines normally look like:
         *
         * [download]  12.3% of ...
         * [download] 100% of ...
         *
         * Other [download] lines, such as Destination/Merging/etc.,
         * should not emit progress because they could make the UI regress.
         */
        if (!line.startsWith("[download]") || !line.contains("%")) {
            return;
        }

        int parsed = DownloadFileNameHelper.parsePercent(line);

        if (parsed < 0) {
            return;
        }

        int safeProgress = Math.max(0, Math.min(100, parsed));
        int previous = lastEmittedProgress.get();

        /*
         * Avoid visual regressions while yt-dlp emits multiple lines.
         * Allow 100 explicitly.
         */
        if (safeProgress < previous && safeProgress != 100) {
            return;
        }

        lastEmittedProgress.set(safeProgress);
        progressConsumer.accept(safeProgress);
    }

    private boolean isNetworkFailureMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }

        return message.contains("unable to download webpage")
                || message.contains("network is unreachable")
                || message.contains("no route to host")
                || message.contains("connection refused")
                || message.contains("connection reset")
                || message.contains("connection aborted")
                || message.contains("remote end closed connection")
                || message.contains("temporary failure in name resolution")
                || message.contains("name or service not known")
                || message.contains("getaddrinfo failed")
                || message.contains("timed out")
                || message.contains("timeout")
                || message.contains("dns");
    }

    private void appendDiagnostic(StringBuilder output, String line) {
        if (line == null) return;
        if (output.length() > 0) output.append('\n');
        output.append(line);
        if (output.length() > MAX_DIAGNOSTIC_TAIL_LENGTH) {
            output.delete(0, output.length() - MAX_DIAGNOSTIC_TAIL_LENGTH);
        }
    }

    private YtDlpFailureKind preferFailure(YtDlpFailureKind current,
                                           YtDlpFailureKind candidate) {
        if (candidate == null || candidate == YtDlpFailureKind.NONE) return current;
        if (current == null || current == YtDlpFailureKind.NONE) return candidate;
        return failurePriority(candidate) > failurePriority(current) ? candidate : current;
    }

    private int failurePriority(YtDlpFailureKind kind) {
        return switch (kind) {
            case BOT_CHALLENGE -> 100;
            case PROVIDER_RATE_LIMITED -> 95;
            case JAVASCRIPT_RUNTIME_REQUIRED -> 92;
            case PROVIDER_FORBIDDEN -> 90;
            case AUTHENTICATION_REQUIRED -> 75;
            case CONTENT_RESTRICTED -> 70;
            case CONTENT_UNAVAILABLE -> 65;
            case TOOL_CONFIGURATION -> 60;
            case PROCESS_START_FAILURE -> 55;
            case TRANSIENT_NETWORK -> 50;
            case UNKNOWN -> 10;
            default -> 0;
        };
    }
}
