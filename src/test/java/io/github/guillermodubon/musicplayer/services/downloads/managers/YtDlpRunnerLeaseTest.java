package io.github.guillermodubon.musicplayer.services.downloads.managers;

import io.github.guillermodubon.musicplayer.services.downloads.context.DownloadTaskContext;
import io.github.guillermodubon.musicplayer.services.downloads.provider.YouTubeExecutionPolicy;
import io.github.guillermodubon.musicplayer.services.downloads.provider.YouTubeRequestCoordinator;
import io.github.guillermodubon.musicplayer.services.downloads.provider.YtDlpFailureClassifier;
import io.github.guillermodubon.musicplayer.services.downloads.provider.YtDlpFailureKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class YtDlpRunnerLeaseTest {

    @Test
    void processStartFailureReleasesItsProviderLease(@TempDir Path directory) throws Exception {
        YouTubeRequestCoordinator coordinator = coordinator();
        YtDlpRunner runner = new YtDlpRunner(
                coordinator,
                new YtDlpFailureClassifier(),
                (arguments, workingDirectory) -> {
                    assertEquals(1, coordinator.activeOperationCount());
                    throw new IOException("simulated process-start failure");
                },
                () -> { }
        );

        YtDlpRunner.AttemptResult result = runner.executeAttempt(
                context(directory), "artist song", 1, 1,
                () -> false, ignored -> { }, ignored -> { }
        );

        assertEquals(YtDlpFailureKind.PROCESS_START_FAILURE, result.getFailureKind());
        assertEquals(0, coordinator.activeOperationCount());
        coordinator.shutdown();
    }

    @Test
    void unexpectedRunnerExitAlsoReleasesItsProviderLease(@TempDir Path directory) {
        YouTubeRequestCoordinator coordinator = coordinator();
        YtDlpRunner runner = new YtDlpRunner(
                coordinator,
                new YtDlpFailureClassifier(),
                (arguments, workingDirectory) -> {
                    throw new IllegalStateException("simulated unexpected start error");
                },
                () -> { }
        );

        assertThrows(IllegalStateException.class, () -> runner.executeAttempt(
                context(directory), "artist song", 1, 1,
                () -> false, ignored -> { }, ignored -> { }
        ));

        assertEquals(0, coordinator.activeOperationCount());
        coordinator.shutdown();
    }

    private DownloadTaskContext context(Path directory) {
        return new DownloadTaskContext("artist song", directory.toFile(), "song");
    }

    private YouTubeRequestCoordinator coordinator() {
        return new YouTubeRequestCoordinator(
                YouTubeExecutionPolicy.defaults(),
                System::currentTimeMillis,
                () -> System.nanoTime(),
                ignored -> 0
        );
    }
}
