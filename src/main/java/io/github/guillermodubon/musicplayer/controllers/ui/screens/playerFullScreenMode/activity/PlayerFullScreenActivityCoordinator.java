package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.activity;

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.PauseTransition;
import javafx.animation.TranslateTransition;
import javafx.scene.Scene;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TouchEvent;
import javafx.scene.layout.BorderPane;
import javafx.util.Duration;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.PlayerMenuBarController;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Owns fullscreen activity filters and the six-second player-bar behavior. */
public final class PlayerFullScreenActivityCoordinator {

    private static final Duration INACTIVITY_DELAY = Duration.seconds(6);
    private static final Duration BAR_ANIMATION_DURATION = Duration.millis(170);
    private static final long TIMER_RESET_INTERVAL_NANOS = 100_000_000L;
    private static final double PLAYER_BAR_HEIGHT = 96.0;

    private final PauseTransition inactivityTimer = new PauseTransition(INACTIVITY_DELAY);
    private final BooleanSupplier active;
    private final Supplier<PlayerMenuBarController> playerBarController;
    private final Runnable bringBarToFront;

    private Scene activityScene;
    private javafx.event.EventHandler<MouseEvent> mouseActivityHandler;
    private javafx.event.EventHandler<KeyEvent> keyActivityHandler;
    private javafx.event.EventHandler<ScrollEvent> scrollActivityHandler;
    private javafx.event.EventHandler<TouchEvent> touchActivityHandler;
    private TranslateTransition playerBarTransition;
    private boolean playerBarHidden;
    private boolean showAnimationRunning;
    private long lastTimerRestartNanos;

    public PlayerFullScreenActivityCoordinator(
            BooleanSupplier active,
            Supplier<PlayerMenuBarController> playerBarController,
            Runnable bringBarToFront
    ) {
        this.active = active;
        this.playerBarController = playerBarController;
        this.bringBarToFront = bringBarToFront;
        inactivityTimer.setOnFinished(event -> hidePlayerBarAfterInactivity());
    }

    public void installTracking(Scene scene) {
        stopTracking();
        if (scene == null) {
            return;
        }
        activityScene = scene;
        mouseActivityHandler = event -> registerActivity();
        keyActivityHandler = event -> registerActivity();
        scrollActivityHandler = event -> registerActivity();
        touchActivityHandler = event -> registerActivity();

        scene.addEventFilter(MouseEvent.MOUSE_MOVED, mouseActivityHandler);
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, mouseActivityHandler);
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, mouseActivityHandler);
        scene.addEventFilter(KeyEvent.ANY, keyActivityHandler);
        scene.addEventFilter(ScrollEvent.ANY, scrollActivityHandler);
        scene.addEventFilter(TouchEvent.ANY, touchActivityHandler);
    }

    public void stopTracking() {
        inactivityTimer.stop();
        lastTimerRestartNanos = 0L;
        if (activityScene != null) {
            if (mouseActivityHandler != null) {
                activityScene.removeEventFilter(MouseEvent.MOUSE_MOVED, mouseActivityHandler);
                activityScene.removeEventFilter(MouseEvent.MOUSE_DRAGGED, mouseActivityHandler);
                activityScene.removeEventFilter(MouseEvent.MOUSE_PRESSED, mouseActivityHandler);
            }
            if (keyActivityHandler != null) {
                activityScene.removeEventFilter(KeyEvent.ANY, keyActivityHandler);
            }
            if (scrollActivityHandler != null) {
                activityScene.removeEventFilter(ScrollEvent.ANY, scrollActivityHandler);
            }
            if (touchActivityHandler != null) {
                activityScene.removeEventFilter(TouchEvent.ANY, touchActivityHandler);
            }
        }
        activityScene = null;
        mouseActivityHandler = null;
        keyActivityHandler = null;
        scrollActivityHandler = null;
        touchActivityHandler = null;
    }

    public void registerActivity() {
        if (!active.getAsBoolean()) {
            return;
        }
        showPlayerBar();
        restartTimer();
    }

    public void restartTimer() {
        if (!active.getAsBoolean()) {
            return;
        }
        long now = System.nanoTime();
        if (inactivityTimer.getStatus() == Animation.Status.RUNNING
                && now - lastTimerRestartNanos < TIMER_RESET_INTERVAL_NANOS) {
            return;
        }
        inactivityTimer.stop();
        inactivityTimer.playFromStart();
        lastTimerRestartNanos = now;
    }

    public void showImmediately() {
        inactivityTimer.stop();
        lastTimerRestartNanos = 0L;
        stopPlayerBarTransition();
        playerBarHidden = false;
        showAnimationRunning = false;
        BorderPane playerBar = resolvePlayerBar();
        if (playerBar == null) {
            return;
        }
        playerBar.setManaged(true);
        playerBar.setVisible(true);
        playerBar.setMouseTransparent(false);
        playerBar.setOpacity(1.0);
        playerBar.setTranslateX(0.0);
        playerBar.setTranslateY(0.0);
        bringBarToFront.run();
    }

    public void stopPlayerBarTransition() {
        if (playerBarTransition == null) {
            showAnimationRunning = false;
            return;
        }
        try {
            playerBarTransition.stop();
        } catch (Exception ignored) {
        }
        playerBarTransition = null;
        showAnimationRunning = false;
    }

    public void dispose() {
        stopTracking();
        stopPlayerBarTransition();
        playerBarHidden = false;
    }

    private void hidePlayerBarAfterInactivity() {
        if (!active.getAsBoolean()) {
            return;
        }
        PlayerMenuBarController controller = playerBarController.get();
        if (controller == null) {
            return;
        }
        if (controller.isUserInteractingWithPlayerControls()) {
            restartTimer();
            return;
        }

        BorderPane playerBar = controller.getPlayerMenuBarRoot();
        if (playerBar == null || playerBarHidden || !playerBar.isVisible()) {
            return;
        }

        stopPlayerBarTransition();
        playerBarHidden = true;
        showAnimationRunning = false;
        playerBar.setMouseTransparent(true);
        playerBar.setOpacity(1.0);

        TranslateTransition slide = new TranslateTransition(BAR_ANIMATION_DURATION, playerBar);
        slide.setFromY(playerBar.getTranslateY());
        slide.setToY(Math.max(PLAYER_BAR_HEIGHT, playerBar.getHeight()));
        slide.setInterpolator(Interpolator.EASE_IN);
        playerBarTransition = slide;
        slide.setOnFinished(event -> {
            playerBarTransition = null;
            if (!active.getAsBoolean() || !playerBarHidden) {
                return;
            }
            playerBar.setVisible(false);
            playerBar.setMouseTransparent(true);
        });
        slide.play();
    }

    private void showPlayerBar() {
        if (!active.getAsBoolean()) {
            return;
        }
        BorderPane playerBar = resolvePlayerBar();
        if (playerBar == null) {
            return;
        }
        if (!playerBarHidden && playerBar.isVisible()
                && playerBar.getOpacity() >= 1.0
                && Math.abs(playerBar.getTranslateY()) < 0.5) {
            return;
        }
        if (showAnimationRunning && playerBarTransition != null
                && playerBarTransition.getStatus() == Animation.Status.RUNNING) {
            return;
        }

        stopPlayerBarTransition();
        playerBarHidden = false;
        showAnimationRunning = true;
        playerBar.setManaged(true);
        playerBar.setVisible(true);
        playerBar.setMouseTransparent(false);
        playerBar.setOpacity(1.0);
        bringBarToFront.run();

        TranslateTransition slide = new TranslateTransition(BAR_ANIMATION_DURATION, playerBar);
        slide.setFromY(playerBar.getTranslateY());
        slide.setToY(0.0);
        slide.setInterpolator(Interpolator.EASE_OUT);
        playerBarTransition = slide;
        slide.setOnFinished(event -> {
            playerBarTransition = null;
            showAnimationRunning = false;
            if (!active.getAsBoolean() || playerBarHidden) {
                return;
            }
            playerBar.setManaged(true);
            playerBar.setVisible(true);
            playerBar.setMouseTransparent(false);
            playerBar.setOpacity(1.0);
            playerBar.setTranslateX(0.0);
            playerBar.setTranslateY(0.0);
            bringBarToFront.run();
        });
        slide.play();
    }

    private BorderPane resolvePlayerBar() {
        PlayerMenuBarController controller = playerBarController.get();
        return controller == null ? null : controller.getPlayerMenuBarRoot();
    }
}
