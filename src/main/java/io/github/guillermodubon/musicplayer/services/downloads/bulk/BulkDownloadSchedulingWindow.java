package io.github.guillermodubon.musicplayer.services.downloads.bulk;

public final class BulkDownloadSchedulingWindow {

    private BulkDownloadSchedulingWindow() {
    }

    public static int availableSlots(int activeTasks,
                                     int activeProviderPhases,
                                     int workerCapacity,
                                     int providerConcurrency) {
        int workerSlots = Math.max(0, workerCapacity - Math.max(0, activeTasks));
        int providerSlots = Math.max(
                0,
                providerConcurrency - Math.max(0, activeProviderPhases)
        );
        return Math.min(workerSlots, providerSlots);
    }
}
