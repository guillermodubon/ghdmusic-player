package io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.downloadDialogs;

import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.base.DialogShellController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistManagementDialogs.PlaylistDialogWindowSupport;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Modal progress dialog displayed while cancelled downloads are cleaned up. */
public final class DownloadCleanupProgressDialog {

    private static final String DIALOG_SHELL_VIEW =
            "/io/github/guillermodubon/musicplayer/Views/components/dialogs/base/DialogShell.fxml";
    private static final String DIALOG_STYLE =
            "/io/github/guillermodubon/musicplayer/Views/components/dialogs/playlistManagmentDialogs/DeletePlaylistDialog.css";

    private DownloadCleanupProgressDialog() {
    }

    public static void show(Window owner,
                            ReadOnlyDoubleProperty progress,
                            CompletableFuture<Void> cleanupFuture) {
        if (cleanupFuture == null) return;

        try {
            FXMLLoader loader = new FXMLLoader(
                    DownloadCleanupProgressDialog.class.getResource(DIALOG_SHELL_VIEW)
            );
            Parent shell = loader.load();
            DialogShellController shellController = loader.getController();

            AnchorPane root = new AnchorPane(shell);
            root.getStyleClass().add("delete-playlist-dialog-root");
            root.getStylesheets().add(
                    DownloadCleanupProgressDialog.class.getResource(DIALOG_STYLE).toExternalForm()
            );
            AnchorPane.setTopAnchor(shell, 0.0);
            AnchorPane.setRightAnchor(shell, 0.0);
            AnchorPane.setBottomAnchor(shell, 0.0);
            AnchorPane.setLeftAnchor(shell, 0.0);

            Stage stage = new Stage();
            stage.initModality(Modality.APPLICATION_MODAL);
            if (owner != null) {
                stage.initOwner(owner);
            }
            stage.initStyle(StageStyle.TRANSPARENT);

            shellController.setTitle("Cleaning up downloads");
            shellController.setSubtitle("");

            ProgressBar progressBar = new ProgressBar();
            progressBar.getStyleClass().add("download-cleanup-progress");
            progressBar.setMaxWidth(Double.MAX_VALUE);
            progressBar.progressProperty().bind(progress);

            Label statusLabel = new Label(
                    "Cancelling downloads and removing temporary files..."
            );
            statusLabel.getStyleClass().add("download-cleanup-status");

            Label percentageLabel = new Label(formatPercentage(progress));
            percentageLabel.getStyleClass().add("download-cleanup-percentage");
            progress.addListener((observable, oldValue, newValue) ->
                    percentageLabel.setText(formatPercentage(newValue.doubleValue()))
            );

            VBox content = new VBox(statusLabel, progressBar, percentageLabel);
            content.getStyleClass().add("download-cleanup-content");
            shellController.setContent(content);
            shellController.setActions();

            Scene scene = new Scene(root);
            scene.setFill(Color.TRANSPARENT);
            stage.setScene(scene);
            PlaylistDialogWindowSupport.configureDownloadExitDialog(stage, root, owner);
            PlaylistDialogWindowSupport.installDragHandling(stage, root);

            AtomicBoolean completionAuthorized = new AtomicBoolean(false);
            stage.addEventHandler(WindowEvent.WINDOW_CLOSE_REQUEST, event -> {
                if (!completionAuthorized.get()) {
                    event.consume();
                }
            });

            cleanupFuture.whenComplete((ignored, error) -> Platform.runLater(() -> {
                completionAuthorized.set(true);
                progressBar.progressProperty().unbind();
                progressBar.setProgress(1.0);
                percentageLabel.setText("100%");
                stage.close();
            }));

            stage.showAndWait();
        } catch (IOException | RuntimeException error) {
            error.printStackTrace();
        }
    }

    private static String formatPercentage(ReadOnlyDoubleProperty progress) {
        return progress == null ? "0%" : formatPercentage(progress.get());
    }

    private static String formatPercentage(double progress) {
        int percentage = (int) Math.round(
                Math.max(0.0, Math.min(1.0, progress)) * 100.0
        );
        return percentage + "%";
    }
}
