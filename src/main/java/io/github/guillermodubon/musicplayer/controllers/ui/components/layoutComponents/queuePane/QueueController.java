package io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane.factory.QueueSidebarViewFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane.helpers.QueueSidebarRenderCoordinator;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane.helpers.QueueSidebarUiCoordinator;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane.helpers.QueuePlaybackRefreshCoordinator;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane.services.QueueSidebarContentService;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

/**
 * FXML facade for the queue sidebar.
 * Rendering, progressive loading and responsive controls live in dedicated coordinators.
 */
public class QueueController {

    private static final BooleanProperty QUEUE_VISIBLE = new SimpleBooleanProperty(false);
    @FXML private BorderPane root;
    @FXML private Region resizeHandle;
    @FXML private ScrollPane queueScrollPane;
    @FXML private AnchorPane nowPlayingContainer;
    @FXML private VBox nextQueueList;
    @FXML private VBox nextFromList;
    @FXML private Label nextFromLabel;
    @FXML private VBox nextQueueSection;
    @FXML private Button closeButton;
    @FXML private Button clearQueueButton;

    private static QueueController instance;

    private final PlaybackManager playbackManager = PlaybackManager.getInstance();
    private final QueueSidebarContentService contentService = new QueueSidebarContentService();
    private BorderPane hostBorderPane;
    private final QueueSidebarUiCoordinator uiCoordinator =
            new QueueSidebarUiCoordinator(() -> hostBorderPane);

    private QueueSidebarRenderCoordinator renderCoordinator;
    private StartUpService startUpService;
    private MusicCardActionManager musicCardActionManager;
    private boolean listenerRegistered;
    private boolean controllerDisposed;

    private final QueuePlaybackRefreshCoordinator playbackRefreshCoordinator =
            new QueuePlaybackRefreshCoordinator(
                    this::isAttachedAndVisible,
                    this::refreshPlaybackFlow
            );

    public static QueueController getInstance() {
        return instance;
    }

    public static BooleanProperty queueVisibleProperty() {
        return QUEUE_VISIBLE;
    }

    public static boolean isQueueVisible() {
        return QUEUE_VISIBLE.get();
    }

    public void init(
            StartUpService service,
            MusicCardActionManager musicActions,
            BorderPane host
    ) {
        this.startUpService = service;
        this.musicCardActionManager = musicActions;
        this.hostBorderPane = host;

        QueueSidebarViewFactory viewFactory = new QueueSidebarViewFactory(startUpService);
        renderCoordinator = new QueueSidebarRenderCoordinator(
                playbackManager,
                contentService,
                viewFactory,
                this::refreshAll,
                this::onPlayThis,
                this::onArtistClick
        );
        renderCoordinator.bindViews(
                queueScrollPane,
                nowPlayingContainer,
                nextQueueList,
                nextFromList,
                nextFromLabel,
                nextQueueSection
        );

        QUEUE_VISIBLE.set(true);
        refreshAll();
    }

    @FXML
    public void initialize() {
        instance = this;
        controllerDisposed = false;

        playbackRefreshCoordinator.start();

        uiCoordinator.bindRoot(root, resizeHandle);
        uiCoordinator.installIconButtons(closeButton, clearQueueButton);
        uiCoordinator.installResizeBehavior();

        if (root != null) {
            root.sceneProperty().addListener((obs, oldScene, newScene) -> {
                if (newScene == null) {
                    QUEUE_VISIBLE.set(false);
                    stopFlowRefreshes();
                } else {
                    controllerDisposed = false;
                    uiCoordinator.bindResponsiveWidth(newScene);
                    Platform.runLater(this::refreshAll);
                }
            });

            Platform.runLater(() -> uiCoordinator.bindResponsiveWidth(root.getScene()));
        }

        if (!listenerRegistered) {
            listenerRegistered = true;
            playbackManager.addTrackChangeListener(QueueController::notifyPlaybackFlowChanged);
        }

        Platform.runLater(this::refreshAll);
    }

    public void refreshAll() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::refreshAll);
            return;
        }

        if (controllerDisposed
                || renderCoordinator == null
                || root == null
                || root.getScene() == null) {
            return;
        }

        renderCoordinator.refreshAll();
    }

    private void onPlayThis(Song song) {
        if (song == null) {
            return;
        }
        playbackManager.enqueueAndPlayNext(song);
        refreshAll();
    }

    private void onArtistClick(String name) {
        if (name == null
                || name.isBlank()
                || musicCardActionManager == null
                || nowPlayingContainer == null) {
            return;
        }

        try {
            musicCardActionManager.artistNameClick(nowPlayingContainer).accept(name);
        } catch (Exception error) {
            error.printStackTrace();
        }
    }

    @FXML
    private void onClearQueue() {
        playbackManager.clearQueue();
        refreshAll();
    }

    @FXML
    private void onClose() {
        closeFromOwner();
    }

    public void closeFromOwner() {
        QUEUE_VISIBLE.set(false);
        stopFlowRefreshes();
        if (renderCoordinator != null) {
            renderCoordinator.invalidate();
        }

        if (hostBorderPane != null) {
            hostBorderPane.setRight(null);
            return;
        }

        if (nowPlayingContainer != null
                && nowPlayingContainer.getScene() != null
                && nowPlayingContainer.getScene().getRoot() instanceof BorderPane sceneRoot) {
            sceneRoot.setRight(null);
        }
    }

    public void requestPlaybackFlowRefresh() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::requestPlaybackFlowRefresh);
            return;
        }

        if (!isAttachedAndVisible()) {
            return;
        }

        playbackRefreshCoordinator.request();
    }

    public void dispose() {
        controllerDisposed = true;
        playbackRefreshCoordinator.stop();
        if (renderCoordinator != null) {
            renderCoordinator.dispose();
        }

        if (instance == this) {
            instance = null;
        }
        QUEUE_VISIBLE.set(false);
    }

    public static void notifyPlaybackFlowChanged() {
        QueueController controller = instance;
        if (controller != null) {
            controller.requestPlaybackFlowRefresh();
        }
    }

    private void stopFlowRefreshes() {
        playbackRefreshCoordinator.stop();
    }

    private void refreshPlaybackFlow() {
        if (renderCoordinator != null) {
            renderCoordinator.refreshAfterPlaybackFlowChange();
        }
    }

    private boolean isAttachedAndVisible() {
        return !controllerDisposed
                && QUEUE_VISIBLE.get()
                && root != null
                && root.getScene() != null
                && root.isVisible();
    }
}
