package io.github.guillermodubon.musicplayer.services.downloads.activity;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.concurrent.Worker;
import io.github.guillermodubon.musicplayer.services.downloads.DownloadManager;
import io.github.guillermodubon.musicplayer.services.downloads.DownloadTask;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Aggregates the live state of DownloadManager tasks for lightweight UI
 * consumers. A task is active only while it has been scheduled or is running;
 * queued batch entries do not inflate the header badge.
 */
public final class DownloadActivityTracker {

    private static final DownloadActivityTracker INSTANCE = new DownloadActivityTracker();

    private final ReadOnlyIntegerWrapper activeCount = new ReadOnlyIntegerWrapper(0);
    private final ReadOnlyDoubleWrapper aggregateProgress = new ReadOnlyDoubleWrapper(0.0);
    private final ReadOnlyBooleanWrapper active = new ReadOnlyBooleanWrapper(false);
    private final Map<DownloadTask, TaskListeners> listeners =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private DownloadActivityTracker() {
        DownloadManager.getInstance().getTasks().forEach(this::track);
        DownloadManager.getInstance().getTasks().addListener((ListChangeListener<DownloadTask>) change -> {
            while (change.next()) {
                if (change.wasRemoved()) change.getRemoved().forEach(this::untrack);
                if (change.wasAdded()) change.getAddedSubList().forEach(this::track);
            }
            refresh();
        });
        refresh();
    }

    public static DownloadActivityTracker getInstance() {
        return INSTANCE;
    }

    public ReadOnlyIntegerProperty activeCountProperty() {
        return activeCount.getReadOnlyProperty();
    }

    public int getActiveCount() {
        return activeCount.get();
    }

    public ReadOnlyDoubleProperty aggregateProgressProperty() {
        return aggregateProgress.getReadOnlyProperty();
    }

    public double getAggregateProgress() {
        return aggregateProgress.get();
    }

    public ReadOnlyBooleanProperty activeProperty() {
        return active.getReadOnlyProperty();
    }

    public boolean isActive() {
        return active.get();
    }

    private void track(DownloadTask task) {
        if (task == null || listeners.containsKey(task)) return;

        ChangeListener<Worker.State> stateListener = (obs, oldState, newState) -> refresh();
        ChangeListener<Number> progressListener = (obs, oldValue, newValue) -> refresh();
        task.stateProperty().addListener(stateListener);
        task.progressProperty().addListener(progressListener);
        listeners.put(task, new TaskListeners(stateListener, progressListener));
        refresh();
    }

    private void untrack(DownloadTask task) {
        if (task == null) return;
        TaskListeners tracked = listeners.remove(task);
        if (tracked == null) return;
        task.stateProperty().removeListener(tracked.stateListener());
        task.progressProperty().removeListener(tracked.progressListener());
    }

    private void refresh() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::refresh);
            return;
        }

        int runningTasks = 0;
        double progressSum = 0.0;

        for (DownloadTask task : DownloadManager.getInstance().getTasks()) {
            if (!isActive(task)) continue;
            runningTasks++;

            double progress = task.getProgress();
            progressSum += progress < 0.0 ? 0.0 : Math.max(0.0, Math.min(1.0, progress));
        }

        activeCount.set(runningTasks);
        aggregateProgress.set(runningTasks == 0 ? 0.0 : progressSum / runningTasks);
        active.set(runningTasks > 0);
    }

    private boolean isActive(DownloadTask task) {
        if (task == null) return false;
        Worker.State state = task.getState();
        return state == Worker.State.SCHEDULED || state == Worker.State.RUNNING;
    }

    private record TaskListeners(ChangeListener<Worker.State> stateListener,
                                 ChangeListener<Number> progressListener) {
    }
}
