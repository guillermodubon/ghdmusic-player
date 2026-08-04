package io.github.guillermodubon.musicplayer.services.downloads.services;

import java.util.List;

public interface PlayerMenuDownloadBridge {

    void publishIntegratedDownload(IntegratedDownloadResult result);

    default void publishIntegratedDownloads(List<IntegratedDownloadResult> results) {
        if (results == null || results.isEmpty()) return;

        for (IntegratedDownloadResult result : results) {
            publishIntegratedDownload(result);
        }
    }
}
