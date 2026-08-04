package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.footer;

import javafx.geometry.Insets;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import io.github.guillermodubon.musicplayer.controllers.ui.components.icons.SvgIconFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.RecommendedSongForPlaylistItemController;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.util.function.Consumer;

/** Builds the reusable recommendation list cells and refresh control. */
public final class PlayerMenuPlaylistRecommendationRenderer {

    private static final String ICON_REFRESH =
            "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/refresh_27dp_FAFAFA_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_NORMAL = "#FAFAFA";
    private static final String ICON_HOVER = "#FFFFFF";
    private static final String ICON_BUTTON_CHROMELESS_STYLE = """
            -fx-background-color: transparent;
            -fx-background-insets: 0;
            -fx-border-color: transparent;
            -fx-border-width: 0;
            -fx-effect: null;
            -fx-focus-color: transparent;
            -fx-faint-focus-color: transparent;
            -fx-padding: 0;
            """;

    private PlayerMenuPlaylistRecommendationRenderer() {
    }

    public static void configureList(ListView<Song> list,
                                     StartUpService service,
                                     Consumer<Song> onPlay,
                                     Consumer<Song> onAdd) {
        if (list == null) return;
        list.setCellFactory(owner -> new ListCell<>() {
            @Override
            protected void updateItem(Song item, boolean empty) {
                super.updateItem(item, empty);
                if (getGraphic() != null && getGraphic().getProperties().get("recommendedSongController")
                        instanceof RecommendedSongForPlaylistItemController controller) {
                    controller.deactivatePlayingState();
                }
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                try {
                    FXMLLoader loader = new FXMLLoader(getClass().getResource(
                            "/io/github/guillermodubon/musicplayer/Views/components/items/RecommendedSongForPlaylistItemController.fxml"));
                    HBox cell = loader.load();
                    RecommendedSongForPlaylistItemController controller = loader.getController();
                    controller.init(item, service, onPlay, onAdd);
                    cell.getProperties().put("recommendedSongController", controller);
                    prepareCellGraphic(cell, owner);
                    setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                    setPadding(Insets.EMPTY);
                    setGraphic(cell);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    setText(item.getTitle());
                    setGraphic(null);
                }
            }
        });
    }

    public static void configureRefreshButton(Button button, Runnable onRefresh) {
        if (button == null || Boolean.TRUE.equals(
                button.getProperties().get("playerMenuRefreshIconInstalled"))) return;

        Node icon = SvgIconFactory.icon(ICON_REFRESH, 21);
        SvgIconFactory.setIconColor(icon, ICON_NORMAL);
        button.setText("");
        button.setGraphic(icon);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setFocusTraversable(false);
        button.setStyle(ICON_BUTTON_CHROMELESS_STYLE);
        button.getProperties().put("playerMenuRefreshIconInstalled", Boolean.TRUE);
        io.github.guillermodubon.musicplayer.controllers.ui.components.popups.SmallPopupTooltip.install(
                button, "Refresh list");
        button.setOnAction(event -> {
            if (onRefresh != null) onRefresh.run();
        });
        button.hoverProperty().addListener((obs, oldValue, hovered) -> updateIconColor(button, icon));
        button.focusedProperty().addListener((obs, oldValue, focused) -> updateIconColor(button, icon));
        button.armedProperty().addListener((obs, oldValue, armed) -> button.setStyle(ICON_BUTTON_CHROMELESS_STYLE));
        button.pressedProperty().addListener((obs, oldValue, pressed) -> button.setStyle(ICON_BUTTON_CHROMELESS_STYLE));
        updateIconColor(button, icon);
    }

    private static void prepareCellGraphic(HBox cell, ListView<Song> owner) {
        if (cell == null || owner == null) return;
        cell.setMaxWidth(Double.MAX_VALUE);
        cell.setMinHeight(58);
        cell.setPrefHeight(58);
        cell.setMaxHeight(58);
        cell.prefWidthProperty().unbind();
        cell.prefWidthProperty().bind(owner.widthProperty().subtract(58));
    }

    private static void updateIconColor(Button button, Node icon) {
        if (button == null || icon == null) return;
        SvgIconFactory.setIconColor(icon, button.isHover() || button.isFocused()
                ? ICON_HOVER : ICON_NORMAL);
    }
}
