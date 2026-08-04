package io.github.guillermodubon.musicplayer.controllers.ui.components.downloadComponents.downloadCell;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.layout.StackPane;

/**
 * Keeps the download cell readable while its list is resized.
 */
final class DownloadCellResponsiveLayout {

    private static final double COMPACT_CELL_WIDTH = 310;

    private final HBox rootHBox;
    private final ImageView thumbImageView;
    private final VBox mainContent;
    private final HBox progressRow;
    private final StackPane statusIconPane;
    private final Label percentLabel;
    private final VBox buttonBox;
    private final Button cancelButton;
    private final Button actionButton;
    private final Button openLocationButton;
    private final Button removeButton;

    DownloadCellResponsiveLayout(HBox rootHBox,
                                 ImageView thumbImageView,
                                 VBox mainContent,
                                 HBox progressRow,
                                 StackPane statusIconPane,
                                 Label percentLabel,
                                 VBox buttonBox,
                                 Button cancelButton,
                                 Button actionButton,
                                 Button openLocationButton,
                                 Button removeButton) {
        this.rootHBox = rootHBox;
        this.thumbImageView = thumbImageView;
        this.mainContent = mainContent;
        this.progressRow = progressRow;
        this.statusIconPane = statusIconPane;
        this.percentLabel = percentLabel;
        this.buttonBox = buttonBox;
        this.cancelButton = cancelButton;
        this.actionButton = actionButton;
        this.openLocationButton = openLocationButton;
        this.removeButton = removeButton;
    }

    void install() {
        if (rootHBox == null) {
            return;
        }

        rootHBox.widthProperty().addListener(
                (obs, oldWidth, newWidth) -> apply(newWidth.doubleValue())
        );
        Platform.runLater(() -> apply(rootHBox.getWidth()));
    }

    private void apply(double width) {
        boolean compact = width > 0 && width < COMPACT_CELL_WIDTH;
        double coverSize = compact ? 46 : 58;
        double buttonSize = compact ? 26 : 30;
        double statusIconSize = compact ? 20 : 24;

        rootHBox.setSpacing(compact ? 6 : 12);
        if (mainContent != null) {
            mainContent.setSpacing(compact ? 6 : 8);
        }
        if (progressRow != null) {
            progressRow.setSpacing(compact ? 2 : 3);
        }
        if (buttonBox != null) {
            buttonBox.setSpacing(compact ? 1 : 3);
            buttonBox.setMinWidth(buttonSize);
            buttonBox.setPrefWidth(buttonSize);
            buttonBox.setMaxWidth(buttonSize);
        }

        if (thumbImageView != null) {
            thumbImageView.setFitWidth(coverSize);
            thumbImageView.setFitHeight(coverSize);
        }

        setButtonSize(cancelButton, buttonSize);
        setButtonSize(actionButton, buttonSize);
        setButtonSize(openLocationButton, buttonSize);
        setButtonSize(removeButton, buttonSize);

        if (statusIconPane != null) {
            statusIconPane.setMinWidth(statusIconSize);
            statusIconPane.setPrefWidth(statusIconSize);
            statusIconPane.setMaxWidth(statusIconSize);
        }

        if (percentLabel != null) {
            double percentWidth = compact ? 34 : 42;
            percentLabel.setMinWidth(percentWidth);
            percentLabel.setPrefWidth(percentWidth);
            percentLabel.setMaxWidth(percentWidth);
        }
    }

    private void setButtonSize(Button button, double size) {
        if (button == null) {
            return;
        }
        button.setMinSize(size, size);
        button.setPrefSize(size, size);
        button.setMaxSize(size, size);
    }
}
