package io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents;

import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import io.github.guillermodubon.musicplayer.controllers.ui.components.icons.SvgIconFactory;
import io.github.guillermodubon.musicplayer.services.navigation.AppLayoutNavigator;
import io.github.guillermodubon.musicplayer.services.navigation.PlayerMenuNavigator;
import io.github.guillermodubon.musicplayer.services.navigation.SceneStateFlowManager;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.libraryCatalogListScreens.common.CatalogType;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.ArtistCardActionManager;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.ArtistOpenCoordinator;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.GenreCardActionManager;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.services.images.AppLogoImageLoader;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

public class SideBarNavigationMenu {

    private static final String ICON_ROOT = "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/";
    private static final String ICON_HOME = ICON_ROOT + "home_27dp_EDCC0E_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_DISCOVER = ICON_ROOT + "explore_27dp_EDCC0E_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_CREATE = ICON_ROOT + "add_2_27dp_EDCC0E_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_PLAYLISTS = ICON_ROOT + "library_music_27dp_EDCC0E_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_ALBUMS = ICON_ROOT + "album_27dp_EDCC0E_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_SINGLES = ICON_ROOT + "music_note_27dp_EDCC0E_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_ARTISTS = ICON_ROOT + "artist_27dp_EDCC0E_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ACTIVE_CLASS = "left-menu-button-active";
    private static final String FOCUSED_CLASS = "left-menu-button-focused";
    private static final PseudoClass SCREEN_FOCUSED = PseudoClass.getPseudoClass("screen-focused");
    private static final double APP_LOGO_SIZE = 40.0;

    @FXML private ImageView appIcon;
    @FXML private Button btnHome, btnDiscover, btnCreatePlaylist;
    @FXML private Button btnShowAllPlaylists, btnShowAllAlbums, btnShowAllSingles, btnShowAllArtists;

    private StartUpService svc;
    private BorderPane mainRoot;
    private AppLayoutNavigator navigator;

    private MusicCardActionManager musicActions;
    private ArtistCardActionManager artistActions;
    private GenreCardActionManager genreActions;
    private Button activeButton;

    @FXML
    private void initialize() {
        configureAppLogo();
        installButtonIcon(btnHome, ICON_HOME);
        installButtonIcon(btnDiscover, ICON_DISCOVER);
        installButtonIcon(btnCreatePlaylist, ICON_CREATE);
        installButtonIcon(btnShowAllPlaylists, ICON_PLAYLISTS);
        installButtonIcon(btnShowAllAlbums, ICON_ALBUMS);
        installButtonIcon(btnShowAllSingles, ICON_SINGLES);
        installButtonIcon(btnShowAllArtists, ICON_ARTISTS);
        setActiveButton(btnHome);
    }

    private void configureAppLogo() {
        if (appIcon == null) return;
        appIcon.setFitWidth(APP_LOGO_SIZE);
        appIcon.setFitHeight(APP_LOGO_SIZE);
        appIcon.setPreserveRatio(true);
        appIcon.setSmooth(true);
        AppLogoImageLoader.installSidebar(appIcon);
    }

    public void init(StartUpService svc, BorderPane root) {
        this.svc = svc;
        this.mainRoot = root;

        PlayerMenuNavigator playerMenuNavigator = new PlayerMenuNavigator(svc);
        ArtistOpenCoordinator artistCoordinator = new ArtistOpenCoordinator(svc, playerMenuNavigator);

        this.musicActions = new MusicCardActionManager(svc,playerMenuNavigator,artistCoordinator);
        this.artistActions = new ArtistCardActionManager(svc,playerMenuNavigator);
        this.genreActions = new GenreCardActionManager(svc);

        this.navigator = new AppLayoutNavigator(svc, root, musicActions, artistActions, genreActions);

        svc.setLeftMenuController(this);
        bindActiveButtonToVisibleScreen(root);

        btnHome.setOnAction(e -> runNavigation(AppLayoutNavigator.SCREEN_HOME, () -> navigator.goHome()));
        btnDiscover.setOnAction(e -> runNavigation(AppLayoutNavigator.SCREEN_DISCOVER, () -> navigator.goDiscover()));
        btnCreatePlaylist.setOnAction(e -> run(() -> navigator.openCreatePlaylistDialog()));

        btnShowAllPlaylists.setOnAction(e -> runNavigation(AppLayoutNavigator.SCREEN_PLAYLISTS, () -> navigator.openCatalog(CatalogType.PLAYLISTS)));
        btnShowAllAlbums.setOnAction(e -> runNavigation(AppLayoutNavigator.SCREEN_ALBUMS, () -> navigator.openCatalog(CatalogType.ALBUMS)));
        btnShowAllSingles.setOnAction(e -> runNavigation(AppLayoutNavigator.SCREEN_SINGLES, () -> navigator.openCatalog(CatalogType.SINGLES)));
        btnShowAllArtists.setOnAction(e -> runNavigation(AppLayoutNavigator.SCREEN_ARTISTS, () -> navigator.openArtistCatalog()));
    }

    /**
     * Reuses the sidebar's navigation flow for links rendered inside other screens.
     */
    public void openCatalog(CatalogType type) {
        if (type == null || navigator == null) return;
        runNavigation(screenKeyForCatalog(type), () -> navigator.openCatalog(type));
    }

    public void openDiscover() {
        if (navigator == null) return;
        runNavigation(AppLayoutNavigator.SCREEN_DISCOVER, navigator::goDiscover);
    }

    private void installButtonIcon(Button button, String iconPath) {
        if (button == null) return;
        if (!button.getStyleClass().contains("left-menu-button")) {
            button.getStyleClass().add("left-menu-button");
        }
        button.setMouseTransparent(false);
        button.setFocusTraversable(false);
        button.setContentDisplay(ContentDisplay.LEFT);
        button.setGraphicTextGap(12);
        button.setGraphic(SvgIconFactory.icon(iconPath, 22));
    }

    private void runNavigation(String targetScreenKey, ActionRunnable action) {
        String currentScreenKey = SceneStateFlowManager.getInstance().getCurrentScreenKey();
        if (targetScreenKey != null && targetScreenKey.equals(currentScreenKey)) return;
        run(() -> {
            action.run();
        });
    }

    private String screenKeyForCatalog(CatalogType type) {
        return switch (type) {
            case PLAYLISTS -> AppLayoutNavigator.SCREEN_PLAYLISTS;
            case ALBUMS -> AppLayoutNavigator.SCREEN_ALBUMS;
            case SINGLES -> AppLayoutNavigator.SCREEN_SINGLES;
        };
    }

    private void setActiveButton(Button button) {
        if (activeButton != null) {
            activeButton.getStyleClass().remove(ACTIVE_CLASS);
            activeButton.getStyleClass().remove(FOCUSED_CLASS);
            activeButton.pseudoClassStateChanged(SCREEN_FOCUSED, false);
        }
        activeButton = button;
        if (activeButton != null) {
            if (!activeButton.getStyleClass().contains(ACTIVE_CLASS)) {
                activeButton.getStyleClass().add(ACTIVE_CLASS);
            }
            if (!activeButton.getStyleClass().contains(FOCUSED_CLASS)) {
                activeButton.getStyleClass().add(FOCUSED_CLASS);
            }
            activeButton.pseudoClassStateChanged(SCREEN_FOCUSED, true);
        }
    }

    private void bindActiveButtonToVisibleScreen(BorderPane root) {
        SceneStateFlowManager navigation = SceneStateFlowManager.getInstance();
        navigation.currentScreenKeyProperty().addListener((obs, oldKey, newKey) ->
                setActiveButton(buttonForScreenKey(newKey)));

        if (root != null) {
            root.centerProperty().addListener((obs, oldCenter, newCenter) ->
                    setActiveButton(buttonForScreenKey(screenKeyForNode(newCenter))));
            setActiveButton(buttonForScreenKey(screenKeyForNode(root.getCenter())));
        } else {
            setActiveButton(buttonForScreenKey(navigation.getCurrentScreenKey()));
        }
    }

    private Button buttonForScreenKey(String screenKey) {
        if (screenKey == null || screenKey.isBlank()) return null;
        return switch (screenKey) {
            case AppLayoutNavigator.SCREEN_HOME -> btnHome;
            case AppLayoutNavigator.SCREEN_DISCOVER -> btnDiscover;
            case AppLayoutNavigator.SCREEN_PLAYLISTS -> btnShowAllPlaylists;
            case AppLayoutNavigator.SCREEN_ALBUMS -> btnShowAllAlbums;
            case AppLayoutNavigator.SCREEN_SINGLES -> btnShowAllSingles;
            case AppLayoutNavigator.SCREEN_ARTISTS -> btnShowAllArtists;
            default -> null;
        };
    }

    private String screenKeyForNode(Node node) {
        if (node == null) return null;
        Object value = node.getProperties().get(SceneStateFlowManager.SCREEN_KEY_PROPERTY);
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private void run(ActionRunnable action) {
        try {
            action.run();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    @FunctionalInterface
    private interface ActionRunnable {
        void run() throws Exception;
    }
}
