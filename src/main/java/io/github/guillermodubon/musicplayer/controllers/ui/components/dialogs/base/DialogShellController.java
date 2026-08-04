package io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.base;

import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

public class DialogShellController {

    @FXML
    private Label titleLabel;
    @FXML private VBox headerBox;
    @FXML private Label subtitleLabel;
    @FXML private StackPane contentHolder;
    @FXML private HBox actionsBar;

    public void setTitle(String title) {
        if (titleLabel != null) {
            titleLabel.setText(title == null ? "" : title);
        }
    }

    public void setSubtitle(String subtitle) {
        if (subtitleLabel != null) {
            subtitleLabel.setText(subtitle == null ? "" : subtitle);
        }
    }

    public void setSubtitleContent(Node content) {
        if (headerBox == null || subtitleLabel == null || content == null) return;

        int index = headerBox.getChildren().indexOf(subtitleLabel);
        if (index >= 0) {
            headerBox.getChildren().set(index, content);
        }
    }

    public void setContent(Node content) {
        if (contentHolder != null) {
            contentHolder.getChildren().setAll(content);
        }
    }

    public void setActions(Node... nodes) {
        if (actionsBar != null) {
            actionsBar.getChildren().setAll(nodes);
        }
    }
}
