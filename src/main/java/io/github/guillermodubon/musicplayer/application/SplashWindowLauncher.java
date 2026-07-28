package io.github.guillermodubon.musicplayer.application;

import io.github.guillermodubon.musicplayer.controllers.ui.screens.SplashScreen.SplashScreenController;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

import java.util.Objects;
import java.util.function.BiConsumer;

/** Owns the fixed, non-interactive splash window and its temporary stage policy. */
public final class SplashWindowLauncher {

    private static final double SPLASH_WIDTH = 560;
    private static final double SPLASH_HEIGHT = 400;
    private static final String SPLASH_VIEW =
            "/io/github/guillermodubon/musicplayer/Views/screens/splashScreen/SplashScreen.fxml";

    private final ApplicationIconLoader iconLoader;
    private ChangeListener<Boolean> fullScreenGuard;

    public SplashWindowLauncher() {
        this.iconLoader = new ApplicationIconLoader();
    }

    public void show(Stage stage, BiConsumer<StartUpService, Stage> onStartupComplete)
            throws Exception {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(onStartupComplete, "onStartupComplete");

        FXMLLoader loader = new FXMLLoader(SplashWindowLauncher.class.getResource(SPLASH_VIEW));
        Scene scene = new Scene(loader.load());
        SplashScreenController controller = loader.getController();
        StartUpService startUpService = new StartUpService();

        configureStage(stage, scene);
        stage.setScene(scene);
        stage.setTitle("Loading...");
        stage.getIcons().setAll(iconLoader.loadIcons());
        stage.show();

        controller.init(startUpService, () -> onStartupComplete.accept(startUpService, stage));
    }

    public void release(Stage stage) {
        if (fullScreenGuard != null) {
            stage.fullScreenProperty().removeListener(fullScreenGuard);
            fullScreenGuard = null;
        }

        stage.setFullScreen(false);
        stage.setResizable(true);
        stage.setMinWidth(0);
        stage.setMinHeight(0);
        stage.setMaxWidth(Double.MAX_VALUE);
        stage.setMaxHeight(Double.MAX_VALUE);
        stage.setFullScreenExitKeyCombination(KeyCombination.valueOf("ESCAPE"));
    }

    private void configureStage(Stage stage, Scene scene) {
        stage.setFullScreen(false);
        stage.setResizable(false);
        stage.setMaximized(false);
        stage.setFullScreenExitHint("");
        stage.setFullScreenExitKeyCombination(KeyCombination.NO_MATCH);

        fullScreenGuard = (observable, wasFullScreen, isFullScreen) -> {
            if (Boolean.TRUE.equals(isFullScreen)) {
                Platform.runLater(() -> stage.setFullScreen(false));
            }
        };
        stage.fullScreenProperty().addListener(fullScreenGuard);

        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.F11) {
                event.consume();
            }
        });

        stage.setMinWidth(SPLASH_WIDTH);
        stage.setMaxWidth(SPLASH_WIDTH);
        stage.setMinHeight(SPLASH_HEIGHT);
        stage.setMaxHeight(SPLASH_HEIGHT);
        stage.setWidth(SPLASH_WIDTH);
        stage.setHeight(SPLASH_HEIGHT);
        stage.centerOnScreen();
    }
}
