package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.header;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.CheckBox;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import io.github.guillermodubon.musicplayer.repository.dao.playlist.PlaylistDao;
import io.github.guillermodubon.musicplayer.services.navigation.SceneStateFlowManager;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistManagementDialogs.DeletePlaylistDialogController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistManagementDialogs.EditPlaylistDialogController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistManagementDialogs.PlaylistDialogWindowSupport;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane.QueueController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.HomePageController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext.ContentType;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.ArtistCardActionManager;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class PlayerMenuPlaylistHeaderActionsService {

    private final PlayerMenuContext context;
    private final PlaybackManager pm;
    private final PlayerMenuPlaylistPersistence persistence = new PlayerMenuPlaylistPersistence();
    private final PlayerMenuPlaylistHeaderUi headerUi = new PlayerMenuPlaylistHeaderUi();

    private Playlist activeRemotePlaylist;

    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "player-menu-playlist-header-actions");
        t.setDaemon(true);
        return t;
    });

    private StartUpService svc;
    private BorderPane parentRoot;

    private MusicCardActionManager musicActions;
    private ArtistCardActionManager artistActions;

    private MenuItem miEdit;
    private MenuItem miDelete;

    private Runnable refreshHeaderFooter = () -> {};
    private Runnable refreshPlaybackContext = () -> {};
    private Runnable refreshQueue = () -> {};
    private Runnable refreshCurrentViewMinimal = () -> {};

    private final AtomicBoolean opInProgress = new AtomicBoolean(false);

    private boolean remoteCheckboxWired = false;
    private boolean updatingRemoteCheckboxState = false;

    public PlayerMenuPlaylistHeaderActionsService(PlayerMenuContext context, PlaybackManager pm) {
        this.context = context;
        this.pm = pm == null ? PlaybackManager.getInstance() : pm;
    }

    public void bindServices(StartUpService svc,
                             PlaylistDao playlistDao,
                             BorderPane parentRoot,
                             MusicCardActionManager musicActions,
                             ArtistCardActionManager artistActions) {
        this.svc = svc;
        persistence.bind(svc, playlistDao);
        this.parentRoot = parentRoot;
        this.musicActions = musicActions;
        this.artistActions = artistActions;
    }

    public void bindUi(MenuButton menuOptions,
                       MenuItem miEdit,
                       MenuItem miDelete,
                       CheckBox remoteSaveCheckBox,
                       ImageView headerCover,
                       Pane actionMenuHost,
                       StackPane fallbackMenuHost) {
        this.miEdit = miEdit;
        this.miDelete = miDelete;
        headerUi.bindUi(
                menuOptions,
                miEdit,
                miDelete,
                remoteSaveCheckBox,
                actionMenuHost,
                fallbackMenuHost
        );
    }

    public void bindCallbacks(Runnable refreshHeaderFooter,
                              Runnable refreshPlaybackContext,
                              Runnable refreshQueue,
                              Runnable refreshCurrentViewMinimal) {
        if (refreshHeaderFooter != null) this.refreshHeaderFooter = refreshHeaderFooter;
        if (refreshPlaybackContext != null) this.refreshPlaybackContext = refreshPlaybackContext;
        if (refreshQueue != null) this.refreshQueue = refreshQueue;
        if (refreshCurrentViewMinimal != null) this.refreshCurrentViewMinimal = refreshCurrentViewMinimal;
    }

    public void applyPlaylistState(Playlist playlist, ContentType type) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> applyPlaylistState(playlist, type));
            return;
        }

        if (playlist == null || type != ContentType.PLAYLIST) {
            hideLocalMenuState();
            hideRemoteCheckbox();
            return;
        }

        if (persistence.isUserLocal(playlist)) {
            showLocalMenuState(true);
            hideRemoteCheckbox();
        } else {
            showRemotePlaylistState(playlist);
        }
    }

    public void wireAfterPlaylistLoad(Playlist playlist, ContentType type) {
        applyPlaylistState(playlist, type);
        wireMenuActions();
    }

    public void onPlaylistModelChanged(Playlist playlist, ContentType type) {
        applyPlaylistState(playlist, type);
    }

    private void wireMenuActions() {
        if (miDelete != null) {
            miDelete.setOnAction(e -> {
                if (context.getCurrentPlaylistModel() == null) return;
                openDeletePlaylistDialog(context.getCurrentPlaylistModel());
            });
        }

        if (miEdit != null) {
            miEdit.setOnAction(e -> {
                if (context.getCurrentPlaylistModel() == null) return;
                openEditPlaylistDialog(context.getCurrentPlaylistModel());
            });
        }
    }

    private void showLocalMenuState(boolean show) {
        headerUi.showLocalMenu(show);
    }

    private void hideLocalMenuState() {
        headerUi.hideLocalMenu();
    }

    private void showRemotePlaylistState(Playlist playlist) {
        hideLocalMenuState();
        activeRemotePlaylist = playlist;
        if (playlist == null) return;
        boolean saved = persistence.existsLocally(playlist);
        updatingRemoteCheckboxState = true;
        try {
            headerUi.showRemotePlaylist(saved, opInProgress.get());
        } finally {
            updatingRemoteCheckboxState = false;
        }
        wireRemoteCheckboxOnce();
    }


    private void hideRemoteCheckbox() {
        activeRemotePlaylist = null;
        updatingRemoteCheckboxState = true;
        try {
            headerUi.hideRemotePlaylist();
        } finally {
            updatingRemoteCheckboxState = false;
        }
    }


    private void wireRemoteCheckboxOnce() {
        if (headerUi.remoteSaveCheckBox() == null || remoteCheckboxWired) {
            return;
        }

        remoteCheckboxWired = true;

        headerUi.setRemoteAction(event -> {
            if (updatingRemoteCheckboxState) {
                return;
            }

            Playlist playlist = activeRemotePlaylist != null
                    ? activeRemotePlaylist
                    : context.getCurrentPlaylistModel();

            if (playlist == null) {
                updateRemoteCheckboxSelection(false);
                return;
            }

            final Playlist operationPlaylist = playlist;
            final boolean requestedSavedState = headerUi.isRemoteSelected();
            final boolean previousSavedState = !requestedSavedState;

            if (!opInProgress.compareAndSet(false, true)) {
                updateRemoteCheckboxSelection(previousSavedState);
                return;
            }

            headerUi.setRemoteDisabled(true);

            CompletableFuture
                    .runAsync(() -> {
                        PlaylistDao dao = persistence.resolveDao();

                        if (dao == null) {
                            throw new IllegalStateException("PlaylistDao is not available");
                        }

                        try {
                            if (requestedSavedState) {
                                Playlist savedPlaylist = persistence.saveRemotePlaylist(operationPlaylist);
                                persistence.addSavedPlaylistToCacheOnFxThread(savedPlaylist);
                            } else {
                                PlayerMenuPlaylistPersistence.DeletedPlaylist deleted =
                                        persistence.deleteRemotePlaylist(operationPlaylist);
                                persistence.removeSavedPlaylistFromCacheOnFxThread(
                                        deleted.playlistId(), deleted.title());
                            }
                        } catch (SQLException error) {
                            throw new CompletionException(error);
                        }
                    }, dbExecutor)
                    .whenComplete((ignored, error) ->
                            Platform.runLater(() -> {
                                try {
                                    if (error != null) {
                                        updateRemoteCheckboxSelection(previousSavedState);
                                        showRemotePlaylistOperationError(unwrap(error));
                                        return;
                                    }

                                    updateRemoteCheckboxSelection(requestedSavedState);
                                    runRefreshCallbacks();
                                } finally {
                                    headerUi.setRemoteDisabled(false);
                                    opInProgress.set(false);
                                    headerUi.refreshRemotePresentation();
                                }
                            })
                    );
        });
    }

    private void showRemotePlaylistOperationError(Throwable error) {
        String message = error == null || error.getMessage() == null
                ? "Unknown error"
                : error.getMessage();

        Alert alert = new Alert(
                Alert.AlertType.ERROR,
                "Error processing playlist operation: " + message
        );

        alert.setHeaderText("Error");
        alert.show();
    }

    private void runRefreshCallbacks() {
        try {
            if (refreshHeaderFooter != null) refreshHeaderFooter.run();
        } catch (Exception ignored) {
        }

        try {
            if (refreshPlaybackContext != null) refreshPlaybackContext.run();
        } catch (Exception ignored) {
        }

        try {
            if (refreshQueue != null) refreshQueue.run();
        } catch (Exception ignored) {
        }

        try {
            if (refreshCurrentViewMinimal != null) refreshCurrentViewMinimal.run();
        } catch (Exception ignored) {
        }
    }

    private void openEditPlaylistDialog(Playlist currentPlaylistModel) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/io/github/guillermodubon/musicplayer/Views/components/dialogs/playlistManagmentDialogs/EditPlaylistDialog.fxml")
            );

            Parent root = loader.load();
            EditPlaylistDialogController dlgCtrl = loader.getController();

            Stage stage = new Stage();
            stage.initModality(Modality.APPLICATION_MODAL);

            Window owner = null;

            if (parentRoot != null && parentRoot.getScene() != null) {
                owner = parentRoot.getScene().getWindow();
                stage.initOwner(owner);
            }

            stage.initStyle(StageStyle.TRANSPARENT);

            Scene scene = new Scene(root);
            scene.setFill(Color.TRANSPARENT);

            stage.setScene(scene);

            PlaylistDialogWindowSupport.configureFormDialog(stage, root, owner);

            dlgCtrl.initForEdit(svc, stage, currentPlaylistModel);

            dlgCtrl.setOnSaved(() -> {
                if (context.getCurrentPlaylistModel() != null
                        && context.getCurrentPlaylistModel().getTitle() != null) {
                    pm.setOriginSource(context.getCurrentPlaylistModel().getTitle());
                }

                runRefreshCallbacks();

                QueueController qc = QueueController.getInstance();
                if (qc != null) qc.refreshAll();
            });

            stage.showAndWait();

            Platform.runLater(() -> {
                if (context.getCurrentPlaylistModel() != null
                        && context.getCurrentPlaylistModel().getTitle() != null) {
                    pm.setOriginSource(context.getCurrentPlaylistModel().getTitle());
                }

                runRefreshCallbacks();

                QueueController qc = QueueController.getInstance();
                if (qc != null) qc.refreshAll();
            });

        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    private void openDeletePlaylistDialog(Playlist playlist) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/io/github/guillermodubon/musicplayer/Views/components/dialogs/playlistManagmentDialogs/DeletePlaylistDialog.fxml")
            );

            Parent root = loader.load();
            DeletePlaylistDialogController dlgCtrl = loader.getController();

            Stage stage = new Stage();
            stage.initModality(Modality.APPLICATION_MODAL);

            Window owner = null;

            if (parentRoot != null && parentRoot.getScene() != null) {
                owner = parentRoot.getScene().getWindow();
                stage.initOwner(owner);
            }

            stage.initStyle(StageStyle.TRANSPARENT);

            Scene scene = new Scene(root);
            scene.setFill(Color.TRANSPARENT);

            stage.setScene(scene);

            PlaylistDialogWindowSupport.configureCompactDialog(stage, root, owner);

            dlgCtrl.initForDelete(
                    svc,
                    stage,
                    playlist,
                    persistence.resolveDao(),
                    this::afterPlaylistDeleted
            );

            stage.showAndWait();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    private void afterPlaylistDeleted() {
        Platform.runLater(() -> {
            pm.setCurrentPlaylistInViewId(-1L);

            runRefreshCallbacks();

            QueueController qc = QueueController.getInstance();
            if (qc != null) qc.refreshAll();

            boolean navigated = SceneStateFlowManager.getInstance().navigateBackDiscardingCurrent();

            if (!navigated) {
                navigateHomeFallback();
            }
        });
    }

    private void navigateHomeFallback() {
        Parent home = createHomeFallbackView();

        if (parentRoot != null && home != null) {
            parentRoot.setCenter(home);
        }
    }

    private Parent createHomeFallbackView() {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/io/github/guillermodubon/musicplayer/Views/screens/homePage/HomePage.fxml")
            );

            Parent home = loader.load();
            HomePageController homeCtrl = loader.getController();

            if (homeCtrl != null) {
                homeCtrl.init(svc, musicActions, artistActions);
                home.getProperties().put("controller", homeCtrl);
            }

            SceneStateFlowManager.attachNavigationFactory(home, this::createHomeFallbackView);

            return home;
        } catch (IOException ex) {
            ex.printStackTrace();
            return null;
        }
    }

    private void updateRemoteCheckboxSelection(boolean selected) {
        updatingRemoteCheckboxState = true;
        try {
            headerUi.setRemoteSelection(selected);
        } finally {
            updatingRemoteCheckboxState = false;
        }
    }

    private Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }


}
