package io.github.guillermodubon.musicplayer.services.downloads.provider;

import java.time.Duration;
import java.util.List;

public record YouTubeExecutionPolicy(
        int healthyDownloadConcurrency,
        int recoveryInitialConcurrency,
        int recoveryIntermediateConcurrency,
        int successesToReachIntermediate,
        int successesToRestoreHealthy,
        Duration singleStartSpacing,
        Duration bulkStartSpacingMinimum,
        Duration bulkStartSpacingMaximum,
        int requestSleepSeconds,
        int bulkDownloadSleepMinimumSeconds,
        int bulkDownloadSleepMaximumSeconds,
        int bulkHttpRetries,
        int bulkFragmentRetries,
        int bulkFileAccessRetries,
        int maximumConcurrentFragments,
        int maximumTransientRetries,
        int minimumDownloadWorkers,
        int maximumDownloadWorkers,
        int maximumConcurrentAuxiliaryOperations,
        int maximumQueuedAuxiliaryLookups,
        int completedBulkSuccessHistoryLimit,
        List<Duration> providerCooldowns,
        Duration cooldownJitterMaximum
) {
    private static final int ABSOLUTE_DOWNLOAD_CONCURRENCY_LIMIT = 5;
    private static final int ABSOLUTE_WORKER_LIMIT = 8;
    private static final int DOWNLOAD_QUEUE_SLOTS_PER_WORKER = 4;
    private static final List<Duration> DEFAULT_COOLDOWNS = List.of(
            Duration.ofSeconds(30),
            Duration.ofMinutes(2),
            Duration.ofMinutes(5),
            Duration.ofMinutes(15)
    );

    public YouTubeExecutionPolicy {
        healthyDownloadConcurrency = clamp(
                healthyDownloadConcurrency, 1, ABSOLUTE_DOWNLOAD_CONCURRENCY_LIMIT);
        recoveryInitialConcurrency = clamp(
                recoveryInitialConcurrency, 1, healthyDownloadConcurrency);
        recoveryIntermediateConcurrency = clamp(
                recoveryIntermediateConcurrency,
                recoveryInitialConcurrency,
                healthyDownloadConcurrency
        );
        successesToReachIntermediate = Math.max(1, successesToReachIntermediate);
        successesToRestoreHealthy = Math.max(
                successesToReachIntermediate,
                successesToRestoreHealthy
        );
        singleStartSpacing = positiveOrZero(singleStartSpacing);
        bulkStartSpacingMinimum = positiveOrZero(bulkStartSpacingMinimum);
        bulkStartSpacingMaximum = positiveOrZero(bulkStartSpacingMaximum);
        if (bulkStartSpacingMaximum.compareTo(bulkStartSpacingMinimum) < 0) {
            bulkStartSpacingMaximum = bulkStartSpacingMinimum;
        }
        requestSleepSeconds = Math.max(0, requestSleepSeconds);
        bulkDownloadSleepMinimumSeconds = Math.max(0, bulkDownloadSleepMinimumSeconds);
        bulkDownloadSleepMaximumSeconds = Math.max(
                bulkDownloadSleepMinimumSeconds,
                bulkDownloadSleepMaximumSeconds
        );
        bulkHttpRetries = Math.max(0, bulkHttpRetries);
        bulkFragmentRetries = Math.max(0, bulkFragmentRetries);
        bulkFileAccessRetries = Math.max(0, bulkFileAccessRetries);
        maximumConcurrentFragments = 1;
        maximumTransientRetries = Math.max(0, maximumTransientRetries);
        minimumDownloadWorkers = clamp(minimumDownloadWorkers, 1, ABSOLUTE_WORKER_LIMIT);
        maximumDownloadWorkers = clamp(
                maximumDownloadWorkers,
                minimumDownloadWorkers,
                ABSOLUTE_WORKER_LIMIT
        );
        maximumConcurrentAuxiliaryOperations = clamp(
                maximumConcurrentAuxiliaryOperations, 1, healthyDownloadConcurrency);
        maximumQueuedAuxiliaryLookups = clamp(maximumQueuedAuxiliaryLookups, 1, 256);
        completedBulkSuccessHistoryLimit = Math.max(1, completedBulkSuccessHistoryLimit);
        providerCooldowns = providerCooldowns == null || providerCooldowns.isEmpty()
                ? DEFAULT_COOLDOWNS
                : providerCooldowns.stream().map(YouTubeExecutionPolicy::positiveOrZero).toList();
        cooldownJitterMaximum = positiveOrZero(cooldownJitterMaximum);
    }

    /** Keeps the original constructor available for existing integrations. */
    public YouTubeExecutionPolicy(
            int maximumParallelOperations,
            int successesToIncreaseParallelism,
            int successesToResetProviderFailures,
            Duration singleStartSpacing,
            Duration bulkStartSpacingMinimum,
            Duration bulkStartSpacingMaximum,
            int requestSleepSeconds,
            int bulkDownloadSleepMinimumSeconds,
            int bulkDownloadSleepMaximumSeconds,
            int bulkHttpRetries,
            int bulkFragmentRetries,
            int bulkFileAccessRetries,
            int maximumConcurrentFragments,
            int maximumTransientRetries,
            List<Duration> providerCooldowns,
            Duration cooldownJitterMaximum
    ) {
        this(
                maximumParallelOperations,
                1,
                Math.min(3, Math.max(1, maximumParallelOperations)),
                successesToIncreaseParallelism,
                successesToResetProviderFailures,
                singleStartSpacing,
                bulkStartSpacingMinimum,
                bulkStartSpacingMaximum,
                requestSleepSeconds,
                bulkDownloadSleepMinimumSeconds,
                bulkDownloadSleepMaximumSeconds,
                bulkHttpRetries,
                bulkFragmentRetries,
                bulkFileAccessRetries,
                maximumConcurrentFragments,
                maximumTransientRetries,
                5,
                8,
                1,
                32,
                200,
                providerCooldowns,
                cooldownJitterMaximum
        );
    }

    public static YouTubeExecutionPolicy defaults() {
        return new YouTubeExecutionPolicy(
                5,
                1,
                3,
                3,
                10,
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                1,
                2,
                5,
                1,
                2,
                2,
                1,
                2,
                5,
                8,
                1,
                32,
                200,
                DEFAULT_COOLDOWNS,
                Duration.ofSeconds(5)
        );
    }

    public int maximumParallelOperations() {
        return healthyDownloadConcurrency;
    }

    public int successesToIncreaseParallelism() {
        return successesToReachIntermediate;
    }

    public int successesToResetProviderFailures() {
        return successesToRestoreHealthy;
    }

    public int initialParallelOperations() {
        return recoveryInitialConcurrency;
    }

    public int downloadWorkerCount(int availableProcessors) {
        return clamp(availableProcessors, minimumDownloadWorkers, maximumDownloadWorkers);
    }

    public int maximumQueuedDownloadTasks() {
        return maximumDownloadWorkers * DOWNLOAD_QUEUE_SLOTS_PER_WORKER;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static Duration positiveOrZero(Duration duration) {
        return duration == null || duration.isNegative() ? Duration.ZERO : duration;
    }
}
