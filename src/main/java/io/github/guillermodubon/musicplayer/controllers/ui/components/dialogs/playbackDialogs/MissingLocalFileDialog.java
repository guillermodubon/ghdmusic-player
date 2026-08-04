package io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playbackDialogs;

import javafx.application.Platform;
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
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.base.DialogKeyboardSupport;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.base.DialogShellController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistManagementDialogs.PlaylistDialogWindowSupport;
import io.github.guillermodubon.musicplayer.models.Song;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/** A single reusable notification for a local song whose media file vanished. */
public final class MissingLocalFileDialog {

    private static final AtomicReference<Stage> ACTIVE_DIALOG = new AtomicReference<>();

    private MissingLocalFileDialog() {
    }

    public static void show(Song song, Window owner) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> show(song, owner));
            return;
        }

        Stage current = ACTIVE_DIALOG.get();
        if (current != null && current.isShowing()) {
            ACTIVE_DIALOG.compareAndSet(current, null);
            current.close();
        }

        try {
            FXMLLoader loader = new FXMLLoader(MissingLocalFileDialog.class.getResource(
                    "/io/github/guillermodubon/musicplayer/Views/components/dialogs/base/DialogShell.fxml"
            ));
            Parent shell = loader.load();
            DialogShellController shellController = loader.getController();

            AnchorPane root = new AnchorPane(shell);
            root.getStyleClass().add("missing-local-file-dialog-root");
            root.getStylesheets().add(MissingLocalFileDialog.class.getResource(
                    "/io/github/guillermodubon/musicplayer/Views/components/dialogs/playbackDialogs/MissingLocalFileDialog.css"
            ).toExternalForm());
            AnchorPane.setTopAnchor(shell, 0.0);
            AnchorPane.setRightAnchor(shell, 0.0);
            AnchorPane.setBottomAnchor(shell, 0.0);
            AnchorPane.setLeftAnchor(shell, 0.0);

            Stage stage = new Stage();
            stage.initModality(Modality.APPLICATION_MODAL);
            Window effectiveOwner = owner == null ? findActiveWindow() : owner;
            if (effectiveOwner != null) stage.initOwner(effectiveOwner);
            stage.initStyle(StageStyle.TRANSPARENT);

            shellController.setTitle("Local audio file not found");
            shellController.setSubtitle("This song is no longer available on this device.");
            shellController.setContent(createContent(song));

            Button close = new Button("Close");
            close.getStyleClass().addAll("dialog-button", "secondary-button");
            close.setOnAction(event -> stage.close());
            shellController.setActions(close);

            Scene scene = new Scene(root);
            scene.setFill(Color.TRANSPARENT);
            stage.setScene(scene);
            PlaylistDialogWindowSupport.configureCompactDialog(stage, root, effectiveOwner);
            PlaylistDialogWindowSupport.installDragHandling(stage, root);
            DialogKeyboardSupport.install(stage, root, close);
            stage.setOnHidden(event -> ACTIVE_DIALOG.compareAndSet(stage, null));
            ACTIVE_DIALOG.set(stage);
            stage.show();
        } catch (IOException | RuntimeException error) {
            ACTIVE_DIALOG.set(null);
            error.printStackTrace();
        }
    }

    private static VBox createContent(Song song) {
        String title = song == null || song.getTitle() == null || song.getTitle().isBlank()
                ? "this song"
                : song.getTitle().trim();

        Text prefix = new Text("The local file for ");
        prefix.getStyleClass().add("missing-local-file-message-text");
        Text songTitle = new Text(title);
        songTitle.getStyleClass().add("missing-local-file-message-highlight");
        Text suffix = new Text(" could not be found. It has been marked as unavailable to prevent playback errors.");
        suffix.getStyleClass().add("missing-local-file-message-text");

        TextFlow message = new TextFlow(prefix, songTitle, suffix);
        message.getStyleClass().add("missing-local-file-message");

        VBox content = new VBox(message);
        content.getStyleClass().add("missing-local-file-content");
        return content;
    }

    private static Window findActiveWindow() {
        return Window.getWindows().stream()
                .filter(Window::isShowing)
                .findFirst()
                .orElse(null);
    }
}
