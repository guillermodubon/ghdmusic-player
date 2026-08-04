package io.github.guillermodubon.musicplayer.controllers.ui.components.downloadComponents.downloadCell;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.concurrent.Worker;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import io.github.guillermodubon.musicplayer.controllers.ui.components.effects.TextShimmerEffect;
import io.github.guillermodubon.musicplayer.controllers.ui.components.popups.SmallPopupTooltip;
import io.github.guillermodubon.musicplayer.controllers.ui.components.downloadComponents.DownloadLocationOpener;
import io.github.guillermodubon.musicplayer.services.downloads.DownloadManager;
import io.github.guillermodubon.musicplayer.services.downloads.DownloadTask;
import io.github.guillermodubon.musicplayer.services.downloads.helpers.YTDLPApiHelpers.YTDLPHelper;
import io.github.guillermodubon.musicplayer.services.downloads.helpers.cache.DownloadUiCache;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;

/**
 * Coordinates one download cell. Detailed rendering and interaction rules
 * live in package-private presenters so this controller remains focused on
 * the FXML lifecycle and task subscriptions.
 */
public class DownloadCellController {

    @FXML private HBox rootHBox;
    @FXML private ImageView thumbImageView;
    @FXML private VBox mainContent;
    @FXML private Label titleLabel;
    @FXML private HBox progressRow;
    @FXML private ProgressBar progressBar;
    @FXML private Label percentLabel;
    @FXML private Label statusLabel;
    @FXML private StackPane statusIconPane;
    @FXML private Label queuedNoticeLabel;
    @FXML private VBox bulkSectionHeader;
    @FXML private Label bulkTitleLabel;
    @FXML private Label bulkQueueLabel;
    @FXML private StackPane bulkStatusIconPane;
    @FXML private Button bulkCancelButton;
    @FXML private Region bulkSectionSeparator;
    @FXML private Button cancelButton;
    @FXML private Button removeButton;
    @FXML private Button openLocationButton;
    @FXML private Button actionButton;
    @FXML private VBox buttonBox;

    private final DownloadTaskCoverResolver coverResolver =
            new DownloadTaskCoverResolver();

    private DownloadCellProgressPresenter progressPresenter;
    private DownloadCellBulkPresenter bulkPresenter;
    private DownloadCellResponsiveLayout responsiveLayout;
    private DownloadCellSourceNavigation sourceNavigation;

    private DownloadTask currentTask;
    private List<DownloadTask> backingList;
    private Consumer<DownloadTask> sourceNavigationHandler;

    private ChangeListener<Worker.State> stateListener;
    private ChangeListener<Number> progressListener;
    private ChangeListener<DownloadTask.ResultStatus> resultStatusListener;
    private ChangeListener<DownloadTask.TerminalPresentation> terminalPresentationListener;
    private ChangeListener<File> completedFileListener;
    private ChangeListener<Boolean> deferredListener;

    @FXML
    public void initialize() {
        DownloadCellUi.installIconButton(
                cancelButton,
                DownloadCellUi.ICON_CLEAR,
                "Cancel download"
        );
        DownloadCellUi.installIconButton(
                actionButton,
                DownloadCellUi.ICON_RETRY,
                "Retry this download"
        );
        DownloadCellUi.installLocationButton(openLocationButton);
        DownloadCellUi.installIconButton(
                removeButton,
                DownloadCellUi.ICON_CLEAR,
                "Clear this download"
        );

        SmallPopupTooltip.install(actionButton, "Retry this download");
        SmallPopupTooltip.install(openLocationButton, "Open download location");
        SmallPopupTooltip.install(removeButton, "Clear this download");

        progressPresenter = new DownloadCellProgressPresenter(
                progressBar,
                percentLabel,
                statusLabel,
                statusIconPane,
                cancelButton,
                removeButton,
                openLocationButton,
                actionButton
        );
        bulkPresenter = new DownloadCellBulkPresenter(
                bulkSectionHeader,
                bulkTitleLabel,
                bulkQueueLabel,
                bulkStatusIconPane,
                bulkCancelButton,
                bulkSectionSeparator
        );
        responsiveLayout = new DownloadCellResponsiveLayout(
                rootHBox,
                thumbImageView,
                mainContent,
                progressRow,
                statusIconPane,
                percentLabel,
                buttonBox,
                cancelButton,
                actionButton,
                openLocationButton,
                removeButton
        );
        sourceNavigation = new DownloadCellSourceNavigation(rootHBox);

        responsiveLayout.install();
        sourceNavigation.install();
    }

    public void bindTask(DownloadTask task,
                         List<DownloadTask> backingList,
                         Consumer<DownloadTask> sourceNavigationHandler) {
        if (task != null
                && task == currentTask
                && this.backingList == backingList) {
            this.sourceNavigationHandler = sourceNavigationHandler;
            sourceNavigation.configure(task, sourceNavigationHandler);
            refreshStructuralPresentation(task);
            return;
        }

        unbindPrevious();
        this.backingList = backingList;
        this.sourceNavigationHandler = sourceNavigationHandler;

        if (task == null) {
            clearUi();
            sourceNavigation.configure(null, sourceNavigationHandler);
            return;
        }

        currentTask = task;
        populateTitle(task);
        populateCover(task);
        configureQueuedNotice(task);
        bulkPresenter.configure(task, backingList, rootHBox);
        progressPresenter.reset(task.getProgress());
        progressPresenter.configureTaskState(task);
        sourceNavigation.configure(task, sourceNavigationHandler);
        installTaskListeners(task);
        installTaskActions(task);
    }

    public void updateForReuse() {
        unbindPrevious();
        clearUi();
    }

    private void installTaskListeners(DownloadTask task) {
        stateListener = (observable, oldState, newState) ->
                runForCurrentTask(task, () ->
                        progressPresenter.configureTaskState(task));

        progressListener = (observable, oldValue, newValue) ->
                runForCurrentTask(task, () ->
                        progressPresenter.animateProgress(newValue.doubleValue()));

        resultStatusListener = (observable, oldStatus, newStatus) ->
                runForCurrentTask(task, () ->
                        progressPresenter.configureTaskState(task));

        terminalPresentationListener =
                (observable, oldPresentation, newPresentation) ->
                        runForCurrentTask(task, () ->
                                progressPresenter.configureTaskState(task));

        completedFileListener = (observable, oldFile, newFile) ->
                runForCurrentTask(task, () ->
                        progressPresenter.configureTaskState(task));

        deferredListener = (observable, wasDeferred, isDeferred) ->
                runForCurrentTask(task, () -> configureQueuedNotice(task));

        task.stateProperty().addListener(stateListener);
        task.progressProperty().addListener(progressListener);
        task.resultStatusProperty().addListener(resultStatusListener);
        task.terminalPresentationProperty().addListener(
                terminalPresentationListener
        );
        task.completedFileProperty().addListener(completedFileListener);
        task.deferredByExclusiveSessionProperty().addListener(deferredListener);
    }

    private void installTaskActions(DownloadTask task) {
        cancelButton.setOnAction(event -> {
            if (!task.isCancelled() && !task.isDone()) {
                task.cancel();
            }
        });

        actionButton.setOnAction(event ->
                DownloadManager.getInstance().retryTask(task));

        openLocationButton.setOnAction(event ->
                DownloadLocationOpener.open(
                                task.getCompletedFile(),
                                task.getTargetDir()
                        ));

        removeButton.setOnAction(event -> {
            if (backingList != null) {
                backingList.remove(task);
            }
        });
    }

    private void refreshStructuralPresentation(DownloadTask task) {
        configureQueuedNotice(task);
        bulkPresenter.configure(task, backingList, rootHBox);
    }

    private void populateTitle(DownloadTask task) {
        String query = task.getQuery();
        String fetchedTitle = task.getFetchedTitle();
        if (fetchedTitle != null && !fetchedTitle.isBlank()) {
            titleLabel.setText(fetchedTitle);
            return;
        }
        if (DownloadUiCache.hasFetchedTitle(query)) {
            titleLabel.setText(DownloadUiCache.getFetchedTitle(query));
            return;
        }
        if (task.isSourceIsSongItem()) {
            String title = task.getCleanSongName();
            titleLabel.setText(
                    title == null || title.isBlank() ? query : title
            );
            return;
        }

        titleLabel.setText("...");
        YTDLPHelper.fetchVideoTitle(query, realTitle ->
                runForCurrentTask(task, () -> {
                    if (realTitle != null && !realTitle.isBlank()) {
                        titleLabel.setText(realTitle);
                    }
                }));
    }

    private void populateCover(DownloadTask task) {
        thumbImageView.setImage(MediaImageResolver.defaultCover(
                DownloadTaskCoverResolver.COVER_DECODE_SIZE,
                DownloadTaskCoverResolver.COVER_DECODE_SIZE
        ));

        Image taskCover = task.getCoverImage();
        if (isUsable(taskCover)) {
            thumbImageView.setImage(taskCover);
            if (hasHighResolution(taskCover)) return;
        }

        Image cachedThumbnail = DownloadUiCache.hasThumbnail(task.getQuery())
                ? DownloadUiCache.getThumbnail(task.getQuery())
                : null;
        if (!task.isSourceIsSongItem() && cachedThumbnail != null) {
            thumbImageView.setImage(cachedThumbnail);
            if (hasHighResolution(cachedThumbnail)) return;
        }

        coverResolver.resolveAsync(task, cover ->
                runForCurrentTask(task, () -> {
                    if (isUsable(cover)) {
                        task.setCoverImage(cover);
                        thumbImageView.setImage(cover);
                    }
                }));

        if (!task.isSourceIsSongItem() && cachedThumbnail == null) {
            YTDLPHelper.loadThumbnail(task.getQuery(), thumbImageView, null, () -> {
            });
        }
    }

    private void configureQueuedNotice(DownloadTask task) {
        boolean visible = task != null
                && task.isDeferredByExclusiveSession()
                && isFirstVisibleDeferredTask(task);
        DownloadCellUi.setManagedVisible(queuedNoticeLabel, visible);
        if (visible) {
            TextShimmerEffect.apply(
                    queuedNoticeLabel,
                    Color.web("#AFAFAF"),
                    Color.web("#FFFFFF")
            );
        } else {
            TextShimmerEffect.stop(
                    queuedNoticeLabel,
                    Color.web("#AFAFAF")
            );
        }
    }

    private boolean isFirstVisibleDeferredTask(DownloadTask task) {
        if (task == null
                || backingList == null
                || !task.isDeferredByExclusiveSession()) {
            return false;
        }

        for (DownloadTask candidate : backingList) {
            if (candidate == task) {
                return true;
            }
            if (candidate != null && candidate.isDeferredByExclusiveSession()) {
                return false;
            }
        }
        return false;
    }

    private void clearUi() {
        titleLabel.setText("");
        thumbImageView.setImage(null);
        TextShimmerEffect.stop(queuedNoticeLabel, Color.web("#AFAFAF"));
        DownloadCellUi.setManagedVisible(queuedNoticeLabel, false);
        bulkPresenter.clear();
        progressPresenter.clear();
        sourceNavigation.clear();
    }

    private void unbindPrevious() {
        if (currentTask != null) {
            removeTaskListeners(currentTask);
        }

        bulkPresenter.clear();
        progressPresenter.stop();
        currentTask = null;
        sourceNavigation.clear();
        stateListener = null;
        progressListener = null;
        resultStatusListener = null;
        terminalPresentationListener = null;
        completedFileListener = null;
        deferredListener = null;
    }

    private void removeTaskListeners(DownloadTask task) {
        if (stateListener != null) {
            task.stateProperty().removeListener(stateListener);
        }
        if (progressListener != null) {
            task.progressProperty().removeListener(progressListener);
        }
        if (resultStatusListener != null) {
            task.resultStatusProperty().removeListener(resultStatusListener);
        }
        if (terminalPresentationListener != null) {
            task.terminalPresentationProperty().removeListener(
                    terminalPresentationListener
            );
        }
        if (completedFileListener != null) {
            task.completedFileProperty().removeListener(completedFileListener);
        }
        if (deferredListener != null) {
            task.deferredByExclusiveSessionProperty().removeListener(
                    deferredListener
            );
        }
    }

    private void runForCurrentTask(DownloadTask task, Runnable action) {
        Runnable guarded = () -> {
            if (currentTask == task) {
                action.run();
            }
        };

        if (Platform.isFxApplicationThread()) {
            guarded.run();
        } else {
            Platform.runLater(guarded);
        }
    }

    private boolean isUsable(Image image) {
        return image != null && !image.isError();
    }

    private boolean hasHighResolution(Image image) {
        return isUsable(image)
                && image.getWidth() >= DownloadTaskCoverResolver.COVER_DECODE_SIZE
                && image.getHeight() >= DownloadTaskCoverResolver.COVER_DECODE_SIZE;
    }
}
