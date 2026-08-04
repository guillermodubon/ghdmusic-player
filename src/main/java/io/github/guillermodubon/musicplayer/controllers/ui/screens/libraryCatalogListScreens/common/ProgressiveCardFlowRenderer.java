package io.github.guillermodubon.musicplayer.controllers.ui.screens.libraryCatalogListScreens.common;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Creates card nodes only for the visible catalog area, then appends small batches
 * as the user approaches the end of the scroll viewport.
 */
public final class ProgressiveCardFlowRenderer<T> {

    private static final ExecutorService CATALOG_IO = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "library-catalog-io");
        thread.setDaemon(true);
        return thread;
    });

    private static final int MINIMUM_BATCH_SIZE = 12;
    private static final int MAX_CARDS_PER_PULSE = 10;
    private static final double PRELOAD_THRESHOLD = 0.72;

    private final ScrollPane scrollPane;
    private final FlowPane flow;
    private final Function<T, Parent> cardFactory;
    private final Consumer<ProgressiveCardFlowRenderer<T>> renderListener;
    private final double cardWidth;
    private final double cardHeight;

    private List<T> items = List.of();
    private int renderedCount;
    private int requestedCount;
    private boolean rendering;
    private boolean listenersInstalled;

    public ProgressiveCardFlowRenderer(ScrollPane scrollPane,
                                       FlowPane flow,
                                       double cardWidth,
                                       double cardHeight,
                                       Function<T, Parent> cardFactory,
                                       Consumer<ProgressiveCardFlowRenderer<T>> renderListener) {
        this.scrollPane = Objects.requireNonNull(scrollPane, "scrollPane");
        this.flow = Objects.requireNonNull(flow, "flow");
        this.cardWidth = Math.max(1, cardWidth);
        this.cardHeight = Math.max(1, cardHeight);
        this.cardFactory = Objects.requireNonNull(cardFactory, "cardFactory");
        this.renderListener = renderListener == null ? ignored -> { } : renderListener;
    }

    public static <R> CompletableFuture<R> loadAsync(Supplier<R> loader) {
        return CompletableFuture.supplyAsync(loader, CATALOG_IO);
    }

    public void install() {
        if (listenersInstalled) return;
        listenersInstalled = true;

        scrollPane.viewportBoundsProperty().addListener((obs, oldBounds, newBounds) -> ensureViewportFilled());
        scrollPane.vvalueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null && newValue.doubleValue() >= PRELOAD_THRESHOLD) {
                requestMore(pageSize());
            }
        });
    }

    public void reset(List<T> entries) {
        items = entries == null ? List.of() : List.copyOf(entries);
        renderedCount = 0;
        requestedCount = 0;
        rendering = false;
        flow.getChildren().clear();
        renderListener.accept(this);
        ensureViewportFilled();
    }

    public void clear() {
        reset(List.of());
    }

    public void renderAtLeast(int count) {
        requestMore(Math.max(0, count) - renderedCount);
    }

    public int totalCount() {
        return items.size();
    }

    public int renderedCount() {
        return renderedCount;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    private void ensureViewportFilled() {
        if (items.isEmpty()) return;
        int target = Math.max(MINIMUM_BATCH_SIZE, cardsPerRow() * visibleRows());
        requestMore(target - renderedCount);
    }

    private void requestMore(int count) {
        if (count <= 0 || renderedCount >= items.size()) return;
        requestedCount = Math.min(items.size(), Math.max(requestedCount, renderedCount + count));
        if (rendering) return;
        rendering = true;
        Platform.runLater(this::renderNextPulse);
    }

    private void renderNextPulse() {
        int upperBound = Math.min(requestedCount, items.size());
        int end = Math.min(upperBound, renderedCount + MAX_CARDS_PER_PULSE);

        while (renderedCount < end) {
            T item = items.get(renderedCount++);
            try {
                Parent card = cardFactory.apply(item);
                if (card != null) flow.getChildren().add(card);
            } catch (Exception ignored) {
            }
        }

        renderListener.accept(this);

        if (renderedCount < requestedCount && renderedCount < items.size()) {
            Platform.runLater(this::renderNextPulse);
            return;
        }
        rendering = false;
    }

    private int pageSize() {
        return Math.max(MINIMUM_BATCH_SIZE, cardsPerRow() * 2);
    }

    private int cardsPerRow() {
        double viewportWidth = scrollPane.getViewportBounds() == null
                ? 0
                : scrollPane.getViewportBounds().getWidth();
        double availableWidth = Math.max(0, viewportWidth - flow.getPadding().getLeft() - flow.getPadding().getRight());
        double gap = Math.max(0, flow.getHgap());
        if (availableWidth <= 0) return 1;
        return Math.max(1, (int) Math.floor((availableWidth + gap) / (cardWidth + gap)));
    }

    private int visibleRows() {
        double viewportHeight = scrollPane.getViewportBounds() == null
                ? 0
                : scrollPane.getViewportBounds().getHeight();
        double gap = Math.max(0, flow.getVgap());
        if (viewportHeight <= 0) return 2;
        return Math.max(2, (int) Math.ceil(viewportHeight / (cardHeight + gap)) + 1);
    }
}
