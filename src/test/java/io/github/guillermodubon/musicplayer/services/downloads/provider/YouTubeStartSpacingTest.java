package io.github.guillermodubon.musicplayer.services.downloads.provider;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class YouTubeStartSpacingTest {

    @Test
    void reservesBulkStartsAtTwoSecondIntervalsWithoutSleeping() {
        YouTubeStartSpacing spacing = new YouTubeStartSpacing();
        long interval = 2_000_000_000L;

        assertEquals(0L, spacing.remainingNanos(0L));
        assertEquals(0L, spacing.reserveStartNanos(0L, interval));
        assertEquals(interval, spacing.remainingNanos(0L));
        assertEquals(interval, spacing.reserveStartNanos(interval, interval));
        assertEquals(0L, spacing.remainingNanos(interval * 2));
        assertEquals(interval * 2, spacing.reserveStartNanos(interval * 2, interval));
    }

    @Test
    void fillsFiveStartSlotsAcrossEightSecondsAtTheConfiguredInterval() {
        YouTubeStartSpacing spacing = new YouTubeStartSpacing();
        long interval = 2_000_000_000L;
        List<Long> starts = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            starts.add(spacing.reserveStartNanos(0L, interval));
        }

        assertEquals(List.of(0L, interval, interval * 2, interval * 3, interval * 4), starts);
        assertEquals(interval * 4, starts.get(starts.size() - 1));
    }

    @Test
    void supportsMonotonicClocksWithNegativeOriginsAndReset() {
        YouTubeStartSpacing spacing = new YouTubeStartSpacing();
        assertEquals(-5L, spacing.reserveStartNanos(-5L, 10L));
        assertEquals(10L, spacing.remainingNanos(-5L));

        spacing.reset();
        assertEquals(0L, spacing.remainingNanos(-5L));
        assertEquals(-5L, spacing.reserveStartNanos(-5L, 0L));
    }
}
