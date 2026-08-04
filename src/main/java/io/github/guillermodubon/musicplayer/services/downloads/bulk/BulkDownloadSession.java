package io.github.guillermodubon.musicplayer.services.downloads.bulk;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.concurrent.Worker;
import io.github.guillermodubon.musicplayer.services.downloads.DownloadTask;
import io.github.guillermodubon.musicplayer.models.Song;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;


public final class BulkDownloadSession {

    public enum Status {
        RUNNING,
        COMPLETED,
        ERROR,
        CANCELLED
    }

    public enum SourceType {
        ALBUM,
        PLAYLIST,
        SINGLE,
        UNKNOWN
    }

    public record ScheduledSong(int index, Song song) {
    }

    private final String id;
    private final String title;
    private final List<Song> songs;
    private final File targetDirectory;
    private final int parallelLimit;
    private final long sourceId;
    private final SourceType sourceType;

    private final AtomicInteger nextIndex = new AtomicInteger();
    private final AtomicInteger activeCount = new AtomicInteger();
    private final AtomicInteger integratedCount = new AtomicInteger();
    private final AtomicInteger completedCount = new AtomicInteger();
    private final AtomicInteger errorCount = new AtomicInteger();
    private final AtomicInteger cancelledCount = new AtomicInteger();

    private final AtomicBoolean cancellationRequested = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean exclusiveDownloadPhaseReleased = new AtomicBoolean(false);

    private final Set<Integer> completedIndexes = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Set<Integer> failedIndexes = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Set<Integer> terminalIndexes = Collections.synchronizedSet(new LinkedHashSet<>());

    private final IntegerProperty queuedCount = new SimpleIntegerProperty();
    private final IntegerProperty integratedCountProperty = new SimpleIntegerProperty();
    private final IntegerProperty completedCountProperty = new SimpleIntegerProperty();
    private final IntegerProperty errorCountProperty = new SimpleIntegerProperty();
    private final IntegerProperty cancelledCountProperty = new SimpleIntegerProperty();

    private final BooleanProperty active = new SimpleBooleanProperty(true);
    private final ObjectProperty<Status> status = new SimpleObjectProperty<>(Status.RUNNING);

    BulkDownloadSession(String id,
                        String title,
                        List<Song> songs,
                        File targetDirectory,
                        int parallelLimit,
                        long sourceId,
                        SourceType sourceType) {
        this.id = id;
        this.title = title == null || title.isBlank() ? "Downloads" : title.trim();
        this.songs = songs == null ? List.of() : List.copyOf(songs);
        this.targetDirectory = targetDirectory;
        this.parallelLimit = Math.max(1, parallelLimit);
        this.sourceId = sourceId;
        this.sourceType = sourceType == null ? SourceType.UNKNOWN : sourceType;
        updateQueuedCount();
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public long getSourceId() {
        return sourceId;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public int getTotalSongs() {
        return songs.size();
    }

    public File getTargetDirectory() {
        return targetDirectory;
    }


    public int getQueuedCount() {
        return queuedCount.get();
    }

    public ReadOnlyIntegerProperty queuedCountProperty() {
        return queuedCount;
    }


    public int getCompletedCount() {
        return completedCount.get();
    }


    public int getErrorCount() {
        return errorCount.get();
    }

    public int getCancelledCount() {
        return cancelledCount.get();
    }

    public int getFailedCount() {
        return Math.max(0, getErrorCount() + getCancelledCount());
    }

    public List<String> getFailedSongTitles(int limit) {
        int safeLimit = Math.max(1, limit);
        List<String> titles = new ArrayList<>();

        synchronized (failedIndexes) {
            for (Integer index : failedIndexes) {
                if (index == null || index < 0 || index >= songs.size()) continue;
                Song song = songs.get(index);
                titles.add(songTitle(song, index));
                if (titles.size() >= safeLimit) break;
            }
        }

        return List.copyOf(titles);
    }

    public int getFailedSongTitleCount() {
        synchronized (failedIndexes) {
            return failedIndexes.size();
        }
    }

    public boolean isActive() {
        return active.get();
    }


    public Status getStatus() {
        return status.get();
    }

    public ReadOnlyObjectProperty<Status> statusProperty() {
        return status;
    }

    public boolean isCancellationRequested() {
        return cancellationRequested.get();
    }

    public boolean isClosed() {
        return closed.get();
    }

    public List<Song> getSongs() {
        return songs;
    }

    public boolean isDownloadPhaseComplete() {
        return isDoneScheduling() && activeCount.get() <= 0;
    }

    boolean markExclusiveDownloadPhaseReleased() {
        return exclusiveDownloadPhaseReleased.compareAndSet(false, true);
    }

    ScheduledSong pollNextScheduledSong() {
        if (isCancellationRequested()) return null;

        int index = nextIndex.getAndIncrement();

        if (index >= songs.size()) {
            nextIndex.set(songs.size());
            updateQueuedCount();
            return null;
        }

        updateQueuedCount();

        return new ScheduledSong(index, songs.get(index));
    }

    boolean hasCapacity() {
        return activeCount.get() < parallelLimit;
    }

    void markTaskStarted() {
        activeCount.incrementAndGet();
    }

    void markDownloadFinished() {
        activeCount.updateAndGet(value -> Math.max(0, value - 1));
    }

    void markTaskIntegrated(DownloadTask task, int songIndex, Throwable integrationError) {
        if (!registerTerminalIndex(songIndex)) {
            return;
        }

        integratedCount.incrementAndGet();
        runFx(() -> integratedCountProperty.set(integratedCount.get()));

        registerTerminalTask(task, songIndex, integrationError);
    }

    boolean isDoneScheduling() {
        return nextIndex.get() >= songs.size();
    }

    boolean isComplete() {
        return isDownloadPhaseComplete()
                && integratedCount.get() >= scheduledCount();
    }

    void requestCancel() {
        cancellationRequested.set(true);
        nextIndex.set(songs.size());
        updateQueuedCount();
    }

    boolean close() {
        Status finalStatus = cancellationRequested.get()
                ? Status.CANCELLED
                : (errorCount.get() > 0 || cancelledCount.get() > 0 ? Status.ERROR : Status.COMPLETED);

        return close(finalStatus);
    }

    boolean close(Status finalStatus) {
        boolean changed = closed.compareAndSet(false, true);

        if (changed) {
            Status safeStatus = finalStatus == null ? Status.COMPLETED : finalStatus;

            runFx(() -> {
                status.set(safeStatus);
                active.set(false);
                queuedCount.set(0);
            });
        }

        return changed;
    }

    List<Song> retrySongs() {
        synchronized (completedIndexes) {
            if (completedIndexes.isEmpty()) {
                return songs;
            }

            List<Song> missing = new ArrayList<>();

            for (int i = 0; i < songs.size(); i++) {
                if (!completedIndexes.contains(i)) {
                    missing.add(songs.get(i));
                }
            }

            return missing;
        }
    }

    private void registerTerminalTask(DownloadTask task, int songIndex, Throwable integrationError) {
        if (integrationError != null) {
            registerFailedIndex(songIndex);
            incrementError();
            return;
        }

        if (task == null) {
            registerFailedIndex(songIndex);
            incrementError();
            return;
        }

        Worker.State state = task.getState();
        DownloadTask.ResultStatus result = task.getResultStatus();

        if (result == DownloadTask.ResultStatus.COMPLETED
                || result == DownloadTask.ResultStatus.WARNING) {
            if (songIndex >= 0) {
                completedIndexes.add(songIndex);
            }

            incrementCompleted();
            return;
        }

        if (result == DownloadTask.ResultStatus.CANCELLED
                || state == Worker.State.CANCELLED) {
            registerFailedIndex(songIndex);
            incrementCancelled();
            return;
        }

        registerFailedIndex(songIndex);
        incrementError();
    }

    private void registerFailedIndex(int songIndex) {
        if (songIndex < 0) return;
        failedIndexes.add(songIndex);
    }

    private boolean registerTerminalIndex(int index) {
        if (index < 0) {
            return true;
        }

        synchronized (terminalIndexes) {
            return terminalIndexes.add(index);
        }
    }

    private int scheduledCount() {
        return Math.min(nextIndex.get(), songs.size());
    }

    private void incrementCompleted() {
        int value = completedCount.incrementAndGet();
        runFx(() -> completedCountProperty.set(value));
    }

    private void incrementError() {
        int value = errorCount.incrementAndGet();
        runFx(() -> errorCountProperty.set(value));
    }

    private void incrementCancelled() {
        int value = cancelledCount.incrementAndGet();
        runFx(() -> cancelledCountProperty.set(value));
    }

    private void updateQueuedCount() {
        int remaining = Math.max(0, songs.size() - nextIndex.get());
        runFx(() -> queuedCount.set(remaining));
    }

    private String songTitle(Song song, int index) {
        if (song != null && song.getTitle() != null && !song.getTitle().isBlank()) {
            return song.getTitle().trim();
        }
        return "Song " + (index + 1);
    }

    private void runFx(Runnable action) {
        if (action == null) return;

        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }
}
