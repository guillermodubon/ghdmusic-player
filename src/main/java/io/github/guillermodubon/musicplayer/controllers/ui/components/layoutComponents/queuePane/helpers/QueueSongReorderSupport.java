package io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane.helpers;

import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.event.EventHandler;
import javafx.scene.control.ButtonBase;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.VBox;
import javafx.scene.layout.HBox;
import io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.services.QueueSongItemHoverSupport;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dragdrop.DragAutoScrollSupport;
import io.github.guillermodubon.musicplayer.models.Song;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * Adds lightweight row reordering to the two non-virtualized queue sections.
 * The support stores snapshot indexes instead of relying on rendered child
 * positions, so progressive rendering does not lose items that are not yet
 * visible.
 */
public final class QueueSongReorderSupport {

    private static final String DRAG_TOKEN = "queue-song-reorder";
    private static final String DROP_BEFORE_STYLE = "queue-reorder-drop-before";
    private static final String DROP_AFTER_STYLE = "queue-reorder-drop-after";

    private final Map<VBox, Section> sections = new IdentityHashMap<>();
    private final Map<Node, RowReference> rows = new IdentityHashMap<>();
    private final Consumer<ReorderRequest> reorderListener;
    private final DragAutoScrollSupport autoScrollSupport = new DragAutoScrollSupport();
    private final EventHandler<DragEvent> viewportDragOverHandler = this::onViewportDragOver;
    private final EventHandler<DragEvent> viewportDragExitedHandler = this::onViewportDragExited;
    private ScrollPane autoScrollViewport;

    private boolean enabled;
    private Node draggedRow;
    private RowReference draggedReference;
    private Node indicatorRow;

    public QueueSongReorderSupport(Consumer<ReorderRequest> reorderListener) {
        this.reorderListener = reorderListener == null ? ignored -> { } : reorderListener;
    }

    public void bind(VBox section, Section type) {
        if (section == null || type == null || sections.containsKey(section)) {
            return;
        }

        sections.put(section, type);
        section.addEventFilter(MouseEvent.DRAG_DETECTED, this::onDragDetected);
        section.addEventFilter(DragEvent.DRAG_OVER, this::onDragOver);
        section.addEventFilter(DragEvent.DRAG_EXITED, this::onDragExited);
        section.addEventFilter(DragEvent.DRAG_DROPPED, this::onDragDropped);
        section.addEventFilter(DragEvent.DRAG_DONE, ignored -> clearDragState());
    }

    public void bindAutoScroll(ScrollPane scrollPane, DoubleConsumer scrollBy) {
        if (autoScrollViewport == scrollPane) {
            autoScrollSupport.bind(scrollPane, scrollBy);
            return;
        }
        if (autoScrollViewport != null) {
            autoScrollViewport.removeEventFilter(DragEvent.DRAG_OVER, viewportDragOverHandler);
            autoScrollViewport.removeEventFilter(DragEvent.DRAG_EXITED, viewportDragExitedHandler);
        }
        autoScrollViewport = scrollPane;
        autoScrollSupport.bind(scrollPane, scrollBy);
        if (scrollPane != null) {
            scrollPane.addEventFilter(DragEvent.DRAG_OVER, viewportDragOverHandler);
            scrollPane.addEventFilter(DragEvent.DRAG_EXITED, viewportDragExitedHandler);
        }
    }

    private void onViewportDragOver(DragEvent event) {
        if (isInternalDrag(event)) autoScrollSupport.update(event);
    }

    private void onViewportDragExited(DragEvent event) {
        if (isInternalDrag(event)) autoScrollSupport.stop();
    }

    public void registerRow(Node row, Section type, int snapshotIndex, Song song) {
        if (row == null || type == null || snapshotIndex < 0 || song == null) {
            return;
        }
        rows.put(row, new RowReference(type, snapshotIndex, song));
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            clearDragState();
        }
    }

    /** Clears row references after a snapshot is replaced. */
    public void reset() {
        clearDragState();
        rows.clear();
    }

    private void onDragDetected(MouseEvent event) {
        if (!enabled || event == null || event.isConsumed()) {
            return;
        }

        Node row = findRegisteredRow(event.getTarget());
        RowReference reference = rows.get(row);
        if (row == null || reference == null || isInteractiveTarget(event.getTarget())) {
            return;
        }

        draggedRow = row;
        draggedReference = reference;

        ClipboardContent content = new ClipboardContent();
        content.putString(DRAG_TOKEN);
        row.startDragAndDrop(TransferMode.MOVE).setContent(content);
        event.consume();
    }

    private void onDragOver(DragEvent event) {
        if (!isInternalDrag(event)) {
            return;
        }

        autoScrollSupport.update(event);

        Node targetRow = findRegisteredRow(event.getTarget());
        RowReference targetReference = rows.get(targetRow);
        if (targetRow == null
                || targetReference == null
                || targetReference.section() != draggedReference.section()) {
            clearDropIndicator();
            return;
        }

        event.acceptTransferModes(TransferMode.MOVE);
        updateDropIndicator(targetRow, isLowerHalf(targetRow, event));
        event.consume();
    }

    private void onDragExited(DragEvent event) {
        if (!isInternalDrag(event)) {
            return;
        }
        autoScrollSupport.stop();
        clearDropIndicator();
    }

    private void onDragDropped(DragEvent event) {
        if (!isInternalDrag(event)) {
            return;
        }

        Node targetRow = findRegisteredRow(event.getTarget());
        RowReference targetReference = rows.get(targetRow);
        boolean moved = targetReference != null
                && targetReference.section() == draggedReference.section();

        if (moved) {
            reorderListener.accept(new ReorderRequest(
                    draggedReference.section(),
                    draggedReference.snapshotIndex(),
                    targetReference.snapshotIndex(),
                    isLowerHalf(targetRow, event)
            ));
        }

        event.setDropCompleted(moved);
        event.consume();
        clearDragState();
    }

    private boolean isInternalDrag(DragEvent event) {
        return enabled
                && draggedReference != null
                && event != null
                && event.getDragboard() != null
                && event.getDragboard().hasString()
                && DRAG_TOKEN.equals(event.getDragboard().getString());
    }

    private Node findRegisteredRow(Object target) {
        if (!(target instanceof Node node)) {
            return null;
        }

        Node current = node;
        while (current != null) {
            if (rows.containsKey(current)) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private boolean isInteractiveTarget(Object target) {
        if (!(target instanceof Node node)) {
            return false;
        }

        Node current = node;
        while (current != null) {
            if (current instanceof ButtonBase) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private boolean isLowerHalf(Node row, DragEvent event) {
        if (row == null || event == null) {
            return false;
        }

        try {
            double middle = row.localToScene(row.getBoundsInLocal()).getMinY()
                    + row.getBoundsInLocal().getHeight() / 2.0;
            return event.getSceneY() >= middle;
        } catch (Exception ignored) {
            return event.getY() >= row.getBoundsInLocal().getHeight() / 2.0;
        }
    }

    private void updateDropIndicator(Node row, boolean insertAfter) {
        if (indicatorRow != row) {
            clearDropIndicator();
            indicatorRow = row;
        } else {
            removeDropStyles(row);
        }

        row.getStyleClass().add(insertAfter ? DROP_AFTER_STYLE : DROP_BEFORE_STYLE);
        refreshRowStyle(row);
    }

    private void clearDragState() {
        autoScrollSupport.stop();
        clearDropIndicator();
        draggedRow = null;
        draggedReference = null;
    }

    private void clearDropIndicator() {
        if (indicatorRow != null) {
            removeDropStyles(indicatorRow);
        }
        indicatorRow = null;
    }

    private void removeDropStyles(Node row) {
        if (row != null) {
            row.getStyleClass().removeAll(DROP_BEFORE_STYLE, DROP_AFTER_STYLE);
            refreshRowStyle(row);
        }
    }

    private void refreshRowStyle(Node row) {
        if (row instanceof HBox hBox) {
            QueueSongItemHoverSupport.refresh(hBox, () -> false);
        }
    }

    public enum Section {
        QUEUE,
        REMAINDER
    }

    public record ReorderRequest(
            Section section,
            int sourceIndex,
            int targetIndex,
            boolean insertAfter
    ) {
    }

    private record RowReference(Section section, int snapshotIndex, Song song) {
    }
}
