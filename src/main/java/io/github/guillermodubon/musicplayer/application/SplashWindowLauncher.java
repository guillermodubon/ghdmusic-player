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
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.Objects;
import java.util.function.BiConsumer;

/** Owns the fixed, non-interactive splash window and its temporary stage policy. */
public final class SplashWindowLauncher {

    private static final double MIN_SPLASH_SIZE = 320;
    private static final double MAX_SPLASH_SIZE = 500;
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

        double splashSize = resolveSplashSize();
        stage.setMinWidth(splashSize);
        stage.setMaxWidth(splashSize);
        stage.setMinHeight(splashSize);
        stage.setMaxHeight(splashSize);
        stage.setWidth(splashSize);
        stage.setHeight(splashSize);
        stage.centerOnScreen();
    }

    private double resolveSplashSize() {
        Rectangle2D visualBounds = Screen.getPrimary().getVisualBounds();
        double availableWidth = visualBounds.getWidth() * 0.80;
        double availableHeight = visualBounds.getHeight() * 0.86;
        double available = Math.min(availableWidth, availableHeight);
        return Math.max(MIN_SPLASH_SIZE, Math.min(MAX_SPLASH_SIZE, available));
    }
}
