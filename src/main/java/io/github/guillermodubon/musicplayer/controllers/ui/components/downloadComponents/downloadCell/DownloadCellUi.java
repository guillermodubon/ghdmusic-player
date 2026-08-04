package io.github.guillermodubon.musicplayer.controllers.ui.components.downloadComponents.downloadCell;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import io.github.guillermodubon.musicplayer.controllers.ui.components.icons.SvgIconFactory;

/**
 * Small view operations shared by the download-cell presenters.
 * Keeping icon and visibility details here prevents the controller from
 * knowing how every terminal state is styled.
 */
final class DownloadCellUi {

    static final String ICON_ROOT = "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/";
    static final String ICON_RETRY = ICON_ROOT + "restart_alt_27dp_FFFFFF_FILL0_wght400_GRAD0_opsz24.svg";
    static final String ICON_CLEAR = ICON_ROOT + "close_27dp_FAFAFA_FILL0_wght400_GRAD0_opsz24.svg";
    static final String ICON_FOLDER = ICON_ROOT + "folder_27dp_FFFFFF_FILL0_wght400_GRAD0_opsz24.svg";
    static final String ICON_FOLDER_OPEN = ICON_ROOT + "folder_open_26dp_FFFFFF_FILL0_wght400_GRAD0_opsz24.svg";
    static final String ICON_SUCCESS = ICON_ROOT + "check_circle_27dp_0077B6_FILL0_wght400_GRAD0_opsz24.svg";
    static final String ICON_ERROR = ICON_ROOT + "error_24dp_D32F2F_FILL0_wght400_GRAD0_opsz24.svg";
    static final String ICON_NORMAL = "#DCDCDC";
    static final String ICON_HOVER = "#FFFFFF";

    private DownloadCellUi() {
    }

    static void installIconButton(Button button,
                                  String iconPath,
                                  String accessibleText) {
        if (button == null) {
            return;
        }

        button.setText("");
        button.setAccessibleText(accessibleText);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);

        Node icon = SvgIconFactory.icon(iconPath, 20);
        SvgIconFactory.setIconColor(icon, ICON_NORMAL);
        button.setGraphic(icon);

        button.hoverProperty().addListener((obs, oldValue, hovered) ->
                updateIconColor(button, icon, hovered));
        button.focusedProperty().addListener((obs, oldValue, focused) ->
                updateIconColor(button, icon, focused));
    }

    static void installLocationButton(Button button) {
        if (button == null) {
            return;
        }

        button.setText("");
        button.setAccessibleText("Open download location");
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);

        Runnable update = () -> {
            boolean highlighted = button.isHover() || button.isFocused();
            Node icon = SvgIconFactory.icon(
                    highlighted ? ICON_FOLDER_OPEN : ICON_FOLDER,
                    20
            );
            SvgIconFactory.setIconColor(
                    icon,
                    highlighted ? ICON_HOVER : ICON_NORMAL
            );
            button.setGraphic(icon);
        };

        button.hoverProperty().addListener((obs, oldValue, hovered) -> update.run());
        button.focusedProperty().addListener((obs, oldValue, focused) -> update.run());
        update.run();
    }

    static void setStatus(Label label, String text, String styleClass) {
        if (label == null) {
            return;
        }

        label.setText(text == null ? "" : text);
        label.getStyleClass().removeAll(
                "download-cell-status-completed",
                "download-cell-status-error",
                "download-cell-status-warning"
        );

        if (styleClass != null && !styleClass.isBlank()) {
            label.getStyleClass().add(styleClass);
        }
    }

    static void setStatusIcon(StackPane pane, String iconPath, String styleClass) {
        if (pane == null || iconPath == null || iconPath.isBlank()) {
            return;
        }

        Node icon = SvgIconFactory.icon(iconPath, 20);
        icon.setStyle("");
        if (styleClass != null && !styleClass.isBlank()) {
            icon.getStyleClass().add(styleClass);
        }

        pane.getChildren().setAll(icon);
        setManagedVisible(pane, true);
    }

    static void setBulkStatusIcon(StackPane pane,
                                  String iconPath,
                                  String styleClass) {
        if (pane == null || iconPath == null || iconPath.isBlank()) {
            return;
        }

        Node icon = SvgIconFactory.icon(iconPath, 19);
        icon.setStyle("");
        if (styleClass != null && !styleClass.isBlank()) {
            icon.getStyleClass().add(styleClass);
        }

        pane.getChildren().setAll(icon);
        setManagedVisible(pane, true);
    }

    static void clearBulkSemanticClasses(Label queueLabel,
                                         StackPane statusIconPane) {
        if (queueLabel != null) {
            queueLabel.getStyleClass().removeAll(
                    "download-bulk-queue-label-success",
                    "download-bulk-queue-label-error",
                    "download-bulk-queue-label-warning"
            );
        }

        if (statusIconPane != null) {
            statusIconPane.getStyleClass().removeAll(
                    "download-bulk-status-icon-success",
                    "download-bulk-status-icon-error",
                    "download-bulk-status-icon-warning"
            );
        }
    }

    static void setManagedVisible(Node node, boolean visible) {
        if (node == null) {
            return;
        }

        node.setVisible(visible);
        node.setManaged(visible);
    }

    private static void updateIconColor(Button button,
                                        Node icon,
                                        boolean stateChanged) {
        SvgIconFactory.setIconColor(
                icon,
                stateChanged || button.isHover() || button.isFocused()
                        ? ICON_HOVER
                        : ICON_NORMAL
        );
    }
}
