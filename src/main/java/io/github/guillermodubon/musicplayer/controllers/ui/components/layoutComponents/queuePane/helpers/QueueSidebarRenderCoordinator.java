package io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane.helpers;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane.factory.QueueSidebarViewFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane.services.QueueSidebarContentService;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

import static java.lang.Math.clamp;

/**
 * Owns the queue sidebar content and its progressive rendering state.
 * The controller only coordinates lifecycle and FXML actions.
 */
public final class QueueSidebarRenderCoordinator {

    private static final int INITIAL_RENDER_BATCH_SIZE = 14;
    private static final int FOLLOW_UP_RENDER_BATCH_SIZE = 12;
    private static final double LOAD_MORE_SCROLL_THRESHOLD = 0.84;
    private static final double VIEWPORT_FILL_TOLERANCE = 32.0;

    private final PlaybackManager playbackManager;
    private final QueueSidebarContentService contentService;
    private final QueueSidebarViewFactory viewFactory;
    private final Runnable refreshCallback;
    private final Consumer<Song> playSong;
    private final Consumer<String> artistClick;
    private final QueueSongReorderSupport reorderSupport;

    private ScrollPane queueScrollPane;
    private AnchorPane nowPlayingContainer;
    private VBox nextQueueList;
    private VBox nextFromList;
    private Label nextFromLabel;
    private VBox nextQueueSection;

    private List<Song> queueSnapshot = List.of();
    private List<Song> remainderSnapshot = List.of();
    private int renderedQueueCount;
    private int renderedRemainderCount;
    private long renderGeneration;
    private boolean renderBatchScheduled;
    private boolean viewportFillCheckScheduled;
    private boolean disposed;

    public QueueSidebarRenderCoordinator(
            PlaybackManager playbackManager,
            QueueSidebarContentService contentService,
            QueueSidebarViewFactory viewFactory,
            Runnable refreshCallback,
            Consumer<Song> playSong,
            Consumer<String> artistClick
    ) {
        this.playbackManager = Objects.requireNonNull(playbackManager);
        this.contentService = Objects.requireNonNull(contentService);
        this.viewFactory = Objects.requireNonNull(viewFactory);
        this.refreshCallback = Objects.requireNonNull(refreshCallback);
        this.playSong = Objects.requireNonNull(playSong);
        this.artistClick = Objects.requireNonNull(artistClick);
        this.reorderSupport = new QueueSongReorderSupport(this::onReorderRequested);
    }

    public void bindViews(
            ScrollPane scrollPane,
            AnchorPane nowPlaying,
            VBox queueList,
            VBox remainderList,
            Label remainderLabel,
            VBox queueSection
    ) {
        this.queueScrollPane = scrollPane;
        this.nowPlayingContainer = nowPlaying;
        this.nextQueueList = queueList;
        this.nextFromList = remainderList;
        this.nextFromLabel = remainderLabel;
        this.nextQueueSection = queueSection;

        reorderSupport.bind(queueList, QueueSongReorderSupport.Section.QUEUE);
        reorderSupport.bind(remainderList, QueueSongReorderSupport.Section.REMAINDER);
        reorderSupport.bindAutoScroll(scrollPane, this::scrollQueueByPixels);
        installProgressiveLoading();
    }

    public void refreshAll() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::refreshAll);
            return;
        }

        if (disposed || queueScrollPane == null || queueScrollPane.getScene() == null) {
            return;
        }

        refreshNowPlaying();
        refreshProgressiveSections();
    }

    public void refreshAfterPlaybackFlowChange() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::refreshAfterPlaybackFlowChange);
            return;
        }

        if (disposed || !isAttachedAndVisible()) {
            return;
        }

        refreshNowPlaying();
        refreshProgressiveSectionsIfChanged();
    }

    public void invalidate() {
        renderGeneration++;
        renderBatchScheduled = false;
        viewportFillCheckScheduled = false;
        reorderSupport.reset();
        queueSnapshot = List.of();
        remainderSnapshot = List.of();
    }

    public void dispose() {
        disposed = true;
        invalidate();
    }

    private void installProgressiveLoading() {
        if (queueScrollPane == null) {
            return;
        }

        queueScrollPane.vvalueProperty().addListener((obs, oldValue, newValue) -> {
            double previous = oldValue == null ? 0.0 : oldValue.doubleValue();
            double current = newValue == null ? 0.0 : newValue.doubleValue();
            if (previous < LOAD_MORE_SCROLL_THRESHOLD
                    && current >= LOAD_MORE_SCROLL_THRESHOLD) {
                requestNextRenderBatch();
            }
        });

        queueScrollPane.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, event -> {
            if (event.getDeltaY() < 0.0
                    && queueScrollPane.getVvalue() >= LOAD_MORE_SCROLL_THRESHOLD) {
                requestNextRenderBatch();
            }
        });

        queueScrollPane.viewportBoundsProperty().addListener(
                (obs, oldBounds, newBounds) -> scheduleViewportFillCheck()
        );
    }

    private void refreshNowPlaying() {
        if (nowPlayingContainer == null) {
            return;
        }

        nowPlayingContainer.getChildren().clear();
        Song current = playbackManager.getCurrentSong();
        if (current == null) {
            return;
        }

        Node node = viewFactory.createSongItem(
                current,
                playSong,
                artistClick,
                song -> {
                    if (song != null) {
                        playbackManager.enqueue(song);
                        refreshCallback.run();
                    }
                }
        );

        nowPlayingContainer.getChildren().add(node);
        if (node instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
            region.prefWidthProperty().bind(nowPlayingContainer.widthProperty());
        }

        AnchorPane.setTopAnchor(node, 0.0);
        AnchorPane.setRightAnchor(node, 0.0);
        AnchorPane.setBottomAnchor(node, 0.0);
        AnchorPane.setLeftAnchor(node, 0.0);
    }

    private void refreshProgressiveSections() {
        List<Song> queue;
        List<Song> remainder;

        try {
            queue = contentService.getQueueSongs(playbackManager);
        } catch (Exception error) {
            error.printStackTrace();
            queue = List.of();
        }

        try {
            remainder = contentService.getVisibleRemainder(playbackManager);
        } catch (Exception error) {
            error.printStackTrace();
            remainder = List.of();
        }

        applyProgressiveSnapshots(
                queue == null ? List.of() : List.copyOf(queue),
                remainder == null ? List.of() : List.copyOf(remainder)
        );
    }

    private boolean refreshProgressiveSectionsIfChanged() {
        List<Song> latestQueue;
        List<Song> latestRemainder;

        try {
            latestQueue = contentService.getQueueSongs(playbackManager);
        } catch (Exception error) {
            error.printStackTrace();
            latestQueue = List.of();
        }

        try {
            latestRemainder = contentService.getVisibleRemainder(playbackManager);
        } catch (Exception error) {
            error.printStackTrace();
            latestRemainder = List.of();
        }

        List<Song> safeQueue = latestQueue == null
                ? List.of()
                : List.copyOf(latestQueue);
        List<Song> safeRemainder = latestRemainder == null
                ? List.of()
                : List.copyOf(latestRemainder);

        if (sameSongSequence(queueSnapshot, safeQueue)
                && sameSongSequence(remainderSnapshot, safeRemainder)) {
            return false;
        }

        applyProgressiveSnapshots(safeQueue, safeRemainder);
        return true;
    }

    private void applyProgressiveSnapshots(List<Song> queue, List<Song> remainder) {
        renderGeneration++;
        renderBatchScheduled = false;
        viewportFillCheckScheduled = false;
        reorderSupport.reset();

        queueSnapshot = queue == null ? List.of() : List.copyOf(queue);
        remainderSnapshot = remainder == null ? List.of() : List.copyOf(remainder);
        renderedQueueCount = 0;
        renderedRemainderCount = 0;
        reorderSupport.setEnabled(queueSnapshot.size() + remainderSnapshot.size() >= 2);

        boolean hasQueue = !queueSnapshot.isEmpty();
        boolean hasRemainder = !remainderSnapshot.isEmpty();

        if (nextQueueSection != null) {
            nextQueueSection.setVisible(hasQueue);
            nextQueueSection.setManaged(hasQueue);
        }
        if (nextFromLabel != null) {
            nextFromLabel.setText(contentService.buildNextFromLabel(playbackManager));
        }
        if (nextFromList != null) {
            nextFromList.setVisible(hasRemainder);
            nextFromList.setManaged(hasRemainder);
            nextFromList.getChildren().clear();
        }
        if (nextQueueList != null) {
            nextQueueList.getChildren().clear();
        }

        appendNextRenderBatch(INITIAL_RENDER_BATCH_SIZE);
        scheduleViewportFillCheck();
    }

    private void requestNextRenderBatch() {
        if (renderBatchScheduled || allSnapshotsRendered()) {
            return;
        }

        long requestedGeneration = renderGeneration;
        renderBatchScheduled = true;
        Platform.runLater(() -> {
            renderBatchScheduled = false;
            if (disposed || requestedGeneration != renderGeneration || allSnapshotsRendered()) {
                return;
            }

            appendNextRenderBatch(FOLLOW_UP_RENDER_BATCH_SIZE);
            scheduleViewportFillCheck();
        });
    }

    private void scrollQueueByPixels(double delta) {
        if (queueScrollPane == null || queueScrollPane.getContent() == null || delta == 0.0) {
            return;
        }

        Node content = queueScrollPane.getContent();
        double contentHeight = content.getBoundsInLocal().getHeight();
        double viewportHeight = queueScrollPane.getViewportBounds().getHeight();
        double maxScroll = Math.max(0.0, contentHeight - viewportHeight);
        if (maxScroll <= 0.0) return;

        double current = queueScrollPane.getVvalue() * maxScroll;
        double next = clamp(current + delta, 0.0, maxScroll);
        if (Math.abs(next - current) < 0.01) return;

        queueScrollPane.setVvalue(next / maxScroll);
        if (delta > 0.0 && queueScrollPane.getVvalue() >= LOAD_MORE_SCROLL_THRESHOLD) {
            requestNextRenderBatch();
        }
    }

    private void appendNextRenderBatch(int batchSize) {
        if (disposed || batchSize <= 0) {
            return;
        }

        int remainingSlots = batchSize;
        while (remainingSlots > 0 && renderedQueueCount < queueSnapshot.size()) {
            int snapshotIndex = renderedQueueCount++;
            Song song = queueSnapshot.get(snapshotIndex);
            if (song == null) {
                continue;
            }

            Node row = viewFactory.createRemoveQueueItem(song, playSong, () -> {
                playbackManager.removeFromQueue(song);
                refreshCallback.run();
            });

            if (row != null && nextQueueList != null) {
                nextQueueList.getChildren().add(row);
                reorderSupport.registerRow(
                        row,
                        QueueSongReorderSupport.Section.QUEUE,
                        snapshotIndex,
                        song
                );
                remainingSlots--;
            }
        }

        while (remainingSlots > 0 && renderedRemainderCount < remainderSnapshot.size()) {
            int snapshotIndex = renderedRemainderCount++;
            Song song = remainderSnapshot.get(snapshotIndex);
            if (song == null) {
                continue;
            }

            Node row = viewFactory.createSongItem(
                    song,
                    playSong,
                    artistClick,
                    queuedSong -> {
                        if (queuedSong != null) {
                            playbackManager.enqueue(queuedSong);
                            refreshCallback.run();
                        }
                    }
            );

            if (row != null && nextFromList != null) {
                nextFromList.getChildren().add(row);
                reorderSupport.registerRow(
                        row,
                        QueueSongReorderSupport.Section.REMAINDER,
                        snapshotIndex,
                        song
                );
                remainingSlots--;
            }
        }
    }

    private void onReorderRequested(QueueSongReorderSupport.ReorderRequest request) {
        if (disposed || request == null) {
            return;
        }

        List<Song> reordered = request.section() == QueueSongReorderSupport.Section.QUEUE
                ? new ArrayList<>(queueSnapshot)
                : new ArrayList<>(remainderSnapshot);

        int sourceIndex = request.sourceIndex();
        int targetIndex = request.targetIndex();
        if (sourceIndex < 0 || sourceIndex >= reordered.size()
                || targetIndex < 0 || targetIndex >= reordered.size()
                || sourceIndex == targetIndex) {
            return;
        }

        int insertionIndex = request.insertAfter() ? targetIndex + 1 : targetIndex;
        Song movedSong = reordered.remove(sourceIndex);
        if (sourceIndex < insertionIndex) {
            insertionIndex--;
        }
        insertionIndex = Math.max(0, Math.min(insertionIndex, reordered.size()));
        reordered.add(insertionIndex, movedSong);

        if (sameSongSequence(
                request.section() == QueueSongReorderSupport.Section.QUEUE
                        ? queueSnapshot
                        : remainderSnapshot,
                reordered
        )) {
            return;
        }

        if (request.section() == QueueSongReorderSupport.Section.QUEUE) {
            playbackManager.reorderQueue(queueSnapshot, reordered);
        } else {
            playbackManager.reorderRemainder(remainderSnapshot, reordered);
        }

        // A failed compare means playback changed while dragging; in both
        // cases refresh from the authoritative playback state.
        refreshCallback.run();
    }

    private void scheduleViewportFillCheck() {
        if (queueScrollPane == null
                || viewportFillCheckScheduled
                || allSnapshotsRendered()) {
            return;
        }

        long requestedGeneration = renderGeneration;
        viewportFillCheckScheduled = true;
        Platform.runLater(() -> {
            viewportFillCheckScheduled = false;
            if (disposed || requestedGeneration != renderGeneration || allSnapshotsRendered()) {
                return;
            }

            Node content = queueScrollPane.getContent();
            double contentHeight = content == null
                    ? 0.0
                    : content.getLayoutBounds().getHeight();
            double viewportHeight = queueScrollPane.getViewportBounds().getHeight();

            if (viewportHeight > 0.0
                    && contentHeight <= viewportHeight + VIEWPORT_FILL_TOLERANCE) {
                requestNextRenderBatch();
            }
        });
    }

    private boolean allSnapshotsRendered() {
        return renderedQueueCount >= queueSnapshot.size()
                && renderedRemainderCount >= remainderSnapshot.size();
    }

    private boolean isAttachedAndVisible() {
        return !disposed
                && queueScrollPane != null
                && queueScrollPane.getScene() != null
                && queueScrollPane.isVisible();
    }

    private boolean sameSongSequence(List<Song> first, List<Song> second) {
        if (first == second) {
            return true;
        }
        if (first == null || second == null || first.size() != second.size()) {
            return false;
        }
        for (int index = 0; index < first.size(); index++) {
            if (!sameSongState(first.get(index), second.get(index))) {
                return false;
            }
        }
        return true;
    }

    private boolean sameSongState(Song first, Song second) {
        if (first == second) {
            return true;
        }
        if (first == null || second == null) {
            return false;
        }

        long firstId = first.getSongID();
        long secondId = second.getSongID();
        boolean sameIdentity = firstId > 0 && secondId > 0
                ? firstId == secondId
                : normalizeSongText(first.getTitle())
                .equals(normalizeSongText(second.getTitle()));

        if (!sameIdentity || first.isLocal() != second.isLocal()) {
            return false;
        }

        return Objects.equals(
                normalizeFilePath(first.getFilePath()),
                normalizeFilePath(second.getFilePath())
        );
    }

    private String normalizeSongText(String value) {
        return value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String normalizeFilePath(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return new File(value).getAbsoluteFile().toPath().normalize().toString();
        } catch (Exception ignored) {
            return value.trim();
        }
    }
}
