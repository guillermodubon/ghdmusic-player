package io.github.guillermodubon.musicplayer.controllers.ui.components.downloadComponents.downloadCell;

import javafx.collections.ObservableList;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ListCell;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import io.github.guillermodubon.musicplayer.services.downloads.DownloadTask;
import java.io.IOException;
import java.util.function.Consumer;

public class DownloadCell extends ListCell<DownloadTask> {

    private Pane pane;
    private DownloadCellController controller;

    private final ObservableList<DownloadTask> backingList;
    private final Consumer<DownloadTask> sourceNavigationHandler;

    public DownloadCell(
            ObservableList<DownloadTask> backingList
    ) {
        this(backingList, null);
    }

    public DownloadCell(
            ObservableList<DownloadTask> backingList,
            Consumer<DownloadTask> sourceNavigationHandler
    ) {
        this.backingList = backingList;
        this.sourceNavigationHandler = sourceNavigationHandler;

        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource(
                            "/io/github/guillermodubon/musicplayer/Views/components/"
                                    + "layoutComponents/downloadSideBarMenu/"
                                    + "DownloadSideBarMenuCell.fxml"
                    )
            );

            pane = loader.load();

            pane.prefWidthProperty()
                    .bind(widthProperty().subtract(2));

            pane.setMinWidth(0);
            pane.setMaxWidth(Double.MAX_VALUE);

            controller = loader.getController();

        } catch (IOException error) {
            error.printStackTrace();

            pane = new Pane();
            controller = null;
        }
    }

    @Override
    protected void updateItem(
            DownloadTask task,
            boolean empty
    ) {
        super.updateItem(task, empty);

        setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        setMinWidth(0);
        setPrefWidth(Region.USE_COMPUTED_SIZE);
        setMaxWidth(Double.MAX_VALUE);

        if (empty || task == null) {
            if (controller != null) {
                controller.updateForReuse();
            }

            setGraphic(null);
            return;
        }

        if (controller != null) {
            controller.bindTask(
                    task,
                    backingList,
                    sourceNavigationHandler
            );
        }

        setGraphic(pane);
    }
}
