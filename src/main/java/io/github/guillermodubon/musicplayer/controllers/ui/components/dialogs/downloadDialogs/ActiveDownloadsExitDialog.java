package io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.downloadDialogs;

import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.base.DialogKeyboardSupport;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.base.DialogShellController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistManagementDialogs.PlaylistDialogWindowSupport;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Confirmation dialog shown before cancelling active downloads on exit. */
public final class ActiveDownloadsExitDialog {

    private static final String DIALOG_SHELL_VIEW =
            "/io/github/guillermodubon/musicplayer/Views/components/dialogs/base/DialogShell.fxml";
    private static final String DIALOG_STYLE =
            "/io/github/guillermodubon/musicplayer/Views/components/dialogs/playlistManagmentDialogs/DeletePlaylistDialog.css";

    private ActiveDownloadsExitDialog() {
    }

    public static boolean confirm(Window owner) {
        return confirm(owner, () -> true);
    }

    public static boolean confirm(Window owner, BooleanSupplier hasActiveDownloads) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    ActiveDownloadsExitDialog.class.getResource(DIALOG_SHELL_VIEW)
            );
            Parent shell = loader.load();
            DialogShellController shellController = loader.getController();

            AnchorPane root = new AnchorPane(shell);
            root.getStyleClass().add("delete-playlist-dialog-root");
            root.getStylesheets().add(
                    ActiveDownloadsExitDialog.class.getResource(DIALOG_STYLE).toExternalForm()
            );
            AnchorPane.setTopAnchor(shell, 0.0);
            AnchorPane.setRightAnchor(shell, 0.0);
            AnchorPane.setBottomAnchor(shell, 0.0);
            AnchorPane.setLeftAnchor(shell, 0.0);

            AtomicBoolean confirmed = new AtomicBoolean(false);
            Stage stage = new Stage();
            stage.initModality(Modality.APPLICATION_MODAL);
            if (owner != null) {
                stage.initOwner(owner);
            }
            stage.initStyle(StageStyle.TRANSPARENT);

            shellController.setTitle("Active downloads");
            shellController.setSubtitle("");
            shellController.setContent(createContent());

            Button cancel = new Button("Cancel");
            cancel.getStyleClass().addAll("dialog-button", "secondary-button");
            cancel.setOnAction(event -> stage.close());

            Button exit = new Button("Exit");
            exit.getStyleClass().addAll("dialog-button", "danger-button");
            exit.setOnAction(event -> {
                /*
                 * The watcher normally closes the dialog as soon as all work
                 * is complete. This guard handles the small race in which the
                 * user clicks Exit before the next watcher tick.
                 */
                if (!isActive(hasActiveDownloads)) {
                    return;
                }
                confirmed.set(true);
                stage.close();
            });
            shellController.setActions(cancel, exit);

            Scene scene = new Scene(root);
            scene.setFill(Color.TRANSPARENT);
            stage.setScene(scene);
            PlaylistDialogWindowSupport.configureDownloadExitDialog(stage, root, owner);
            PlaylistDialogWindowSupport.installDragHandling(stage, root);
            DialogKeyboardSupport.install(stage, root, exit);

            Timeline completionWatcher = new Timeline(new KeyFrame(
                    Duration.millis(200),
                    event -> {
                        if (!isActive(hasActiveDownloads)) {
                            stage.close();
                        }
                    }
            ));
            completionWatcher.setCycleCount(Animation.INDEFINITE);
            stage.addEventHandler(WindowEvent.WINDOW_SHOWN, event -> completionWatcher.play());
            stage.setOnHidden(event -> completionWatcher.stop());

            stage.showAndWait();
            return confirmed.get();
        } catch (IOException | RuntimeException error) {
            error.printStackTrace();
            return false;
        }
    }

    private static boolean isActive(BooleanSupplier hasActiveDownloads) {
        if (hasActiveDownloads == null) return true;

        try {
            return hasActiveDownloads.getAsBoolean();
        } catch (RuntimeException ignored) {
            /* A transient status read must never enable an unsafe exit. */
            return true;
        }
    }

    private static VBox createContent() {
        Text message = new Text(
                "If you close the app now, active downloads will be cancelled and "
                        + "their incomplete files will be removed.\n\n"
                        + "Are you sure you want to exit?"
        );
        message.getStyleClass().add("delete-playlist-message-text");

        TextFlow messageFlow = new TextFlow(message);
        messageFlow.getStyleClass().add("delete-playlist-message");

        VBox body = new VBox(messageFlow);
        body.getStyleClass().add("delete-playlist-content");
        return body;
    }
}
