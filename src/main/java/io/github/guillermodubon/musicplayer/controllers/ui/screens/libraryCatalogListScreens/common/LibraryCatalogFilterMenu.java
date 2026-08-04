package io.github.guillermodubon.musicplayer.controllers.ui.screens.libraryCatalogListScreens.common;

import io.github.guillermodubon.musicplayer.controllers.ui.components.icons.SvgIconFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.components.popups.SmallPopupTooltip;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.Scene;

import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Reusable filter button and popup presentation for library catalog screens. */
public final class LibraryCatalogFilterMenu {

    private static final String OPTION_HANDLER_INSTALLED_KEY =
            LibraryCatalogFilterMenu.class.getName() + ".optionHandlerInstalled";
    private static final String POPUP_STYLESHEET_LISTENER_INSTALLED_KEY =
            LibraryCatalogFilterMenu.class.getName() + ".popupStylesheetListenerInstalled";
    private static final String PLAYER_MENU_SORT_BUTTON_STYLE_CLASS = "player-menu-sort-button";
    private static final String PLAYER_MENU_STYLESHEET =
            "/io/github/guillermodubon/musicplayer/Views/screens/playerMenu/player-menu.css";
    private static final String FILTER_MENU_STYLESHEET =
            "/io/github/guillermodubon/musicplayer/Views/screens/libraryCatalogListScreens/common/library-catalog-filter.css";
    private static final String ICON_ROOT = "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/";
    private static final String ICON_FILTER = ICON_ROOT + "filter_alt_27dp_AFAFAF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_SELECTED_SORT = ICON_ROOT + "south_24dp_AFAFAF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String FILTER_ACCENT_COLOR = "#0077B6";
    private static final String FILTER_NORMAL_COLOR = "#AFAFAF";

    public record Option(String id, String label) {
        public Option {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
        }
    }

    private final MenuButton button;
    private final List<Option> options;
    private final Consumer<Option> onSelected;
    private final String iconPath;
    private final String buttonStyleClass;
    private final String menuStyleClass;
    private final String accessibleText;
    private final String tooltipText;
    private final double iconSize;
    private final Map<String, MenuItem> items = new LinkedHashMap<>();
    private final Map<String, HBox> rows = new LinkedHashMap<>();
    private final Map<String, Label> labels = new LinkedHashMap<>();

    private String selectedId;
    private Node filterIcon;
    private Label headerLabel;
    private HBox headerRow;

    public LibraryCatalogFilterMenu(MenuButton button,
                                    List<Option> options,
                                    String initialId,
                                    Consumer<Option> onSelected) {
        this(
                button,
                options,
                initialId,
                onSelected,
                ICON_FILTER,
                "catalog-filter-button",
                "Filter library catalog",
                "Change filter",
                20
        );
    }

    public LibraryCatalogFilterMenu(MenuButton button,
                                    List<Option> options,
                                    String initialId,
                                    Consumer<Option> onSelected,
                                    String iconPath,
                                    String buttonStyleClass,
                                    String accessibleText,
                                    String tooltipText) {
        this(
                button,
                options,
                initialId,
                onSelected,
                iconPath,
                buttonStyleClass,
                accessibleText,
                tooltipText,
                20
        );
    }

    public LibraryCatalogFilterMenu(MenuButton button,
                                    List<Option> options,
                                    String initialId,
                                    Consumer<Option> onSelected,
                                    String iconPath,
                                    String buttonStyleClass,
                                    String accessibleText,
                                    String tooltipText,
                                    double iconSize) {
        this.button = button;
        this.options = options == null ? List.of() : List.copyOf(options);
        this.onSelected = onSelected;
        this.iconPath = iconPath == null || iconPath.isBlank() ? ICON_FILTER : iconPath;
        this.buttonStyleClass = buttonStyleClass == null || buttonStyleClass.isBlank()
                ? "catalog-filter-button"
                : buttonStyleClass;
        this.menuStyleClass = this.buttonStyleClass + "-menu";
        this.accessibleText = accessibleText == null || accessibleText.isBlank()
                ? "Filter library catalog"
                : accessibleText;
        this.tooltipText = tooltipText == null || tooltipText.isBlank()
                ? "Change filter"
                : tooltipText;
        this.iconSize = iconSize > 0 ? iconSize : 20;
        this.selectedId = resolveOption(initialId).id();
        configure();
    }

    public String selectedId() {
        return selectedId;
    }

    public Option selectedOption() {
        return resolveOption(selectedId);
    }

    public void setSelected(String id) {
        String resolvedId = resolveOption(id).id();
        if (!Objects.equals(selectedId, resolvedId)) {
            selectedId = resolvedId;
        }
        updatePresentation();
    }

    public void setVisible(boolean visible) {
        if (button == null) return;
        button.setVisible(visible);
        button.setManaged(visible);
    }

    private void configure() {
        if (button == null) return;

        button.setContentDisplay(ContentDisplay.RIGHT);
        button.setAccessibleText(accessibleText);
        if (!button.getStyleClass().contains(buttonStyleClass)) {
            button.getStyleClass().add(buttonStyleClass);
        }

        filterIcon = SvgIconFactory.icon(iconPath, iconSize);
        filterIcon.getStyleClass().add("catalog-filter-button-icon");
        button.setGraphic(filterIcon);
        button.hoverProperty().addListener((obs, oldValue, newValue) -> updateFilterIcon());
        button.focusedProperty().addListener((obs, oldValue, newValue) -> updateFilterIcon());
        button.showingProperty().addListener((obs, oldValue, newValue) -> {
            updatePresentation();
            if (newValue) {
                Platform.runLater(this::installOptionContainerHandlers);
            }
        });
        SmallPopupTooltip.install(button, tooltipText);

        headerLabel = new Label("Sort by");
        headerLabel.getStyleClass().add("catalog-filter-menu-header-label");
        headerLabel.setMouseTransparent(true);
        headerRow = new HBox(headerLabel);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.setMouseTransparent(true);
        CustomMenuItem header = new CustomMenuItem(headerRow, false);
        header.setDisable(true);
        header.setStyle(
                "-fx-background-color: transparent;"
                        + "-fx-background-insets: 0;"
                        + "-fx-border-color: transparent;"
                        + "-fx-border-width: 0;"
                        + "-fx-opacity: 1;"
                        + "-fx-cursor: default;"
        );
        header.getStyleClass().addAll("catalog-filter-menu-header", menuStyleClass + "-header");

        List<MenuItem> menuItems = new ArrayList<>();
        menuItems.add(header);
        for (Option option : options) {
            Label label = new Label(option.label());
            label.getStyleClass().add("catalog-filter-option-label");
            HBox row = new HBox(14, label);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setMaxWidth(Double.MAX_VALUE);
            row.getStyleClass().add(menuStyleClass + "-option-row");

            CustomMenuItem item = new CustomMenuItem(row, true);
            item.getStyleClass().addAll("catalog-filter-menu-item", menuStyleClass + "-item");
            item.setOnAction(event -> selectAndHide(option.id()));
            row.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
                if (event.getButton() != MouseButton.PRIMARY) return;
                selectAndHide(option.id());
                event.consume();
            });
            items.put(option.id(), item);
            rows.put(option.id(), row);
            labels.put(option.id(), label);
            menuItems.add(item);
        }
        button.getItems().setAll(menuItems);
        updatePresentation();
    }

    private void installOptionContainerHandlers() {
        attachPlayerMenuPopupStylesheet();
        configurePlayerMenuHeaderContainer();
        for (Option option : options) {
            HBox row = rows.get(option.id());
            if (row == null) continue;

            Node container = findMenuItemContainer(row);
            if (container == null
                    || container.getProperties().putIfAbsent(OPTION_HANDLER_INSTALLED_KEY, Boolean.TRUE) != null) {
                continue;
            }
            container.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
                if (event.getButton() != MouseButton.PRIMARY) return;
                selectAndHide(option.id());
                event.consume();
            });
        }
    }

    private void configurePlayerMenuHeaderContainer() {
        if (!PLAYER_MENU_SORT_BUTTON_STYLE_CLASS.equals(buttonStyleClass) || headerRow == null) return;

        Node container = findMenuItemContainer(headerRow);
        if (container == null) return;

        container.setMouseTransparent(true);
        container.setStyle(
                "-fx-background-color: transparent;"
                        + "-fx-background-insets: 0;"
                        + "-fx-background-radius: 0;"
                        + "-fx-border-color: transparent;"
                        + "-fx-border-width: 0;"
                        + "-fx-padding: 9 14 9 12;"
                        + "-fx-opacity: 1;"
                        + "-fx-cursor: default;"
        );
        headerRow.setStyle("-fx-background-color: transparent;");
        headerLabel.setStyle(
                "-fx-text-fill: #AFAFAF;"
                        + "-fx-font-size: 13px;"
                        + "-fx-font-weight: bold;"
                        + "-fx-cursor: default;"
        );
    }

    private void attachPlayerMenuPopupStylesheet() {
        if (!PLAYER_MENU_SORT_BUTTON_STYLE_CLASS.equals(buttonStyleClass)) return;

        Node popupContent = rows.values().stream().findFirst().orElse(null);
        if (popupContent == null) return;
        Scene popupScene = popupContent.getScene();
        if (popupScene == null) {
            if (popupContent.getProperties().putIfAbsent(
                    POPUP_STYLESHEET_LISTENER_INSTALLED_KEY,
                    Boolean.TRUE
            ) == null) {
                popupContent.sceneProperty().addListener((obs, oldScene, newScene) -> {
                    if (newScene != null) {
                        attachStylesheet(newScene, PLAYER_MENU_STYLESHEET);
                        attachStylesheet(newScene, FILTER_MENU_STYLESHEET);
                    }
                });
            }
            return;
        }

        attachStylesheet(popupScene, PLAYER_MENU_STYLESHEET);
        attachStylesheet(popupScene, FILTER_MENU_STYLESHEET);
    }

    private void attachStylesheet(Scene scene, String resourcePath) {
        URL stylesheet = LibraryCatalogFilterMenu.class.getResource(resourcePath);
        if (stylesheet == null) return;

        String stylesheetUrl = stylesheet.toExternalForm();
        if (!scene.getStylesheets().contains(stylesheetUrl)) {
            scene.getStylesheets().add(stylesheetUrl);
        }
    }

    private Node findMenuItemContainer(Node content) {
        Node current = content.getParent();
        Node fallback = null;
        while (current != null) {
            if (current.getStyleClass().contains("context-menu")) {
                break;
            }
            if (current.getStyleClass().contains("menu-item")) {
                return current;
            }
            fallback = current;
            current = current.getParent();
        }
        return fallback;
    }

    private void selectAndHide(String id) {
        select(id);
        if (button != null) {
            button.hide();
        }
    }

    private void select(String id) {
        Option option = resolveOption(id);
        boolean changed = !Objects.equals(selectedId, option.id());
        selectedId = option.id();
        updatePresentation();
        if (changed && onSelected != null) {
            onSelected.accept(option);
        }
    }

    private Option resolveOption(String id) {
        return options.stream()
                .filter(option -> Objects.equals(option.id(), id))
                .findFirst()
                .orElse(options.isEmpty() ? new Option("", "") : options.get(0));
    }

    private void updateFilterIcon() {
        if (button == null || filterIcon == null) return;
        SvgIconFactory.setIconColor(
                filterIcon,
                button.isHover() || button.isFocused() ? "#FFFFFF" : FILTER_NORMAL_COLOR
        );
    }

    private void updatePresentation() {
        if (button == null) return;
        button.setText(selectedOption().label());
        updateFilterIcon();

        for (Option option : options) {
            MenuItem item = items.get(option.id());
            HBox row = rows.get(option.id());
            Label label = labels.get(option.id());
            if (item == null || row == null || label == null) continue;

            boolean selected = Objects.equals(option.id(), selectedId);
            item.getStyleClass().remove("catalog-filter-menu-item-selected");
            label.setStyle(
                    "-fx-text-fill: " + (selected ? FILTER_ACCENT_COLOR : "#FFFFFF") + ";"
                            + "-fx-font-size: 14px;"
                            + "-fx-font-weight: bold;"
            );
            row.getChildren().clear();
            row.getChildren().add(label);
            if (selected) {
                item.getStyleClass().add("catalog-filter-menu-item-selected");
                Node icon = SvgIconFactory.icon(ICON_SELECTED_SORT, 20);
                icon.getStyleClass().add("catalog-filter-selected-icon");
                SvgIconFactory.setIconColor(icon, FILTER_ACCENT_COLOR);
                row.getChildren().add(icon);
            }
        }
    }
}
