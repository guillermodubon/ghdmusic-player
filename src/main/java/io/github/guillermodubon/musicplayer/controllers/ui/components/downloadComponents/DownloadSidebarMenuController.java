package io.github.guillermodubon.musicplayer.controllers.ui.components.downloadComponents;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.concurrent.Worker;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Window;
import javafx.util.Duration;
import io.github.guillermodubon.musicplayer.controllers.layout.AppShellController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.icons.SvgIconFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.PlayerMenuController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.AlbumPlaybackCoordinator;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.PlaylistPlaybackCoordinator;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.SongPlaybackCoordinator;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.services.downloads.DownloadTask;
import io.github.guillermodubon.musicplayer.controllers.ui.components.downloadComponents.downloadCell.DownloadCell;
import io.github.guillermodubon.musicplayer.services.downloads.DownloadManager;
import io.github.guillermodubon.musicplayer.services.downloads.context.DownloadTaskContext;
import io.github.guillermodubon.musicplayer.services.downloads.preferences.DownloadPreferences;
import io.github.guillermodubon.musicplayer.controllers.ui.components.popups.SmallPopupTooltip;
import io.github.guillermodubon.musicplayer.services.navigation.PlayerMenuNavigator;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.io.File;
import java.util.*;

public class DownloadSidebarMenuController {

    private static final String ICON_ROOT = "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/";
    private static final String ICON_CLOSE = ICON_ROOT + "right_panel_close_27dp_FFFFFF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_FOLDER = ICON_ROOT + "folder_27dp_FFFFFF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_FOLDER_OPEN = ICON_ROOT + "folder_open_26dp_FFFFFF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_NORMAL = "#DCDCDC";
    private static final String ICON_HOVER = "#FFFFFF";
    private static final String ICON_ACTIVE = "#0077B6FF";
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
    private static final double DEFAULT_WIDTH = 380;
    private static final double MIN_WIDTH = 300;
    private static final double COMPACT_MIN_WIDTH = 240;
    private static final BooleanProperty DOWNLOAD_VISIBLE = new SimpleBooleanProperty(false);
    private static DownloadSidebarMenuController activeController;

    @FXML private StackPane downloadSidebarPane;
    @FXML private Region resizeHandle;
    @FXML private HBox locationRow;
    @FXML private Button choosePathButton;
    @FXML private Button clearAllButton;
    @FXML private Label selectedPathLabel;
    @FXML private Button closeButton;
    @FXML private ListView<DownloadTask> downloadsListView;

    private File selectedDirectory;
    private BorderPane hostBorderPane = null;
    private AnchorPane overlayPane;
    private Node closeIcon;
    private Node folderIcon;
    private double dragStartSceneX;
    private double dragStartWidth;
    private ObservableList<DownloadTask> tasks;
    private final Map<DownloadTask, ChangeListener<Worker.State>> taskStateListeners = new IdentityHashMap<>();
    private final Map<DownloadTask, ChangeListener<DownloadTask.ResultStatus>> taskResultListeners = new IdentityHashMap<>();
    private final Set<DownloadTask> terminalTasks = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean structuralRefreshScheduled;

    public static BooleanProperty downloadVisibleProperty() {
        return DOWNLOAD_VISIBLE;
    }

    public static boolean isDownloadVisible() {
        return DOWNLOAD_VISIBLE.get();
    }

    public static void closeActiveSidebar() {
        DownloadSidebarMenuController controller = activeController;
        if (controller != null) {
            controller.hide();
        } else {
            DOWNLOAD_VISIBLE.set(false);
        }
    }

    @FXML
    public void initialize() {
        DownloadManager.getInstance().setSidebarController(this);
        selectedDirectory = DownloadPreferences.loadDownloadDirectory();
        updateSelectedPathLabel();
        installIconButtons();
        bindPathLabelWidth();
        installResizeBehavior();

        choosePathButton.setOnAction(e -> {
            setFolderButtonActive(true);
            Platform.runLater(() -> {
                Window window = choosePathButton.getScene() != null ? choosePathButton.getScene().getWindow() : null;
                try {
                    File chosen = DownloadDirectoryChooser.chooseDirectory(
                            window,
                            "Choose Download Folder",
                            selectedDirectory
                    );
                    if (chosen != null) {
                        selectedDirectory = chosen;
                        updateSelectedPathLabel();
                        DownloadPreferences.saveDownloadDirectory(selectedDirectory);
                    }
                } finally {
                    setFolderButtonActive(false);
                }
            });
        });

        closeButton.setOnAction(e -> hide());

        tasks = DownloadManager.getInstance().getTasks();
        downloadsListView.setItems(tasks);
        downloadsListView.setCellFactory(
                listView -> new DownloadCell(
                        tasks,
                        this::navigateToDownloadSource
                )
        );
        installEmptyDownloadsPlaceholder();
        clearAllButton.setOnAction(event -> clearTerminalDownloads());
        installClearAllTracking();
    }

    private void installEmptyDownloadsPlaceholder() {
        if (downloadsListView == null) return;
        Label placeholder = new Label("You don't have any active downloads at the moment.");
        placeholder.setWrapText(true);
        placeholder.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        placeholder.setAlignment(javafx.geometry.Pos.CENTER);
        placeholder.setMaxWidth(260);
        placeholder.getStyleClass().add("download-empty-state");
        downloadsListView.setPlaceholder(placeholder);
    }

    private void installClearAllTracking() {
        if (tasks == null) return;
        tasks.forEach(this::trackTaskState);
        tasks.addListener((ListChangeListener<DownloadTask>) change -> {
            while (change.next()) {
                if (change.wasRemoved()) change.getRemoved().forEach(this::untrackTaskState);
                if (change.wasAdded()) change.getAddedSubList().forEach(this::trackTaskState);
            }
            updateClearAllVisibility();
            requestStructuralListRefresh();
        });
        updateClearAllVisibility();
    }

    private void trackTaskState(DownloadTask task) {
        if (task == null || taskStateListeners.containsKey(task)) return;

        ChangeListener<Worker.State> stateListener =
                (obs, oldState, newState) -> {
                    syncTerminalState(task);
                };
        ChangeListener<DownloadTask.ResultStatus> resultListener =
                (obs, oldStatus, newStatus) -> {
                    syncTerminalState(task);
                };
        task.stateProperty().addListener(stateListener);
        task.resultStatusProperty().addListener(resultListener);
        taskStateListeners.put(task, stateListener);
        taskResultListeners.put(task, resultListener);
        syncTerminalState(task);
    }

    private void untrackTaskState(DownloadTask task) {
        if (task == null) return;
        ChangeListener<Worker.State> stateListener = taskStateListeners.remove(task);
        ChangeListener<DownloadTask.ResultStatus> resultListener = taskResultListeners.remove(task);
        if (stateListener != null) task.stateProperty().removeListener(stateListener);
        if (resultListener != null) task.resultStatusProperty().removeListener(resultListener);
        terminalTasks.remove(task);
    }

    private void syncTerminalState(DownloadTask task) {
        if (task == null) return;
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> syncTerminalState(task));
            return;
        }
        if (isTerminal(task)) {
            terminalTasks.add(task);
        } else {
            terminalTasks.remove(task);
        }
        updateClearAllVisibility();
    }

    private void updateClearAllVisibility() {
        Runnable update = () -> {
            setManagedVisible(clearAllButton, terminalTasks.size() > 1);
        };
        if (Platform.isFxApplicationThread()) update.run();
        else Platform.runLater(update);
    }

    private boolean isTerminal(DownloadTask task) {
        if (task == null) return false;
        DownloadTask.ResultStatus result = task.getResultStatus();
        if (result != null && result != DownloadTask.ResultStatus.RUNNING) return true;
        Worker.State state = task.getState();
        return state == Worker.State.SUCCEEDED
                || state == Worker.State.FAILED
                || state == Worker.State.CANCELLED;
    }

    private void clearTerminalDownloads() {
        if (tasks == null) return;
        tasks.removeIf(this::isTerminal);
    }

    /**
     * Only structural task-list changes need a ListView refresh. Progress and
     * terminal state are bound directly by the visible virtualized cells.
     */
    private void requestStructuralListRefresh() {
        if (downloadsListView == null || structuralRefreshScheduled) return;
        structuralRefreshScheduled = true;

        Platform.runLater(() -> {
            structuralRefreshScheduled = false;
            if (downloadsListView != null) {
                downloadsListView.refresh();
            }
        });
    }

    private void setManagedVisible(Node node, boolean visible) {
        if (node == null) return;
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private void installIconButtons() {
        closeIcon = installIconOnlyButton(closeButton, ICON_CLOSE, "Close downloads menu", 22);
        SmallPopupTooltip.install(closeButton, "Close Menu");

        folderIcon = installIconOnlyButton(choosePathButton, ICON_FOLDER, "Change download folder", 19);
        choosePathButton.setOnMouseEntered(event -> {
            if (!Boolean.TRUE.equals(choosePathButton.getProperties().get("folderChoosing"))) {
                swapFolderIcon(true);
            }
            updateIconColor(choosePathButton, folderIcon, false);
        });
        choosePathButton.setOnMouseExited(event -> {
            if (!Boolean.TRUE.equals(choosePathButton.getProperties().get("folderChoosing"))) {
                swapFolderIcon(false);
            }
            updateIconColor(choosePathButton, folderIcon, false);
        });
    }

    private Node installIconOnlyButton(Button button, String iconPath, String accessibleText, double size) {
        if (button == null) return null;
        button.setText("");
        button.setAccessibleText(accessibleText);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        applyChromelessButtonStyle(button);
        Node icon = SvgIconFactory.icon(iconPath, size);
        SvgIconFactory.setIconColor(icon, ICON_NORMAL);
        button.setGraphic(icon);
        button.hoverProperty().addListener((obs, oldValue, isHover) -> {
            applyChromelessButtonStyle(button);
            updateIconColor(button, icon, false);
        });
        button.focusedProperty().addListener((obs, oldValue, isFocused) -> {
            applyChromelessButtonStyle(button);
            updateIconColor(button, icon, false);
        });
        button.armedProperty().addListener((obs, oldValue, isArmed) -> applyChromelessButtonStyle(button));
        button.pressedProperty().addListener((obs, oldValue, isPressed) -> applyChromelessButtonStyle(button));
        return icon;
    }

    private void applyChromelessButtonStyle(Button button) {
        if (button != null) {
            button.setStyle(ICON_BUTTON_CHROMELESS_STYLE);
        }
    }

    private void updateIconColor(Button button, Node icon, boolean active) {
        if (icon == null) return;
        boolean folderActive = button == choosePathButton
                && Boolean.TRUE.equals(choosePathButton.getProperties().get("folderChoosing"));
        if (active || folderActive) {
            SvgIconFactory.setIconColor(icon, ICON_ACTIVE);
            return;
        }
        boolean highlighted = button != null && (button.isHover() || button.isFocused());
        SvgIconFactory.setIconColor(icon, highlighted ? ICON_HOVER : ICON_NORMAL);
    }

    private void setFolderButtonActive(boolean active) {
        if (choosePathButton == null) return;
        choosePathButton.getProperties().put("folderChoosing", active);
        swapFolderIcon(active || choosePathButton.isHover());
        updateIconColor(choosePathButton, folderIcon, active);
    }

    private void swapFolderIcon(boolean open) {
        if (choosePathButton == null) return;
        String target = open ? ICON_FOLDER_OPEN : ICON_FOLDER;
        Node nextIcon = SvgIconFactory.icon(target, 19);
        folderIcon = nextIcon;
        updateIconColor(choosePathButton, folderIcon, Boolean.TRUE.equals(choosePathButton.getProperties().get("folderChoosing")));

        javafx.animation.FadeTransition fadeOut = new javafx.animation.FadeTransition(Duration.millis(70), choosePathButton);
        fadeOut.setFromValue(1.0);
        fadeOut.setToValue(0.72);
        fadeOut.setOnFinished(event -> {
            choosePathButton.setGraphic(nextIcon);
            javafx.animation.FadeTransition fadeIn = new javafx.animation.FadeTransition(Duration.millis(120), choosePathButton);
            fadeIn.setFromValue(0.72);
            fadeIn.setToValue(1.0);
            fadeIn.play();
        });
        fadeOut.play();
    }

    private void updateSelectedPathLabel() {
        if (selectedPathLabel == null) return;
        String path = selectedDirectory == null ? "" : selectedDirectory.getAbsolutePath();
        selectedPathLabel.setText(path);
    }

    private void bindPathLabelWidth() {
        if (locationRow != null) {
            locationRow.setMaxWidth(Region.USE_PREF_SIZE);
            locationRow.setMinWidth(Region.USE_PREF_SIZE);
        }
        if (selectedPathLabel == null || downloadSidebarPane == null || selectedPathLabel.maxWidthProperty().isBound()) return;
        selectedPathLabel.maxWidthProperty().bind(Bindings.max(90, downloadSidebarPane.widthProperty().subtract(86)));
    }

    private void installResizeBehavior() {
        if (downloadSidebarPane != null) {
            setSidebarWidth(DEFAULT_WIDTH);
        }
        if (resizeHandle == null) return;

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

    private void bindResponsiveWidth(Scene scene) {
        if (scene == null || downloadSidebarPane == null) return;
        Runnable update = () -> setSidebarWidth(currentSidebarWidth());
        scene.widthProperty().addListener((obs, oldValue, newValue) -> update.run());
        Platform.runLater(update);
    }

    private double currentSidebarWidth() {
        if (downloadSidebarPane == null) return DEFAULT_WIDTH;
        double width = downloadSidebarPane.getWidth();
        if (width <= 0) width = downloadSidebarPane.getPrefWidth();
        return width <= 0 ? DEFAULT_WIDTH : width;
    }

    private void setSidebarWidth(double requestedWidth) {
        if (downloadSidebarPane == null) return;
        double availableWidth = hostBorderPane == null ? 0 : hostBorderPane.getWidth();
        Scene scene = downloadSidebarPane.getScene();
        if (availableWidth <= 0 && scene != null) {
            availableWidth = scene.getWidth();
        }

        double maxWidth = availableWidth > 0
                ? Math.max(COMPACT_MIN_WIDTH, availableWidth * 0.5)
                : 620;
        double minWidth = Math.min(MIN_WIDTH, maxWidth);
        double safeWidth = Math.max(minWidth, Math.min(maxWidth, requestedWidth));

        downloadSidebarPane.setMinWidth(minWidth);
        downloadSidebarPane.setPrefWidth(safeWidth);
        downloadSidebarPane.setMaxWidth(maxWidth);
    }

    public void showInRoot(Parent root) {
        if (root == null) return;
        DownloadManager.getInstance().setSidebarController(this);
        io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane.QueueController qc =
                io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane.QueueController.getInstance();
        if (qc != null && io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane.QueueController.isQueueVisible()) {
            qc.closeFromOwner();
        }
        Scene scene = root.getScene();
        if (scene == null) return;
        activeController = this;

        Parent layoutHost = resolveSidebarHost(root);
        if (layoutHost instanceof BorderPane bp) {
            hostBorderPane = bp;
            if (bp.getRight() == downloadSidebarPane) return;
            Platform.runLater(() -> {
                bp.setRight(downloadSidebarPane);
                bindResponsiveWidth(scene);
                DOWNLOAD_VISIBLE.set(true);
            });
            return;
        }

        Node found = scene.getRoot().lookup("#downloadOverlayPane");
        if (found instanceof AnchorPane) {
            overlayPane = (AnchorPane) found;
        } else {
            overlayPane = new AnchorPane();
            overlayPane.setId("downloadOverlayPane");
            overlayPane.setPickOnBounds(false);

            overlayPane.setPrefSize(scene.getWidth(), scene.getHeight());
            scene.widthProperty().addListener((obs, oldV, newV) -> overlayPane.setPrefWidth(newV.doubleValue()));
            scene.heightProperty().addListener((obs, oldV, newV) -> overlayPane.setPrefHeight(newV.doubleValue()));

            if (root instanceof Pane pRoot) {
                pRoot.getChildren().add(overlayPane);
            } else if (scene.getRoot() instanceof Pane p) {
                p.getChildren().add(overlayPane);
            } else {
                return;
            }
        }

        if (!overlayPane.getChildren().contains(downloadSidebarPane)) {
            AnchorPane.setTopAnchor(downloadSidebarPane, 0.0);
            AnchorPane.setRightAnchor(downloadSidebarPane, 0.0);
            Platform.runLater(() -> {
                overlayPane.getChildren().add(downloadSidebarPane);
                bindResponsiveWidth(scene);
                DOWNLOAD_VISIBLE.set(true);
            });
        }

        DownloadManager.getInstance().setSidebarController(this);
    }

    /**
     * AppShell exposes a dedicated content host for sidebars so a pane can
     * never cover the persistent player bar placed at the shell bottom.
     */
    private Parent resolveSidebarHost(Parent root) {
        if (root == null) {
            return null;
        }

        Object configuredHost = root.getProperties().get("appSidePanelHost");
        return configuredHost instanceof Parent parent ? parent : root;
    }

    public void hide() {
        if (hostBorderPane != null) {
            BorderPane bp = hostBorderPane;
            hostBorderPane = null;
            Platform.runLater(() -> {
                if (bp.getRight() == downloadSidebarPane) bp.setRight(null);
                if (activeController == this) activeController = null;
                DOWNLOAD_VISIBLE.set(false);
            });
            return;
        }

        if (downloadSidebarPane != null && downloadSidebarPane.getParent() instanceof Pane p) {
            Platform.runLater(() -> {
                p.getChildren().remove(downloadSidebarPane);
                if (activeController == this) activeController = null;
                DOWNLOAD_VISIBLE.set(false);
            });
        } else {
            if (activeController == this) activeController = null;
            DOWNLOAD_VISIBLE.set(false);
        }
    }

    private void navigateToDownloadSource(
            DownloadTask task
    ) {
        if (task == null
                || task.getContext() == null) {
            return;
        }

        DownloadTaskContext downloadContext =
                task.getContext();

        Long sourceId =
                downloadContext.getSourceCollectionId();

        PlayerMenuContext.ContentType sourceType =
                resolveSourceContentType(
                        downloadContext.getSourceCollectionType()
                );

        if (sourceId == null
                || sourceId <= 0
                || sourceType == null) {
            return;
        }


        if (isDownloadSourceAlreadyOpen(
                sourceId,
                sourceType
        )) {
            return;
        }

        StartUpService service =
                StartUpService.getInstance();

        if (service == null) {
            return;
        }

        Node navigationProbe =
                resolveNavigationProbe();

        if (navigationProbe == null) {
            return;
        }


        AppShellController shell =
                service.getAppShellController();

        MusicCardActionManager musicActions =
                shell == null
                        ? null
                        : shell.getMusicActions();

        if (musicActions == null) {
            return;
        }

        PlayerMenuNavigator navigator =
                new PlayerMenuNavigator(
                        service,
                        musicActions
                );

        Playlist exactSource =
                downloadContext.getSourcePlaylistModel();

        if (isValidExactSource(
                exactSource,
                sourceId
        )) {
            navigator.openPlayerMenu(
                    exactSource,
                    sourceType,
                    navigationProbe
            );
            return;
        }

        navigateLegacyDownloadSource(
                navigator,
                service,
                sourceId,
                sourceType,
                navigationProbe
        );
    }

    private PlayerMenuContext.ContentType resolveSourceContentType(
            String sourceType
    ) {
        if (sourceType == null
                || sourceType.isBlank()) {
            return null;
        }

        String normalized =
                sourceType.trim()
                        .toUpperCase(Locale.ROOT);

        return switch (normalized) {
            case "ALBUM" ->
                    PlayerMenuContext.ContentType.ALBUM;

            case "PLAYLIST" ->
                    PlayerMenuContext.ContentType.PLAYLIST;

            case "SINGLE" ->
                    PlayerMenuContext.ContentType.SINGLE;

            default -> null;
        };
    }

    private boolean isDownloadSourceAlreadyOpen(
            long sourceId,
            PlayerMenuContext.ContentType sourceType
    ) {
        try {
            PlayerMenuController currentController = resolveVisiblePlayerMenuController();

            if (currentController == null
                    || !currentController
                    .isCurrentCenterViewVisible()) {
                return false;
            }

            return currentController
                    .getCurrentPlaylistInViewId() == sourceId
                    && currentController
                    .getCurrentContentTypeInView() == sourceType;

        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * The playback controller can represent a song started from a different
     * collection. Navigation decisions must therefore prefer the controller
     * currently mounted in the AppShell center region.
     */
    private PlayerMenuController resolveVisiblePlayerMenuController() {
        try {
            StartUpService service = StartUpService.getInstance();
            AppShellController shell = service == null ? null : service.getAppShellController();
            if (shell != null && shell.getCenterHost() != null) {
                Node center = shell.getCenterHost().getCenter();
                if (center instanceof javafx.scene.Parent parent) {
                    Object controller = parent.getProperties().get("controller");
                    if (controller instanceof PlayerMenuController playerMenu
                            && playerMenu.isCurrentCenterViewVisible()) {
                        return playerMenu;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        try {
            PlayerMenuController playbackController = PlaybackManager
                    .getInstance()
                    .getMenuController();
            return playbackController != null && playbackController.isCurrentCenterViewVisible()
                    ? playbackController
                    : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Node resolveNavigationProbe() {

        if (downloadSidebarPane != null
                && downloadSidebarPane.getScene() != null
                && downloadSidebarPane
                .getScene()
                .getRoot() != null) {

            return downloadSidebarPane
                    .getScene()
                    .getRoot();
        }

        return downloadSidebarPane;
    }

    private boolean isValidExactSource(
            Playlist source,
            long expectedId
    ) {
        if (source == null) {
            return false;
        }

        if (source.getId() != expectedId) {
            return false;
        }

        /*
         * An empty list may be valid for a user-created playlist, but for
         * navigating a download, the song that initiated the task must
         * at least be present.
         */
        return source.getSongList() != null;
    }

    private void navigateLegacyDownloadSource(
            PlayerMenuNavigator navigator,
            StartUpService service,
            long sourceId,
            PlayerMenuContext.ContentType sourceType,
            Node navigationProbe
    ) {
        String sourceIdText =
                Long.toString(sourceId);

        switch (sourceType) {
            case ALBUM ->
                    new AlbumPlaybackCoordinator(
                            service,
                            navigator
                    ).handle(
                            sourceIdText,
                            navigationProbe
                    );

            case PLAYLIST ->
                    new PlaylistPlaybackCoordinator(
                            service,
                            navigator
                    ).handle(
                            sourceIdText,
                            navigationProbe
                    );

            case SINGLE ->
                    new SongPlaybackCoordinator(
                            service,
                            navigator
                    ).handle(
                            sourceIdText,
                            navigationProbe
                    );

            default -> {
            }
        }
    }
}
