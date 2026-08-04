package io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane.helpers;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import io.github.guillermodubon.musicplayer.controllers.ui.components.icons.SvgIconFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.components.popups.SmallPopupTooltip;

import java.util.function.Supplier;

/** Owns queue controls, icon styling and responsive sidebar sizing. */
public final class QueueSidebarUiCoordinator {

    private static final String ICON_ROOT = "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/";
    private static final String ICON_CLOSE = ICON_ROOT
            + "right_panel_close_27dp_FFFFFF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_CLEAR = ICON_ROOT
            + "clear_all_27dp_AFAFAF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_NORMAL = "#DCDCDC";
    private static final String ICON_HOVER = "#FFFFFF";
    private static final String CHROMELESS_BUTTON_STYLE = """
            -fx-background-color: transparent;
            -fx-background-insets: 0;
            -fx-border-color: transparent;
            -fx-border-width: 0;
            -fx-effect: null;
            -fx-focus-color: transparent;
            -fx-faint-focus-color: transparent;
            -fx-padding: 0;
            """;

    private static final double DEFAULT_WIDTH = 380;
    private static final double MIN_WIDTH = 300;
    private static final double COMPACT_MIN_WIDTH = 240;

    private final Supplier<BorderPane> hostSupplier;
    private BorderPane root;
    private Region resizeHandle;
    private double dragStartSceneX;
    private double dragStartWidth;

    public QueueSidebarUiCoordinator(Supplier<BorderPane> hostSupplier) {
        this.hostSupplier = hostSupplier;
    }

    public void bindRoot(BorderPane root, Region resizeHandle) {
        this.root = root;
        this.resizeHandle = resizeHandle;
    }

    public void installIconButtons(Button closeButton, Button clearQueueButton) {
        installIconOnlyButton(closeButton, ICON_CLOSE, "Close queue menu", 22);
        installIconOnlyButton(clearQueueButton, ICON_CLEAR, "Clear queue", 20);
        SmallPopupTooltip.install(closeButton, "Close Menu");
        SmallPopupTooltip.install(clearQueueButton, "Clear queue");
    }

    public void installResizeBehavior() {
        if (root != null) {
            setSidebarWidth(DEFAULT_WIDTH);
        }
        if (resizeHandle == null) {
            return;
        }

        resizeHandle.setOnMousePressed(event -> {
            dragStartSceneX = event.getSceneX();
            dragStartWidth = currentSidebarWidth();
            event.consume();
        });
        resizeHandle.setOnMouseDragged(event -> {
            double delta = event.getSceneX() - dragStartSceneX;
            setSidebarWidth(dragStartWidth - delta);
            event.consume();
        });
    }

    public void bindResponsiveWidth(Scene scene) {
        if (scene == null || root == null) {
            return;
        }

        Runnable update = () -> setSidebarWidth(currentSidebarWidth());
        scene.widthProperty().addListener((obs, oldValue, newValue) -> update.run());
        Platform.runLater(update);
    }

    public void setSidebarWidth(double requestedWidth) {
        if (root == null) {
            return;
        }

        BorderPane host = hostSupplier == null ? null : hostSupplier.get();
        double availableWidth = host == null ? 0 : host.getWidth();
        Scene scene = root.getScene();
        if (availableWidth <= 0 && scene != null) {
            availableWidth = scene.getWidth();
        }

        double maxWidth = availableWidth > 0
                ? Math.max(COMPACT_MIN_WIDTH, availableWidth * 0.5)
                : 620;
        double minWidth = Math.min(MIN_WIDTH, maxWidth);
        double safeWidth = Math.max(minWidth, Math.min(maxWidth, requestedWidth));

        root.setMinWidth(minWidth);
        root.setPrefWidth(safeWidth);
        root.setMaxWidth(maxWidth);
    }

    private double currentSidebarWidth() {
        if (root == null) {
            return DEFAULT_WIDTH;
        }
        double width = root.getWidth();
        if (width <= 0) {
            width = root.getPrefWidth();
        }
        return width <= 0 ? DEFAULT_WIDTH : width;
    }

    private Node installIconOnlyButton(
            Button button,
            String iconPath,
            String accessibleText,
            double size
    ) {
        if (button == null) {
            return null;
        }

        button.setText("");
        button.setAccessibleText(accessibleText);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setStyle(CHROMELESS_BUTTON_STYLE);

        Node icon = SvgIconFactory.icon(iconPath, size);
        SvgIconFactory.setIconColor(icon, ICON_NORMAL);
        button.setGraphic(icon);
        button.hoverProperty().addListener((obs, oldValue, isHover) -> {
            button.setStyle(CHROMELESS_BUTTON_STYLE);
            updateIconColor(button, icon);
        });
        button.focusedProperty().addListener((obs, oldValue, isFocused) -> {
            button.setStyle(CHROMELESS_BUTTON_STYLE);
            updateIconColor(button, icon);
        });
        button.armedProperty().addListener(
                (obs, oldValue, isArmed) -> button.setStyle(CHROMELESS_BUTTON_STYLE)
        );
        button.pressedProperty().addListener(
                (obs, oldValue, isPressed) -> button.setStyle(CHROMELESS_BUTTON_STYLE)
        );
        return icon;
    }

    private void updateIconColor(Button button, Node icon) {
        if (icon == null) {
            return;
        }
        boolean highlighted = button != null && (button.isHover() || button.isFocused());
        SvgIconFactory.setIconColor(icon, highlighted ? ICON_HOVER : ICON_NORMAL);
    }
}
