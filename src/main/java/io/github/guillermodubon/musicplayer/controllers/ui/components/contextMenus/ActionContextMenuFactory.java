package io.github.guillermodubon.musicplayer.controllers.ui.components.contextMenus;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.stage.Screen;
import io.github.guillermodubon.musicplayer.controllers.ui.components.icons.SvgIconFactory;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;

public final class ActionContextMenuFactory {

    private static final String FXML = "/io/github/guillermodubon/musicplayer/Views/components/contextMenus/ActionContextMenu.fxml";
    private static final String CSS = "/io/github/guillermodubon/musicplayer/Views/components/contextMenus/action-context-menu.css";
    private static final String ICON_ROOT = "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/";
    public static final String ICON_ADD_TO_PLAYLIST = ICON_ROOT + "library_add_27dp_AFAFAF_FILL0_wght400_GRAD0_opsz24.svg";
    public static final String ICON_ADD_TO_QUEUE = ICON_ROOT + "playlist_add_check_27dp_AFAFAF_FILL0_wght400_GRAD0_opsz24.svg";
    public static final String ICON_OPEN_SONG_LOCATION = ICON_ROOT + "folder_open_26dp_FFFFFF_FILL0_wght400_GRAD0_opsz24.svg";

    private ActionContextMenuFactory() {
    }

    public static ContextMenu songActions(Runnable addToPlaylist, Runnable addToQueue) {
        return songActions(addToPlaylist, addToQueue, null);
    }

    public static ContextMenu songActions(Runnable addToPlaylist,
                                          Runnable addToQueue,
                                          Runnable openSongLocation) {
        ContextMenu menu = loadMenu();
        MenuItem addToPlaylistItem = iconItem("Add to Playlist", ICON_ADD_TO_PLAYLIST, addToPlaylist);
        MenuItem addToQueueItem = iconItem("Add to Queue", ICON_ADD_TO_QUEUE, addToQueue);
        MenuItem openSongLocationItem = iconItem(
                "Open song location",
                ICON_OPEN_SONG_LOCATION,
                openSongLocation
        );

        if (openSongLocation == null) {
            menu.getItems().setAll(addToPlaylistItem, addToQueueItem);
        } else {
            menu.getItems().setAll(addToPlaylistItem, addToQueueItem, openSongLocationItem);
        }
        installStylesheet(menu);
        return menu;
    }

    public static ContextMenu iconMenu(MenuItem... items) {
        ContextMenu menu = loadMenu();
        if (items != null) {
            menu.getItems().setAll(items);
        }
        installStylesheet(menu);
        return menu;
    }

    public static void showNearButton(ContextMenu menu, Node anchor) {
        if (menu == null || anchor == null) return;

        if (menu.isShowing()) {
            menu.hide();
            return;
        }

        Bounds anchorBounds = anchor.localToScreen(anchor.getBoundsInLocal());
        if (anchorBounds == null) {
            menu.show(anchor, 0, 0);
            Platform.runLater(() -> installStylesheet(menu));
            return;
        }

        Rectangle2D screenBounds = resolveScreenBounds(anchorBounds);
        double gap = 6;
        double initialX = clamp(anchorBounds.getMaxX() - 190, screenBounds.getMinX() + 8, screenBounds.getMaxX() - 190);
        double initialY = anchorBounds.getMaxY() + gap;

        menu.show(anchor, initialX, initialY);
        Platform.runLater(() -> {
            installStylesheet(menu);
            positionMenu(menu, anchorBounds, screenBounds, gap);
        });
    }

    private static ContextMenu loadMenu() {
        try {
            FXMLLoader loader = new FXMLLoader(ActionContextMenuFactory.class.getResource(FXML));
            Object loaded = loader.load();
            if (loaded instanceof ContextMenu menu) {
                if (!menu.getStyleClass().contains("app-action-context-menu")) {
                    menu.getStyleClass().add("app-action-context-menu");
                }
                return menu;
            }
        } catch (IOException ignored) {
        }

        ContextMenu fallback = new ContextMenu();
        fallback.getStyleClass().add("app-action-context-menu");
        return fallback;
    }

    public static MenuItem iconItem(String text, String iconPath, Runnable action) {
        return iconItem(text, iconPath, "#AFAFAF", action);
    }

    public static MenuItem iconItem(String text,
                                    String iconPath,
                                    String iconColor,
                                    Runnable action) {
        MenuItem item = new MenuItem(text);
        item.getStyleClass().add("app-action-menu-item");

        Node icon = SvgIconFactory.icon(iconPath, 18);
        icon.getStyleClass().add("action-menu-icon");
        SvgIconFactory.setIconColor(icon, iconColor);
        item.setGraphic(icon);

        item.setOnAction(event -> {
            if (action != null) action.run();
        });
        return item;
    }

    private static void installStylesheet(ContextMenu menu) {
        if (menu == null || menu.getScene() == null) return;

        URL cssUrl = ActionContextMenuFactory.class.getResource(CSS);
        if (cssUrl == null) return;

        String stylesheet = cssUrl.toExternalForm();
        if (!menu.getScene().getStylesheets().contains(stylesheet)) {
            menu.getScene().getStylesheets().add(stylesheet);
        }
    }

    private static void positionMenu(ContextMenu menu, Bounds anchorBounds, Rectangle2D screenBounds, double gap) {
        if (menu == null || anchorBounds == null || screenBounds == null) return;

        double menuWidth = Math.max(1, menu.getWidth());
        double menuHeight = Math.max(1, menu.getHeight());
        double belowSpace = screenBounds.getMaxY() - anchorBounds.getMaxY();
        double aboveSpace = anchorBounds.getMinY() - screenBounds.getMinY();

        boolean showAbove = belowSpace < menuHeight + gap && aboveSpace > belowSpace;
        double x = anchorBounds.getMaxX() - menuWidth;
        double y = showAbove
                ? anchorBounds.getMinY() - menuHeight - gap
                : anchorBounds.getMaxY() + gap;

        x = clamp(x, screenBounds.getMinX() + 8, screenBounds.getMaxX() - menuWidth - 8);
        y = clamp(y, screenBounds.getMinY() + 8, screenBounds.getMaxY() - menuHeight - 8);

        menu.setX(x);
        menu.setY(y);
    }

    private static Rectangle2D resolveScreenBounds(Bounds anchorBounds) {
        return Screen.getScreensForRectangle(
                        anchorBounds.getMinX(),
                        anchorBounds.getMinY(),
                        Math.max(1, anchorBounds.getWidth()),
                        Math.max(1, anchorBounds.getHeight()))
                .stream()
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(Screen.getPrimary())
                .getVisualBounds();
    }

    private static double clamp(double value, double min, double max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }
}
