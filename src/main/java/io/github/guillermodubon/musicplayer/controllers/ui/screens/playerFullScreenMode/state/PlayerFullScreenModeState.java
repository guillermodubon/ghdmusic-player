package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.state;

import javafx.animation.PauseTransition;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.Background;
import javafx.util.Duration;

import java.util.IdentityHashMap;
import java.util.Map;

public final class PlayerFullScreenModeState {

    private static final Duration CLOSE_BUTTON_IDLE_DELAY = Duration.seconds(5);

    private boolean active;
    private Background previousShellBackground;
    private final Map<Node, VisibilitySnapshot> visibilitySnapshots = new IdentityHashMap<>();
    private PauseTransition closeButtonIdleTimer;
    private Button closeButton;

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Background previousShellBackground() {
        return previousShellBackground;
    }

    public void setPreviousShellBackground(Background previousShellBackground) {
        this.previousShellBackground = previousShellBackground;
    }

    public void rememberVisibility(Node node) {
        if (node == null || visibilitySnapshots.containsKey(node)) return;
        visibilitySnapshots.put(node, new VisibilitySnapshot(node.isVisible(), node.isManaged()));
    }

    public void restoreVisibility() {
        visibilitySnapshots.forEach((node, snapshot) -> {
            if (node == null || snapshot == null) return;
            node.setVisible(snapshot.visible());
            node.setManaged(snapshot.managed());
        });
        visibilitySnapshots.clear();
    }

    /** Shows the close affordance, then hides it after a short idle period. */
    public void bindCloseButton(Button button) {
        releaseCloseButton();
        closeButton = button;
        if (closeButton == null) return;

        closeButtonIdleTimer = new PauseTransition(CLOSE_BUTTON_IDLE_DELAY);
        closeButtonIdleTimer.setOnFinished(event -> hideCloseButton());
        registerUserActivity();
    }

    /** Restarts the inactivity window after any fullscreen interaction. */
    public void registerUserActivity() {
        if (closeButton == null || closeButtonIdleTimer == null) return;
        closeButton.setVisible(true);
        closeButton.setManaged(true);
        closeButton.setOpacity(1.0);
        closeButtonIdleTimer.stop();
        closeButtonIdleTimer.playFromStart();
    }

    public void releaseCloseButton() {
        if (closeButtonIdleTimer != null) {
            closeButtonIdleTimer.stop();
            closeButtonIdleTimer.setOnFinished(null);
            closeButtonIdleTimer = null;
        }
        closeButton = null;
    }

    private void hideCloseButton() {
        if (closeButton == null) return;
        closeButton.setVisible(false);
        closeButton.setManaged(false);
    }

    private record VisibilitySnapshot(boolean visible, boolean managed) {}
}
