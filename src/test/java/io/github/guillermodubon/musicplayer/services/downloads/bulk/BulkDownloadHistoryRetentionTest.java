package io.github.guillermodubon.musicplayer.services.downloads.bulk;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkDownloadHistoryRetentionTest {

    @Test
    void tenThousandSuccessfulRowsRetainOnlyTheNewestConfiguredHistory() {
        List<BulkDownloadHistoryRetention.RowState> rows = new ArrayList<>(10_000);
        for (int index = 0; index < 10_000; index++) {
            rows.add(new BulkDownloadHistoryRetention.RowState("session", true, true, true, false));
        }

        List<Integer> remove = BulkDownloadHistoryRetention.oldestSuccessfulRowsToRemove(
                rows, "session", 200);

        assertEquals(9_800, remove.size());
        assertEquals(0, remove.get(0));
        assertEquals(9_799, remove.get(remove.size() - 1));
    }

    @Test
    void retentionPreservesFailedDeferredRunningAndOtherSessionRows() {
        List<BulkDownloadHistoryRetention.RowState> rows = List.of(
                new BulkDownloadHistoryRetention.RowState("session", true, true, false, false),
                new BulkDownloadHistoryRetention.RowState("session", true, true, true, false),
                new BulkDownloadHistoryRetention.RowState("session", true, true, true, true),
                new BulkDownloadHistoryRetention.RowState("session", true, false, true, false),
                new BulkDownloadHistoryRetention.RowState("other", true, true, true, false)
        );

        List<Integer> remove = BulkDownloadHistoryRetention.oldestSuccessfulRowsToRemove(
                rows, "session", 0);

        assertEquals(List.of(1), remove);
        assertTrue(remove.stream().noneMatch(index -> index != 1));
    }
}
