package io.github.guillermodubon.musicplayer.controllers.ui.screens.discoverPage.providers.base;

import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.discoverPage.context.DiscoverPageContext;
import io.github.guillermodubon.musicplayer.utils.DiscoverUtils;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

public abstract class BaseDiscoverPagePageSectionProvider implements DiscoverPageSectionProvider {
    private static final String RENDER_GENERATION_KEY = "discover.renderGeneration";
    private static final String RENDER_ACTIVE_KEY = "discover.renderActive";

    private static final int DISCOVER_IO_CONCURRENCY = 6;
    private static final int UI_RENDER_BATCH_SIZE = 4;
    private static final AtomicInteger IO_THREAD_SEQUENCE = new AtomicInteger();

    protected static final ExecutorService IO_POOL = Executors.newFixedThreadPool(
            DISCOVER_IO_CONCURRENCY,
            discoverThreadFactory()
    );

    protected static final int TRENDS_MAX = 16;
    protected static final int PER_GENRE_LIMIT = 5;
    protected static final int GLOBAL_MAX = 16;
    protected static final int MAX_GENRES_TO_QUERY = 4;
    protected static final double DISCOVER_GRID_GAP = 16.0;

    // 220px retains the fixed 112px cover and readable title while allowing three
    // genre cards within the Discover content area on standard desktop widths.
    protected static final double GENRE_CARD_MIN_WIDTH = 220.0;
    protected static final int GENRE_GRID_MAX_COLUMNS = 3;

    protected final DiscoverPageContext context;

    protected BaseDiscoverPagePageSectionProvider(DiscoverPageContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    protected <T> CompletableFuture<T> supplyAsync(Callable<T> loader) {
        if (context.requestScope() != null) {
            return context.requestScope().supplyAsync(loader, IO_POOL);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return loader.call();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }, IO_POOL);
    }

    /**
     * Runs a small group of independent remote lookups under the screen request scope.
     * Tasks are cancelled together when the user leaves Discover, preventing stale work
     * from competing with the next screen for network and CPU resources.
     */
    protected <T> List<T> loadConcurrently(Collection<? extends Callable<T>> tasks) {
        if (tasks == null || tasks.isEmpty() || shouldAbort()) return List.of();

        List<CompletableFuture<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            if (task == null || shouldAbort()) break;
            futures.add(supplyAsync(task));
        }

        List<T> results = new ArrayList<>(futures.size());
        for (CompletableFuture<T> future : futures) {
            if (shouldAbort()) break;
            try {
                T value = future.get();
                if (value != null) results.add(value);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ignored) {
                // An individual Deezer endpoint may fail without invalidating the section.
            }
        }
        return results;
    }

    protected boolean shouldAbort() {
        return Thread.currentThread().isInterrupted()
                || (context.requestScope() != null && !context.requestScope().isActive());
    }

    /**
     * FXML controls must be created on the FX Application Thread. Rendering a few cards
     * at a time keeps the scene responsive while a large Discover response is applied.
     */
    protected <T> void appendNodesInBatches(VBox container,
                                            int renderGeneration,
                                            FlowPane target,
                                            List<T> models,
                                            Function<T, ? extends Node> nodeFactory,
                                            Consumer<Integer> onFinished) {
        if (target == null || nodeFactory == null) return;

        List<T> safeModels = models == null ? List.of() : List.copyOf(models);
        final class BatchRenderer implements Runnable {
            private int offset;
            private int rendered;

            @Override
            public void run() {
                if (!isRenderCurrent(container, renderGeneration)) return;

                int end = Math.min(offset + UI_RENDER_BATCH_SIZE, safeModels.size());
                for (int index = offset; index < end; index++) {
                    try {
                        Node node = nodeFactory.apply(safeModels.get(index));
                        if (node != null) {
                            target.getChildren().add(node);
                            rendered++;
                        }
                    } catch (Exception ignored) {
                        // Keep the rest of the Discover response usable when one card fails.
                    }
                }
                offset = end;

                if (offset < safeModels.size()) {
                    Platform.runLater(this);
                } else if (onFinished != null && isRenderCurrent(container, renderGeneration)) {
                    onFinished.accept(rendered);
                }
            }
        }

        Platform.runLater(new BatchRenderer());
    }

    private static ThreadFactory discoverThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "discover-io-" + IO_THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public static int beginRenderCycle(VBox container) {
        if (container == null) return -1;
        int generation = readGeneration(container) + 1;
        container.getProperties().put(RENDER_GENERATION_KEY, generation);
        container.getProperties().put(RENDER_ACTIVE_KEY, Boolean.TRUE);
        return generation;
    }

    public static void invalidateRenderCycle(VBox container) {
        if (container == null) return;
        container.getProperties().put(RENDER_GENERATION_KEY, readGeneration(container) + 1);
        container.getProperties().put(RENDER_ACTIVE_KEY, Boolean.FALSE);
    }

    protected int captureRenderGeneration(VBox container) {
        return readGeneration(container);
    }

    protected boolean isRenderCurrent(VBox container, int generation) {
        if (container == null || generation < 0) return false;
        return Boolean.TRUE.equals(container.getProperties().get(RENDER_ACTIVE_KEY))
                && readGeneration(container) == generation;
    }

    private static int readGeneration(VBox container) {
        if (container == null) return 0;
        Object value = container.getProperties().get(RENDER_GENERATION_KEY);
        return value instanceof Number n ? n.intValue() : 0;
    }

    protected Label sectionTitle(String text) {
        Label lbl = new Label(text);
        lbl.getStyleClass().add("discover-section-title");
        return lbl;
    }

    protected VBox sectionBlock(String title) {
        VBox box = new VBox(10);
        box.getStyleClass().add("discover-section");
        // VBox only stretches children that advertise an unbounded maximum width.
        // Without this, the genre section keeps the FlowPane's old preferred width
        // and can never use enough space to render a third column.
        box.setMaxWidth(Double.MAX_VALUE);
        box.setFillWidth(true);
        box.getChildren().add(sectionTitle(title));
        return box;
    }

    protected FlowPane flowSection(VBox container, String title) {
        FlowPane pane = new FlowPane(12, 12);
        pane.getStyleClass().add("discover-flow");
        bindFlowWrap(pane, container);
        pane.setPadding(new Insets(6, 0, 6, 0));
        VBox section = sectionBlock(title);
        section.getChildren().add(pane);
        container.getChildren().add(section);
        return pane;
    }

    protected FlowPane createContentFlow(VBox screenContainer) {
        FlowPane pane = new FlowPane(DISCOVER_GRID_GAP, DISCOVER_GRID_GAP);
        pane.getStyleClass().addAll("discover-flow", "discover-card-grid");
        pane.setPadding(new Insets(4, 0, 0, 0));
        bindFlowWrap(pane, screenContainer);
        return pane;
    }

    protected FlowPane createGenreGrid(VBox screenContainer) {
        FlowPane pane = new FlowPane(DISCOVER_GRID_GAP, DISCOVER_GRID_GAP);
        pane.getStyleClass().addAll("discover-flow", "discover-genre-grid");
        pane.setPadding(new Insets(4, 0, 0, 0));
        bindFlowWrap(pane, screenContainer);
        bindResponsiveChildWidths(pane, screenContainer, GENRE_GRID_MAX_COLUMNS, GENRE_CARD_MIN_WIDTH, DISCOVER_GRID_GAP);
        return pane;
    }

    private void bindFlowWrap(FlowPane pane, Region screenContainer) {
        if (pane == null || screenContainer == null) return;
        pane.setMaxWidth(Double.MAX_VALUE);
        Runnable update = () -> pane.setPrefWrapLength(Math.max(260, availableContentWidth(screenContainer)));
        screenContainer.widthProperty().addListener((obs, oldValue, newValue) -> update.run());
        Platform.runLater(update);
    }

    private void bindResponsiveChildWidths(FlowPane pane,
                                           Region screenContainer,
                                           int maxColumns,
                                           double minCardWidth,
                                           double gap) {
        if (pane == null || screenContainer == null) return;

        Runnable update = () -> {
            // The parent VBox includes its own padding and the ScrollPane viewport.
            // Use the FlowPane's actual laid-out width whenever it is available so the
            // calculated third card never overflows and forces an unintended second row.
            double laidOutWidth = pane.getWidth();
            double available = laidOutWidth > 0
                    ? Math.max(minCardWidth, availableContentWidth(pane))
                    : Math.max(minCardWidth, availableContentWidth(screenContainer));
            int columns = Math.max(1, Math.min(maxColumns, (int) Math.floor((available + gap) / (minCardWidth + gap))));
            double cardWidth = Math.floor((available - gap * (columns - 1)) / columns);

            for (Node child : pane.getChildren()) {
                if (child instanceof Region region) {
                    region.setMinWidth(cardWidth);
                    region.setPrefWidth(cardWidth);
                    region.setMaxWidth(cardWidth);
                }
            }
        };

        screenContainer.widthProperty().addListener((obs, oldValue, newValue) -> update.run());
        pane.widthProperty().addListener((obs, oldValue, newValue) -> update.run());
        pane.getChildren().addListener((ListChangeListener<Node>) change -> update.run());
        Platform.runLater(() -> {
            update.run();
            // The first pulse establishes the final viewport width for a ScrollPane.
            Platform.runLater(update);
        });
    }

    private double availableContentWidth(Region region) {
        if (region == null) return 0;
        Insets insets = region.getInsets();
        double horizontalInsets = insets == null ? 0 : insets.getLeft() + insets.getRight();
        return Math.max(0, region.getWidth() - horizontalInsets);
    }

    protected Label emptyState(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("discover-empty-state");
        return label;
    }

    protected Image defaultCover() {
        return DiscoverUtils.defaultCover();
    }

    protected JsonObject getJson(String url) {
        try {
            return context.deezer() == null ? null : context.deezer().getJson(url);
        } catch (Exception ignored) {
            return null;
        }
    }

    protected Node probe(VBox container) {
        return container;
    }

    protected boolean matchesFilter(String text, Collection<String> extras, String filter) {
        return DiscoverUtils.matchesFilter(text, extras, filter);
    }


    protected String norm(String filter) {
        return filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
    }

    protected LinkedHashSet<String> extractArtistNamesFromResource(JsonObject obj) {
        return DiscoverUtils.extractArtistNamesFromResource(obj);
    }

    protected LinkedHashSet<Long> extractArtistIdsFromResource(JsonObject obj) {
        return DiscoverUtils.extractArtistIdsFromResource(obj);
    }

    protected LinkedHashSet<String> extractAlbumArtistNamesFromResource(JsonObject obj) {
        return DiscoverUtils.extractAlbumArtistNamesFromResource(obj);
    }

    protected LinkedHashSet<Long> extractAlbumArtistIdsFromResource(JsonObject obj) {
        return DiscoverUtils.extractAlbumArtistIdsFromResource(obj);
    }

    protected List<String> resolveTrackArtistNames(long trackId, JsonObject baseJson) {
        return DiscoverUtils.resolveTrackArtistNames(context.endpoints(), trackId, baseJson);
    }

    protected List<Long> resolveTrackArtistIds(long trackId, JsonObject baseJson) {
        return DiscoverUtils.resolveTrackArtistIds(context.endpoints(), trackId, baseJson);
    }

    protected String resolveTrackCoverUrl(long trackId, JsonObject baseJson) {
        return DiscoverUtils.resolveTrackCoverUrl(context.endpoints(), trackId, baseJson);
    }

}
