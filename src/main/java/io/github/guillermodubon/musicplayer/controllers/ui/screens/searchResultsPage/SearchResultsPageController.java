package io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage;

import javafx.fxml.FXML;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import io.github.guillermodubon.musicplayer.controllers.ui.components.icons.SvgIconFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.common.ScreenRequestScope;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.context.SearchResultsPageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.context.SearchResultsPageLoadTracker;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.context.SearchResultsPageRenderContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.context.SearchResultsPageSharedState;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.providers.common.SearchResultsPageSectionRegistry;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.repositories.SearchResultsPageDeezerRepository;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.repositories.SearchResultsPageMemoryRepository;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.ArtistCardActionManager;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.utils.DeezerEndpoints;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class SearchResultsPageController {

    private static final String WIFI_OFF_ICON = "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/wifi_off_27dp_AFAFAF_FILL0_wght400_GRAD0_opsz24.svg";

    @FXML private Label titleLabel;
    @FXML private VBox contentBox;
    @FXML private ScrollPane scrollPane;
    @FXML private VBox feedbackBox;
    @FXML private StackPane feedbackIconHost;
    @FXML private Label feedbackLabel;

    private StartUpService svc;
    private MusicCardActionManager musicActions;
    private ArtistCardActionManager artistActions;

    private SearchResultsPageContext context;
    private SearchResultsPageSharedState sharedState;
    private SearchResultsPageRenderContext renderContext;
    private SearchResultsPageSectionRegistry registry;

    private final AtomicInteger generation = new AtomicInteger(0);
    private volatile boolean alive = false;
    private final ScreenRequestScope requestScope = new ScreenRequestScope();

    public void init(StartUpService svc,
                     MusicCardActionManager musicActions,
                     ArtistCardActionManager artistActions) {
        this.svc = Objects.requireNonNull(svc, "svc");
        this.musicActions = Objects.requireNonNull(musicActions, "musicActions");
        this.artistActions = Objects.requireNonNull(artistActions, "artistActions");

        DeezerEndpoints.SearchResultsEndpoints endpoints = DeezerEndpoints.defaultSearchResultsEndpoints();

        this.context = new SearchResultsPageContext(
                svc,
                new SearchResultsPageMemoryRepository(),
                new SearchResultsPageDeezerRepository(endpoints),
                endpoints,
                musicActions,
                artistActions,
                requestScope
        );

        this.sharedState = new SearchResultsPageSharedState();
        this.registry = new SearchResultsPageSectionRegistry(
                context,
                new UiBindings(titleLabel, contentBox, scrollPane)
        );
        this.alive = true;
    }

    public void initAndSearch(StartUpService svc,
                              MusicCardActionManager musicActions,
                              ArtistCardActionManager artistActions,
                              String query) throws IOException {
        boolean needInit = this.svc == null || this.svc != svc || registry == null;
        if (needInit) {
            init(svc, musicActions, artistActions);
        }
        search(query);
    }

    public void search(String query) throws IOException {
        if (!alive || registry == null || sharedState == null || context == null) return;

        final String q = query == null ? "" : query.trim();
        requestScope.restart();

        sharedState.clearTransient();
        int runId = sharedState.searchRunId().get();
        generation.set(runId);
        SearchResultsPageLoadTracker loadTracker = new SearchResultsPageLoadTracker(
                registry.providerCount(),
                summary -> handleLoadSummary(runId, q, summary)
        );
        this.renderContext = new SearchResultsPageRenderContext(
                q,
                context,
                sharedState,
                runId,
                () -> alive,
                loadTracker
        );

        resetUiForSearch(q);

        registry.renderAll(renderContext);
    }

    private void resetUiForSearch(String query) {
        Runnable reset = () -> {
            if (titleLabel != null) titleLabel.setText("Search results for \"" + query + "\"");
            if (contentBox != null) contentBox.getChildren().clear();
            if (scrollPane != null) scrollPane.setVvalue(0.0);
            hideFeedback();
        };

        if (Platform.isFxApplicationThread()) reset.run();
        else Platform.runLater(reset);
    }

    private void handleLoadSummary(int runId,
                                   String query,
                                   SearchResultsPageLoadTracker.Summary summary) {
        Runnable update = () -> {
            if (!alive || sharedState == null || sharedState.searchRunId().get() != runId || summary == null) {
                return;
            }

            if (summary.apiUnavailable() && !summary.hasResults()) {
                showFeedback(
                        "An error occurred while displaying the search results. Please check your internet connection and try again.",
                        true
                );
            } else if (!summary.hasResults()) {
                showFeedback("No results found for \"" + query + "\"", false);
            }
        };

        if (Platform.isFxApplicationThread()) update.run();
        else Platform.runLater(update);
    }

    private void showFeedback(String message, boolean showNetworkIcon) {
        if (feedbackLabel != null) feedbackLabel.setText(message == null ? "" : message);
        if (feedbackIconHost != null) {
            if (showNetworkIcon) {
                Node icon = SvgIconFactory.icon(WIFI_OFF_ICON, 34);
                SvgIconFactory.setIconColor(icon, "#AFAFAF");
                feedbackIconHost.getChildren().setAll(icon);
                feedbackIconHost.setManaged(true);
                feedbackIconHost.setVisible(true);
            } else {
                feedbackIconHost.getChildren().clear();
                feedbackIconHost.setManaged(false);
                feedbackIconHost.setVisible(false);
            }
        }
        if (feedbackBox != null) {
            feedbackBox.setManaged(true);
            feedbackBox.setVisible(true);
        }
    }

    private void hideFeedback() {
        if (feedbackBox != null) {
            feedbackBox.setManaged(false);
            feedbackBox.setVisible(false);
        }
        if (feedbackIconHost != null) {
            feedbackIconHost.getChildren().clear();
            feedbackIconHost.setManaged(false);
            feedbackIconHost.setVisible(false);
        }
    }

    public Map<String, Object> captureState() {
        Map<String, Object> state = new HashMap<>();
        state.put("snapshotTime", System.nanoTime());
        state.put("query", renderContext == null ? null : renderContext.query());
        state.put("scrollVvalue", scrollPane == null ? 0.0 : scrollPane.getVvalue());
        return state;
    }

    public void restoreState(Map<String, Object> state) throws IOException {
        if (state == null) return;

        Object q = state.get("query");
        String query = q instanceof String s ? s : "";
        if (!query.isBlank()) {
            search(query);
        }

        Object scroll = state.get("scrollVvalue");
        if (scroll instanceof Number n && scrollPane != null) {
            scrollPane.setVvalue(n.doubleValue());
        }

        if (titleLabel != null) {
            titleLabel.setText("Search results for \"" + query + "\"");
        }
    }

    public Parent getRoot() {
        return contentBox == null ? null : contentBox.getParent();
    }

    public record UiBindings(Label titleLabel, VBox contentBox, ScrollPane scrollPane) {}
}
