package io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane.factory;

import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.RemoveSongFromQueueItemController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.PlayableSongItemController;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.io.IOException;
import java.util.function.Consumer;

public class QueueSidebarViewFactory {

    private final StartUpService startUpService;

    public QueueSidebarViewFactory(StartUpService startUpService) {
        this.startUpService = startUpService;
    }

    public Node createSongItem(
            Song song,
            Consumer<Song> onPlaySong,
            Consumer<String> onArtistClick,
            Consumer<Song> onAddToQueue
    ) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/io/github/guillermodubon/musicplayer/Views/components/items/PlayableSongItemController.fxml")
            );
            Node node = loader.load();

            PlayableSongItemController ctrl = loader.getController();
            if (ctrl != null) {
                ctrl.setStartUpService(startUpService);
                ctrl.setFullRowPlayable(true);
                ctrl.setDeferPlayUntilRelease(true);
                ctrl.init(song, onPlaySong, onArtistClick, onAddToQueue);
            }

            return node;
        } catch (IOException e) {
            e.printStackTrace();
            return new AnchorPane();
        }
    }

    public Node createRemoveQueueItem(
            Song song,
            Consumer<Song> onPlaySong,
            Runnable onRemove
    ) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/io/github/guillermodubon/musicplayer/Views/components/items/RemoveSongFromQueueItemController.fxml")
            );
            HBox row = loader.load();

            RemoveSongFromQueueItemController ctrl = loader.getController();
            if (ctrl != null) {
                ctrl.setStartUpService(startUpService);
                ctrl.setDeferPlayUntilRelease(true);
                ctrl.init(song, onPlaySong, onRemove);
            }

            return row;
        } catch (IOException e) {
            e.printStackTrace();
            return new HBox();
        }
    }
}
