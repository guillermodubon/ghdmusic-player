package io.github.guillermodubon.musicplayer.controllers.ui.screens.SplashScreen;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.util.Duration;
import io.github.guillermodubon.musicplayer.services.downloads.dependencies.YtDlpUpdateService;
import io.github.guillermodubon.musicplayer.services.images.AppLogoImageLoader;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

public class SplashScreenController {

    private static final double DEFAULT_LOGO_SIZE = 240.0;
    private static final double MIN_LOGO_SIZE = 120.0;
    private static final double MAX_LOGO_SIZE = 280.0;
    private static final Color SPLASH_BASE_COLOR = Color.web("#111111");
    private static final Color SPLASH_TEAL_COLOR = Color.web("#00283D");
    private static final Color SPLASH_PLUM_COLOR = Color.web("#00283D");

    private static final LinearGradient SPLASH_AMBIENT_GRADIENT = new LinearGradient(
            0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
            new Stop(0.00, SPLASH_BASE_COLOR.interpolate(SPLASH_TEAL_COLOR, 0.48)),
            new Stop(0.16, SPLASH_BASE_COLOR.interpolate(SPLASH_TEAL_COLOR, 0.35)),
            new Stop(0.34, SPLASH_BASE_COLOR.interpolate(SPLASH_TEAL_COLOR, 0.16)),
            new Stop(0.52, SPLASH_BASE_COLOR),
            new Stop(0.68, SPLASH_BASE_COLOR),
            new Stop(0.82, SPLASH_BASE_COLOR.interpolate(SPLASH_PLUM_COLOR, 0.16)),
            new Stop(1.00, SPLASH_BASE_COLOR.interpolate(SPLASH_PLUM_COLOR, 0.46))
    );

    @FXML private VBox splashRoot;
    @FXML private ProgressBar progressBar;
    @FXML private ImageView logoView;
    @FXML private Label statusLabel;

    private StartUpService startUpService;
    private Runnable onSuccess;
    private final YtDlpUpdateService ytDlpUpdateService = new YtDlpUpdateService();

    public void init(StartUpService svc, Runnable onSuccess) {
        this.startUpService = svc;
        this.onSuccess = onSuccess;

        configureResponsiveLogo();
        logoView.setPreserveRatio(true);
        logoView.setSmooth(true);
        AppLogoImageLoader.installSplash(logoView);

        applyAmbientBackground();
        // Reapply after the first CSS/layout pulse so no later stylesheet pass can
        // replace the background visible behind the splash content.
        Platform.runLater(this::applyAmbientBackground);

        startBackgroundInit();
    }

    private void configureResponsiveLogo() {
        if (logoView == null || splashRoot == null) return;

        if (logoView.fitWidthProperty().isBound()) {
            logoView.fitWidthProperty().unbind();
        }
        if (logoView.fitHeightProperty().isBound()) {
            logoView.fitHeightProperty().unbind();
        }

        var responsiveSize = Bindings.createDoubleBinding(
                () -> resolveLogoSize(splashRoot.getWidth(), splashRoot.getHeight()),
                splashRoot.widthProperty(),
                splashRoot.heightProperty()
        );
        logoView.fitWidthProperty().bind(responsiveSize);
        logoView.fitHeightProperty().bind(responsiveSize);
    }

    private double resolveLogoSize(double width, double height) {
        if (width <= 0 || height <= 0) return DEFAULT_LOGO_SIZE;

        double availableWidth = Math.max(0, width - 72);
        double availableHeight = Math.max(0, height * 0.52);
        double size = Math.min(availableWidth * 0.72, availableHeight);
        return Math.max(MIN_LOGO_SIZE, Math.min(MAX_LOGO_SIZE, size));
    }

    private void applyAmbientBackground() {
        if (splashRoot == null) {
            return;
        }

        splashRoot.setBackground(new Background(
                new BackgroundFill(SPLASH_AMBIENT_GRADIENT, CornerRadii.EMPTY, Insets.EMPTY)));
    }

    private void startBackgroundInit() {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                updateMessage("Preparing your music library...");
                updateProgress(0, 1);

                startUpService.setStartupStatusListener(this::updateMessage);
                startUpService.setStartupProgressListener(progress -> updateProgress(progress, 1));

                startUpService.runStartup();

                updateMessage("Looking for updates");
                updateProgress(0.99, 1);
                ytDlpUpdateService.updateBundledYtDlp();

                updateMessage("Your music is ready.");
                updateProgress(1, 1);
                return null;
            }
        };

        if (statusLabel != null) {
            statusLabel.textProperty().bind(task.messageProperty());
        }
        if (progressBar != null) {
            progressBar.setProgress(0);
            progressBar.progressProperty().bind(task.progressProperty());
        }

        task.setOnSucceeded(e -> {
            clearStartupListeners();
            finishProgress(1);

            PauseTransition completedState = new PauseTransition(Duration.millis(140));
            completedState.setOnFinished(event -> onSuccess.run());
            completedState.play();
        });

        task.setOnFailed(e -> {
            clearStartupListeners();
            finishProgress(0);
            if (statusLabel != null) {
                statusLabel.textProperty().unbind();
                statusLabel.setText("We could not finish loading your library.");
            }

            task.getException().printStackTrace();
            onSuccess.run();
        });

        Thread startupThread = new Thread(task, "startup-loader");
        startupThread.setDaemon(true);
        startupThread.start();
    }

    private void clearStartupListeners() {
        startUpService.setStartupStatusListener(null);
        startUpService.setStartupProgressListener(null);
    }

    private void finishProgress(double progress) {
        if (progressBar == null) {
            return;
        }
        progressBar.progressProperty().unbind();
        progressBar.setProgress(progress);
    }
}
