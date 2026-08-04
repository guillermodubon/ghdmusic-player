package io.github.guillermodubon.musicplayer.services.keyboard;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import io.github.guillermodubon.musicplayer.controllers.layout.AppShellController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.downloadComponents.DownloadSidebarMenuController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.services.PreviewService;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.PlayerMenuBarController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane.QueueController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.PlayerFullScreenModeController;
import io.github.guillermodubon.musicplayer.controllers.layout.window.ApplicationWindowPolicy;

public final class GlobalKeyboardShortcutManager {

    private final AppShellController shellController;
    private Scene installedScene;

    public GlobalKeyboardShortcutManager(AppShellController shellController) {
        this.shellController = shellController;
    }

    public void install(Region root) {
        if (root == null) return;

        root.sceneProperty().addListener((obs, oldScene, newScene) -> installOnScene(newScene));
        installOnScene(root.getScene());
    }

    private void installOnScene(Scene scene) {
        if (scene == null || scene == installedScene) return;

        if (installedScene != null) {
            installedScene.removeEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyPressed);
        }

        installedScene = scene;
        installedScene.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyPressed);
    }

    private void handleKeyPressed(KeyEvent event) {
        if (event == null || event.isConsumed()) return;

        KeyCode code = event.getCode();
        if (code == KeyCode.F11) {
            if (ApplicationWindowPolicy.toggleFullScreen(installedScene)) {
                event.consume();
            }
            return;
        }

        if (code == KeyCode.ESCAPE) {
            /*
             * The internal player presentation is independent from JavaFX
             * Stage fullscreen. ESC closes this layer first, preserving the
             * native fullscreen state until the next ESC press.
             */
            if (PlayerFullScreenModeController.getInstance().exitIfActive()) {
                event.consume();
                return;
            }

            if (closeTopMostTransientUi()) {
                event.consume();
            }
            return;
        }

        if (isTextInputFocused(event)) {
            return;
        }

        if (PreviewService.handleActivePreviewShortcut(event)) {
            event.consume();
            return;
        }

        PlayerMenuBarController bar = shellController == null ? null : shellController.getPlayerMenuBarController();
        if (bar != null && bar.handleGlobalKeyboardShortcut(event)) {
            event.consume();
        }
    }

    private boolean closeTopMostTransientUi() {
        if (PreviewService.closeActivePreview()) {
            return true;
        }

        if (QueueController.isQueueVisible()) {
            QueueController queueController = QueueController.getInstance();
            if (queueController != null) {
                queueController.closeFromOwner();
            } else if (shellController != null) {
                shellController.hideQueueSidebar();
            }
            return true;
        }

        if (DownloadSidebarMenuController.isDownloadVisible()) {
            DownloadSidebarMenuController.closeActiveSidebar();
            return true;
        }

        return false;
    }

    private boolean isTextInputFocused(KeyEvent event) {
        Node focused = installedScene == null ? null : installedScene.getFocusOwner();
        return focused instanceof TextInputControl;
    }
}
