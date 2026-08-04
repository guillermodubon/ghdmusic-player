package io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistContentManagementDialogs.common;

import javafx.fxml.FXML;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import io.github.guillermodubon.musicplayer.controllers.ui.components.inputs.SearchBarController;

public class ManagePlaylistSongsContentController {

    public StackPane searchBox;
    @FXML public TextField searchField;
    @FXML public SearchBarController playlistSearchBarController;
    @FXML public ScrollPane playlistScrollPane;
    @FXML public VBox listContainer;
    @FXML public VBox emptyStateBox;

    @FXML
    private void initialize() {
        if (playlistSearchBarController != null) {
            playlistSearchBarController.setPromptText("Search for a playlist");
            searchBox = playlistSearchBarController.getRoot();
            searchField = playlistSearchBarController.getTextField();
        }
    }
}
