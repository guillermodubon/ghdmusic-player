package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.view;

import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;

public final class PlayerFullScreenModeViewController {

    @FXML private StackPane root;
    @FXML private ImageView backgroundImageView;

    @FXML
    private void initialize() {
        if (backgroundImageView == null || root == null) return;
        root.setAlignment(Pos.CENTER);
        // Preserve the original source pixels while the parent adapts to the
        // current monitor. The visual flags already live in FXML, leaving
        // this controller to do the one-time responsive wiring only.
        backgroundImageView.setCache(false);
        StackPane.setAlignment(backgroundImageView, Pos.CENTER);
        backgroundImageView.fitWidthProperty().bind(root.widthProperty());
        backgroundImageView.fitHeightProperty().bind(root.heightProperty());
    }

}
