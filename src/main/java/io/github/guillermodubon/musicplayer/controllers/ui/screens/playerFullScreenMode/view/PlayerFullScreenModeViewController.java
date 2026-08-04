package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.view;

import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;

public final class PlayerFullScreenModeViewController {

    @FXML private StackPane root;
    @FXML private ImageView backgroundImageView;

    @FXML
    private void initialize() {
        if (backgroundImageView == null || root == null) return;
        root.setAlignment(Pos.CENTER);
        backgroundImageView.setPreserveRatio(true);
        backgroundImageView.setSmooth(true);
        backgroundImageView.setCache(false);
        backgroundImageView.setViewport(null);
        StackPane.setAlignment(backgroundImageView, Pos.CENTER);
        backgroundImageView.fitWidthProperty().bind(root.widthProperty());
        backgroundImageView.fitHeightProperty().bind(root.heightProperty());
    }

}
