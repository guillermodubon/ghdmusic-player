package io.github.guillermodubon.musicplayer.services.downloads.services;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

public final class DownloadUiIntegrationBatcher {

    private final List<IntegratedDownloadResult> pending = new ArrayList<>();
    private final PauseTransition debounce = new PauseTransition(Duration.millis(120));
    private final PlayerMenuDownloadBridge bridge;

    public DownloadUiIntegrationBatcher(PlayerMenuDownloadBridge bridge) {
        this.bridge = bridge;
        debounce.setOnFinished(event -> flushNow());
    }

    public void submit(IntegratedDownloadResult result) {
        if (result == null) return;

        Platform.runLater(() -> {
            pending.add(result);
            debounce.playFromStart();
        });
    }

    public void flushNow() {
        if (pending.isEmpty()) return;

        List<IntegratedDownloadResult> copy = new ArrayList<>(pending);
        pending.clear();

        bridge.publishIntegratedDownloads(copy);
    }
}