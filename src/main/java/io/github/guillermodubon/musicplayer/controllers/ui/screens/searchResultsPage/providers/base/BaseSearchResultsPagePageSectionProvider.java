package io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.providers.base;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.common.SectionCarouselFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.SearchResultsPageController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.context.SearchResultsPageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.context.SearchResultsPageRenderContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.services.SearchResultsService;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

public abstract class BaseSearchResultsPagePageSectionProvider implements SearchResultsPageSectionProvider {
    protected static final int MAX_CARDS_PER_SECTION = 18;
    private static final int CARD_RENDER_BATCH_SIZE = 4;

    private static final ExecutorService SECTION_EXECUTOR = Executors.newFixedThreadPool(5, runnable -> {
        Thread thread = new Thread(runnable, "search-results-section");
        thread.setDaemon(true);
        return thread;
    });

    protected final SearchResultsPageContext context;
    protected final SearchResultsService service;
    protected final SearchResultsPageController.UiBindings ui;
    protected final VBox sectionSlot = new VBox(10);

    protected BaseSearchResultsPagePageSectionProvider(SearchResultsPageContext context,
                                                       SearchResultsPageController.UiBindings ui) {
        this.context = Objects.requireNonNull(context, "context");
        this.ui = Objects.requireNonNull(ui, "ui");
        this.service = new SearchResultsService(context);
        this.sectionSlot.getStyleClass().addAll("app-section", "search-results-section");
        this.sectionSlot.setManaged(false);
        this.sectionSlot.setVisible(false);
    }

    protected void prepareSectionSlot() {
        Runnable reset = () -> {
            if (ui.contentBox() == null) return;
            clearSectionChildren();
            sectionSlot.setManaged(false);
            sectionSlot.setVisible(false);
            if (!ui.contentBox().getChildren().contains(sectionSlot)) {
                ui.contentBox().getChildren().add(sectionSlot);
            }
        };

        if (Platform.isFxApplicationThread()) reset.run();
        else Platform.runLater(reset);
    }

    protected <T> void loadAsync(SearchResultsPageRenderContext rc,
                                 ThrowingSupplier<T> loader,
                                 Consumer<T> renderer) {
        java.util.concurrent.Callable<LoadOutcome<T>> task = () -> {
            if (!isCurrent(rc)) return new LoadOutcome<>(null, false);
            try {
                return new LoadOutcome<>(loader.get(), false);
            } catch (Exception ignored) {
                return new LoadOutcome<>(null, true);
            }
        };

        CompletableFuture<LoadOutcome<T>> future = context.requestScope() == null
                ? CompletableFuture.supplyAsync(() -> {
                    try {
                        return task.call();
                    } catch (Exception ex) {
                        return new LoadOutcome<T>(null, true);
                    }
                }, SECTION_EXECUTOR)
                : context.requestScope().supplyAsync(task, SECTION_EXECUTOR);

        future.thenAccept(outcome -> Platform.runLater(() -> {
            if (!isCurrent(rc) || outcome == null) return;

            boolean hasResults = hasItems(outcome.result());
            try {
                if (!outcome.failed() && outcome.result() != null) {
                    renderer.accept(outcome.result());
                }
            } catch (Exception ignored) {
                hideSection();
                hasResults = false;
            } finally {
                if (rc.loadTracker() != null) {
                    rc.loadTracker().record(
                            isRemoteSection(),
                            hasResults,
                            outcome.failed()
                    );
                }
            }
        }));
    }

    protected boolean isRemoteSection() {
        return true;
    }


    /**
     * Builds cards across a few UI pulses. This preserves the existing card factory while
     * allowing the heading and the first cards to appear before a large section is complete.
     */
    protected <T> void showSectionBatched(SearchResultsPageRenderContext rc,
                                          String title,
                                          List<T> results,
                                          Function<T, ? extends Node> cardFactory) {
        if (!isCurrent(rc) || results == null || results.isEmpty() || cardFactory == null) {
            hideSection();
            return;
        }

        Label titleLabel = new Label(title == null ? "" : title);
        titleLabel.getStyleClass().addAll("app-section-title", "search-results-section-title");

        FlowPane pane = new FlowPane(16, 24);
        pane.getStyleClass().addAll("app-card-grid", "search-results-card-grid");
        pane.setMaxWidth(Double.MAX_VALUE);
        if (ui.contentBox() != null) {
            pane.prefWrapLengthProperty().bind(Bindings.max(260, ui.contentBox().widthProperty().subtract(56)));
        } else {
            pane.setPrefWrapLength(980);
        }

        sectionSlot.getChildren().setAll(titleLabel, pane);
        sectionSlot.setManaged(true);
        sectionSlot.setVisible(true);
        appendCardBatch(rc, pane, List.copyOf(results), cardFactory, 0);
    }

    protected void showCarouselSection(SearchResultsPageRenderContext rc, String title, List<? extends Node> cards) {
        if (!isCurrent(rc) || cards == null || cards.isEmpty()) {
            hideSection();
            return;
        }

        Label titleLabel = new Label(title == null ? "" : title);
        titleLabel.getStyleClass().addAll("app-section-title", "search-results-section-title");

        sectionSlot.getChildren().setAll(titleLabel, SectionCarouselFactory.createMusicCarousel(cards));
        sectionSlot.setManaged(true);
        sectionSlot.setVisible(true);
    }

    protected void hideSection() {
        clearSectionChildren();
        sectionSlot.setManaged(false);
        sectionSlot.setVisible(false);
    }

    private <T> void appendCardBatch(SearchResultsPageRenderContext rc,
                                     FlowPane pane,
                                     List<T> results,
                                     Function<T, ? extends Node> cardFactory,
                                     int startIndex) {
        if (!isCurrent(rc) || pane == null) return;

        int endIndex = Math.min(results.size(), startIndex + CARD_RENDER_BATCH_SIZE);
        for (int index = startIndex; index < endIndex; index++) {
            try {
                Node card = cardFactory.apply(results.get(index));
                if (card != null) pane.getChildren().add(card);
            } catch (Exception ignored) {
            }
        }

        if (endIndex < results.size()) {
            Platform.runLater(() -> appendCardBatch(rc, pane, results, cardFactory, endIndex));
        } else if (pane.getChildren().isEmpty()) {
            hideSection();
        }
    }

    private void clearSectionChildren() {
        for (Node child : sectionSlot.getChildren()) {
            if (child instanceof FlowPane flowPane && flowPane.prefWrapLengthProperty().isBound()) {
                flowPane.prefWrapLengthProperty().unbind();
            }
            if (child instanceof Region region && region.prefWidthProperty().isBound()) {
                region.prefWidthProperty().unbind();
            }
        }
        sectionSlot.getChildren().clear();
    }

    protected boolean isCurrent(SearchResultsPageRenderContext rc) {
        return rc != null && rc.isCurrent();
    }

    private boolean hasItems(Object value) {
        return value instanceof java.util.Collection<?> collection && !collection.isEmpty();
    }

    private record LoadOutcome<T>(T result, boolean failed) {
    }

    @FunctionalInterface
    protected interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
