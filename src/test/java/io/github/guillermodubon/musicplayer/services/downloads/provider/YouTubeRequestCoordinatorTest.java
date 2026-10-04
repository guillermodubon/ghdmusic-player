package io.github.guillermodubon.musicplayer.services.downloads.provider;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YouTubeRequestCoordinatorTest {

    private final long[] clock = {1_000L};

    @Test
    void grantsFiveHealthyDownloadLeasesAndNeverAUnboundedSixth() throws Exception {
        YouTubeRequestCoordinator coordinator = coordinator();
        List<YouTubeRequestCoordinator.Lease> leases = acquireDownloads(coordinator, 5);
        assertEquals(5, coordinator.activeOperationCount());
        assertEquals(5, coordinator.recommendedParallelism());

        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch acquired = new CountDownLatch(1);
        AtomicReference<YouTubeRequestCoordinator.Acquisition> sixth = new AtomicReference<>();
        Thread waiter = acquireOnThread(coordinator, ProviderOperation.DOWNLOAD_BULK,
                () -> { waiting.countDown(); return false; }, sixth, acquired);

        assertTrue(waiting.await(1, TimeUnit.SECONDS));
        assertFalse(acquired.await(100, TimeUnit.MILLISECONDS));
        assertEquals(5, coordinator.activeOperationCount());

        leases.get(0).complete(YtDlpFailureKind.NONE);
        assertTrue(acquired.await(1, TimeUnit.SECONDS));
        assertTrue(sixth.get().granted());
        assertEquals(5, coordinator.activeOperationCount());

        leases.subList(1, leases.size()).forEach(lease -> lease.complete(YtDlpFailureKind.NONE));
        sixth.get().lease().complete(YtDlpFailureKind.NONE);
        waiter.join(1_000);
        coordinator.shutdown();
    }

    @Test
    void providerRejectionFromFiveActiveLeasesCreatesOneCooldownEpisode() throws Exception {
        YouTubeRequestCoordinator coordinator = coordinator();
        List<YouTubeRequestCoordinator.Lease> leases = acquireDownloads(coordinator, 5);

        ProviderCooldownState first = leases.get(0).reportFailure(YtDlpFailureKind.PROVIDER_FORBIDDEN);
        for (int index = 1; index < leases.size(); index++) {
            leases.get(index).reportFailure(YtDlpFailureKind.PROVIDER_FORBIDDEN);
        }
        leases.forEach(lease -> lease.complete(YtDlpFailureKind.PROVIDER_FORBIDDEN));

        assertEquals(1, first.rejectionCount());
        assertEquals(1, coordinator.cooldownState().rejectionCount());
        assertEquals(YouTubeRequestCoordinator.Mode.COOLDOWN, coordinator.mode());
        assertEquals(0, coordinator.recommendedParallelism());
        assertEquals(YtDlpFailureKind.PROVIDER_COOLDOWN,
                coordinator.acquire(ProviderOperation.SEARCH, () -> false).failureKind());
        coordinator.shutdown();
    }

    @Test
    void providerRejectionKindsAllGateNewOperations() throws Exception {
        for (YtDlpFailureKind kind : List.of(
                YtDlpFailureKind.PROVIDER_FORBIDDEN,
                YtDlpFailureKind.PROVIDER_RATE_LIMITED,
                YtDlpFailureKind.BOT_CHALLENGE)) {
            YouTubeRequestCoordinator coordinator = coordinator();
            var operation = coordinator.acquire(ProviderOperation.DOWNLOAD_BULK, () -> false);
            operation.lease().reportFailure(kind);
            operation.lease().complete(kind);

            assertEquals(YouTubeRequestCoordinator.Mode.COOLDOWN, coordinator.mode());
            assertEquals(YtDlpFailureKind.PROVIDER_COOLDOWN,
                    coordinator.acquire(ProviderOperation.DOWNLOAD_BULK, () -> false).failureKind());
            coordinator.shutdown();
        }
    }

    @Test
    void recoveryProgressesFromOneToThreeToFiveAfterRequiredSuccesses() throws Exception {
        YouTubeRequestCoordinator coordinator = coordinator();
        enterRecovery(coordinator, YtDlpFailureKind.PROVIDER_FORBIDDEN);
        assertEquals(1, coordinator.recommendedParallelism());

        completeHealthyDownloads(coordinator, 3);
        assertEquals(3, coordinator.recommendedParallelism());
        completeHealthyDownloads(coordinator, 7);
        assertEquals(5, coordinator.recommendedParallelism());
        assertEquals(YouTubeRequestCoordinator.Mode.HEALTHY, coordinator.mode());
        coordinator.shutdown();
    }

    @Test
    void rejectionDuringRecoveryRestartsCooldownAndRecoveryAtOne() throws Exception {
        YouTubeRequestCoordinator coordinator = coordinator();
        enterRecovery(coordinator, YtDlpFailureKind.PROVIDER_RATE_LIMITED);
        completeHealthyDownloads(coordinator, 3);
        assertEquals(3, coordinator.recommendedParallelism());

        var rejection = coordinator.acquire(ProviderOperation.DOWNLOAD_BULK, () -> false);
        rejection.lease().reportFailure(YtDlpFailureKind.BOT_CHALLENGE);
        rejection.lease().complete(YtDlpFailureKind.BOT_CHALLENGE);
        assertEquals(2, coordinator.cooldownState().rejectionCount());
        assertEquals(0, coordinator.recommendedParallelism());

        resumeAfterCooldown(coordinator);
        assertEquals(1, coordinator.recommendedParallelism());
        coordinator.shutdown();
    }

    @Test
    void transientNetworkFailureDoesNotAdvanceRecoveryConcurrency() throws Exception {
        YouTubeRequestCoordinator coordinator = coordinator();
        enterRecovery(coordinator, YtDlpFailureKind.PROVIDER_FORBIDDEN);
        completeHealthyDownloads(coordinator, 1);
        var transientFailure = coordinator.acquire(ProviderOperation.DOWNLOAD_BULK, () -> false);
        transientFailure.lease().complete(YtDlpFailureKind.TRANSIENT_NETWORK);

        completeHealthyDownloads(coordinator, 2);
        assertEquals(1, coordinator.recommendedParallelism());
        completeHealthyDownloads(coordinator, 1);
        assertEquals(3, coordinator.recommendedParallelism());
        coordinator.shutdown();
    }

    @Test
    void downloadWaiterTakesPriorityOverAuxiliaryLookup() throws Exception {
        YouTubeRequestCoordinator coordinator = coordinator();
        List<YouTubeRequestCoordinator.Lease> active = acquireDownloads(coordinator, 5);

        CountDownLatch auxiliaryWaiting = new CountDownLatch(1);
        CountDownLatch downloadWaiting = new CountDownLatch(1);
        CountDownLatch auxiliaryAcquired = new CountDownLatch(1);
        CountDownLatch downloadAcquired = new CountDownLatch(1);
        AtomicReference<YouTubeRequestCoordinator.Acquisition> auxiliary = new AtomicReference<>();
        AtomicReference<YouTubeRequestCoordinator.Acquisition> download = new AtomicReference<>();

        Thread auxiliaryThread = acquireOnThread(coordinator, ProviderOperation.SEARCH,
                () -> { auxiliaryWaiting.countDown(); return false; }, auxiliary, auxiliaryAcquired);
        assertTrue(auxiliaryWaiting.await(1, TimeUnit.SECONDS));
        Thread downloadThread = acquireOnThread(coordinator, ProviderOperation.DOWNLOAD_BULK,
                () -> { downloadWaiting.countDown(); return false; }, download, downloadAcquired);
        assertTrue(downloadWaiting.await(1, TimeUnit.SECONDS));

        active.get(0).complete(YtDlpFailureKind.NONE);
        assertTrue(downloadAcquired.await(1, TimeUnit.SECONDS));
        assertFalse(auxiliaryAcquired.await(100, TimeUnit.MILLISECONDS));
        assertTrue(download.get().granted());

        active.subList(1, active.size()).forEach(lease -> lease.complete(YtDlpFailureKind.NONE));
        download.get().lease().complete(YtDlpFailureKind.NONE);
        assertTrue(auxiliaryAcquired.await(1, TimeUnit.SECONDS));
        auxiliary.get().lease().complete(YtDlpFailureKind.NONE);
        auxiliaryThread.join(1_000);
        downloadThread.join(1_000);
        coordinator.shutdown();
    }

    @Test
    void searchesAndProbesCannotCreateASixthProviderOperation() throws Exception {
        YouTubeRequestCoordinator coordinator = coordinator();
        List<YouTubeRequestCoordinator.Lease> downloads = acquireDownloads(coordinator, 5);
        CountDownLatch searchWaiting = new CountDownLatch(1);
        CountDownLatch probeWaiting = new CountDownLatch(1);
        CountDownLatch searchFinished = new CountDownLatch(1);
        CountDownLatch probeFinished = new CountDownLatch(1);
        AtomicReference<YouTubeRequestCoordinator.Acquisition> search = new AtomicReference<>();
        AtomicReference<YouTubeRequestCoordinator.Acquisition> probe = new AtomicReference<>();

        Thread searchThread = acquireOnThread(coordinator, ProviderOperation.SEARCH,
                () -> { searchWaiting.countDown(); return false; }, search, searchFinished);
        Thread probeThread = acquireOnThread(coordinator, ProviderOperation.FORMAT_PROBE,
                () -> { probeWaiting.countDown(); return false; }, probe, probeFinished);
        assertTrue(searchWaiting.await(1, TimeUnit.SECONDS));
        assertTrue(probeWaiting.await(1, TimeUnit.SECONDS));
        assertFalse(searchFinished.await(100, TimeUnit.MILLISECONDS));
        assertFalse(probeFinished.await(100, TimeUnit.MILLISECONDS));
        assertEquals(5, coordinator.activeOperationCount());

        downloads.get(0).complete(YtDlpFailureKind.NONE);
        assertTrue(searchFinished.await(1, TimeUnit.SECONDS)
                || probeFinished.await(1, TimeUnit.SECONDS));
        assertEquals(5, coordinator.activeOperationCount());
        downloads.subList(1, downloads.size())
                .forEach(lease -> lease.complete(YtDlpFailureKind.NONE));
        if (search.get() != null && search.get().granted()) {
            search.get().lease().complete(YtDlpFailureKind.NONE);
            assertTrue(probeFinished.await(1, TimeUnit.SECONDS));
            if (probe.get().granted()) probe.get().lease().complete(YtDlpFailureKind.NONE);
        } else {
            probe.get().lease().complete(YtDlpFailureKind.NONE);
            assertTrue(searchFinished.await(1, TimeUnit.SECONDS));
            if (search.get().granted()) search.get().lease().complete(YtDlpFailureKind.NONE);
        }
        searchThread.join(1_000);
        probeThread.join(1_000);
        coordinator.shutdown();
    }

    @Test
    void cancellationWhileWaitingForStartSpacingDoesNotHoldAProviderLease() throws Exception {
        YouTubeRequestCoordinator coordinator = coordinator(Duration.ofSeconds(2));
        var first = coordinator.acquire(ProviderOperation.DOWNLOAD_BULK, () -> false);
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<YouTubeRequestCoordinator.Acquisition> result = new AtomicReference<>();
        Thread waiter = acquireOnThread(coordinator, ProviderOperation.DOWNLOAD_BULK,
                () -> { waiting.countDown(); return cancelled.get(); }, result, completed);

        assertTrue(waiting.await(1, TimeUnit.SECONDS));
        assertEquals(1, coordinator.activeOperationCount());
        cancelled.set(true);
        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertTrue(result.get().cancelled());
        assertEquals(1, coordinator.activeOperationCount());

        first.lease().complete(YtDlpFailureKind.NONE);
        waiter.join(1_000);
        coordinator.shutdown();
    }

    @Test
    void shutdownWakesPermitWaitersAndReleasesActiveLeasesWhenTheyExit() throws Exception {
        YouTubeRequestCoordinator coordinator = coordinator();
        List<YouTubeRequestCoordinator.Lease> active = acquireDownloads(coordinator, 5);
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<YouTubeRequestCoordinator.Acquisition> result = new AtomicReference<>();
        Thread waiter = acquireOnThread(coordinator, ProviderOperation.DOWNLOAD_BULK,
                () -> { waiting.countDown(); return false; }, result, completed);
        assertTrue(waiting.await(1, TimeUnit.SECONDS));

        coordinator.shutdown();

        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertEquals(YtDlpFailureKind.COORDINATOR_SHUTDOWN, result.get().failureKind());
        assertEquals(5, coordinator.activeOperationCount());
        active.forEach(lease -> lease.complete(YtDlpFailureKind.NONE));
        assertEquals(0, coordinator.activeOperationCount());
        waiter.join(1_000);
    }

    @Test
    void cooldownEscalatesOncePerDistinctRejectionEpisodeAndManualResumeStartsAtOne() throws Exception {
        YouTubeRequestCoordinator coordinator = coordinator();
        long[] expectedDelays = {30_000L, 120_000L, 300_000L, 900_000L};

        for (int index = 0; index < expectedDelays.length; index++) {
            var rejected = coordinator.acquire(ProviderOperation.DOWNLOAD_BULK, () -> false);
            ProviderCooldownState state = rejected.lease()
                    .reportFailure(YtDlpFailureKind.PROVIDER_RATE_LIMITED);
            rejected.lease().complete(YtDlpFailureKind.PROVIDER_RATE_LIMITED);
            assertEquals(expectedDelays[index], state.resumeAtMillis() - clock[0]);
            resumeAfterCooldown(coordinator);
            assertEquals(1, coordinator.recommendedParallelism());
        }

        var manual = coordinator.acquire(ProviderOperation.DOWNLOAD_BULK, () -> false);
        ProviderCooldownState state = manual.lease()
                .reportFailure(YtDlpFailureKind.PROVIDER_RATE_LIMITED);
        manual.lease().complete(YtDlpFailureKind.PROVIDER_RATE_LIMITED);
        assertTrue(state.manualResumeRequired());
        assertEquals(YtDlpFailureKind.PROVIDER_COOLDOWN,
                coordinator.acquire(ProviderOperation.SEARCH, () -> false).failureKind());

        coordinator.resumeManually();
        assertEquals(1, coordinator.recommendedParallelism());
        coordinator.shutdown();
    }

    @Test
    void cancellationAndShutdownDoNotGrantNewOperations() throws Exception {
        YouTubeRequestCoordinator coordinator = coordinator();
        var cancelled = coordinator.acquire(ProviderOperation.SEARCH, () -> true);
        assertTrue(cancelled.cancelled());
        coordinator.shutdown();
        var shutdown = coordinator.acquire(ProviderOperation.SEARCH, () -> false);
        assertEquals(YtDlpFailureKind.COORDINATOR_SHUTDOWN, shutdown.failureKind());
        coordinator.resumeManually();
        assertEquals(YouTubeRequestCoordinator.Mode.SHUTDOWN, coordinator.mode());
    }

    @Test
    void applicationShutdownDuringCooldownCannotRestartProviderWork() throws Exception {
        YouTubeRequestCoordinator coordinator = coordinator();
        var rejected = coordinator.acquire(ProviderOperation.DOWNLOAD_BULK, () -> false);
        rejected.lease().reportFailure(YtDlpFailureKind.PROVIDER_FORBIDDEN);
        rejected.lease().complete(YtDlpFailureKind.PROVIDER_FORBIDDEN);
        assertTrue(coordinator.cooldownState().isActive(clock[0]));

        coordinator.shutdown();

        assertTrue(coordinator.isShutdown());
        assertEquals(YtDlpFailureKind.COORDINATOR_SHUTDOWN,
                coordinator.acquire(ProviderOperation.DOWNLOAD_BULK, () -> false).failureKind());
        assertEquals(YtDlpFailureKind.COORDINATOR_SHUTDOWN,
                coordinator.acquire(ProviderOperation.SEARCH, () -> false).failureKind());
    }

    @Test
    void cancelledWaiterLeavesWithoutAcquiringAPermit() throws Exception {
        YouTubeRequestCoordinator coordinator = coordinator();
        List<YouTubeRequestCoordinator.Lease> leases = acquireDownloads(coordinator, 5);
        AtomicBoolean cancelled = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<YouTubeRequestCoordinator.Acquisition> result = new AtomicReference<>();
        Thread waiter = acquireOnThread(coordinator, ProviderOperation.DOWNLOAD_BULK,
                () -> { started.countDown(); return cancelled.get(); }, result, completed);

        assertTrue(started.await(1, TimeUnit.SECONDS));
        cancelled.set(true);
        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertTrue(result.get().cancelled());
        leases.forEach(lease -> lease.complete(YtDlpFailureKind.NONE));
        waiter.join(1_000);
        coordinator.shutdown();
    }

    @Test
    void cancellationDuringCooldownAndRecoveryDoesNotAcquireOrAdvanceState() throws Exception {
        YouTubeRequestCoordinator coordinator = coordinator();
        var rejected = coordinator.acquire(ProviderOperation.DOWNLOAD_BULK, () -> false);
        rejected.lease().reportFailure(YtDlpFailureKind.PROVIDER_FORBIDDEN);
        rejected.lease().complete(YtDlpFailureKind.PROVIDER_FORBIDDEN);

        assertTrue(coordinator.acquire(ProviderOperation.DOWNLOAD_BULK, () -> true).cancelled());
        assertEquals(YouTubeRequestCoordinator.Mode.COOLDOWN, coordinator.mode());

        resumeAfterCooldown(coordinator);
        var recoveryLease = coordinator.acquire(ProviderOperation.DOWNLOAD_BULK, () -> false);
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<YouTubeRequestCoordinator.Acquisition> waitingResult = new AtomicReference<>();
        Thread waiter = acquireOnThread(coordinator, ProviderOperation.DOWNLOAD_BULK,
                () -> { waiting.countDown(); return cancelled.get(); }, waitingResult, completed);
        assertTrue(waiting.await(1, TimeUnit.SECONDS));
        cancelled.set(true);
        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertTrue(waitingResult.get().cancelled());
        recoveryLease.lease().complete(YtDlpFailureKind.NONE);
        waiter.join(1_000);

        completeHealthyDownloads(coordinator, 2);
        assertEquals(3, coordinator.recommendedParallelism());
        List<YouTubeRequestCoordinator.Lease> intermediateLeases = acquireDownloads(coordinator, 3);
        CountDownLatch intermediateWaiting = new CountDownLatch(1);
        CountDownLatch intermediateCompleted = new CountDownLatch(1);
        AtomicBoolean intermediateCancelled = new AtomicBoolean();
        AtomicReference<YouTubeRequestCoordinator.Acquisition> intermediateResult = new AtomicReference<>();
        Thread intermediateWaiter = acquireOnThread(coordinator, ProviderOperation.DOWNLOAD_BULK,
                () -> { intermediateWaiting.countDown(); return intermediateCancelled.get(); },
                intermediateResult, intermediateCompleted);
        assertTrue(intermediateWaiting.await(1, TimeUnit.SECONDS));
        intermediateCancelled.set(true);
        assertTrue(intermediateCompleted.await(1, TimeUnit.SECONDS));
        assertTrue(intermediateResult.get().cancelled());
        intermediateLeases.forEach(lease -> lease.complete(YtDlpFailureKind.NONE));
        intermediateWaiter.join(1_000);
        coordinator.shutdown();
    }

    @Test
    void honorsLongerProviderRetryAfterValue() throws Exception {
        YouTubeRequestCoordinator coordinator = coordinator();
        var acquisition = coordinator.acquire(ProviderOperation.DOWNLOAD_SINGLE, () -> false);
        acquisition.lease().reportFailure(YtDlpFailureKind.PROVIDER_RATE_LIMITED);
        var extended = acquisition.lease().reportRetryAfter(Duration.ofSeconds(90));
        assertEquals(91_000L, extended.resumeAtMillis());
        acquisition.lease().complete(YtDlpFailureKind.PROVIDER_RATE_LIMITED);
        coordinator.shutdown();
    }

    private List<YouTubeRequestCoordinator.Lease> acquireDownloads(
            YouTubeRequestCoordinator coordinator,
            int count
    ) throws InterruptedException {
        List<YouTubeRequestCoordinator.Lease> leases = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            var result = coordinator.acquire(ProviderOperation.DOWNLOAD_BULK, () -> false);
            assertTrue(result.granted());
            leases.add(result.lease());
        }
        return leases;
    }

    private void completeHealthyDownloads(YouTubeRequestCoordinator coordinator, int count)
            throws InterruptedException {
        for (int index = 0; index < count; index++) {
            var result = coordinator.acquire(ProviderOperation.DOWNLOAD_BULK, () -> false);
            assertTrue(result.granted());
            result.lease().complete(YtDlpFailureKind.NONE);
        }
    }

    private void enterRecovery(YouTubeRequestCoordinator coordinator, YtDlpFailureKind kind)
            throws InterruptedException {
        var result = coordinator.acquire(ProviderOperation.DOWNLOAD_BULK, () -> false);
        result.lease().reportFailure(kind);
        ProviderCooldownState state = result.lease().cooldownState();
        result.lease().complete(kind);
        clock[0] = state.resumeAtMillis();
        assertEquals(YouTubeRequestCoordinator.Mode.RECOVERING, coordinator.mode());
    }

    private void resumeAfterCooldown(YouTubeRequestCoordinator coordinator) {
        clock[0] = coordinator.cooldownState().resumeAtMillis();
        coordinator.mode();
    }

    private Thread acquireOnThread(
            YouTubeRequestCoordinator coordinator,
            ProviderOperation operation,
            BooleanSupplier cancelled,
            AtomicReference<YouTubeRequestCoordinator.Acquisition> result,
            CountDownLatch completed
    ) {
        Thread thread = new Thread(() -> {
            try {
                result.set(coordinator.acquire(operation, cancelled));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                completed.countDown();
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private YouTubeRequestCoordinator coordinator() {
        return coordinator(Duration.ZERO);
    }

    private YouTubeRequestCoordinator coordinator(Duration bulkSpacing) {
        clock[0] = 1_000L;
        return new YouTubeRequestCoordinator(
                new YouTubeExecutionPolicy(
                        5, 1, 3, 3, 10,
                        Duration.ZERO, bulkSpacing, bulkSpacing,
                        1, 2, 5, 1, 2, 2,
                        1, 2, 5, 8, 1, 32, 200,
                        List.of(Duration.ofSeconds(30), Duration.ofMinutes(2),
                                Duration.ofMinutes(5), Duration.ofMinutes(15)),
                        Duration.ZERO
                ),
                () -> clock[0],
                () -> TimeUnit.MILLISECONDS.toNanos(clock[0]),
                ignored -> 0
        );
    }
}
