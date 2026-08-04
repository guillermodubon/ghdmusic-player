package io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import io.github.guillermodubon.musicplayer.controllers.ui.components.icons.SvgIconFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.components.inputs.SearchBarController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.downloadComponents.DownloadSidebarMenuController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.downloadComponents.DownloadActivityIndicator;
import io.github.guillermodubon.musicplayer.services.downloads.activity.DownloadActivityTracker;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane.QueueController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.searchDropdown.SearchDropdownController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.popups.SmallPopupTooltip;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.ArtistCardActionManager;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.services.navigation.SceneStateFlowManager;
import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.function.Consumer;
import javafx.scene.Parent;
import javafx.scene.Scene;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;


public class HeaderController {

    private static final String ICON_ROOT = "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/";
    private static final String ICON_BACK = ICON_ROOT + "arrow_back_ios_27dp_EDCC0E_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_NEXT = ICON_ROOT + "arrow_forward_ios_27dp_EDCC0E_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_DOWNLOAD = ICON_ROOT + "download_27dp_EDCC0E_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_COLOR = "#AFAFAF";
    private static final String ICON_HOVER_COLOR = "#FFFFFF";
    private static final String ICON_ACTIVE_COLOR = "#0077B6FF";
    private static final String ICON_DOWNLOAD_IN_PROGRESS_COLOR = "#F0F0F0";
    private static final String ICON_DISABLED_COLOR = "#3A3A3A";

    @FXML private HBox root;
    @FXML private Button btnPrevious;
    @FXML private Button btnNext;
    @FXML private Button btnDownloads;
    @FXML private StackPane searchBox;
    @FXML private SearchBarController searchBarController;
    private TextField searchField;

    private Consumer<String> onFilterChanged;
    private StartUpService startUpService;
    private MusicCardActionManager musicCardActions;
    private ArtistCardActionManager artistCardActions;

    private SearchDropdownController dropdownController;
    private Parent downloadSidebarRoot;
    private DownloadSidebarMenuController downloadSidebarMenuController;
    private Node previousIcon;
    private Node nextIcon;
    private Node downloadIcon;
    private DownloadActivityIndicator downloadActivityIndicator;

    public void init(Consumer<String> onFilterChanged,
                     MusicCardActionManager musicCardActions,
                     ArtistCardActionManager artistCardActions,
                     StartUpService startUpService) {
        this.onFilterChanged = onFilterChanged;
        this.musicCardActions = musicCardActions;
        this.artistCardActions = artistCardActions;
        this.startUpService = startUpService;

        initSearchDropdown();
        configureSearch();
    }

    @FXML
    private void initialize() {
        if (root != null) {
            root.getStyleClass().add("app-header");
        }
        if (btnPrevious != null) {
            btnPrevious.getStyleClass().add("header-nav-button");
            previousIcon = installIconOnlyButton(btnPrevious, ICON_BACK, "Back");
        }
        if (btnNext != null) {
            btnNext.getStyleClass().add("header-nav-button");
            nextIcon = installIconOnlyButton(btnNext, ICON_NEXT, "Next");
        }
        if (btnDownloads != null) {
            btnDownloads.getStyleClass().add("header-action-button");
            downloadIcon = installDownloadActivityButton(btnDownloads);
            SmallPopupTooltip.install(btnDownloads, "Manage Downloads");
            DownloadSidebarMenuController.downloadVisibleProperty().addListener((obs, oldValue, newValue) ->
                    updateHeaderIconColor(btnDownloads, downloadIcon));
            DownloadActivityTracker.getInstance().activeProperty().addListener((obs, oldValue, active) ->
                    updateHeaderIconColor(btnDownloads, downloadIcon));
        }
        if (searchBarController != null) {
            searchBarController.setPromptText("Search for whatever you want to listen to.");
            searchField = searchBarController.getTextField();
        }
        bindNavigationButtonStates();
    }

    private Node installIconOnlyButton(Button button, String resourcePath, String accessibleText) {
        if (button == null) return null;
        button.setText("");
        button.setAccessibleText(accessibleText);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        Node icon = SvgIconFactory.icon(resourcePath, 23);
        SvgIconFactory.setIconColor(icon, ICON_COLOR);
        button.hoverProperty().addListener((obs, wasHover, isHover) ->
                updateHeaderIconColor(button, icon));
        button.focusedProperty().addListener((obs, wasFocused, isFocused) ->
                updateHeaderIconColor(button, icon));
        button.disabledProperty().addListener((obs, wasDisabled, isDisabled) ->
                updateHeaderIconColor(button, icon));
        button.setGraphic(icon);
        updateHeaderIconColor(button, icon);
        return icon;
    }

    private Node installDownloadActivityButton(Button button) {
        if (button == null) return null;
        button.setText("");
        button.setAccessibleText("Downloads");
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);

        downloadActivityIndicator = new DownloadActivityIndicator();
        Node icon = downloadActivityIndicator.getDownloadIcon();
        button.hoverProperty().addListener((obs, wasHover, isHover) -> updateHeaderIconColor(button, icon));
        button.focusedProperty().addListener((obs, wasFocused, isFocused) -> updateHeaderIconColor(button, icon));
        button.disabledProperty().addListener((obs, wasDisabled, isDisabled) -> updateHeaderIconColor(button, icon));
        button.setGraphic(downloadActivityIndicator);
        updateHeaderIconColor(button, icon);
        return icon;
    }

    private void updateHeaderIconColor(Button button, Node icon) {
        if (icon == null) return;
        if (button != null && button.isDisabled()) {
            SvgIconFactory.setIconColor(icon, ICON_DISABLED_COLOR);
            return;
        }
        if (button != null && button.isHover()) {
            SvgIconFactory.setIconColor(icon, ICON_HOVER_COLOR);
            return;
        }
        if (button == btnDownloads && DownloadSidebarMenuController.isDownloadVisible()) {
            SvgIconFactory.setIconColor(icon, ICON_ACTIVE_COLOR);
            return;
        }
        if (button == btnDownloads && downloadActivityIndicator != null && downloadActivityIndicator.isActive()) {
            SvgIconFactory.setIconColor(icon, ICON_DOWNLOAD_IN_PROGRESS_COLOR);
            return;
        }
        SvgIconFactory.setIconColor(icon, ICON_COLOR);
    }

    private void bindNavigationButtonStates() {
        SceneStateFlowManager navigation = SceneStateFlowManager.getInstance();
        if (btnPrevious != null && !btnPrevious.disableProperty().isBound()) {
            btnPrevious.disableProperty().bind(navigation.canNavigateBackProperty().not());
        }
        if (btnNext != null && !btnNext.disableProperty().isBound()) {
            btnNext.disableProperty().bind(navigation.canNavigateForwardProperty().not());
        }
    }

    private void initSearchDropdown() {
        try {
            URL fxml = getClass().getResource("/io/github/guillermodubon/musicplayer/Views/components/layoutComponents/searchDropwdown/SearchDropdown.fxml");
            if (fxml == null) return;

            FXMLLoader loader = new FXMLLoader(fxml);
            loader.load();

            dropdownController = loader.getController();
            if (dropdownController != null) {
                dropdownController.bindToTextField(
                        searchField,
                        onFilterChanged,
                        musicCardActions,
                        artistCardActions,
                        startUpService
                );
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void configureSearch() {
        if (searchField == null) return;

        searchField.textProperty().addListener((obs, oldValue, newValue) -> {
            if (onFilterChanged != null) {
                onFilterChanged.accept(newValue == null ? "" : newValue);
            }
        });
    }

    @FXML
    private void onPreviousClicked() {
        try {
            SceneStateFlowManager.getInstance().navigateBack();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    @FXML
    private void onNextClicked() {
        try {
            SceneStateFlowManager.getInstance().navigateForward();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    @FXML
    private void onDownloadsClicked() {
        if (searchField == null || searchField.getScene() == null) return;

        Parent sceneRoot = searchField.getScene().getRoot();
        if (sceneRoot == null) return;

        try {
            if (downloadSidebarRoot == null) {
                loadDownloadSidebar();
            }

            if (downloadSidebarRoot != null && downloadSidebarRoot.getParent() != null) {
                if (downloadSidebarMenuController != null) {
                    downloadSidebarMenuController.hide();
                }
                return;
            }

            QueueController qc = QueueController.getInstance();
            if (qc != null && QueueController.isQueueVisible()) {
                qc.closeFromOwner();
            }

            if (downloadSidebarMenuController != null) {
                downloadSidebarMenuController.showInRoot(sceneRoot);
            }
        } catch (Exception e) {
            e.printStackTrace();
            openDownloadsInFallbackWindow();
        }
    }

    private void loadDownloadSidebar() throws IOException {
        URL fxmlUrl = getClass().getResource("/io/github/guillermodubon/musicplayer/Views/components/layoutComponents/downloadSideBarMenu/DownloadSideBarMenu.fxml");
        if (fxmlUrl == null) return;

        FXMLLoader loader = new FXMLLoader(Objects.requireNonNull(fxmlUrl));
        Parent view = loader.load();

        Object ctrl = loader.getController();
        if (view != null && ctrl != null) {
            view.getProperties().put("controller", ctrl);
        }

        downloadSidebarRoot = view;
        if (ctrl instanceof DownloadSidebarMenuController c) {
            downloadSidebarMenuController = c;
        }
    }

    private void openDownloadsInFallbackWindow() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/io/github/guillermodubon/musicplayer/Views/components/layoutComponents/downloadSideBarMenu/DownloadSideBarMenu.fxml"));
            Parent view = loader.load();

            Object ctrl = loader.getController();
            if (view != null && ctrl != null) {
                view.getProperties().put("controller", ctrl);
            }

            Stage s = new Stage();
            s.setTitle("Downloads");
            s.setScene(new Scene(view));
            if (searchField != null && searchField.getScene() != null) {
                s.initOwner(searchField.getScene().getWindow());
            }
            s.show();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
