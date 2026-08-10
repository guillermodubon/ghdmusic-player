package io.github.guillermodubon.musicplayer.application;

import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.downloadDialogs.ActiveDownloadsExitDialog;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.downloadDialogs.DownloadCleanupProgressDialog;
import io.github.guillermodubon.musicplayer.services.downloads.DownloadShutdownCoordinator;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

/** Guards the main window close request while downloads are still active. */
public final class DownloadAwareCloseHandler {

    private final DownloadShutdownCoordinator shutdownCoordinator;
    private boolean closeAuthorized;
    private boolean confirmationVisible;
    private boolean cancellationInProgress;

    public DownloadAwareCloseHandler() {
        this.shutdownCoordinator = new DownloadShutdownCoordinator();
    }

    public void install(Stage stage) {
        if (stage == null) return;

        stage.addEventHandler(WindowEvent.WINDOW_CLOSE_REQUEST, event ->
                handleCloseRequest(stage, event)
        );
    }

    private void handleCloseRequest(Stage stage, WindowEvent event) {
        if (closeAuthorized) return;

        /*
         * A task may already report itself as cancelled while its worker-side
         * cleanup is still running. Keep consuming close requests until the
         * shutdown coordinator authorizes the final close.
         */
        if (cancellationInProgress) {
            event.consume();
            return;
        }

        if (!shutdownCoordinator.hasActiveDownloads()) {
            return;
        }

        event.consume();

        if (confirmationVisible) {
            return;
        }

        confirmationVisible = true;
        boolean shouldExit = ActiveDownloadsExitDialog.confirm(
                stage,
                shutdownCoordinator::hasActiveDownloads
        );
        confirmationVisible = false;

        if (!shouldExit) return;

        cancellationInProgress = true;
        var cleanupFuture = shutdownCoordinator.cancelActiveDownloadsAndAwaitCleanup();

        DownloadCleanupProgressDialog.show(
                stage,
                shutdownCoordinator.cleanupProgressProperty(),
                cleanupFuture
        );

        cleanupFuture.whenComplete((ignored, error) -> Platform.runLater(() -> {
            closeAuthorized = true;
            stage.close();
        }));
    }
}
