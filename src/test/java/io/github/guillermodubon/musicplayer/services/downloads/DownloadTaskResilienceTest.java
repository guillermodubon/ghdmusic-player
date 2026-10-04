package io.github.guillermodubon.musicplayer.services.downloads;

import io.github.guillermodubon.musicplayer.models.DeezerApiMetaData;
import io.github.guillermodubon.musicplayer.services.downloads.context.DownloadTaskContext;
import io.github.guillermodubon.musicplayer.services.downloads.managers.DownloadFileFinalizer;
import io.github.guillermodubon.musicplayer.services.downloads.managers.DownloadRetryPolicy;
import io.github.guillermodubon.musicplayer.services.downloads.managers.YtDlpRunner;
import io.github.guillermodubon.musicplayer.services.downloads.provider.ProviderCooldownState;
import io.github.guillermodubon.musicplayer.services.downloads.provider.YouTubeExecutionPolicy;
import io.github.guillermodubon.musicplayer.services.downloads.provider.YouTubeRequestCoordinator;
import io.github.guillermodubon.musicplayer.services.downloads.provider.ProviderOperation;
import io.github.guillermodubon.musicplayer.services.downloads.provider.YtDlpFailureClassifier;
import io.github.guillermodubon.musicplayer.services.downloads.provider.YtDlpFailureKind;
import javafx.application.Platform;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadTaskResilienceTest {

    @BeforeAll
    static void startJavaFx() {
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // Another test may have initialized JavaFX.
        }
    }

    @AfterAll
    static void stopJavaFx() {
        Platform.exit();
    }

    @Test
    void successfulSingleAndBulkTasksKeepTheExistingFinalizationPath(@TempDir Path directory)
            throws Exception {
        for (boolean bulk : List.of(false, true)) {
            Path target = Files.createDirectory(directory.resolve(bulk ? "bulk" : "single"));
            DownloadTaskContext context = context(target, bulk);
            File temporary = createTemporaryAudio(context);
            File finalFile = target.resolve("song.mp3").toFile();
            FakeRunner runner = new FakeRunner(temporary, YtDlpFailureKind.NONE);
            FakeFinalizer finalizer = new FakeFinalizer(finalFile, false);
            DirectDownloadTask task = new DirectDownloadTask(context, runner, finalizer);

            task.runDirectly();

            assertEquals(1, runner.attempts.get());
            assertEquals(DownloadTask.TerminalPresentation.COMPLETED,
                    task.getTerminalPresentation());
            assertEquals(1, finalizer.publicationCount.get());
            assertTrue(finalFile.isFile());
            assertFalse(temporary.exists());
        }
    }

    @Test
    void providerPhaseCompletionSignalIsOneShotAcrossSuccessAndFailure(@TempDir Path directory)
            throws Exception {
        for (int index = 0; index < 3; index++) {
            Path itemDirectory = Files.createDirectory(directory.resolve("phase-" + index));
            DownloadTaskContext context = context(itemDirectory, true);
            YtDlpFailureKind kind = switch (index) {
                case 0 -> YtDlpFailureKind.NONE;
                case 1 -> YtDlpFailureKind.PROVIDER_FORBIDDEN;
                default -> YtDlpFailureKind.PROCESS_START_FAILURE;
            };
            File temporary = kind == YtDlpFailureKind.NONE ? createTemporaryAudio(context) : null;
            DirectDownloadTask task = new DirectDownloadTask(
                    context,
                    new FakeRunner(temporary, kind),
                    new FakeFinalizer(itemDirectory.resolve("song.mp3").toFile(), false)
            );
            AtomicInteger phaseCompletions = new AtomicInteger();
            task.setProviderPhaseCompletionListener(ignored -> phaseCompletions.incrementAndGet());

            try {
                task.runDirectly();
            } catch (Exception expectedFailure) {
                assertEquals(YtDlpFailureKind.PROCESS_START_FAILURE, kind);
            }
            task.completeProviderPhase();

            assertEquals(1, phaseCompletions.get());
        }
    }

    @Test
    void providerPhaseSignalRunsAfterTheRunnerReleasesItsLease(@TempDir Path directory)
            throws Exception {
        YouTubeRequestCoordinator coordinator = new YouTubeRequestCoordinator(
                YouTubeExecutionPolicy.defaults(),
                System::currentTimeMillis,
                System::nanoTime,
                ignored -> 0
        );
        DownloadTaskContext context = context(directory, true);
        YtDlpRunner runner = new YtDlpRunner(
                coordinator,
                new YtDlpFailureClassifier(),
                (arguments, workingDirectory) -> {
                    assertEquals(1, coordinator.activeOperationCount());
                    throw new IOException("simulated process-start failure");
                },
                () -> { }
        );
        DirectDownloadTask task = new DirectDownloadTask(
                context, runner,
                new FakeFinalizer(directory.resolve("song.mp3").toFile(), false));
        AtomicInteger releasedBeforeCallback = new AtomicInteger();
        task.setProviderPhaseCompletionListener(kind -> {
            if (coordinator.activeOperationCount() == 0) releasedBeforeCallback.incrementAndGet();
        });

        task.runDirectly();

        assertEquals(1, releasedBeforeCallback.get());
        assertEquals(0, coordinator.activeOperationCount());
        coordinator.shutdown();
    }

    @Test
    void cancellationDoesNotReleaseProviderPhaseBeforeTheRunnerLease(@TempDir Path directory)
            throws Exception {
        YouTubeRequestCoordinator coordinator = new YouTubeRequestCoordinator(
                YouTubeExecutionPolicy.defaults(), System::currentTimeMillis,
                System::nanoTime, ignored -> 0);
        CountDownLatch runnerStarted = new CountDownLatch(1);
        CountDownLatch releaseRunner = new CountDownLatch(1);
        LeaseHoldingRunner runner = new LeaseHoldingRunner(coordinator, runnerStarted, releaseRunner);
        DirectDownloadTask task = new DirectDownloadTask(
                context(directory, true),
                runner,
                new FakeFinalizer(directory.resolve("song.mp3").toFile(), false));
        AtomicInteger callbackCount = new AtomicInteger();
        AtomicInteger callbacksAfterLeaseRelease = new AtomicInteger();
        task.setProviderPhaseCompletionListener(kind -> {
            callbackCount.incrementAndGet();
            if (coordinator.activeOperationCount() == 0) callbacksAfterLeaseRelease.incrementAndGet();
        });

        Thread worker = new Thread(() -> {
            try {
                task.runDirectly();
            } catch (Exception ignored) {
            }
        });
        worker.setDaemon(true);
        worker.start();
        assertTrue(runnerStarted.await(1, TimeUnit.SECONDS));
        assertEquals(1, coordinator.activeOperationCount());

        task.cancel();
        assertEquals(0, callbackCount.get());
        assertEquals(1, coordinator.activeOperationCount());
        assertFalse(task.isExecutionComplete());

        releaseRunner.countDown();
        worker.join(2_000);
        assertFalse(worker.isAlive());
        assertTrue(task.isExecutionComplete());
        assertEquals(1, callbackCount.get());
        assertEquals(1, callbacksAfterLeaseRelease.get());
        assertEquals(0, coordinator.activeOperationCount());
        coordinator.shutdown();
    }

    @Test
    void providerRejectionStopsSearchVariantsAndDefersTheSong(@TempDir Path directory)
            throws Exception {
        int index = 0;
        for (YtDlpFailureKind kind : List.of(
                YtDlpFailureKind.PROVIDER_FORBIDDEN,
                YtDlpFailureKind.PROVIDER_RATE_LIMITED,
                YtDlpFailureKind.BOT_CHALLENGE)) {
            Path itemDirectory = Files.createDirectory(directory.resolve("provider-" + index++));
            DownloadTaskContext context = context(itemDirectory, true);
            context.setSearchQueries(List.of("primary query", "alternate artist query"));
            FakeRunner runner = new FakeRunner(null, kind);
            DirectDownloadTask task = new DirectDownloadTask(
                    context, runner,
                    new FakeFinalizer(itemDirectory.resolve("song.mp3").toFile(), false));
            task.setMaxAttempts(4);

            task.runDirectly();

            assertEquals(1, runner.attempts.get());
            assertTrue(task.isDeferredByProvider());
            assertEquals(DownloadTask.TerminalPresentation.PROVIDER_PAUSED,
                    task.getTerminalPresentation());
        }
    }

    @Test
    void toolAndContentFailuresStopWithoutAutomaticRetries(@TempDir Path directory)
            throws Exception {
        List<YtDlpFailureKind> kinds = List.of(
                YtDlpFailureKind.JAVASCRIPT_RUNTIME_REQUIRED,
                YtDlpFailureKind.AUTHENTICATION_REQUIRED,
                YtDlpFailureKind.CONTENT_UNAVAILABLE,
                YtDlpFailureKind.CONTENT_RESTRICTED,
                YtDlpFailureKind.PROCESS_START_FAILURE
        );
        List<DownloadTask.TerminalPresentation> presentations = List.of(
                DownloadTask.TerminalPresentation.MEDIA_TOOLS_ERROR,
                DownloadTask.TerminalPresentation.UNSUPPORTED_CONTENT,
                DownloadTask.TerminalPresentation.UNAVAILABLE_CONTENT,
                DownloadTask.TerminalPresentation.UNSUPPORTED_CONTENT,
                DownloadTask.TerminalPresentation.MEDIA_TOOLS_ERROR
        );
        for (int index = 0; index < kinds.size(); index++) {
            Path itemDirectory = Files.createDirectory(directory.resolve("classified-" + index));
            FakeRunner runner = new FakeRunner(null, kinds.get(index));
            DirectDownloadTask task = new DirectDownloadTask(
                    context(itemDirectory, false), runner,
                    new FakeFinalizer(itemDirectory.resolve("song.mp3").toFile(), false));
            task.setMaxAttempts(5);

            task.runDirectly();

            assertEquals(1, runner.attempts.get());
            assertEquals(presentations.get(index), task.getTerminalPresentation());
        }
    }

    @Test
    void unknownFailuresAreLimitedToOneRetry(@TempDir Path directory) {
        DownloadTaskContext context = context(directory, false);
        SequenceRunner runner = new SequenceRunner(List.of(
                failure(YtDlpFailureKind.UNKNOWN),
                failure(YtDlpFailureKind.UNKNOWN),
                failure(YtDlpFailureKind.UNKNOWN)
        ));
        DirectDownloadTask task = new DirectDownloadTask(
                context, runner,
                new FakeFinalizer(directory.resolve("song.mp3").toFile(), false),
                new ImmediateRetryPolicy());

        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, task::runDirectly);

        assertEquals(2, runner.attempts.get());
        assertEquals(DownloadTask.TerminalPresentation.YT_DLP_ERROR,
                task.getTerminalPresentation());
    }

    @Test
    void transientNetworkFailuresUseAtMostTwoRetries(@TempDir Path directory) throws Exception {
        DownloadTaskContext context = context(directory, false);
        File temporary = createTemporaryAudio(context);
        SequenceRunner runner = new SequenceRunner(List.of(
                failure(YtDlpFailureKind.TRANSIENT_NETWORK),
                failure(YtDlpFailureKind.TRANSIENT_NETWORK),
                new YtDlpRunner.AttemptResult(false, false, false, 0, temporary,
                        null, false, YtDlpFailureKind.NONE, null)
        ));
        DirectDownloadTask task = new DirectDownloadTask(
                context, runner, new FakeFinalizer(directory.resolve("song.mp3").toFile(), false),
                new ImmediateRetryPolicy());

        task.runDirectly();

        assertEquals(3, runner.attempts.get());
        assertEquals(DownloadTask.TerminalPresentation.COMPLETED,
                task.getTerminalPresentation());
    }

    @Test
    void existingFinalFileIsPublishedOnceWithoutStartingYtDlp(@TempDir Path directory)
            throws Exception {
        File existing = Files.write(directory.resolve("song.mp3"), new byte[]{1}).toFile();
        DownloadTaskContext context = context(directory, false);
        FakeRunner runner = new FakeRunner(null, YtDlpFailureKind.NONE);
        FakeFinalizer finalizer = new FakeFinalizer(existing, true);
        DirectDownloadTask task = new DirectDownloadTask(context, runner, finalizer);

        task.runDirectly();

        assertEquals(0, runner.attempts.get());
        assertEquals(DownloadTask.TerminalPresentation.ALREADY_EXISTS,
                task.getTerminalPresentation());
        assertEquals(1, finalizer.publicationCount.get());
        assertTrue(existing.isFile());
    }

    private DownloadTaskContext context(Path target, boolean bulk) {
        DownloadTaskContext context = new DownloadTaskContext("Artist - Song", target.toFile(), "Song");
        if (bulk) {
            context.setBulkSessionId("simulated-bulk-session");
            context.setBulkSessionTitle("Simulated batch");
            context.setBulkSongIndex(0);
            context.setBulkTotalSongs(1);
        }
        return context;
    }

    private File createTemporaryAudio(DownloadTaskContext context) throws IOException {
        String safeToken = io.github.guillermodubon.musicplayer.services.downloads.helpers.DownloadFileNameHelper
                .sanitizeToken(context.getDownloadToken());
        return Files.write(context.getTargetDir().toPath()
                .resolve("dl_tmp_" + safeToken + "_simulated.webm"), new byte[]{1}).toFile();
    }

    private static final class DirectDownloadTask extends DownloadTask {
        private DirectDownloadTask(DownloadTaskContext context,
                                   YtDlpRunner runner,
                                   DownloadFileFinalizer finalizer) {
            this(context, runner, finalizer, new DownloadRetryPolicy());
        }

        private DirectDownloadTask(DownloadTaskContext context,
                                   YtDlpRunner runner,
                                   DownloadFileFinalizer finalizer,
                                   DownloadRetryPolicy retryPolicy) {
            super(context, runner, finalizer, retryPolicy);
        }

        private void runDirectly() throws Exception {
            super.call();
        }
    }

    private static YtDlpRunner.AttemptResult failure(YtDlpFailureKind kind) {
        return new YtDlpRunner.AttemptResult(false, false, true, 1, null,
                new IOException("simulated " + kind),
                kind == YtDlpFailureKind.TRANSIENT_NETWORK, kind, null);
    }

    private static final class SequenceRunner extends YtDlpRunner {
        private final List<AttemptResult> results;
        private final AtomicInteger attempts = new AtomicInteger();

        private SequenceRunner(List<AttemptResult> results) {
            this.results = results;
        }

        @Override
        public AttemptResult executeAttempt(DownloadTaskContext context,
                                            String searchQuery,
                                            int candidateIndex,
                                            BooleanSupplier cancelledSupplier,
                                            IntConsumer progressConsumer,
                                            Consumer<String> messageConsumer) throws IOException {
            int index = attempts.getAndIncrement();
            AttemptResult result = results.get(Math.min(index, results.size() - 1));
            if (result.getCreatedTmp() != null) {
                Files.write(result.getCreatedTmp().toPath(), new byte[]{1});
            }
            return result;
        }
    }

    private static final class LeaseHoldingRunner extends YtDlpRunner {
        private final YouTubeRequestCoordinator coordinator;
        private final CountDownLatch started;
        private final CountDownLatch release;

        private LeaseHoldingRunner(YouTubeRequestCoordinator coordinator,
                                   CountDownLatch started,
                                   CountDownLatch release) {
            this.coordinator = coordinator;
            this.started = started;
            this.release = release;
        }

        @Override
        public AttemptResult executeAttempt(DownloadTaskContext context,
                                            String searchQuery,
                                            int candidateIndex,
                                            BooleanSupplier cancelledSupplier,
                                            IntConsumer progressConsumer,
                                            Consumer<String> messageConsumer) throws IOException {
            YouTubeRequestCoordinator.Lease lease;
            try {
                lease = coordinator.acquire(ProviderOperation.DOWNLOAD_BULK, cancelledSupplier).lease();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
            started.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException cancellation) {
                    interrupted = true;
                }
            }
            lease.complete(YtDlpFailureKind.UNKNOWN);
            if (interrupted) Thread.currentThread().interrupt();
            return new AttemptResult(true, false, false, -1, null, null, false,
                    YtDlpFailureKind.UNKNOWN, null);
        }
    }

    private static final class ImmediateRetryPolicy extends DownloadRetryPolicy {
        @Override
        public long computeDelayMillis(int attempt) {
            return 0L;
        }
    }

    private static final class FakeRunner extends YtDlpRunner {
        private final File temporaryFile;
        private final YtDlpFailureKind resultKind;
        private final AtomicInteger attempts = new AtomicInteger();

        private FakeRunner(File temporaryFile, YtDlpFailureKind resultKind) {
            this.temporaryFile = temporaryFile;
            this.resultKind = resultKind;
        }

        @Override
        public AttemptResult executeAttempt(DownloadTaskContext context,
                                            String searchQuery,
                                            int candidateIndex,
                                            BooleanSupplier cancelledSupplier,
                                            IntConsumer progressConsumer,
                                            Consumer<String> messageConsumer) {
            attempts.incrementAndGet();
            if (resultKind == YtDlpFailureKind.PROVIDER_FORBIDDEN) {
                return new AttemptResult(false, false, true, 1, null, null, false,
                        resultKind, new ProviderCooldownState(1, System.currentTimeMillis() + 30_000,
                        false, resultKind, 1));
            }
            if (resultKind == YtDlpFailureKind.PROVIDER_RATE_LIMITED
                    || resultKind == YtDlpFailureKind.BOT_CHALLENGE) {
                return new AttemptResult(false, false, true, 1, null, null, false,
                        resultKind, new ProviderCooldownState(1, System.currentTimeMillis() + 30_000,
                        false, resultKind, 1));
            }
            if (resultKind != YtDlpFailureKind.NONE) {
                return new AttemptResult(false, false, true, 1, null,
                        new IOException("simulated " + resultKind), false, resultKind, null);
            }
            return new AttemptResult(false, false, false, 0, temporaryFile, null, false,
                    YtDlpFailureKind.NONE, null);
        }
    }

    private static final class FakeFinalizer extends DownloadFileFinalizer {
        private final File finalFile;
        private final boolean alreadyExists;
        private final AtomicInteger publicationCount = new AtomicInteger();

        private FakeFinalizer(File finalFile, boolean alreadyExists) {
            this.finalFile = finalFile;
            this.alreadyExists = alreadyExists;
        }

        @Override
        public boolean alreadyExists(DownloadTaskContext context, String desiredBase) {
            return alreadyExists;
        }

        @Override
        public File resolveFinalTarget(DownloadTaskContext context, String desiredBase) {
            return finalFile;
        }

        @Override
        public File finalizeDownloadedFile(DownloadTaskContext context,
                                           String desiredBase,
                                           File createdTmp) {
            try {
                Files.write(finalFile.toPath(), new byte[]{1});
                return finalFile;
            } catch (IOException error) {
                return null;
            }
        }

        @Override
        public CompletableFuture<DeezerApiMetaData> prepareMetadataAsync(
                DownloadTaskContext context,
                String desiredBase,
                File finalFile,
                java.util.function.BiConsumer<String, Double> stageReporter) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> publishPreparedDownloadAsync(
                DownloadTaskContext context,
                DeezerApiMetaData metadata,
                File finalFile) {
            publicationCount.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
    }
}
