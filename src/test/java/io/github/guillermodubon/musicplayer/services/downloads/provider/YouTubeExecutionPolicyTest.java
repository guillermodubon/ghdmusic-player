package io.github.guillermodubon.musicplayer.services.downloads.provider;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class YouTubeExecutionPolicyTest {

    @Test
    void defaultsEncodeFiveDownloadsAndOneFragmentPerProcess() {
        YouTubeExecutionPolicy policy = YouTubeExecutionPolicy.defaults();

        assertEquals(5, policy.healthyDownloadConcurrency());
        assertEquals(5, policy.maximumParallelOperations());
        assertEquals(1, policy.recoveryInitialConcurrency());
        assertEquals(3, policy.recoveryIntermediateConcurrency());
        assertEquals(3, policy.successesToReachIntermediate());
        assertEquals(10, policy.successesToRestoreHealthy());
        assertEquals(Duration.ofSeconds(2), policy.bulkStartSpacingMinimum());
        assertEquals(Duration.ofSeconds(2), policy.bulkStartSpacingMaximum());
        assertEquals(1, policy.maximumConcurrentFragments());
        assertEquals(1, policy.requestSleepSeconds());
        assertEquals(2, policy.bulkDownloadSleepMinimumSeconds());
        assertEquals(5, policy.bulkDownloadSleepMaximumSeconds());
        assertEquals(1, policy.bulkHttpRetries());
        assertEquals(2, policy.bulkFragmentRetries());
        assertEquals(2, policy.bulkFileAccessRetries());
        assertEquals(1, policy.maximumConcurrentAuxiliaryOperations());
        assertEquals(32, policy.maximumQueuedAuxiliaryLookups());
        assertEquals(List.of(Duration.ofSeconds(30), Duration.ofMinutes(2),
                Duration.ofMinutes(5), Duration.ofMinutes(15)), policy.providerCooldowns());
        assertEquals(Duration.ofSeconds(5), policy.cooldownJitterMaximum());
        assertEquals(5, policy.minimumDownloadWorkers());
        assertEquals(8, policy.maximumDownloadWorkers());
        assertEquals(32, policy.maximumQueuedDownloadTasks());
    }

    @Test
    void clampsDownloadAndWorkerLimitsToTheirIndependentMaximums() {
        YouTubeExecutionPolicy defaults = YouTubeExecutionPolicy.defaults();
        YouTubeExecutionPolicy invalid = new YouTubeExecutionPolicy(
                20, 0, 50, 0, 0,
                Duration.ZERO, Duration.ZERO, Duration.ZERO,
                1, 2, 5, 1, 2, 2,
                12, 2, 5, 8, 0, 300, 0,
                defaults.providerCooldowns(), Duration.ZERO
        );

        assertEquals(5, invalid.healthyDownloadConcurrency());
        assertEquals(1, invalid.recoveryInitialConcurrency());
        assertEquals(5, invalid.recoveryIntermediateConcurrency());
        assertEquals(1, invalid.successesToReachIntermediate());
        assertEquals(1, invalid.successesToRestoreHealthy());
        assertEquals(1, invalid.maximumConcurrentFragments());
        assertEquals(1, invalid.maximumConcurrentAuxiliaryOperations());
        assertEquals(256, invalid.maximumQueuedAuxiliaryLookups());
        assertEquals(1, invalid.completedBulkSuccessHistoryLimit());
        assertEquals(5, invalid.downloadWorkerCount(1));
        assertEquals(5, invalid.downloadWorkerCount(5));
        assertEquals(8, invalid.downloadWorkerCount(64));
    }

    @Test
    void keepsThePreviousPolicyConstructorCompatible() {
        YouTubeExecutionPolicy policy = new YouTubeExecutionPolicy(
                5, 3, 10,
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(2),
                1, 2, 5, 1, 2, 2, 4, 2,
                YouTubeExecutionPolicy.defaults().providerCooldowns(), Duration.ofSeconds(5)
        );

        assertEquals(5, policy.healthyDownloadConcurrency());
        assertEquals(1, policy.recoveryInitialConcurrency());
        assertEquals(3, policy.recoveryIntermediateConcurrency());
        assertEquals(1, policy.maximumConcurrentFragments());
        assertEquals(32, policy.maximumQueuedAuxiliaryLookups());
    }
}
