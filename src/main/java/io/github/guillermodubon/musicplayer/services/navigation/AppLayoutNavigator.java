package io.github.guillermodubon.musicplayer.services.navigation;

import javafx.geometry.Rectangle2D;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.discoverPage.DiscoverPageController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.libraryCatalogListScreens.LibraryArtistsCatalogListController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.libraryCatalogListScreens.LibraryMusicCatalogListController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.libraryCatalogListScreens.common.CatalogType;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.HomePageController;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.ArtistCardActionManager;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.GenreCardActionManager;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class AppLayoutNavigator {

    public static final String SCREEN_HOME = "home";
    public static final String SCREEN_DISCOVER = "discover";
    public static final String SCREEN_PLAYLISTS = "library.playlists";
    public static final String SCREEN_ALBUMS = "library.albums";
    public static final String SCREEN_SINGLES = "library.singles";
    public static final String SCREEN_ARTISTS = "library.artists";

    private final StartUpService svc;
    private final BorderPane centerHost;
    private final MusicCardActionManager musicActions;
    private final ArtistCardActionManager artistActions;
    private final GenreCardActionManager genreActions;

    public AppLayoutNavigator(StartUpService svc,
                              BorderPane centerHost,
                              MusicCardActionManager musicActions,
                              ArtistCardActionManager artistActions,
                              GenreCardActionManager genreActions) {
        this.svc = Objects.requireNonNull(svc, "svc");
        this.centerHost = Objects.requireNonNull(centerHost, "centerHost");
        this.musicActions = Objects.requireNonNull(musicActions, "musicActions");
        this.artistActions = Objects.requireNonNull(artistActions, "artistActions");
        this.genreActions= Objects.requireNonNull(genreActions, "genreActions");
    }

    public void goHome() throws IOException {
        loadAndNavigate(
                "/io/github/guillermodubon/musicplayer/Views/screens/homePage/HomePage.fxml",
                SCREEN_HOME,
                ctrl -> {
                    if (ctrl instanceof HomePageController c) {
                        c.init(svc, musicActions, artistActions);
                    }
                },
                ctrl -> ctrl instanceof HomePageController
        );
    }

    public void goDiscover() throws IOException {
        loadAndNavigate(
                "/io/github/guillermodubon/musicplayer/Views/screens/discoverScreen/DiscoverPage.fxml",
                SCREEN_DISCOVER,
                ctrl -> {
                    if (ctrl instanceof DiscoverPageController c) {
                        c.init(svc, musicActions, artistActions,genreActions);
                    }
                },
                ctrl -> ctrl instanceof DiscoverPageController
        );
    }

    public void openCatalog(CatalogType type) throws IOException {
        if (type == null) return;

        loadAndNavigate(
                "/io/github/guillermodubon/musicplayer/Views/screens/libraryCatalogListScreens/MusicLibrary/LibraryMusicCatalogListView.fxml",
                screenKeyForCatalog(type),
                ctrl -> {
                    if (ctrl instanceof LibraryMusicCatalogListController c) {
                        c.init(svc, centerHost, type);
                    }
                },
                ctrl -> ctrl instanceof LibraryMusicCatalogListController c && c.getType() == type
        );
    }

    public void openArtistCatalog() throws IOException {
        loadAndNavigate(
                "/io/github/guillermodubon/musicplayer/Views/screens/libraryCatalogListScreens/ArtistsLibrary/LibraryArtistsCatalogList.fxml",
                SCREEN_ARTISTS,
                ctrl -> {
                    if (ctrl instanceof LibraryArtistsCatalogListController c) {
                        c.init(svc, centerHost);
                    }
                },
                ctrl -> ctrl instanceof LibraryArtistsCatalogListController
        );
    }

    public void openCreatePlaylistDialog() throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource(
                "/io/github/guillermodubon/musicplayer/Views/components/dialogs/playlistManagmentDialogs/CreatePlaylistDialog.fxml"));

        Parent dialogContent = loader.load();
        Object ctrl = loader.getController();
        if (dialogContent != null && ctrl != null) {
            dialogContent.getProperties().put("controller", ctrl);
        }

        Stage dialogStage = new Stage();
        dialogStage.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        Window owner = centerHost.getScene() != null ? centerHost.getScene().getWindow() : null;
        if (owner != null) {
            dialogStage.initOwner(owner);
        }
        dialogStage.initStyle(javafx.stage.StageStyle.TRANSPARENT);
        Scene scene = new Scene(dialogContent);
        scene.setFill(Color.TRANSPARENT);
        dialogStage.setScene(scene);
        configureCreatePlaylistDialogStage(dialogStage, dialogContent);

        if (ctrl != null) {
            try {
                ctrl.getClass()
                        .getMethod("init", StartUpService.class, Stage.class, BorderPane.class, MusicCardActionManager.class)
                        .invoke(ctrl, svc, dialogStage, centerHost, musicActions);
            } catch (NoSuchMethodException ignored) {
                try {
                    ctrl.getClass().getMethod("init", StartUpService.class, Stage.class).invoke(ctrl, svc, dialogStage);
                } catch (NoSuchMethodException ignoredAgain) {
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        dialogStage.showAndWait();
    }

    private void configureCreatePlaylistDialogStage(Stage dialogStage, Parent dialogContent) {
        if (dialogStage == null || dialogContent == null) return;

        Rectangle2D bounds = resolveOwnerScreenBounds();
        double maxWidth = Math.min(720, bounds.getWidth() * 0.84);
        double maxHeight = Math.min(470, bounds.getHeight() * 0.82);
        double minWidth = Math.min(600, maxWidth);
        double minHeight = Math.min(430, maxHeight);
        double width = clamp(bounds.getWidth() * 0.54, minWidth, maxWidth);
        double height = clamp(bounds.getHeight() * 0.48, minHeight, maxHeight);

        dialogStage.setResizable(false);
        dialogStage.setWidth(width);
        dialogStage.setHeight(height);

        if (dialogContent instanceof Region region) {
            region.setMinSize(minWidth, minHeight);
            region.setPrefSize(width, height);
            region.setMaxSize(width, height);
        }

        dialogStage.setOnShown(event -> {
            dialogStage.setWidth(width);
            dialogStage.setHeight(height);
            dialogStage.setX(bounds.getMinX() + (bounds.getWidth() - width) / 2.0);
            dialogStage.setY(bounds.getMinY() + (bounds.getHeight() - height) / 2.0);
        });
    }

    private Rectangle2D resolveOwnerScreenBounds() {
        Window owner = centerHost != null && centerHost.getScene() != null
                ? centerHost.getScene().getWindow()
                : null;
        Screen screen = null;
        if (owner != null) {
            screen = Screen.getScreensForRectangle(
                            owner.getX(),
                            owner.getY(),
                            Math.max(1, owner.getWidth()),
                            Math.max(1, owner.getHeight()))
                    .stream()
                    .findFirst()
                    .orElse(null);
        }
        if (screen == null) {
            screen = Screen.getPrimary();
        }
        return screen.getVisualBounds();
    }

    private double clamp(double value, double min, double max) {
        if (max < min) return max;
        return Math.max(min, Math.min(max, value));
    }

    private void loadAndNavigate(String fxmlPath,
                                 String screenKey,
                                 Consumer<Object> initAction,
                                 Predicate<Object> sameScreenPredicate) throws IOException {

        Object currentCtrl = getCurrentController();
        if (currentCtrl != null && sameScreenPredicate != null && sameScreenPredicate.test(currentCtrl)) {
            return;
        }

        Parent view = loadView(fxmlPath, screenKey, initAction);

        Supplier<Map<String, Object>> captureCurrent = () -> {
            Parent current = (Parent) centerHost.getCenter();
            if (current == null) return null;

            Object c = current.getProperties().get("controller");
            if (c == null) return null;

            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> snap = (Map<String, Object>) c.getClass().getMethod("captureState").invoke(c);
                return snap;
            } catch (NoSuchMethodException ignored) {
                return null;
            } catch (Exception ignored) {
                return null;
            }
        };

        BiConsumer<Parent, Map<String, Object>> restoreAction = (p, state) -> {
            if (p == null || state == null) return;
            try {
                Object c = p.getProperties().get("controller");
                if (c != null) {
                    c.getClass().getMethod("restoreState", Map.class).invoke(c, state);
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Exception ignored) {
            }
        };

        SceneStateFlowManager.getInstance().navigateToAndPushCurrent(view, captureCurrent, restoreAction);
    }

    private Parent loadView(String fxmlPath, String screenKey, Consumer<Object> initAction) throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath));
        Parent view = loader.load();
        Object ctrl = loader.getController();

        if (view != null && ctrl != null) {
            view.getProperties().put("controller", ctrl);
        }
        if (view != null && screenKey != null && !screenKey.isBlank()) {
            view.getProperties().put(SceneStateFlowManager.SCREEN_KEY_PROPERTY, screenKey);
        }

        if (initAction != null && ctrl != null) {
            initAction.accept(ctrl);
        }

        SceneStateFlowManager.attachNavigationFactory(view, () -> {
            try {
                return loadView(fxmlPath, screenKey, initAction);
            } catch (IOException ex) {
                ex.printStackTrace();
                return null;
            }
        });

        return view;
    }

    private String screenKeyForCatalog(CatalogType type) {
        if (type == null) return null;
        return switch (type) {
            case PLAYLISTS -> SCREEN_PLAYLISTS;
            case ALBUMS -> SCREEN_ALBUMS;
            case SINGLES -> SCREEN_SINGLES;
        };
    }

    private Object getCurrentController() {
        Parent current = (Parent) centerHost.getCenter();
        if (current == null) return null;
        return current.getProperties().get("controller");
    }
}
