package io.github.guillermodubon.musicplayer.application;

import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import javafx.stage.Stage;

/** Coordinates the transition from the splash window to the main window. */
public final class MusicPlayerApplicationBootstrap {

    private final SplashWindowLauncher splashWindowLauncher;
    private final MainWindowLauncher mainWindowLauncher;

    public MusicPlayerApplicationBootstrap() {
        this.splashWindowLauncher = new SplashWindowLauncher();
        this.mainWindowLauncher = new MainWindowLauncher(splashWindowLauncher);
    }

    public void start(Stage stage) throws Exception {
        splashWindowLauncher.show(stage, this::openMainWindow);
    }

    private void openMainWindow(StartUpService startUpService, Stage stage) {
        try {
            mainWindowLauncher.show(stage, startUpService);
            System.out.println("LA APLICACION HA CARGADO CORRECTAMENTE");
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }
}
