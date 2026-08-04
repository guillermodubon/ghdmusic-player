package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.layout;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.function.BooleanSupplier;

/** Keeps the internal fullscreen layer aligned with window and F11 changes. */
public final class PlayerFullScreenWindowTracker {

    private final BooleanSupplier active;
    private final Runnable synchronize;
    private Stage trackedStage;
    private Scene trackedScene;
    private javafx.beans.value.ChangeListener<Boolean> stageFullScreenListener;
    private javafx.beans.value.ChangeListener<Number> stageWidthListener;
    private javafx.beans.value.ChangeListener<Number> stageHeightListener;
    private javafx.beans.value.ChangeListener<Number> sceneWidthListener;
    private javafx.beans.value.ChangeListener<Number> sceneHeightListener;
    private boolean synchronizationScheduled;
    private boolean settleScheduled;

    public PlayerFullScreenWindowTracker(BooleanSupplier active, Runnable synchronize) {
        this.active = active;
        this.synchronize = synchronize;
    }

    public void install(Scene scene) {
        stop();
        if (scene == null || !(scene.getWindow() instanceof Stage stage)) {
            return;
        }
        trackedStage = stage;
        trackedScene = scene;
        stageFullScreenListener = (obs, oldValue, newValue) -> {
            scheduleSynchronization();
            scheduleNativeFullScreenSettledSynchronization();
        };
        stageWidthListener = (obs, oldValue, newValue) -> scheduleSynchronization();
        stageHeightListener = (obs, oldValue, newValue) -> scheduleSynchronization();
        sceneWidthListener = (obs, oldValue, newValue) -> scheduleSynchronization();
        sceneHeightListener = (obs, oldValue, newValue) -> scheduleSynchronization();

        stage.fullScreenProperty().addListener(stageFullScreenListener);
        stage.widthProperty().addListener(stageWidthListener);
        stage.heightProperty().addListener(stageHeightListener);
        scene.widthProperty().addListener(sceneWidthListener);
        scene.heightProperty().addListener(sceneHeightListener);
    }

    public void stop() {
        if (trackedStage != null) {
            if (stageFullScreenListener != null) {
                trackedStage.fullScreenProperty().removeListener(stageFullScreenListener);
            }
            if (stageWidthListener != null) {
                trackedStage.widthProperty().removeListener(stageWidthListener);
            }
            if (stageHeightListener != null) {
                trackedStage.heightProperty().removeListener(stageHeightListener);
            }
        }
        if (trackedScene != null) {
            if (sceneWidthListener != null) {
                trackedScene.widthProperty().removeListener(sceneWidthListener);
            }
            if (sceneHeightListener != null) {
                trackedScene.heightProperty().removeListener(sceneHeightListener);
            }
        }
        trackedStage = null;
        trackedScene = null;
        stageFullScreenListener = null;
        stageWidthListener = null;
        stageHeightListener = null;
        sceneWidthListener = null;
        sceneHeightListener = null;
        synchronizationScheduled = false;
        settleScheduled = false;
    }

    public void scheduleSynchronization() {
        if (!active.getAsBoolean() || synchronizationScheduled) {
            return;
        }
        synchronizationScheduled = true;
        Platform.runLater(() -> Platform.runLater(() -> {
            synchronizationScheduled = false;
            synchronize.run();
        }));
    }

    public void dispose() {
        stop();
    }

    private void scheduleNativeFullScreenSettledSynchronization() {
        if (settleScheduled) {
            return;
        }
        settleScheduled = true;
        PauseTransition firstPass = new PauseTransition(Duration.millis(180));
        firstPass.setOnFinished(event -> {
            synchronize.run();
            PauseTransition finalPass = new PauseTransition(Duration.millis(220));
            finalPass.setOnFinished(finalEvent -> {
                synchronize.run();
                settleScheduled = false;
            });
            finalPass.play();
        });
        firstPass.play();
    }
}
