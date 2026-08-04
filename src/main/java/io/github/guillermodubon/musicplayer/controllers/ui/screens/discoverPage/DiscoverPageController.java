package io.github.guillermodubon.musicplayer.controllers.ui.screens.discoverPage;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.*;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.common.ScreenRequestScope;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.common.AmbientGradientSupport;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.discoverPage.context.DiscoverPageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.discoverPage.providers.base.BaseDiscoverPagePageSectionProvider;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.discoverPage.providers.common.DiscoverPageSectionRegistry;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.discoverPage.repositories.DiscoverPageDeezerRepository;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.discoverPage.repositories.DiscoverPageMemoryRepository;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.ArtistCardActionManager;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.GenreCardActionManager;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;


public class DiscoverPageController {

    @FXML private BorderPane screenRoot;
    @FXML private VBox rootContainer;
    @FXML private ScrollPane scrollPane;

    private StartUpService svc;
    private MusicCardActionManager musicActions;
    private ArtistCardActionManager artistActions;
    private GenreCardActionManager genreActions;

    private DiscoverPageContext context;
    private DiscoverPageSectionRegistry registry;

    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final ScreenRequestScope requestScope = new ScreenRequestScope();
    private volatile long lastSnapshotTime = 0L;

    @FXML
    private void initialize() {
        applyAmbientBackground();
        Platform.runLater(this::applyAmbientBackground);
    }

    private void applyAmbientBackground() {
        AmbientGradientSupport.applyTopAmbientGradient(screenRoot);
    }

    public void init(StartUpService svc,
                     MusicCardActionManager musicActions,
                     ArtistCardActionManager artistActions,
                     GenreCardActionManager genreActions) {
        this.svc = Objects.requireNonNull(svc, "svc");
        this.musicActions = Objects.requireNonNull(musicActions, "musicActions");
        this.artistActions = Objects.requireNonNull(artistActions, "artistActions");
        this.genreActions = Objects.requireNonNull(genreActions, "genreActions");

        this.context = new DiscoverPageContext(
                svc,
                new DiscoverPageMemoryRepository(svc),
                new DiscoverPageDeezerRepository(),
                io.github.guillermodubon.musicplayer.utils.DeezerEndpoints.defaultDiscoverEndpoints(),
                musicActions,
                artistActions,
                genreActions,
                requestScope
        );

        this.registry = new DiscoverPageSectionRegistry(context);
        initialized.set(true);
        refresh();
    }

    private void refresh() {
        if (!initialized.get() || rootContainer == null || registry == null) return;
        requestScope.restart();
        BaseDiscoverPagePageSectionProvider.beginRenderCycle(rootContainer);
        registry.renderAll(rootContainer);
        Platform.runLater(() -> {
            if (scrollPane != null) scrollPane.setVvalue(0);
        });
    }

    public Map<String, Object> captureState() {
        Map<String, Object> state = new HashMap<>();
        state.put("scrollV", scrollPane == null ? 0.0 : scrollPane.getVvalue());
        state.put("snapshotTime", lastSnapshotTime);
        return state;
    }

    public void restoreState(Map<String, Object> state) {
        if (state == null) return;
        Platform.runLater(() -> {
            try {
                Object vv = state.get("scrollV");
                if (vv instanceof Number n && scrollPane != null) {
                    scrollPane.setVvalue(n.doubleValue());
                }
            } catch (Exception ignored) {}
        });
    }

}
