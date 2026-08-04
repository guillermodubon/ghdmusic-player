package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.header;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import io.github.guillermodubon.musicplayer.controllers.ui.components.icons.SvgIconFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.components.popups.SmallPopupTooltip;

/** Owns playlist-specific header controls and their presentation state. */
public final class PlayerMenuPlaylistHeaderUi {

    private static final String ICON_ROOT = "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/";
    private static final String ICON_MENU = ICON_ROOT + "edit_arrow_down_27dp_FFFFFF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_EDIT = ICON_ROOT + "edit_27dp_AFAFAF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_DELETE = ICON_ROOT + "delete_27dp_FFFFFF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_FAVORITE = ICON_ROOT + "favorite_27dp_AFAFAF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_FAVORITE_FILLED = ICON_ROOT + "favorite_filled_27dp_AFAFAF_FILL1_wght400_GRAD0_opsz24.svg";
    private static final String ICON_NORMAL_COLOR = "#AFAFAF";
    private static final String ICON_HOVER_COLOR = "#FFFFFF";
    private static final String ICON_SELECTED_COLOR = "#0077B6";

    private MenuButton menuOptions;
    private MenuItem editItem;
    private MenuItem deleteItem;
    private CheckBox remoteSaveCheckBox;
    private Pane actionMenuHost;
    private StackPane fallbackMenuHost;
    private Node menuOptionsIcon;
    private Node remoteSaveIcon;
    private Node remoteSaveFilledIcon;
    private Tooltip remoteSaveTooltip;

    public void bindUi(MenuButton menuOptions,
                       MenuItem editItem,
                       MenuItem deleteItem,
                       CheckBox remoteSaveCheckBox,
                       Pane actionMenuHost,
                       StackPane fallbackMenuHost) {
        this.menuOptions = menuOptions;
        this.editItem = editItem;
        this.deleteItem = deleteItem;
        this.remoteSaveCheckBox = remoteSaveCheckBox;
        this.actionMenuHost = actionMenuHost;
        this.fallbackMenuHost = fallbackMenuHost;

        setNodeState(this.fallbackMenuHost, false);

        configureLocalMenu();
        configureRemoteToggle();
    }

    public CheckBox remoteSaveCheckBox() {
        return remoteSaveCheckBox;
    }

    public boolean isRemoteSelected() {
        return remoteSaveCheckBox != null && remoteSaveCheckBox.isSelected();
    }

    public void setRemoteAction(EventHandler<ActionEvent> handler) {
        if (remoteSaveCheckBox != null) {
            remoteSaveCheckBox.setOnAction(handler);
        }
    }

    public void setRemoteSelection(boolean selected) {
        if (remoteSaveCheckBox == null) {
            return;
        }
        remoteSaveCheckBox.setSelected(selected);
        updateRemotePresentation();
    }

    public void setRemoteDisabled(boolean disabled) {
        if (remoteSaveCheckBox == null) {
            return;
        }
        remoteSaveCheckBox.setDisable(disabled);
        updateRemotePresentation();
    }

    public void showLocalMenu(boolean show) {
        if (show) {
            attachMenuToCurrentOptionsHost();
        }
        setNodeState(menuOptions, show);
        setMenuItemState(editItem, show);
        setMenuItemState(deleteItem, show);
        setNodeState(fallbackMenuHost, show && menuOptions != null
                && menuOptions.getParent() == fallbackMenuHost);
        updateMenuOptionsIcon();
    }

    public void hideLocalMenu() {
        showLocalMenu(false);
    }

    public void showRemotePlaylist(boolean selected, boolean disabled) {
        hideLocalMenu();
        ensureRemoteCheckboxExists();
        if (remoteSaveCheckBox == null) {
            return;
        }

        remoteSaveCheckBox.setVisible(true);
        remoteSaveCheckBox.setManaged(true);
        remoteSaveCheckBox.setDisable(disabled);
        remoteSaveCheckBox.setSelected(selected);
        updateRemotePresentation();
    }

    public void hideRemotePlaylist() {
        if (remoteSaveCheckBox == null) {
            return;
        }
        remoteSaveCheckBox.setVisible(false);
        remoteSaveCheckBox.setManaged(false);
        remoteSaveCheckBox.setDisable(false);
        remoteSaveCheckBox.setSelected(false);
        updateRemotePresentation();
    }

    public void refreshRemotePresentation() {
        updateRemotePresentation();
    }

    private void configureLocalMenu() {
        if (menuOptions != null
                && !Boolean.TRUE.equals(menuOptions.getProperties().get("playerMenuPlaylistIconInstalled"))) {
            menuOptions.setText("");
            menuOptions.setAccessibleText("Edit this playlist");
            menuOptions.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);

            menuOptionsIcon = SvgIconFactory.icon(ICON_MENU, 24);
            menuOptionsIcon.getStyleClass().add("player-menu-options-icon");
            menuOptions.setGraphic(menuOptionsIcon);
            SmallPopupTooltip.install(menuOptions, "Edit this playlist");

            menuOptions.getProperties().put("playerMenuPlaylistIconInstalled", Boolean.TRUE);
        }

        configureMenuItem(editItem, "Edit Playlist", ICON_EDIT, ICON_NORMAL_COLOR);
        configureMenuItem(deleteItem, "Delete Playlist", ICON_DELETE, ICON_NORMAL_COLOR);
        updateMenuOptionsIcon();
    }

    private void attachMenuToCurrentOptionsHost() {
        if (menuOptions == null) {
            return;
        }

        Pane target = actionMenuHost != null
                && actionMenuHost.isManaged()
                && actionMenuHost.isVisible()
                ? actionMenuHost
                : fallbackMenuHost;
        if (target == null) {
            return;
        }

        if (menuOptions.getParent() != target) {
            if (menuOptions.getParent() instanceof Pane currentParent) {
                currentParent.getChildren().remove(menuOptions);
            }
            int insertionIndex = target == actionMenuHost && !target.getChildren().isEmpty()
                    ? target.getChildren().size() - 1
                    : target.getChildren().size();
            target.getChildren().add(Math.max(0, insertionIndex), menuOptions);
        }

        setNodeState(fallbackMenuHost, target == fallbackMenuHost);
    }

    private void configureMenuItem(MenuItem item, String text, String iconPath, String color) {
        if (item == null) {
            return;
        }
        item.setText(text);
        Node icon = SvgIconFactory.icon(iconPath, 18);
        icon.getStyleClass().add("player-menu-option-menu-icon");
        SvgIconFactory.setIconColor(icon, color);
        item.setGraphic(icon);
    }

    private void updateMenuOptionsIcon() {
        if (menuOptionsIcon == null || menuOptions == null) {
            return;
        }
        SvgIconFactory.setIconColor(menuOptionsIcon, ICON_NORMAL_COLOR);
    }

    private void configureRemoteToggle() {
        if (remoteSaveCheckBox == null) {
            return;
        }

        remoteSaveCheckBox.setText("");
        remoteSaveCheckBox.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        remoteSaveCheckBox.setFocusTraversable(false);
        if (!remoteSaveCheckBox.getStyleClass().contains("remote-library-check-button")) {
            remoteSaveCheckBox.getStyleClass().add("remote-library-check-button");
        }
        ensureRemoteSaveIcons();

        Tooltip attachedTooltip = remoteSaveCheckBox.getTooltip();
        if (attachedTooltip == null) {
            attachedTooltip = SmallPopupTooltip.create(remoteSaveTooltipText(remoteSaveCheckBox.isSelected()));
            remoteSaveCheckBox.setTooltip(attachedTooltip);
        }
        remoteSaveTooltip = attachedTooltip;
        configureRemoteTooltip(remoteSaveTooltip);
        updateRemoteTooltipText();

        if (Boolean.TRUE.equals(remoteSaveCheckBox.getProperties().get("playerMenuRemoteSaveConfigured"))) {
            updateRemotePresentation();
            return;
        }

        remoteSaveCheckBox.selectedProperty().addListener((obs, oldValue, selected) -> {
            updateRemotePresentation();
            updateRemoteTooltipText();
        });
        remoteSaveCheckBox.hoverProperty().addListener((obs, oldValue, hovered) -> {
            if (hovered) {
                updateRemoteTooltipText();
            }
            updateRemotePresentation();
        });
        remoteSaveCheckBox.focusedProperty().addListener((obs, oldValue, focused) -> updateRemotePresentation());
        remoteSaveCheckBox.disabledProperty().addListener((obs, oldValue, disabled) -> updateRemotePresentation());
        remoteSaveCheckBox.getProperties().put("playerMenuRemoteSaveConfigured", Boolean.TRUE);
        updateRemotePresentation();
    }

    private void ensureRemoteCheckboxExists() {
        if (remoteSaveCheckBox == null) {
            remoteSaveCheckBox = new CheckBox("");
            remoteSaveCheckBox.setVisible(false);
            remoteSaveCheckBox.setManaged(false);
        }
        remoteSaveCheckBox.getStyleClass().removeAll(
                "player-menu-inline-icon-button",
                "player-menu-inline-toggle-button",
                "player-menu-remote-save-toggle",
                "remote-library-check-button"
        );
        remoteSaveCheckBox.getStyleClass().addAll(
                "player-menu-inline-icon-button",
                "player-menu-inline-toggle-button",
                "player-menu-remote-save-toggle",
                "remote-library-check-button"
        );
        configureRemoteToggle();
    }

    private void ensureRemoteSaveIcons() {
        if (remoteSaveIcon == null) {
            remoteSaveIcon = SvgIconFactory.icon(ICON_FAVORITE, 27);
            remoteSaveIcon.getStyleClass().add("player-menu-remote-save-icon");
        }
        if (remoteSaveFilledIcon == null) {
            remoteSaveFilledIcon = SvgIconFactory.icon(ICON_FAVORITE_FILLED, 27);
            remoteSaveFilledIcon.getStyleClass().add("player-menu-remote-save-icon");
        }
    }

    private void configureRemoteTooltip(Tooltip tooltip) {
        if (tooltip == null) {
            return;
        }
        if (!tooltip.getStyleClass().contains("small-popup-tooltip")) {
            tooltip.getStyleClass().add("small-popup-tooltip");
        }
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(320);
        tooltip.setStyle(
                "-fx-background-color: #111827;"
                        + "-fx-background-insets: 0;"
                        + "-fx-background-radius: 9;"
                        + "-fx-border-color: rgba(255, 255, 255, 0.14);"
                        + "-fx-border-radius: 9;"
                        + "-fx-border-width: 1;"
                        + "-fx-padding: 8 12 8 12;"
                        + "-fx-text-fill: #FFFFFF;"
                        + "-fx-font-size: 13px;"
                        + "-fx-font-weight: normal;"
                        + "-fx-opacity: 1;"
        );
        tooltip.setOnShowing(event -> {
            updateRemoteTooltipText();
            Platform.runLater(() -> forceTooltipTextVisible(tooltip));
        });
        tooltip.setOnShown(event -> Platform.runLater(() -> forceTooltipTextVisible(tooltip)));
    }

    private void forceTooltipTextVisible(Tooltip tooltip) {
        if (tooltip == null || remoteSaveCheckBox == null) {
            return;
        }
        tooltip.setText(remoteSaveTooltipText(remoteSaveCheckBox.isSelected()));
        try {
            if (tooltip.getScene() == null || tooltip.getScene().getRoot() == null) {
                return;
            }
            Parent root = tooltip.getScene().getRoot();
            root.applyCss();
            root.layout();
            root.lookupAll(".text").forEach(node -> {
                node.setStyle("-fx-fill: #FFFFFF; -fx-text-fill: #FFFFFF; -fx-opacity: 1;");
                node.setVisible(true);
                node.setOpacity(1.0);
            });
            root.lookupAll(".label").forEach(node -> {
                node.setStyle("-fx-text-fill: #FFFFFF; -fx-opacity: 1;");
                node.setVisible(true);
                node.setOpacity(1.0);
            });
        } catch (Exception ignored) {
        }
    }

    private String remoteSaveTooltipText(boolean selected) {
        return selected
                ? "Remove this playlist from your library"
                : "Add this playlist to your library";
    }

    private void updateRemoteTooltipText() {
        if (remoteSaveCheckBox == null) {
            return;
        }
        String text = remoteSaveTooltipText(remoteSaveCheckBox.isSelected());
        remoteSaveCheckBox.setAccessibleText(text);
        Tooltip tooltip = remoteSaveCheckBox.getTooltip();
        if (tooltip == null) {
            tooltip = SmallPopupTooltip.create(text);
            remoteSaveCheckBox.setTooltip(tooltip);
            configureRemoteTooltip(tooltip);
        }
        tooltip.setText(text);
        remoteSaveTooltip = tooltip;
        if (tooltip.isShowing()) {
            Tooltip finalTooltip = tooltip;
            Platform.runLater(() -> forceTooltipTextVisible(finalTooltip));
        }
    }

    private void updateRemotePresentation() {
        if (remoteSaveCheckBox == null) {
            return;
        }
        ensureRemoteSaveIcons();

        boolean selected = remoteSaveCheckBox.isSelected();
        boolean highlighted = remoteSaveCheckBox.isHover() || remoteSaveCheckBox.isFocused();
        Node icon = selected ? remoteSaveFilledIcon : remoteSaveIcon;
        remoteSaveCheckBox.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        if (remoteSaveCheckBox.getGraphic() != icon) {
            remoteSaveCheckBox.setGraphic(icon);
        }
        SvgIconFactory.setIconColor(
                icon,
                highlighted
                        ? ICON_HOVER_COLOR
                        : selected ? ICON_SELECTED_COLOR : ICON_NORMAL_COLOR
        );
        remoteSaveCheckBox.setOpacity(remoteSaveCheckBox.isDisabled() ? 0.62 : 1.0);
        remoteSaveCheckBox.getStyleClass().removeAll(
                "remote-library-check-button-selected",
                "remote-library-check-button-unselected"
        );
        remoteSaveCheckBox.getStyleClass().add(
                selected
                        ? "remote-library-check-button-selected"
                        : "remote-library-check-button-unselected"
        );
        updateRemoteTooltipText();
    }

    private void setNodeState(Node node, boolean visible) {
        if (node == null) {
            return;
        }
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private void setMenuItemState(MenuItem item, boolean visible) {
        if (item == null) {
            return;
        }
        item.setVisible(visible);
        item.setDisable(!visible);
    }
}
