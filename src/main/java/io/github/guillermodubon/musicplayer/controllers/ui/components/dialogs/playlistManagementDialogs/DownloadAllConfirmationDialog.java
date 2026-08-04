package io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistManagementDialogs;

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

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DownloadAllConfirmationDialog {

    private DownloadAllConfirmationDialog() {
    }

    public static boolean confirm(Window owner, int songCount, String collectionTitle) {
        if (songCount <= 0) return false;
        try {
            FXMLLoader loader = new FXMLLoader(DownloadAllConfirmationDialog.class.getResource(
                    "/io/github/guillermodubon/musicplayer/Views/components/dialogs/base/DialogShell.fxml"
            ));
            Parent shell = loader.load();
            DialogShellController shellController = loader.getController();

            AnchorPane root = new AnchorPane(shell);
            root.getStyleClass().add("delete-playlist-dialog-root");
            root.getStylesheets().add(DownloadAllConfirmationDialog.class.getResource(
                    "/io/github/guillermodubon/musicplayer/Views/components/dialogs/playlistManagmentDialogs/DeletePlaylistDialog.css"
            ).toExternalForm());
            AnchorPane.setTopAnchor(shell, 0.0);
            AnchorPane.setRightAnchor(shell, 0.0);
            AnchorPane.setBottomAnchor(shell, 0.0);
            AnchorPane.setLeftAnchor(shell, 0.0);

            AtomicBoolean confirmed = new AtomicBoolean(false);
            Stage stage = new Stage();
            stage.initModality(Modality.APPLICATION_MODAL);
            if (owner != null) stage.initOwner(owner);
            stage.initStyle(StageStyle.TRANSPARENT);

            shellController.setTitle("Download songs?");
            shellController.setSubtitle("");
            shellController.setContent(createContent(songCount, collectionTitle));

            Button cancel = new Button("Cancel");
            cancel.getStyleClass().addAll("dialog-button", "secondary-button");
            cancel.setOnAction(event -> stage.close());

            Button download = new Button("Download");
            download.getStyleClass().addAll("dialog-button", "primary-button");
            download.setOnAction(event -> {
                confirmed.set(true);
                stage.close();
            });
            shellController.setActions(cancel, download);

            Scene scene = new Scene(root);
            scene.setFill(Color.TRANSPARENT);
            stage.setScene(scene);
            PlaylistDialogWindowSupport.configureCompactDialog(stage, root, owner);
            PlaylistDialogWindowSupport.installDragHandling(stage, root);
            DialogKeyboardSupport.install(stage, root, download);
            stage.showAndWait();
            return confirmed.get();
        } catch (IOException | RuntimeException ex) {
            ex.printStackTrace();
            return false;
        }
    }

    private static VBox createContent(int songCount, String collectionTitle) {
        String safeTitle = collectionTitle == null || collectionTitle.isBlank()
                ? "this collection"
                : collectionTitle.trim();

        Text prefix = new Text("Are sure you want to download the ");
        prefix.getStyleClass().add("delete-playlist-message-text");

        Text count = new Text(String.valueOf(songCount));
        count.getStyleClass().add("delete-playlist-message-highlight");

        Text middle = new Text(songCount == 1 ? " song from " : " songs from ");
        middle.getStyleClass().add("delete-playlist-message-text");

        Text title = new Text(safeTitle);
        title.getStyleClass().add("delete-playlist-message-highlight");

        TextFlow message = new TextFlow(prefix, count, middle, title);
        message.getStyleClass().add("delete-playlist-message");

        VBox body = new VBox(message);
        body.getStyleClass().add("delete-playlist-content");
        return body;
    }
}
