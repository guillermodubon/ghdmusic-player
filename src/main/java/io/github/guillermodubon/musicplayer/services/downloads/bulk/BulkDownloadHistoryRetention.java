package io.github.guillermodubon.musicplayer.services.downloads.bulk;

import java.util.ArrayList;
import java.util.List;

public final class BulkDownloadHistoryRetention {

    public record RowState(String sessionId,
                           boolean bulk,
                           boolean terminal,
                           boolean successful,
                           boolean deferred) {
    }

    private BulkDownloadHistoryRetention() {
    }

    public static List<Integer> oldestSuccessfulRowsToRemove(
            List<RowState> rows,
            String sessionId,
            int retainedLimit
    ) {
        if (rows == null || sessionId == null || retainedLimit < 0) return List.of();

        int successfulRows = 0;
        for (RowState row : rows) {
            if (isRetainedSuccess(row, sessionId)) successfulRows++;
        }

        int removeCount = Math.max(0, successfulRows - retainedLimit);
        if (removeCount == 0) return List.of();

        List<Integer> indexes = new ArrayList<>(removeCount);
        for (int index = 0; index < rows.size() && indexes.size() < removeCount; index++) {
            if (isRetainedSuccess(rows.get(index), sessionId)) indexes.add(index);
        }
        return List.copyOf(indexes);
    }

    private static boolean isRetainedSuccess(RowState row, String sessionId) {
        return row != null
                && row.bulk()
                && row.terminal()
                && row.successful()
                && !row.deferred()
                && sessionId.equals(row.sessionId());
    }
}
