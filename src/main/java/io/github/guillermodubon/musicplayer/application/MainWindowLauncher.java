package io.github.guillermodubon.musicplayer.application;

import io.github.guillermodubon.musicplayer.controllers.layout.AppShellController;
import io.github.guillermodubon.musicplayer.controllers.layout.window.ApplicationWindowPolicy;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.ArtistCardActionManager;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.ArtistOpenCoordinator;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.GenreCardActionManager;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.services.navigation.AppLayoutNavigator;
import io.github.guillermodubon.musicplayer.services.navigation.PlayerMenuNavigator;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/** Builds the main application shell after startup services have completed. */
public final class MainWindowLauncher {

    private static final String APP_SHELL_VIEW =
            "/io/github/guillermodubon/musicplayer/Views/layout/AppShell.fxml";

    private final SplashWindowLauncher splashWindowLauncher;

    public MainWindowLauncher(SplashWindowLauncher splashWindowLauncher) {
        this.splashWindowLauncher = splashWindowLauncher;
    }

    public void show(Stage stage, StartUpService startUpService) throws Exception {
        splashWindowLauncher.release(stage);

        PlayerMenuNavigator playerMenuNavigator = new PlayerMenuNavigator(startUpService);
        ArtistOpenCoordinator artistOpenCoordinator =
                new ArtistOpenCoordinator(startUpService, playerMenuNavigator);
        MusicCardActionManager musicActions = new MusicCardActionManager(
                startUpService,
                playerMenuNavigator,
                artistOpenCoordinator
        );
        ArtistCardActionManager artistActions =
                new ArtistCardActionManager(startUpService, playerMenuNavigator);
        GenreCardActionManager genreActions = new GenreCardActionManager(startUpService);

        FXMLLoader shellLoader = new FXMLLoader(MainWindowLauncher.class.getResource(APP_SHELL_VIEW));
        Parent shellRoot = shellLoader.load();
        AppShellController shellController = shellLoader.getController();
        shellController.init(startUpService, musicActions, artistActions);

        Scene mainScene = new Scene(shellRoot);
        stage.setScene(mainScene);
        stage.setTitle("Inicio");
        ApplicationWindowPolicy.configureMainStage(stage);
        stage.show();

        Platform.runLater(() -> openHomeScreen(
                startUpService,
                shellController,
                musicActions,
                artistActions,
                genreActions
        ));
    }

    private void openHomeScreen(
            StartUpService startUpService,
            AppShellController shellController,
            MusicCardActionManager musicActions,
            ArtistCardActionManager artistActions,
            GenreCardActionManager genreActions
    ) {
        try {
            AppLayoutNavigator appNavigator = new AppLayoutNavigator(
                    startUpService,
                    shellController.getCenterHost(),
                    musicActions,
                    artistActions,
                    genreActions
            );
            appNavigator.goHome();
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }
}
