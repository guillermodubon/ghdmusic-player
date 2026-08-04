package io.github.guillermodubon.musicplayer.controllers.ui.components.downloadComponents.downloadCell;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.concurrent.Worker;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import io.github.guillermodubon.musicplayer.services.downloads.DownloadTask;

import java.io.File;

/**
 * Renders the progress and terminal state of one download task.
 */
final class DownloadCellProgressPresenter {

    private final ProgressBar progressBar;
    private final Label percentLabel;
    private final Label statusLabel;
    private final StackPane statusIconPane;
    private final Button cancelButton;
    private final Button removeButton;
    private final Button openLocationButton;
    private final Button actionButton;

    private Timeline progressTween;
    private double displayedProgress;

    DownloadCellProgressPresenter(ProgressBar progressBar,
                                  Label percentLabel,
                                  Label statusLabel,
                                  StackPane statusIconPane,
                                  Button cancelButton,
                                  Button removeButton,
                                  Button openLocationButton,
                                  Button actionButton) {
        this.progressBar = progressBar;
        this.percentLabel = percentLabel;
        this.statusLabel = statusLabel;
        this.statusIconPane = statusIconPane;
        this.cancelButton = cancelButton;
        this.removeButton = removeButton;
        this.openLocationButton = openLocationButton;
        this.actionButton = actionButton;
    }

    void reset(double progress) {
        stopAnimation();
        displayedProgress = normalizeProgress(progress);
        if (progressBar != null) {
            progressBar.setProgress(displayedProgress);
        }
        if (percentLabel != null) {
            percentLabel.setText(formatPercent(displayedProgress));
        }
    }

    void configureTaskState(DownloadTask task) {
        if (task == null) {
            return;
        }

        hideControls();

        Worker.State state = task.getState();
        DownloadTask.TerminalPresentation presentation =
                task.getTerminalPresentation();

        if (presentation == null
                && (state == Worker.State.READY
                || state == Worker.State.SCHEDULED
                || state == Worker.State.RUNNING)) {
            DownloadCellUi.setManagedVisible(percentLabel, true);
            DownloadCellUi.setManagedVisible(cancelButton, true);
            return;
        }

        if (presentation == null) {
            presentation = state == Worker.State.CANCELLED
                    ? DownloadTask.TerminalPresentation.CANCELLED
                    : DownloadTask.TerminalPresentation.YT_DLP_ERROR;
        }

        DownloadCellUi.setManagedVisible(percentLabel, false);
        DownloadCellUi.setManagedVisible(removeButton, true);
        DownloadCellUi.setManagedVisible(statusLabel, true);
        renderTerminalPresentation(task, presentation);
    }

    void animateProgress(double newProgress) {
        double safeProgress = normalizeProgress(newProgress);
        if (safeProgress < displayedProgress) {
            return;
        }

        displayedProgress = safeProgress;
        if (percentLabel != null) {
            percentLabel.setText(formatPercent(safeProgress));
        }

        if (progressBar == null) {
            return;
        }

        double currentProgress = normalizeProgress(progressBar.getProgress());
        if (safeProgress <= currentProgress) {
            return;
        }

        stopAnimation();
        progressTween = new Timeline(
                new KeyFrame(
                        Duration.ZERO,
                        new KeyValue(
                                progressBar.progressProperty(),
                                currentProgress
                        )
                ),
                new KeyFrame(
                        Duration.millis(240),
                        new KeyValue(
                                progressBar.progressProperty(),
                                safeProgress,
                                Interpolator.EASE_BOTH
                        )
                )
        );
        progressTween.play();
    }

    void clear() {
        stopAnimation();
        displayedProgress = 0;

        if (percentLabel != null) {
            percentLabel.setText("0%");
        }
        if (statusLabel != null) {
            statusLabel.setText("");
        }
        if (progressBar != null) {
            progressBar.setProgress(0);
        }

        hideControls();
    }

    void stop() {
        stopAnimation();
    }

    private void renderTerminalPresentation(
            DownloadTask task,
            DownloadTask.TerminalPresentation presentation
    ) {
        DownloadTask.ResultStatus result = presentation.getStatus();

        if (result == DownloadTask.ResultStatus.COMPLETED
                || result == DownloadTask.ResultStatus.WARNING) {
            animateProgress(1);
            File completedFile = task.getCompletedFile();
            DownloadCellUi.setManagedVisible(
                    openLocationButton,
                    completedFile != null && completedFile.exists()
            );
        } else {
            DownloadCellUi.setManagedVisible(actionButton, true);
        }

        String statusStyleClass = switch (result) {
            case COMPLETED -> "download-cell-status-completed";
            case WARNING, CANCELLED -> "download-cell-status-warning";
            default -> "download-cell-status-error";
        };

        String iconStyleClass = switch (result) {
            case COMPLETED -> "download-cell-terminal-icon-completed";
            case WARNING, CANCELLED -> "download-cell-terminal-icon-warning";
            default -> "download-cell-terminal-icon-error";
        };

        DownloadCellUi.setStatus(
                statusLabel,
                presentation.getMessage(),
                statusStyleClass
        );
        DownloadCellUi.setStatusIcon(
                statusIconPane,
                presentation.getIconPath(),
                iconStyleClass
        );
    }

    private void hideControls() {
        DownloadCellUi.setManagedVisible(cancelButton, false);
        DownloadCellUi.setManagedVisible(actionButton, false);
        DownloadCellUi.setManagedVisible(openLocationButton, false);
        DownloadCellUi.setManagedVisible(removeButton, false);
        DownloadCellUi.setManagedVisible(statusLabel, false);
        DownloadCellUi.setManagedVisible(statusIconPane, false);
        DownloadCellUi.setManagedVisible(percentLabel, true);
        if (statusIconPane != null) {
            statusIconPane.getChildren().clear();
        }
    }

    private void stopAnimation() {
        if (progressTween != null) {
            progressTween.stop();
            progressTween = null;
        }
    }

    private double normalizeProgress(double progress) {
        if (Double.isNaN(progress) || progress < 0) {
            return 0;
        }
        return Math.min(1, progress);
    }

    private String formatPercent(double progress) {
        return Math.round(normalizeProgress(progress) * 100) + "%";
    }
}
