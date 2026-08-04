package io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistManagementDialogs;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.io.IOException;
import java.util.function.Consumer;

public final class PlaylistManagementDialogLauncher {

    private PlaylistManagementDialogLauncher() {}

    public static void openCreatePlaylistDialog(StartUpService svc,
                                                Window owner,
                                                BorderPane parentRoot,
                                                MusicCardActionManager musicActions,
                                                Consumer<io.github.guillermodubon.musicplayer.models.Playlist> onPlaylistCreated) {
        if (svc == null) return;
        try {
            FXMLLoader loader = new FXMLLoader(PlaylistManagementDialogLauncher.class.getResource(
                    "/io/github/guillermodubon/musicplayer/Views/components/dialogs/playlistManagmentDialogs/CreatePlaylistDialog.fxml"
            ));
            Parent content = loader.load();
            CreatePlaylistDialogController controller = loader.getController();

            Stage stage = new Stage();
            stage.initModality(Modality.APPLICATION_MODAL);
            if (owner != null) {
                stage.initOwner(owner);
            }
            stage.initStyle(StageStyle.TRANSPARENT);

            Scene scene = new Scene(content);
            scene.setFill(Color.TRANSPARENT);
            stage.setScene(scene);
            PlaylistDialogWindowSupport.configureFormDialog(stage, content, owner);

            controller.init(svc, stage, parentRoot, musicActions);
            controller.setOnPlaylistCreated(onPlaylistCreated);
            stage.showAndWait();
        } catch (IOException ex) {
            throw new RuntimeException("Could not open create playlist dialog.", ex);
        }
    }
}
