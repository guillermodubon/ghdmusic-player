package io.github.guillermodubon.musicplayer.controllers.layout;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.application.Platform;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import io.github.guillermodubon.musicplayer.services.keyboard.GlobalKeyboardShortcutManager;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane.QueueController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.PlayerMenuBarController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.downloadComponents.DownloadSidebarMenuController;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.HeaderController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.SideBarNavigationMenu;
import io.github.guillermodubon.musicplayer.services.navigation.SceneStateFlowManager;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.ArtistCardActionManager;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;

import java.io.IOException;


public class AppShellController {

    @FXML private BorderPane shellRoot;
    @FXML private BorderPane contentHost;
    @FXML private BorderPane centerHost;
    @FXML private Pane leftHost;
    @FXML private Pane headerHost;
    @FXML private Pane bottomHost;


    private StartUpService svc;
    private SideBarNavigationMenu sideBarController;
    private HeaderController headerController;

    private PlayerMenuBarController playerMenuBarController;
    private boolean playerMenuBarLoaded = false;

    private QueueController queueController;
    private MusicCardActionManager musicActions;
    private ArtistCardActionManager artistActions;
    private GlobalKeyboardShortcutManager keyboardShortcutManager;
    private boolean shellLayoutRefreshQueued;

    public MusicCardActionManager getMusicActions() {
        return musicActions;
    }

    public void init(StartUpService svc,
                     MusicCardActionManager musicActions,
                     ArtistCardActionManager artistActions) {
        this.svc = svc;
        this.musicActions = musicActions;
        this.artistActions = artistActions;

        if (svc != null) {
            svc.setAppShellController(this);
        }

        publishShellHosts();
        installShellLayoutSynchronization();

        SceneStateFlowManager.getInstance().setRoot(centerHost);

        loadLeftHost();
        loadHeaderHost(musicActions, artistActions);
        installKeyboardShortcuts();

    }

    private void installKeyboardShortcuts() {
        if (keyboardShortcutManager == null) {
            keyboardShortcutManager = new GlobalKeyboardShortcutManager(this);
        }
        keyboardShortcutManager.install(shellRoot);
    }

    private void loadLeftHost() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/io/github/guillermodubon/musicplayer/Views/components/layoutComponents/sideBarNavigationMenu/SideBarNavigationMenu.fxml"));
            Parent view = loader.load();

            Object ctrl = loader.getController();
            if (view != null && ctrl != null) {
                view.getProperties().put("controller", ctrl);
            }

            if (leftHost != null) {
                fillHost(leftHost, view, true, true);
            }

            if (ctrl instanceof SideBarNavigationMenu sidebar) {
                this.sideBarController = sidebar;
                sidebar.init(svc, centerHost);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadHeaderHost(MusicCardActionManager musicActions,
                                ArtistCardActionManager artistActions) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/io/github/guillermodubon/musicplayer/Views/components/layoutComponents/headerMenu/HeaderView.fxml"));
            Parent view = loader.load();

            Object ctrl = loader.getController();
            if (view != null && ctrl != null) {
                view.getProperties().put("controller", ctrl);
            }

            if (headerHost != null) {
                fillHost(headerHost, view, true, false);
            }

            if (ctrl instanceof HeaderController header) {
                this.headerController = header;
                header.init(
                        filter -> {
                            Object current = centerHost == null ? null : centerHost.getProperties().get("controller");
                            if (current != null) {
                                try {
                                    current.getClass().getMethod("refreshSections", String.class).invoke(current, filter);
                                } catch (NoSuchMethodException ignored) {
                                } catch (Exception ex) {
                                    ex.printStackTrace();
                                }
                            }
                        },
                        musicActions,
                        artistActions,
                        svc
                );
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void ensurePlayerMenuBarLoaded() {
        if (playerMenuBarLoaded && playerMenuBarController != null) {
            playerMenuBarController.setPrimaryHost(bottomHost);
            playerMenuBarController.setSidePanelHost(contentHost);
            if (!io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.PlayerFullScreenModeController
                    .getInstance()
                    .isActive()) {
                playerMenuBarController.restoreToPrimaryHost();
            }
            PlaybackManager.getInstance().setBarController(playerMenuBarController);
            return;
        }
        if (bottomHost == null || svc == null) return;

        try {
            if (!bottomHost.getChildren().isEmpty() && playerMenuBarController != null) {
                playerMenuBarLoaded = true;
                playerMenuBarController.setPrimaryHost(bottomHost);
                playerMenuBarController.setSidePanelHost(contentHost);
                playerMenuBarController.restoreToPrimaryHost();
                PlaybackManager.getInstance().setBarController(playerMenuBarController);
                return;
            }

            FXMLLoader loader = new FXMLLoader(getClass().getResource(
                    "/io/github/guillermodubon/musicplayer/Views/components/layoutComponents/playerMenuBar/PlayerMenuBar.fxml"));
            Parent view = loader.load();
            PlayerMenuBarController ctrl = loader.getController();

            if (ctrl != null) {
                ctrl.init(svc, musicActions, shellRoot);
                ctrl.setPrimaryHost(bottomHost);
                ctrl.setSidePanelHost(contentHost);
            }

            bottomHost.getChildren().setAll(view);

            playerMenuBarController = ctrl;
            playerMenuBarLoaded = ctrl != null;
            if (ctrl != null) {
                ctrl.restoreToPrimaryHost();
                PlaybackManager.getInstance().setBarController(ctrl);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    public void hideQueueSidebar() {
        QueueController.queueVisibleProperty().set(false);
        if (contentHost != null) {
            contentHost.setRight(null);
            refreshShellLayout();
        }
    }

    public BorderPane getShellRoot() {
        return shellRoot;
    }

    public BorderPane getCenterHost() {
        return centerHost;
    }

    /**
     * Host reserved for lateral application panes. It never contains the
     * persistent player bar, which belongs to the shell bottom edge.
     */
    public BorderPane getSidePanelHost() {
        return sidePanelHost();
    }

    public PlayerMenuBarController getPlayerMenuBarController() {
        return playerMenuBarController;
    }

    public Pane getLeftHost() {
        return leftHost;
    }

    public Pane getHeaderHost() {
        return headerHost;
    }

    public Pane getBottomHost() {
        return bottomHost;
    }

    private void fillHost(Pane host, Parent view, boolean bindWidth, boolean bindHeight) {
        if (host == null || view == null) return;

        if (view instanceof Region region) {
            if (bindWidth) {
                region.prefWidthProperty().bind(host.widthProperty());
            }
            if (bindHeight) {
                region.prefHeightProperty().bind(host.heightProperty());
            }
            region.setMaxWidth(Double.MAX_VALUE);
            region.setMaxHeight(Double.MAX_VALUE);
        }

        host.getChildren().setAll(view);
    }

    private BorderPane sidePanelHost() {
        return contentHost != null ? contentHost : shellRoot;
    }

    private void publishShellHosts() {
        if (shellRoot == null) {
            return;
        }

        shellRoot.getProperties().put("appCenterHost", centerHost);
        shellRoot.getProperties().put("appSidePanelHost", sidePanelHost());
    }

    private void installShellLayoutSynchronization() {
        if (shellRoot == null) {
            return;
        }

        shellRoot.widthProperty().addListener((obs, oldValue, newValue) -> refreshShellLayout());
        shellRoot.heightProperty().addListener((obs, oldValue, newValue) -> refreshShellLayout());
        if (contentHost != null) {
            contentHost.widthProperty().addListener((obs, oldValue, newValue) -> refreshShellLayout());
            contentHost.heightProperty().addListener((obs, oldValue, newValue) -> refreshShellLayout());
        }
    }

    private void refreshShellLayout() {
        if (shellLayoutRefreshQueued) {
            return;
        }

        shellLayoutRefreshQueued = true;
        Platform.runLater(() -> {
            shellLayoutRefreshQueued = false;
            if (shellRoot != null) {
                shellRoot.requestLayout();
            }
            if (contentHost != null) {
                contentHost.requestLayout();
            }
            if (playerMenuBarController != null) {
                playerMenuBarController.synchronizePrimaryHostLayout();
            }
        });
    }
}
