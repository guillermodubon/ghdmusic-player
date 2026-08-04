package io.github.guillermodubon.musicplayer.controllers.ui.components.downloadComponents.downloadCell;

import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import io.github.guillermodubon.musicplayer.services.downloads.DownloadTask;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Handles clicks on the non-action area of a cell.
 */
final class DownloadCellSourceNavigation {

    private final HBox rootHBox;
    private DownloadTask currentTask;
    private Consumer<DownloadTask> handler;

    DownloadCellSourceNavigation(HBox rootHBox) {
        this.rootHBox = rootHBox;
    }

    void install() {
        if (rootHBox != null) {
            rootHBox.setOnMouseClicked(this::handleClick);
        }
    }

    void configure(DownloadTask task, Consumer<DownloadTask> handler) {
        this.currentTask = task;
        this.handler = handler;

        if (rootHBox == null) {
            return;
        }

        boolean navigable = handler != null && hasNavigableSource(task);
        rootHBox.setCursor(navigable ? Cursor.HAND : Cursor.DEFAULT);
        rootHBox.setAccessibleText(
                navigable ? "Open download source" : null
        );
    }

    void clear() {
        currentTask = null;
        handler = null;
        if (rootHBox != null) {
            rootHBox.setCursor(Cursor.DEFAULT);
            rootHBox.setAccessibleText(null);
        }
    }

    private void handleClick(MouseEvent event) {
        if (event == null
                || event.isConsumed()
                || event.getButton() != MouseButton.PRIMARY
                || event.getClickCount() != 1
                || isActionButtonTarget(event.getTarget())) {
            return;
        }

        DownloadTask task = currentTask;
        if (task == null || handler == null || !hasNavigableSource(task)) {
            return;
        }

        event.consume();
        handler.accept(task);
    }

    private boolean isActionButtonTarget(Object eventTarget) {
        if (!(eventTarget instanceof Node node)) {
            return false;
        }

        Node current = node;
        while (current != null && current != rootHBox) {
            if (current instanceof Button) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private boolean hasNavigableSource(DownloadTask task) {
        if (task == null || task.getContext() == null) {
            return false;
        }

        Long sourceId = task.getContext().getSourceCollectionId();
        if (sourceId == null || sourceId <= 0) {
            return false;
        }

        String sourceType = task.getContext().getSourceCollectionType();
        if (sourceType == null || sourceType.isBlank()) {
            return false;
        }

        return switch (sourceType.trim().toUpperCase(Locale.ROOT)) {
            case "ALBUM", "PLAYLIST", "SINGLE" -> true;
            default -> false;
        };
    }
}
