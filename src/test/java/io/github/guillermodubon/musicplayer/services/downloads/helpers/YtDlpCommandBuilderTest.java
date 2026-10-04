package io.github.guillermodubon.musicplayer.services.downloads.helpers;

import io.github.guillermodubon.musicplayer.services.downloads.helpers.YTDLPApiHelpers.YtDlpCommandBuilder;
import io.github.guillermodubon.musicplayer.services.downloads.preferences.DownloadAudioPreset;
import io.github.guillermodubon.musicplayer.services.downloads.provider.YouTubeExecutionPolicy;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YtDlpCommandBuilderTest {

    @Test
    void bulkPolicyAddsConservativeSpacingAndDoesNotAddAuthenticationOptions() {
        List<String> args = YtDlpCommandBuilder.buildBaseArgs(
                "Artist Song",
                new File("target"),
                1,
                "test-token",
                DownloadAudioPreset.BEST_AVAILABLE,
                true,
                YouTubeExecutionPolicy.defaults()
        );

        assertTrue(args.contains("--ignore-config"));
        assertTrue(args.contains("--no-config-locations"));
        assertTrue(args.contains("--sleep-requests"));
        assertTrue(args.contains("--sleep-interval"));
        assertTrue(args.contains("--max-sleep-interval"));
        assertTrue(args.contains("--concurrent-fragments"));
        assertTrue(args.get(args.indexOf("--concurrent-fragments") + 1).equals("1"));
        assertTrue(args.get(args.indexOf("--retries") + 1).equals("1"));
        assertTrue(args.get(args.indexOf("--fragment-retries") + 1).equals("2"));
        assertTrue(args.get(args.indexOf("--file-access-retries") + 1).equals("2"));
        assertFalse(args.contains("--cookies"));
        assertFalse(args.contains("--cookies-from-browser"));
        assertFalse(args.contains("--proxy"));
    }

    @Test
    void singleDownloadKeepsItsExistingRetryPolicyWithoutBulkSleepFlags() {
        List<String> args = YtDlpCommandBuilder.buildBaseArgs(
                "Artist Song", new File("target"), 1, "test-token",
                DownloadAudioPreset.BEST_AVAILABLE
        );

        assertFalse(args.contains("--sleep-requests"));
        assertFalse(args.contains("--sleep-interval"));
        assertTrue(args.contains("--retries"));
        assertTrue(args.get(args.indexOf("--retries") + 1).equals("3"));
        assertTrue(args.get(args.indexOf("--concurrent-fragments") + 1).equals("1"));
    }
}
