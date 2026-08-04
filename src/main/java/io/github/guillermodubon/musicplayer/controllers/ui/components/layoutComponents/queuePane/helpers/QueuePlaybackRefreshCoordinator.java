package io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane.helpers;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.util.Duration;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Debounces playback-flow changes before asking the queue renderer to refresh. */
public final class QueuePlaybackRefreshCoordinator {

    private static final Duration REFRESH_DELAY = Duration.millis(140);
    private static final Duration REFRESH_DEBOUNCE = Duration.millis(90);
    private static final Duration SETTLE_DELAY = Duration.millis(260);
    private static final int MAX_SETTLE_PASSES = 2;

    private final BooleanSupplier attachedAndVisible;
    private final Runnable refreshCallback;
    private final PauseTransition refreshDelay = new PauseTransition(REFRESH_DELAY);
    private final PauseTransition debounce = new PauseTransition(REFRESH_DEBOUNCE);
    private final PauseTransition settleDelay = new PauseTransition(SETTLE_DELAY);
    private int pendingSettlePasses;

    public QueuePlaybackRefreshCoordinator(
            BooleanSupplier attachedAndVisible,
            Runnable refreshCallback
    ) {
        this.attachedAndVisible = Objects.requireNonNull(attachedAndVisible);
        this.refreshCallback = Objects.requireNonNull(refreshCallback);
    }

    public void start() {
        refreshDelay.setOnFinished(event -> {
            if (!attachedAndVisible.getAsBoolean()) {
                pendingSettlePasses = 0;
                return;
            }

            pendingSettlePasses = MAX_SETTLE_PASSES;
            performRefresh();
        });
        debounce.setOnFinished(event -> performRefresh());
        settleDelay.setOnFinished(event -> {
            if (!attachedAndVisible.getAsBoolean()) {
                pendingSettlePasses = 0;
                return;
            }
            performRefresh();
        });
    }

    public void request() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::request);
            return;
        }

        if (!attachedAndVisible.getAsBoolean()) {
            return;
        }

        refreshDelay.stop();
        refreshDelay.playFromStart();
    }

    public void stop() {
        refreshDelay.stop();
        debounce.stop();
        settleDelay.stop();
        pendingSettlePasses = 0;
    }

    private void performRefresh() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::performRefresh);
            return;
        }

        if (!attachedAndVisible.getAsBoolean()) {
            pendingSettlePasses = 0;
            return;
        }

        refreshCallback.run();
        if (pendingSettlePasses > 0) {
            pendingSettlePasses--;
            settleDelay.stop();
            settleDelay.playFromStart();
        }
    }
}
