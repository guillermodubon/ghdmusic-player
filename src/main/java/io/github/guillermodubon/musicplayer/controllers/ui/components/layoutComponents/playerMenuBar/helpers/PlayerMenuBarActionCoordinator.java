package io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.helpers;

import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.BorderPane;
import org.controlsfx.control.PopOver;
import io.github.guillermodubon.musicplayer.controllers.ui.components.contextMenus.ActionContextMenuFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.components.downloadComponents.DownloadSidebarMenuController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistContentManagementDialogs.ManagePlaylistSongsDialogController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistContentManagementDialogs.ManagePlaylistSongsPopoverSupport;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistManagementDialogs.PlaylistManagementDialogLauncher;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane.QueueController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.PlayerFullScreenModeController;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.io.IOException;

/** Handles menus and side-panel actions triggered from the player bar. */
public final class PlayerMenuBarActionCoordinator {

    private static final String QUEUE_FXML =
            "/io/github/guillermodubon/musicplayer/Views/components/layoutComponents/queuePane/QueuePane.fxml";
    private static final String PLAYLIST_DIALOG_FXML =
            "/io/github/guillermodubon/musicplayer/Views/components/dialogs/playlistContentManagementDialogs/ManagePlaylistSongsDialog.fxml";

    private final PlaybackManager playbackManager = PlaybackManager.getInstance();

    public void enqueueCurrentSong() {
        Song current = playbackManager.getCurrentSong();
        if (current == null) return;

        playbackManager.enqueue(current);
        QueueController queue = QueueController.getInstance();
        if (queue != null) queue.refreshAll();
    }

    public ContextMenu createActionsMenu(Runnable addToPlaylist, Runnable addToQueue) {
        return ActionContextMenuFactory.songActions(addToPlaylist, addToQueue);
    }

    public void toggleQueue(StartUpService startUpService,
                            MusicCardActionManager musicCardActionManager,
                            BorderPane panelHost,
                            PlayerFullScreenModeController fullScreenController) {
        if (QueueController.isQueueVisible()) {
            QueueController queue = QueueController.getInstance();
            if (queue != null) {
                queue.closeFromOwner();
            } else if (panelHost != null) {
                panelHost.setRight(null);
                QueueController.queueVisibleProperty().set(false);
            }
            return;
        }

        try {
            if (DownloadSidebarMenuController.isDownloadVisible()) {
                DownloadSidebarMenuController.closeActiveSidebar();
            }

            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource(QUEUE_FXML)
            );
            BorderPane pane = loader.load();
            QueueController queue = loader.getController();
            queue.init(startUpService, musicCardActionManager, panelHost);
            pane.setId("QueuePane");

            if (panelHost != null) {
                panelHost.setRight(pane);
                if (fullScreenController != null && fullScreenController.isActive()) {
                    fullScreenController.syncLayout(startUpService);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void openAddToPlaylist(StartUpService startUpService,
                                  MusicCardActionManager musicCardActionManager,
                                  BorderPane parentRoot,
                                  BorderPane playerBarRoot,
                                  Button actionsMenuButton) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource(PLAYLIST_DIALOG_FXML)
            );
            AnchorPane content = loader.load();
            ManagePlaylistSongsDialogController controller = loader.getController();
            controller.init(
                    startUpService,
                    playbackManager.getCurrentSong(),
                    playbackManager.getCurrentPlaylistInViewId()
            );
            controller.setCreatePlaylistLauncher(onCreated ->
                    PlaylistManagementDialogLauncher.openCreatePlaylistDialog(
                            startUpService,
                            playerBarRoot != null && playerBarRoot.getScene() != null
                                    ? playerBarRoot.getScene().getWindow()
                                    : null,
                            parentRoot,
                            musicCardActionManager,
                            onCreated
                    )
            );

            PopOver popOver = new PopOver(content);
            Node anchor = actionsMenuButton != null ? actionsMenuButton : playerBarRoot;
            ManagePlaylistSongsPopoverSupport.configure(popOver, content, anchor);
            ManagePlaylistSongsPopoverSupport.show(popOver, anchor);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
