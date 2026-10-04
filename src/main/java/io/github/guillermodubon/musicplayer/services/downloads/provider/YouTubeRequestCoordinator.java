package io.github.guillermodubon.musicplayer.services.downloads.provider;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;

public final class YouTubeRequestCoordinator {

    public enum Mode {
        HEALTHY,
        COOLDOWN,
        RECOVERING,
        MANUAL_PAUSE,
        SHUTDOWN
    }

    private static final YouTubeRequestCoordinator INSTANCE =
            new YouTubeRequestCoordinator(YouTubeExecutionPolicy.defaults());

    private final Object monitor = new Object();
    private final YouTubeExecutionPolicy policy;
    private final LongSupplier clockMillis;
    private final LongSupplier monotonicClockNanos;
    private final LongUnaryOperator jitterMillis;
    private final YouTubeStartSpacing startSpacing = new YouTubeStartSpacing();

    private int activeOperations;
    private int activeDownloadOperations;
    private int activeAuxiliaryOperations;
    private int activeBulkOperations;
    private int waitingDownloads;
    private int waitingBulkDownloads;
    private int recoverySuccesses;
    private int providerRejections;
    private long generation;
    private long rejectionEpoch;
    private boolean shutdown;
    private YtDlpFailureKind lastProviderFailure = YtDlpFailureKind.NONE;
    private long resumeAtMillis;
    private Mode mode = Mode.HEALTHY;

    public YouTubeRequestCoordinator(YouTubeExecutionPolicy policy) {
        this(policy, System::currentTimeMillis, System::nanoTime,
                maximum -> maximum <= 0 ? 0 : ThreadLocalRandom.current().nextLong(maximum + 1));
    }

    public YouTubeRequestCoordinator(
            YouTubeExecutionPolicy policy,
            LongSupplier clockMillis,
            LongUnaryOperator jitterMillis
    ) {
        this(policy, clockMillis, System::nanoTime, jitterMillis);
    }

    public YouTubeRequestCoordinator(
            YouTubeExecutionPolicy policy,
            LongSupplier clockMillis,
            LongSupplier monotonicClockNanos,
            LongUnaryOperator jitterMillis
    ) {
        this.policy = Objects.requireNonNull(policy);
        this.clockMillis = Objects.requireNonNull(clockMillis);
        this.monotonicClockNanos = Objects.requireNonNull(monotonicClockNanos);
        this.jitterMillis = Objects.requireNonNull(jitterMillis);
    }

    public static YouTubeRequestCoordinator getInstance() {
        return INSTANCE;
    }

    public YouTubeExecutionPolicy policy() {
        return policy;
    }

    public Acquisition acquire(ProviderOperation operation, BooleanSupplier cancelled)
            throws InterruptedException {
        ProviderOperation requested = operation == null ? ProviderOperation.SEARCH : operation;
        boolean countedAsWaitingDownload = false;
        boolean countedAsWaitingBulkDownload = false;
        try {
            synchronized (monitor) {
                if (requested.isDownload()) {
                    waitingDownloads++;
                    countedAsWaitingDownload = true;
                }
                if (requested.isBulk()) {
                    waitingBulkDownloads++;
                    countedAsWaitingBulkDownload = true;
                }

                while (true) {
                    if (isCancelled(cancelled)) {
                        return Acquisition.cancelledResult();
                    }
                    if (shutdown) {
                        return Acquisition.rejected(
                                YtDlpFailureKind.COORDINATOR_SHUTDOWN,
                                cooldownStateUnsafe()
                        );
                    }

                    refreshModeUnsafe();
                    if (mode == Mode.COOLDOWN || mode == Mode.MANUAL_PAUSE) {
                        return Acquisition.rejected(
                                YtDlpFailureKind.PROVIDER_COOLDOWN,
                                cooldownStateUnsafe()
                        );
                    }

                    int concurrencyLimit = concurrencyLimitUnsafe();
                    if (requested.isDownload()) {
                        if (activeDownloadOperations >= concurrencyLimit) {
                            monitor.wait(200L);
                            continue;
                        }
                    } else if (waitingDownloads > 0
                            || activeAuxiliaryOperations >= policy.maximumConcurrentAuxiliaryOperations()) {
                        monitor.wait(200L);
                        continue;
                    }

                    if (activeOperations >= concurrencyLimit) {
                        monitor.wait(200L);
                        continue;
                    }

                    long nowNanos = monotonicClockNanos.getAsLong();
                    long spacingRemaining = startSpacing.remainingNanos(nowNanos);
                    if (spacingRemaining > 0) {
                        long waitMillis = Math.max(
                                1L,
                                Math.min(200L, (spacingRemaining + 999_999L) / 1_000_000L)
                        );
                        monitor.wait(waitMillis);
                        continue;
                    }

                    activeOperations++;
                    if (requested.isDownload()) {
                        activeDownloadOperations++;
                    } else {
                        activeAuxiliaryOperations++;
                    }
                    if (requested.isBulk()) {
                        activeBulkOperations++;
                    }
                    startSpacing.reserveStartNanos(nowNanos, spacingNanos(requested));
                    return Acquisition.granted(new Lease(this, requested, rejectionEpoch));
                }
            }
        } finally {
            if (countedAsWaitingDownload || countedAsWaitingBulkDownload) {
                synchronized (monitor) {
                    if (countedAsWaitingDownload) {
                        waitingDownloads = Math.max(0, waitingDownloads - 1);
                    }
                    if (countedAsWaitingBulkDownload) {
                        waitingBulkDownloads = Math.max(0, waitingBulkDownloads - 1);
                    }
                    monitor.notifyAll();
                }
            }
        }
    }

    public int recommendedParallelism() {
        synchronized (monitor) {
            refreshModeUnsafe();
            return concurrencyLimitUnsafe();
        }
    }

    public int activeOperationCount() {
        synchronized (monitor) {
            return activeOperations;
        }
    }

    public Mode mode() {
        synchronized (monitor) {
            refreshModeUnsafe();
            return mode;
        }
    }

    public ProviderCooldownState cooldownState() {
        synchronized (monitor) {
            refreshModeUnsafe();
            return cooldownStateUnsafe();
        }
    }

    public ProviderCooldownState resumeManually() {
        synchronized (monitor) {
            if (shutdown) return cooldownStateUnsafe();
            mode = Mode.RECOVERING;
            resumeAtMillis = 0L;
            recoverySuccesses = 0;
            rejectionEpoch++;
            generation++;
            startSpacing.reset();
            monitor.notifyAll();
            return cooldownStateUnsafe();
        }
    }

    public void shutdown() {
        synchronized (monitor) {
            shutdown = true;
            mode = Mode.SHUTDOWN;
            generation++;
            monitor.notifyAll();
        }
    }

    public boolean isShutdown() {
        synchronized (monitor) {
            return shutdown;
        }
    }

    private ProviderCooldownState recordFailure(YtDlpFailureKind kind, long leaseEpoch) {
        synchronized (monitor) {
            refreshModeUnsafe();
            if (!kind.isProviderRejection()
                    || shutdown
                    || leaseEpoch != rejectionEpoch
                    || (mode != Mode.HEALTHY && mode != Mode.RECOVERING)) {
                return cooldownStateUnsafe();
            }

            providerRejections++;
            recoverySuccesses = 0;
            lastProviderFailure = kind;
            rejectionEpoch++;
            generation++;

            if (providerRejections > policy.providerCooldowns().size()) {
                mode = Mode.MANUAL_PAUSE;
                resumeAtMillis = 0L;
            } else {
                Duration cooldown = policy.providerCooldowns().get(providerRejections - 1);
                long baseDelay = cooldown.toMillis();
                long jitterMax = policy.cooldownJitterMaximum().toMillis();
                long jitter = Math.max(0L, Math.min(
                        jitterMax,
                        jitterMillis.applyAsLong(jitterMax)
                ));
                resumeAtMillis = safeAdd(clockMillis.getAsLong(), safeAdd(baseDelay, jitter));
                mode = Mode.COOLDOWN;
            }

            monitor.notifyAll();
            return cooldownStateUnsafe();
        }
    }

    private ProviderCooldownState extendCooldown(Duration retryAfter) {
        synchronized (monitor) {
            if (shutdown || mode == Mode.MANUAL_PAUSE || retryAfter == null || retryAfter.isNegative()) {
                return cooldownStateUnsafe();
            }
            long retryAfterMillis;
            try {
                retryAfterMillis = retryAfter.toMillis();
            } catch (ArithmeticException overflow) {
                retryAfterMillis = Long.MAX_VALUE;
            }
            long requestedResumeAt = safeAdd(clockMillis.getAsLong(), retryAfterMillis);
            if (requestedResumeAt > resumeAtMillis) {
                resumeAtMillis = requestedResumeAt;
                mode = Mode.COOLDOWN;
                generation++;
                monitor.notifyAll();
            }
            return cooldownStateUnsafe();
        }
    }

    private ProviderCooldownState complete(ProviderOperation operation, long leaseEpoch,
                                           YtDlpFailureKind kind) {
        synchronized (monitor) {
            activeOperations = Math.max(0, activeOperations - 1);
            if (operation.isDownload()) {
                activeDownloadOperations = Math.max(0, activeDownloadOperations - 1);
            } else {
                activeAuxiliaryOperations = Math.max(0, activeAuxiliaryOperations - 1);
            }
            if (operation.isBulk()) {
                activeBulkOperations = Math.max(0, activeBulkOperations - 1);
            }

            if (kind == YtDlpFailureKind.NONE
                    && operation.isDownload()
                    && mode == Mode.RECOVERING
                    && leaseEpoch == rejectionEpoch) {
                recoverySuccesses++;
                if (recoverySuccesses >= policy.successesToRestoreHealthy()) {
                    mode = Mode.HEALTHY;
                    providerRejections = 0;
                    lastProviderFailure = YtDlpFailureKind.NONE;
                    resumeAtMillis = 0L;
                    recoverySuccesses = 0;
                    rejectionEpoch++;
                    generation++;
                }
            } else if (operation.isDownload()
                    && kind != YtDlpFailureKind.NONE
                    && !kind.isProviderRejection()
                    && mode == Mode.RECOVERING
                    && leaseEpoch == rejectionEpoch) {
                recoverySuccesses = 0;
            }

            refreshModeUnsafe();
            monitor.notifyAll();
            return cooldownStateUnsafe();
        }
    }

    private void refreshModeUnsafe() {
        if (shutdown) {
            mode = Mode.SHUTDOWN;
            return;
        }
        if (mode == Mode.COOLDOWN && clockMillis.getAsLong() >= resumeAtMillis) {
            mode = Mode.RECOVERING;
            recoverySuccesses = 0;
            generation++;
            monitor.notifyAll();
        }
    }

    private int concurrencyLimitUnsafe() {
        return switch (mode) {
            case HEALTHY -> policy.healthyDownloadConcurrency();
            case RECOVERING -> recoverySuccesses >= policy.successesToReachIntermediate()
                    ? policy.recoveryIntermediateConcurrency()
                    : policy.recoveryInitialConcurrency();
            case COOLDOWN, MANUAL_PAUSE, SHUTDOWN -> 0;
        };
    }

    private ProviderCooldownState cooldownStateUnsafe() {
        return new ProviderCooldownState(
                providerRejections,
                resumeAtMillis,
                mode == Mode.MANUAL_PAUSE,
                lastProviderFailure,
                generation
        );
    }

    private long spacingNanos(ProviderOperation operation) {
        boolean bulkPressure = operation.isBulk()
                || waitingBulkDownloads > 0
                || activeBulkOperations > 0;
        Duration minimum = bulkPressure
                ? policy.bulkStartSpacingMinimum()
                : policy.singleStartSpacing();
        Duration maximum = bulkPressure
                ? policy.bulkStartSpacingMaximum()
                : minimum;
        long minMillis = minimum.toMillis();
        long maxMillis = Math.max(minMillis, maximum.toMillis());
        long range = maxMillis - minMillis;
        long randomOffset = range <= 0 ? 0 : ThreadLocalRandom.current().nextLong(range + 1);
        return Duration.ofMillis(minMillis + randomOffset).toNanos();
    }

    private boolean isCancelled(BooleanSupplier cancelled) {
        return Thread.currentThread().isInterrupted()
                || (cancelled != null && cancelled.getAsBoolean());
    }

    private long safeAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        if (right < 0 && left < Long.MIN_VALUE - right) return Long.MIN_VALUE;
        return left + right;
    }

    public static final class Acquisition {
        private final Lease lease;
        private final YtDlpFailureKind failureKind;
        private final ProviderCooldownState cooldownState;
        private final boolean cancelled;

        private Acquisition(Lease lease, YtDlpFailureKind failureKind,
                            ProviderCooldownState cooldownState, boolean cancelled) {
            this.lease = lease;
            this.failureKind = failureKind;
            this.cooldownState = cooldownState;
            this.cancelled = cancelled;
        }

        private static Acquisition granted(Lease lease) {
            return new Acquisition(lease, YtDlpFailureKind.NONE, null, false);
        }

        private static Acquisition rejected(YtDlpFailureKind kind, ProviderCooldownState state) {
            return new Acquisition(null, kind, state, false);
        }

        private static Acquisition cancelledResult() {
            return new Acquisition(null, YtDlpFailureKind.NONE, null, true);
        }

        public Lease lease() { return lease; }
        public YtDlpFailureKind failureKind() { return failureKind; }
        public ProviderCooldownState cooldownState() { return cooldownState; }
        public boolean cancelled() { return cancelled; }
        public boolean granted() { return lease != null; }
    }

    public static final class Lease implements AutoCloseable {
        private final YouTubeRequestCoordinator coordinator;
        private final ProviderOperation operation;
        private final long rejectionEpoch;
        private final AtomicBoolean closed = new AtomicBoolean();
        private YtDlpFailureKind failureKind = YtDlpFailureKind.NONE;
        private boolean providerFailureRecorded;
        private ProviderCooldownState cooldownState;

        private Lease(YouTubeRequestCoordinator coordinator,
                      ProviderOperation operation,
                      long rejectionEpoch) {
            this.coordinator = coordinator;
            this.operation = operation;
            this.rejectionEpoch = rejectionEpoch;
        }

        public ProviderCooldownState reportFailure(YtDlpFailureKind kind) {
            if (kind == null || !kind.isProviderRejection()) return coordinator.cooldownState();
            failureKind = kind;
            if (!providerFailureRecorded) {
                cooldownState = coordinator.recordFailure(kind, rejectionEpoch);
                providerFailureRecorded = true;
            }
            return cooldownState;
        }

        public ProviderCooldownState reportRetryAfter(Duration retryAfter) {
            if (retryAfter == null || retryAfter.isZero() || retryAfter.isNegative()) {
                return cooldownState();
            }
            cooldownState = coordinator.extendCooldown(retryAfter);
            return cooldownState;
        }

        public ProviderCooldownState cooldownState() {
            return cooldownState;
        }

        public void complete(YtDlpFailureKind kind) {
            if (!closed.compareAndSet(false, true)) return;
            YtDlpFailureKind result = kind == null ? failureKind : kind;
            if (result.isProviderRejection() && !providerFailureRecorded) {
                cooldownState = coordinator.recordFailure(result, rejectionEpoch);
                providerFailureRecorded = true;
            }
            coordinator.complete(operation, rejectionEpoch, result);
        }

        @Override
        public void close() {
            complete(failureKind == YtDlpFailureKind.NONE
                    ? YtDlpFailureKind.UNKNOWN
                    : failureKind);
        }
    }
}
