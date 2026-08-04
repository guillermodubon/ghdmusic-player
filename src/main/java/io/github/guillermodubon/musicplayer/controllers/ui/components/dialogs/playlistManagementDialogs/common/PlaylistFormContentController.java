package io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistManagementDialogs.common;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import io.github.guillermodubon.musicplayer.controllers.ui.components.images.SquareImageViewSupport;

public class PlaylistFormContentController {

    @FXML public ImageView playlistImage;
    @FXML public StackPane playlistCoverFrame;
    @FXML public StackPane playlistCoverHoverOverlay;
    @FXML public StackPane playlistCoverEditIconHost;
    @FXML public Button playlistCoverMoreButton;
    @FXML public Label playlistCoverCaption;
    @FXML public TextField titleField;
    @FXML public HBox titleErrorRow;
    @FXML public StackPane titleErrorIconHost;
    @FXML public Label titleErrorLabel;
    @FXML public TextArea descriptionArea;

    @FXML
    private void initialize() {
        SquareImageViewSupport.install(playlistImage);
    }
}
