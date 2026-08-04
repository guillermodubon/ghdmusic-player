package io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers;

import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import io.github.guillermodubon.musicplayer.services.navigation.PlayerMenuNavigator;
import io.github.guillermodubon.musicplayer.services.navigation.SceneStateFlowManager;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.GenrePageController;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

public class GenrePlaybackCoordinator {

    private final StartUpService svc;

    public GenrePlaybackCoordinator(StartUpService svc) {
        this.svc = svc;
    }

    public void handle(int genreId, String genreName, Node probe) {
        openGenreDetails(genreId, genreName);
    }

    public void handle(int genreId, String genreName) {
        handle(genreId, genreName, null);
    }

    private void openGenreDetails(int genreId, String genreName) {
        try {
            Parent view = createGenreView(genreId, genreName);
            if (view == null) {
                return;
            }
            SceneStateFlowManager.getInstance().navigateToAndPushCurrent(view, null, null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Parent createGenreView(int genreId, String genreName) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/io/github/guillermodubon/musicplayer/Views/screens/genrePage/GenrePage.fxml")
            );

            Parent view = loader.load();
            Object ctrlObj = loader.getController();
            if (ctrlObj instanceof GenrePageController ctrl) {
                PlayerMenuNavigator navigator = new PlayerMenuNavigator(svc);
                ArtistOpenCoordinator artistOpenCoordinator = new ArtistOpenCoordinator(svc, navigator);
                MusicCardActionManager musicActions = new MusicCardActionManager(svc, navigator, artistOpenCoordinator);
                ArtistCardActionManager artistActions = new ArtistCardActionManager(svc, navigator);

                ctrl.init(svc, musicActions, artistActions, genreId, genreName);
            }

            if (view != null && ctrlObj != null) {
                view.getProperties().put("controller", ctrlObj);
            }

            SceneStateFlowManager.attachNavigationFactory(
                    view,
                    () -> createGenreView(genreId, genreName)
            );
            return view;
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }
    }
}
