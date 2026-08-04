package io.github.guillermodubon.musicplayer.controllers.ui.components.inputs;

import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import io.github.guillermodubon.musicplayer.controllers.ui.components.icons.SvgIconFactory;

public class SearchBarController {

    private static final String ICON_SEARCH = "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/search_27dp_EDCC0E_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_CLEAR = "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/close_27dp_FAFAFA_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String MINIMAL_ROOT_CLASS = "app-search-bar-minimal";
    private static final String MINIMAL_FIELD_CLASS = "app-search-field-minimal";
    private static final String TEXT_QUALITY_CLASS = "app-search-text-quality";

    @FXML private StackPane root;
    @FXML private StackPane iconHost;
    @FXML private TextField searchField;
    @FXML private Button clearButton;

    @FXML
    private void initialize() {
        addStyleClass(searchField, TEXT_QUALITY_CLASS);

        if (iconHost != null) {
            Node icon = SvgIconFactory.icon(ICON_SEARCH, 24);
            SvgIconFactory.setIconColor(icon, "#FFFFFF");
            StackPane.setAlignment(icon, Pos.CENTER);
            iconHost.getChildren().setAll(icon);
        }

        if (clearButton != null) {
            Node clearIcon = SvgIconFactory.icon(ICON_CLEAR, 18);
            SvgIconFactory.setIconColor(clearIcon, "#AFAFAF");
            clearButton.setGraphic(clearIcon);
            clearButton.hoverProperty().addListener((obs, oldValue, hovered) ->
                    SvgIconFactory.setIconColor(clearIcon, hovered ? "#FFFFFF" : "#AFAFAF"));
            clearButton.visibleProperty().bind(searchField.textProperty().isNotEmpty());
            clearButton.managedProperty().bind(clearButton.visibleProperty());
        }
    }

    public StackPane getRoot() {
        return root;
    }

    public TextField getTextField() {
        return searchField;
    }

    public String getText() {
        return searchField.getText();
    }

    public void setText(String text) {
        searchField.setText(text);
    }

    public void clear() {
        searchField.clear();
    }

    public void setPromptText(String promptText) {
        searchField.setPromptText(promptText);
    }

    public void setOnAction(EventHandler<ActionEvent> handler) {
        searchField.setOnAction(handler);
    }

    @FXML
    private void onClearClicked() {
        if (searchField == null) return;
        searchField.clear();
        searchField.requestFocus();
    }

    public void useMinimalUnderlineStyle() {
        addStyleClass(root, MINIMAL_ROOT_CLASS);
        addStyleClass(searchField, MINIMAL_FIELD_CLASS);
    }

    private void addStyleClass(Node node, String styleClass) {
        if (node == null || styleClass == null || styleClass.isBlank()) return;
        if (!node.getStyleClass().contains(styleClass)) {
            node.getStyleClass().add(styleClass);
        }
    }
}
