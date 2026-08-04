package io.github.guillermodubon.musicplayer.controllers.ui.components.downloadComponents.downloadCell;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.beans.value.ChangeListener;
import io.github.guillermodubon.musicplayer.controllers.ui.components.effects.TextShimmerEffect;
import io.github.guillermodubon.musicplayer.services.downloads.DownloadTask;
import io.github.guillermodubon.musicplayer.services.downloads.bulk.BulkDownloadManager;
import io.github.guillermodubon.musicplayer.services.downloads.bulk.BulkDownloadSession;

import java.util.List;

/**
 * Owns the visual grouping and actions of a bulk-download section.
 */
final class DownloadCellBulkPresenter {

    private final VBox bulkSectionHeader;
    private final Label bulkTitleLabel;
    private final Label bulkQueueLabel;
    private final StackPane bulkStatusIconPane;
    private final Button bulkCancelButton;
    private final Region bulkSectionSeparator;

    private BulkDownloadSession boundBulkSession;
    private ChangeListener<Number> bulkQueuedListener;
    private ChangeListener<BulkDownloadSession.Status> bulkStatusListener;
    private DownloadTask currentTask;
    private List<DownloadTask> backingList;
    private Node sceneProbe;

    DownloadCellBulkPresenter(VBox bulkSectionHeader,
                              Label bulkTitleLabel,
                              Label bulkQueueLabel,
                              StackPane bulkStatusIconPane,
                              Button bulkCancelButton,
                              Region bulkSectionSeparator) {
        this.bulkSectionHeader = bulkSectionHeader;
        this.bulkTitleLabel = bulkTitleLabel;
        this.bulkQueueLabel = bulkQueueLabel;
        this.bulkStatusIconPane = bulkStatusIconPane;
        this.bulkCancelButton = bulkCancelButton;
        this.bulkSectionSeparator = bulkSectionSeparator;
        configureTitleLabel();
    }

    void configure(DownloadTask task,
                   List<DownloadTask> backingList,
                   Node sceneProbe) {
        detachSessionListeners();
        this.currentTask = task;
        this.backingList = backingList;
        this.sceneProbe = sceneProbe;

        DownloadCellUi.setManagedVisible(bulkSectionHeader, false);
        DownloadCellUi.setManagedVisible(bulkSectionSeparator, false);

        if (task == null
                || task.getContext() == null
                || !task.getContext().isBulkDownload()) {
            return;
        }

        DownloadCellUi.setManagedVisible(
                bulkSectionSeparator,
                isLastVisibleTaskInBulkSession(task)
        );

        if (!isFirstVisibleTaskInBulkSession(task)) {
            return;
        }

        BulkDownloadSession session = BulkDownloadManager
                .getInstance()
                .getSession(task.getContext().getBulkSessionId());

        String title = session != null
                ? session.getTitle()
                : task.getContext().getBulkSessionTitle();
        if (title == null || title.isBlank()) {
            title = "Downloads";
        }

        final String fallbackTitle = title;
        updateTitle(title);
        updateBulkHeader(
                session,
                task.getContext().getBulkTotalSongs(),
                title
        );

        if (session != null) {
            boundBulkSession = session;
            bulkQueuedListener = (obs, oldValue, newValue) ->
                    refreshSessionHeader(
                            session,
                            task.getContext().getBulkTotalSongs(),
                            fallbackTitle
                    );
            bulkStatusListener = (obs, oldStatus, newStatus) ->
                    refreshSessionHeader(
                            session,
                            task.getContext().getBulkTotalSongs(),
                            fallbackTitle
                    );

            session.queuedCountProperty().addListener(bulkQueuedListener);
            session.statusProperty().addListener(bulkStatusListener);
        }

        DownloadCellUi.setManagedVisible(bulkSectionHeader, true);
    }

    void clear() {
        detachSessionListeners();
        currentTask = null;
        backingList = null;
        sceneProbe = null;

        TextShimmerEffect.stop(bulkQueueLabel, Color.web("#AFAFAF"));
        DownloadCellUi.setManagedVisible(bulkSectionHeader, false);
        DownloadCellUi.setManagedVisible(bulkSectionSeparator, false);
        DownloadCellUi.setManagedVisible(bulkStatusIconPane, false);
        if (bulkStatusIconPane != null) {
            bulkStatusIconPane.getChildren().clear();
        }
        if (bulkTitleLabel != null) {
            bulkTitleLabel.setText("");
            bulkTitleLabel.setAccessibleText("Download batch title");
        }
    }

    private void refreshSessionHeader(BulkDownloadSession session,
                                      int fallbackTotal,
                                      String fallbackTitle) {
        String title = session.getTitle();
        if (title == null || title.isBlank()) {
            title = fallbackTitle;
        }
        updateTitle(title);
        updateBulkHeader(session, fallbackTotal, title);
    }

    private void configureTitleLabel() {
        if (bulkTitleLabel == null) {
            return;
        }

        bulkTitleLabel.setMouseTransparent(true);
        bulkTitleLabel.setFocusTraversable(false);
        bulkTitleLabel.setAccessibleText("Download batch title");
        bulkTitleLabel.getStyleClass().removeAll(
                "download-bulk-section-link",
                "download-batch-title-link"
        );
        if (!bulkTitleLabel.getStyleClass().contains(
                "download-bulk-section-title")) {
            bulkTitleLabel.getStyleClass().add(
                    "download-bulk-section-title"
            );
        }
    }

    private void updateTitle(String title) {
        if (bulkTitleLabel == null) {
            return;
        }
        bulkTitleLabel.setText(title);
        bulkTitleLabel.setAccessibleText(title);
    }

    private boolean isFirstVisibleTaskInBulkSession(DownloadTask task) {
        if (task == null || task.getContext() == null || backingList == null) {
            return false;
        }

        String sessionId = task.getContext().getBulkSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }

        for (DownloadTask candidate : backingList) {
            if (candidate == null || candidate.getContext() == null) {
                continue;
            }
            if (sessionId.equals(candidate.getContext().getBulkSessionId())) {
                return candidate == task;
            }
        }
        return false;
    }

    private boolean isLastVisibleTaskInBulkSession(DownloadTask task) {
        if (task == null || task.getContext() == null || backingList == null) {
            return false;
        }

        String sessionId = task.getContext().getBulkSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }

        DownloadTask last = null;
        for (DownloadTask candidate : backingList) {
            if (candidate == null || candidate.getContext() == null) {
                continue;
            }
            if (sessionId.equals(candidate.getContext().getBulkSessionId())) {
                last = candidate;
            }
        }
        return last == task;
    }

    private void updateBulkHeader(BulkDownloadSession session,
                                  int fallbackTotal,
                                  String fallbackTitle) {
        if (bulkQueueLabel == null) {
            return;
        }

        DownloadCellUi.clearBulkSemanticClasses(
                bulkQueueLabel,
                bulkStatusIconPane
        );
        DownloadCellUi.setManagedVisible(bulkStatusIconPane, false);

        String title = fallbackTitle == null || fallbackTitle.isBlank()
                ? "this collection"
                : fallbackTitle;
        BulkDownloadSession.Status status = session == null
                ? BulkDownloadSession.Status.RUNNING
                : session.getStatus();

        if (status == BulkDownloadSession.Status.RUNNING) {
            int queued = session == null
                    ? Math.max(0, fallbackTotal - 1)
                    : session.getQueuedCount();
            boolean integrating = session != null
                    && session.isDownloadPhaseComplete();

            bulkQueueLabel.setText(integrating
                    ? "Finishing library integration."
                    : queued <= 0
                    ? "Downloads in progress."
                    : queued + (queued == 1
                    ? " song to be downloaded on queue."
                    : " songs to be downloaded on queue."));
            bulkCancelButton.setText("Cancel");
            bulkCancelButton.setOnAction(event -> cancelCurrentSession());
            TextShimmerEffect.apply(
                    bulkQueueLabel,
                    Color.web("#AFAFAF"),
                    Color.web("#FFFFFF")
            );
            return;
        }

        TextShimmerEffect.stop(bulkQueueLabel);
        switch (status) {
            case COMPLETED -> {
                bulkQueueLabel.setText(
                        "The download of " + title
                                + " has finished successfully."
                );
                bulkQueueLabel.getStyleClass().add(
                        "download-bulk-queue-label-success"
                );
                DownloadCellUi.setBulkStatusIcon(
                        bulkStatusIconPane,
                        DownloadCellUi.ICON_SUCCESS,
                        "download-bulk-status-icon-success"
                );
                bulkCancelButton.setText("Clear");
                bulkCancelButton.setOnAction(event -> clearCurrentSession());
            }
            case CANCELLED -> {
                bulkQueueLabel.setText(buildCancelledBulkMessage(session, title));
                bulkQueueLabel.getStyleClass().add(
                        "download-bulk-queue-label-warning"
                );
                DownloadCellUi.setBulkStatusIcon(
                        bulkStatusIconPane,
                        DownloadCellUi.ICON_ERROR,
                        "download-bulk-status-icon-warning"
                );
                bulkCancelButton.setText("Retry Download");
                bulkCancelButton.setOnAction(event -> retryCurrentSession());
            }
            case ERROR -> {
                bulkQueueLabel.setText(buildErrorBulkMessage(session, title));
                bulkQueueLabel.getStyleClass().add(
                        "download-bulk-queue-label-error"
                );
                DownloadCellUi.setBulkStatusIcon(
                        bulkStatusIconPane,
                        DownloadCellUi.ICON_ERROR,
                        "download-bulk-status-icon-error"
                );
                bulkCancelButton.setText("Retry Download");
                bulkCancelButton.setOnAction(event -> retryCurrentSession());
            }
            default -> {
            }
        }
    }

    private void cancelCurrentSession() {
        if (currentTask == null
                || currentTask.getContext() == null) {
            return;
        }
        BulkDownloadManager.getInstance().cancelSession(
                currentTask.getContext().getBulkSessionId()
        );
    }

    private void clearCurrentSession() {
        if (currentTask == null
                || currentTask.getContext() == null) {
            return;
        }
        BulkDownloadManager.getInstance().clearSession(
                currentTask.getContext().getBulkSessionId()
        );
    }

    private void retryCurrentSession() {
        if (currentTask == null
                || currentTask.getContext() == null
                || sceneProbe == null) {
            return;
        }
        BulkDownloadManager.getInstance().retrySession(
                currentTask.getContext().getBulkSessionId(),
                sceneProbe.getScene() == null
                        ? null
                        : sceneProbe.getScene().getRoot()
        );
    }

    private String buildErrorBulkMessage(BulkDownloadSession session,
                                         String title) {
        int total = session == null ? 0 : Math.max(0, session.getTotalSongs());
        int completed = session == null
                ? 0
                : Math.max(0, session.getCompletedCount());
        int failed = session == null
                ? 0
                : Math.max(0, session.getFailedCount());

        if (total <= 0) {
            return "The download of " + title
                    + " ended with errors. Please try again.";
        }
        if (completed <= 0) {
            return "The download of " + title
                    + " failed. No songs were downloaded."
                    + failedSongsSummary(session)
                    + " Please try again.";
        }

        String failedSummary = failedSongsSummary(session);
        return "The download of " + title
                + " finished partially. "
                + completed + " of " + total
                + " songs were downloaded successfully"
                + (failed <= 0 ? "." : ", and " + failed + " failed.")
                + failedSummary
                + " Please try again.";
    }

    private String buildCancelledBulkMessage(BulkDownloadSession session,
                                              String title) {
        int total = session == null ? 0 : Math.max(0, session.getTotalSongs());
        int completed = session == null
                ? 0
                : Math.max(0, session.getCompletedCount());

        if (total <= 0) {
            return "The download of " + title + " has been cancelled.";
        }
        if (completed <= 0) {
            return "The download of " + title
                    + " has been cancelled. No songs were downloaded.";
        }
        return "The download of " + title
                + " has been cancelled. "
                + completed + " of " + total
                + " songs were downloaded successfully.";
    }

    private String failedSongsSummary(BulkDownloadSession session) {
        if (session == null) {
            return "";
        }

        List<String> failedTitles = session.getFailedSongTitles(3);
        if (failedTitles.isEmpty()) {
            return "";
        }

        String summary = String.join(", ", failedTitles);
        int remaining = Math.max(
                0,
                session.getFailedSongTitleCount() - failedTitles.size()
        );
        if (remaining > 0) {
            summary += ", and " + remaining + " more";
        }
        return " Failed: " + summary + ".";
    }

    private void detachSessionListeners() {
        if (boundBulkSession != null) {
            if (bulkQueuedListener != null) {
                boundBulkSession.queuedCountProperty()
                        .removeListener(bulkQueuedListener);
            }
            if (bulkStatusListener != null) {
                boundBulkSession.statusProperty()
                        .removeListener(bulkStatusListener);
            }
        }
        boundBulkSession = null;
        bulkQueuedListener = null;
        bulkStatusListener = null;
    }
}
