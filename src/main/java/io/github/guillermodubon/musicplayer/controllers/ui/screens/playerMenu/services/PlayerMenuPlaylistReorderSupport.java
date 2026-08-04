package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services;

import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dragdrop.DragAutoScrollSupport;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/** Handles row reordering without coupling drag state to a virtualized cell. */
final class PlayerMenuPlaylistReorderSupport {
    private static final String DRAG_TOKEN = "player-menu-playlist-reorder";
    private static final String DROP_BEFORE_STYLE = "playlist-reorder-drop-before";
    private static final String DROP_AFTER_STYLE = "playlist-reorder-drop-after";

    private final PlayerMenuContext context;
    private final PlaybackManager playbackManager;
    private final PlayerMenuPlaylistOrderPersistenceService persistenceService;
    private final Map<ListView<Song>, Boolean> boundViews = new IdentityHashMap<>();
    private final DragAutoScrollSupport autoScrollSupport = new DragAutoScrollSupport();
    private final EventHandler<DragEvent> viewportDragOverHandler = this::onViewportDragOver;
    private final EventHandler<DragEvent> viewportDragExitedHandler = this::onViewportDragExited;
    private Node autoScrollViewport;

    private StartUpService startUpService;
    private Playlist activePlaylist;
    private boolean playlistAllowed;
    private boolean enabled;
    private Song draggedSong;
    private ListCell<Song> indicatorCell;
    private Consumer<List<Song>> reorderListener = ignored -> { };

    PlayerMenuPlaylistReorderSupport(
            PlayerMenuContext context,
            PlaybackManager playbackManager,
            PlayerMenuPlaylistOrderPersistenceService persistenceService
    ) {
        this.context = context;
        this.playbackManager = playbackManager;
        this.persistenceService = persistenceService;
    }

    void setReorderListener(Consumer<List<Song>> listener) {
        this.reorderListener = listener == null ? ignored -> { } : listener;
    }

    void bindAutoScroll(Node viewport, DoubleConsumer scrollBy) {
        if (autoScrollViewport == viewport) {
            autoScrollSupport.bind(viewport, scrollBy);
            return;
        }
        if (autoScrollViewport != null) {
            autoScrollViewport.removeEventFilter(DragEvent.DRAG_OVER, viewportDragOverHandler);
            autoScrollViewport.removeEventFilter(DragEvent.DRAG_EXITED, viewportDragExitedHandler);
        }
        autoScrollViewport = viewport;
        autoScrollSupport.bind(viewport, scrollBy);
        if (viewport != null) {
            viewport.addEventFilter(DragEvent.DRAG_OVER, viewportDragOverHandler);
            viewport.addEventFilter(DragEvent.DRAG_EXITED, viewportDragExitedHandler);
        }
    }

    private void onViewportDragOver(DragEvent event) {
        if (isInternalDrag(event)) autoScrollSupport.update(event);
    }

    private void onViewportDragExited(DragEvent event) {
        if (isInternalDrag(event)) autoScrollSupport.stop();
    }

    void bind(ListView<Song> listView) {
        if (listView == null || boundViews.putIfAbsent(listView, Boolean.TRUE) != null) return;

        listView.addEventFilter(MouseEvent.DRAG_DETECTED, this::onDragDetected);
        listView.addEventFilter(DragEvent.DRAG_OVER, this::onDragOver);
        listView.addEventFilter(DragEvent.DRAG_EXITED, this::onDragExited);
        listView.addEventFilter(DragEvent.DRAG_DROPPED, this::onDragDropped);
        listView.addEventFilter(DragEvent.DRAG_DONE, event -> clearDragState());
    }

    void activate(StartUpService startUpService, Playlist playlist, boolean enabled) {
        this.startUpService = startUpService;
        this.activePlaylist = playlist;
        this.playlistAllowed = enabled
                && startUpService != null
                && playlist != null
                && playlist.getId() > 0;
        this.enabled = this.playlistAllowed;
        if (!this.enabled) clearDragState();
    }

    void setEnabled(boolean enabled) {
        this.enabled = playlistAllowed && enabled;
        if (!this.enabled) clearDragState();
    }

    private void onDragDetected(MouseEvent event) {
        if (!enabled || event == null || event.isConsumed()) return;

        ListCell<Song> sourceCell = findCell(event.getTarget());
        if (sourceCell == null || sourceCell.getItem() == null
                || isInteractiveTarget(event.getTarget())) {
            return;
        }

        draggedSong = sourceCell.getItem();
        var dragboard = sourceCell.startDragAndDrop(TransferMode.MOVE);
        ClipboardContent content = new ClipboardContent();
        content.putString(DRAG_TOKEN);
        dragboard.setContent(content);
        event.consume();
    }

    private void onDragOver(DragEvent event) {
        if (!isInternalDrag(event)) return;

        autoScrollSupport.update(event);

        ListCell<Song> targetCell = findCell(event.getTarget());
        if (targetCell == null || targetCell.getItem() == null) return;

        event.acceptTransferModes(TransferMode.MOVE);
        boolean insertAfter = isLowerHalf(targetCell, event);
        updateDropIndicator(targetCell, insertAfter);
        event.consume();
    }

    private void onDragExited(DragEvent event) {
        if (!isInternalDrag(event)) return;
        autoScrollSupport.stop();
        Node target = event.getTarget() instanceof Node node ? node : null;
        if (target == null || findCell(target) == indicatorCell) {
            clearDropIndicator();
        }
    }

    private void onDragDropped(DragEvent event) {
        if (!isInternalDrag(event)) return;

        ListCell<Song> targetCell = findCell(event.getTarget());
        boolean moved = targetCell != null && moveSong(targetCell, event);
        event.setDropCompleted(moved);
        event.consume();
        clearDragState();
    }

    private boolean moveSong(ListCell<Song> targetCell, DragEvent event) {
        if (activePlaylist == null || draggedSong == null || targetCell == null) return false;

        List<Song> current = new ArrayList<>(context.getMasterSongList());
        int sourceIndex = indexOfSong(current, draggedSong);
        int targetIndex = indexOfSong(current, targetCell.getItem());
        if (sourceIndex < 0 || targetIndex < 0 || sourceIndex == targetIndex && !isLowerHalf(targetCell, event)) {
            return false;
        }

        boolean insertAfter = isLowerHalf(targetCell, event);
        int insertionIndex = insertAfter ? targetIndex + 1 : targetIndex;
        Song movedSong = current.remove(sourceIndex);
        if (sourceIndex < insertionIndex) insertionIndex--;
        insertionIndex = Math.max(0, Math.min(insertionIndex, current.size()));
        current.add(insertionIndex, movedSong);

        if (sameOrder(current, context.getMasterSongList())) return false;

        activePlaylist.getSongList().setAll(current);
        context.setMasterSongList(current);
        reorderPlayableSongs(current);
        syncPlaybackFlowIfRequired(current);
        persistenceService.requestCustom(startUpService, activePlaylist, current);
        reorderListener.accept(List.copyOf(current));
        return true;
    }

    private void syncPlaybackFlowIfRequired(List<Song> orderedSongs) {
        if (playbackManager == null
                || activePlaylist == null
                || playbackManager.isRandomMode()
                || playbackManager.getCurrentContentTypePlaying()
                != PlayerMenuContext.ContentType.PLAYLIST
                || playbackManager.getCurrentPlaylistPlayingId() != activePlaylist.getId()) {
            return;
        }

        playbackManager.syncCurrentSourceSongs(orderedSongs);
    }

    private void reorderPlayableSongs(List<Song> orderedSongs) {
        List<Song> playable = new ArrayList<>(context.getCurrentSongList());
        playable.sort((left, right) -> Integer.compare(
                indexOfSong(orderedSongs, left),
                indexOfSong(orderedSongs, right)
        ));
        context.setCurrentSongList(playable);
    }

    private boolean isInternalDrag(DragEvent event) {
        return enabled
                && draggedSong != null
                && event != null
                && event.getDragboard() != null
                && event.getDragboard().hasString()
                && DRAG_TOKEN.equals(event.getDragboard().getString());
    }

    private boolean isLowerHalf(ListCell<Song> cell, DragEvent event) {
        if (cell == null || event == null) return false;
        try {
            double middle = cell.localToScene(cell.getBoundsInLocal()).getMinY()
                    + cell.getHeight() / 2.0;
            return event.getSceneY() >= middle;
        } catch (Exception ignored) {
            return event.getY() >= cell.getHeight() / 2.0;
        }
    }

    private void updateDropIndicator(ListCell<Song> cell, boolean insertAfter) {
        if (indicatorCell == cell) {
            removeDropStyles(cell);
        } else {
            clearDropIndicator();
            indicatorCell = cell;
        }
        cell.getStyleClass().add(insertAfter ? DROP_AFTER_STYLE : DROP_BEFORE_STYLE);
    }

    private void clearDragState() {
        autoScrollSupport.stop();
        clearDropIndicator();
        draggedSong = null;
    }

    private void clearDropIndicator() {
        if (indicatorCell != null) removeDropStyles(indicatorCell);
        indicatorCell = null;
    }

    private void removeDropStyles(ListCell<Song> cell) {
        if (cell == null) return;
        cell.getStyleClass().removeAll(DROP_BEFORE_STYLE, DROP_AFTER_STYLE);
    }

    private ListCell<Song> findCell(Object target) {
        if (!(target instanceof Node node)) return null;
        Node current = node;
        while (current != null) {
            if (current instanceof ListCell<?> cell) {
                @SuppressWarnings("unchecked")
                ListCell<Song> songCell = (ListCell<Song>) cell;
                return songCell;
            }
            current = current.getParent();
        }
        return null;
    }

    private boolean isInteractiveTarget(Object target) {
        if (!(target instanceof Node node)) return false;
        Node current = node;
        while (current != null) {
            if (current instanceof ButtonBase) return true;
            current = current.getParent();
        }
        return false;
    }

    private int indexOfSong(List<Song> songs, Song expected) {
        if (songs == null || expected == null) return -1;
        for (int index = 0; index < songs.size(); index++) {
            Song candidate = songs.get(index);
            if (candidate == expected) return index;
            if (candidate != null && candidate.getSongID() > 0 && expected.getSongID() > 0
                    && candidate.getSongID() == expected.getSongID()) {
                return index;
            }
        }
        return -1;
    }

    private boolean sameOrder(List<Song> left, List<Song> right) {
        if (left == right) return true;
        if (left == null || right == null || left.size() != right.size()) return false;
        for (int index = 0; index < left.size(); index++) {
            Song leftSong = left.get(index);
            Song rightSong = right.get(index);
            if (leftSong == rightSong) continue;
            if (leftSong == null || rightSong == null
                    || leftSong.getSongID() <= 0
                    || rightSong.getSongID() <= 0
                    || leftSong.getSongID() != rightSong.getSongID()) {
                return false;
            }
        }
        return true;
    }
}
