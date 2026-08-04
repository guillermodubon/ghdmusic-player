package io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage;

import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.context.HomePageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.common.ScreenRequestScope;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.common.AmbientGradientSupport;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.providers.common.HomePageSectionRegistry;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.repositories.MainMenuDeezerRepository;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.repositories.MainMenuMemoryRepository;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.libraryCatalogListScreens.common.CatalogType;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.SideBarNavigationMenu;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.ArtistCardActionManager;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.models.*;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.utils.DeezerEndpoints;
import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import javafx.util.Duration;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import java.io.File;

public class HomePageController {

    @FXML private VBox sectionContainer;
    @FXML private ScrollPane scrollPane;

    private StartUpService svc;
    private HomePageContext context;
    private HomePageSectionRegistry registry;

    private MusicCardActionManager musicActions;
    private ArtistCardActionManager artistActions;

    private BiConsumer<DeezerApiMetaData, File> downloadListener;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicLong renderVersion = new AtomicLong(0L);
    private final ScreenRequestScope requestScope = new ScreenRequestScope();
    private final PauseTransition filterRefreshDelay = new PauseTransition(Duration.millis(160));
    private final PauseTransition libraryRefreshDelay = new PauseTransition(Duration.millis(220));
    private String lastRenderedFilter = null;
    private String activeFilter = "";
    private double pendingScrollRestore = Double.NaN;
    private double pendingViewportWidth = -1;
    private boolean responsiveUpdateQueued;
    private boolean responsiveLayoutQueued;

    @FXML
    private void initialize() {
        if (scrollPane == null || sectionContainer == null) return;

        if (!scrollPane.getStyleClass().contains("screen-root")) {
            scrollPane.getStyleClass().add("screen-root");
        }

        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setPannable(true);
        sectionContainer.setMinWidth(0);
        sectionContainer.setMaxWidth(Double.MAX_VALUE);
        sectionContainer.setFillWidth(true);
        applyTopAmbientBackground();

        scrollPane.viewportBoundsProperty().addListener((obs, oldBounds, bounds) -> {
            if (bounds == null) return;
            scheduleResponsiveMetrics(bounds.getWidth());
        });

        Platform.runLater(() -> {
            // Apply it after the stylesheet has been processed. This keeps
            // the controller-owned background from being replaced during the
            // first CSS/layout pass.
            applyTopAmbientBackground();
            if (scrollPane.getViewportBounds() != null) {
                scheduleResponsiveMetrics(scrollPane.getViewportBounds().getWidth());
            }
        });
    }

    private void applyTopAmbientBackground() {
        AmbientGradientSupport.applyTopAmbientGradient(sectionContainer);
    }

    public void init(StartUpService svc,
                     MusicCardActionManager musicActions,
                     ArtistCardActionManager artistActions) {

        this.svc = Objects.requireNonNull(svc, "svc");
        this.musicActions = Objects.requireNonNull(musicActions, "musicActions");
        this.artistActions = Objects.requireNonNull(artistActions, "artistActions");

        this.context = new HomePageContext(
                svc,
                new MainMenuMemoryRepository(svc),
                new MainMenuDeezerRepository(),
                DeezerEndpoints.defaultMainMenuEndpoints(),
                musicActions,
                artistActions,
                renderVersion,
                requestScope,
                this::openLibraryCatalog
        );

        this.registry = new HomePageSectionRegistry(context);

        activate();

        renderSectionsNow("", true);
    }

    public void refreshSections(String filter) {
        if (!initialized.get()) return;
        String safeFilter = filter == null ? "" : filter;
        filterRefreshDelay.stop();
        filterRefreshDelay.setOnFinished(event -> renderSectionsNow(safeFilter, false));
        filterRefreshDelay.playFromStart();
    }

    private void renderSectionsNow(String filter, boolean force) {
        if (!initialized.get()) return;
        if (sectionContainer == null || registry == null) return;

        final String safeFilter = filter == null ? "" : filter;
        if (!force && safeFilter.equals(lastRenderedFilter)) return;
        lastRenderedFilter = safeFilter;
        activeFilter = safeFilter;
        final long renderId = renderVersion.incrementAndGet();
        requestScope.restart();

        Runnable renderTask = () -> {
            if (!isCurrentRender(renderId)) return;
            registry.renderAll(sectionContainer, safeFilter, renderId,
                    summary -> completeRender(summary, renderId));
        };

        if (Platform.isFxApplicationThread()) {
            renderTask.run();
        } else {
            Platform.runLater(renderTask);
        }
    }

    private boolean isCurrentRender(long renderId) {
        return initialized.get()
                && requestScope.isActive()
                && renderVersion.get() == renderId;
    }

    private void completeRender(HomePageSectionRegistry.RenderSummary summary, long renderId) {
        if (!isCurrentRender(renderId)) return;
        showDiscoveryPromptWhenNeeded(summary, renderId);
        restorePendingScroll(renderId);
        scheduleResponsiveLayout();
    }

    private void restorePendingScroll(long renderId) {
        if (scrollPane == null || Double.isNaN(pendingScrollRestore) || !isCurrentRender(renderId)) return;
        double target = Math.max(0, Math.min(1, pendingScrollRestore));
        pendingScrollRestore = Double.NaN;
        Platform.runLater(() -> {
            if (isCurrentRender(renderId) && scrollPane != null) {
                scrollPane.applyCss();
                scrollPane.layout();
                scrollPane.setVvalue(target);
            }
        });
    }

    private void scheduleResponsiveMetrics(double viewportWidth) {
        pendingViewportWidth = Math.max(0, viewportWidth);
        if (responsiveUpdateQueued) return;
        responsiveUpdateQueued = true;
        Platform.runLater(() -> {
            responsiveUpdateQueued = false;
            applyResponsiveMetrics(pendingViewportWidth);
        });
    }

    private void applyResponsiveMetrics(double viewportWidth) {
        if (sectionContainer == null) return;

        double horizontalPadding;
        double verticalPadding;
        double spacing;

        if (viewportWidth < 640) {
            horizontalPadding = 12;
            verticalPadding = 16;
            spacing = 10;
        } else if (viewportWidth < 1040) {
            horizontalPadding = 20;
            verticalPadding = 20;
            spacing = 12;
        } else {
            horizontalPadding = 28;
            verticalPadding = 26;
            spacing = 14;
        }

        sectionContainer.setPadding(new Insets(verticalPadding, horizontalPadding, 30, horizontalPadding));
        sectionContainer.setSpacing(spacing);
        if (scrollPane != null && scrollPane.getViewportBounds() != null) {
            sectionContainer.setMinHeight(Math.max(0, scrollPane.getViewportBounds().getHeight()));
        }

        // Force the content hierarchy to recalculate after a viewport change.
        // This is important while a carousel is publishing cards progressively:
        // its parent must receive the new height before the next section is laid
        // out, otherwise the following section can be painted over it.
        sectionContainer.requestLayout();
        if (scrollPane != null) scrollPane.requestLayout();
        scheduleResponsiveLayout();
    }

    private void scheduleResponsiveLayout() {
        if (responsiveLayoutQueued) return;
        responsiveLayoutQueued = true;
        Platform.runLater(() -> {
            responsiveLayoutQueued = false;
            if (sectionContainer == null) return;
            layoutSectionHierarchy();

            // Carousel cards update their measured width during this layout.
            // A second pulse lets the VBox consume the resulting height before
            // JavaFX paints the section below it.
            Platform.runLater(this::layoutSectionHierarchy);
        });
    }

    private void layoutSectionHierarchy() {
        if (sectionContainer == null) return;
        sectionContainer.applyCss();
        sectionContainer.requestLayout();
        sectionContainer.layout();
        if (scrollPane != null) {
            scrollPane.requestLayout();
            scrollPane.layout();
        }
    }

    private void showDiscoveryPromptWhenNeeded(HomePageSectionRegistry.RenderSummary summary, long renderId) {
        if (!isCurrentRender(renderId) || summary == null) return;
        if (summary.hasSections() && !summary.hasOnlyDiscoveryPromptSections()) return;

        VBox prompt = createDiscoveryPrompt();
        if (!summary.hasSections()) {
            VBox.setVgrow(prompt, Priority.ALWAYS);
            prompt.setMinHeight(Math.max(260, scrollPane == null || scrollPane.getViewportBounds() == null
                    ? 420
                    : scrollPane.getViewportBounds().getHeight() - 60));
        }
        sectionContainer.getChildren().add(prompt);
    }

    private VBox createDiscoveryPrompt() {
        Hyperlink discoverLink = new Hyperlink("Discover Music");
        discoverLink.getStyleClass().add("home-discovery-prompt-link");
        discoverLink.setFocusTraversable(false);
        discoverLink.setOnAction(event -> openDiscover());

        Label titleSuffix = new Label("or try searching what you like to get started");
        titleSuffix.getStyleClass().add("home-discovery-prompt-title");

        FlowPane title = new FlowPane(8, 4);
        title.getStyleClass().add("home-discovery-prompt-title-row");
        title.setAlignment(Pos.CENTER);
        title.setPrefWrapLength(760);
        title.getChildren().setAll(discoverLink, titleSuffix);

        Label subtitle = new Label("Start downloading music to help you build a feed you'll love");
        subtitle.getStyleClass().add("home-discovery-prompt-subtitle");
        subtitle.setWrapText(true);
        subtitle.setMaxWidth(720);

        VBox prompt = new VBox(14, title, subtitle);
        prompt.getStyleClass().add("home-discovery-prompt");
        prompt.setAlignment(Pos.CENTER);
        prompt.setMaxWidth(Double.MAX_VALUE);
        return prompt;
    }

    private void openDiscover() {
        if (svc == null) return;
        SideBarNavigationMenu navigationMenu = svc.getLeftMenuController();
        if (navigationMenu != null) navigationMenu.openDiscover();
    }

    public Map<String, Object> captureState() {
        Map<String, Object> state = new HashMap<>();
        state.put("scrollV", scrollPane == null ? 0.0 : scrollPane.getVvalue());
        state.put("filter", activeFilter);
        return state;
    }

    public void restoreState(Map<String, Object> state) {
        Platform.runLater(() -> {
            try {
                activate();
                Object vv = state == null ? null : state.get("scrollV");
                if (vv instanceof Number n && scrollPane != null) {
                    pendingScrollRestore = n.doubleValue();
                }
                Object filter = state == null ? null : state.get("filter");
                String restoredFilter = filter instanceof String text ? text : activeFilter;
                renderSectionsNow(restoredFilter, true);
            } catch (Exception ignored) {
            }
        });
    }


    private void activate() {
        if (svc == null || registry == null) return;
        initialized.set(true);
        svc.setMainMenuController(this);
        registerDownloadListener();
    }

    private void registerDownloadListener() {
        if (svc == null || downloadListener != null) return;

        downloadListener = (meta, file) -> Platform.runLater(this::scheduleLibraryRefresh);

        try {
            svc.addDownloadListener(downloadListener);
        } catch (Exception ignored) {
            downloadListener = null;
        }
    }


    private void scheduleLibraryRefresh() {
        if (!initialized.get()) return;
        libraryRefreshDelay.stop();
        libraryRefreshDelay.setOnFinished(event -> {
            if (!initialized.get()) return;
            try {
                if (context != null && context.memory() != null) {
                    context.memory().rebuildSnapshots();
                }
                renderSectionsNow(activeFilter, true);
            } catch (Exception ignored) {
            }
        });
        libraryRefreshDelay.playFromStart();
    }

    private void openLibraryCatalog(CatalogType type) {
        if (svc == null || type == null) return;
        SideBarNavigationMenu navigationMenu = svc.getLeftMenuController();
        if (navigationMenu == null) return;
        navigationMenu.openCatalog(type);
    }
}
