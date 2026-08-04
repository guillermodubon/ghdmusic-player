package io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.*;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.GenreCard;
import io.github.guillermodubon.musicplayer.controllers.ui.components.icons.SvgIconFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.common.ScreenRequestScope;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.common.GenrePageSharedState;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.context.GenrePageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.context.GenrePageRenderContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.providers.common.GenrePageSectionRegistry;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.repositories.GenrePageDeezerRepository;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.repositories.GenrePageMemoryRepository;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.ArtistCardActionManager;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.utils.DeezerEndpoints;

import java.net.URL;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class GenrePageController {

    private static final String CONNECTION_ERROR_ICON =
            "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/wifi_off_27dp_AFAFAF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String CONNECTION_ERROR_MESSAGE =
            "The screen cannot be loaded. Please check your internet connection and try again.";

    @FXML private StackPane rootPane;
    @FXML private Label titleLabel;
    @FXML private StackPane headerPane;
    @FXML private VBox itemsContainer;
    @FXML private ScrollPane scrollPane;
    @FXML private ProgressIndicator loadingIndicator;

    private StartUpService svc;
    private GenrePageContext context;
    private GenrePageSectionRegistry registry;

    private MusicCardActionManager musicActions;
    private ArtistCardActionManager artistActions;

    private final GenrePageSharedState shared = new GenrePageSharedState();
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicInteger generation = new AtomicInteger(0);
    private final ScreenRequestScope requestScope = new ScreenRequestScope();

    private volatile int genreId = -1;
    private volatile String genreName = "";
    private volatile String lastFilter = "";
    private volatile long lastSnapshotTime = 0L;

    @FXML
    private void initialize() {
        attachStylesheetSafely();
        if (headerPane != null) {
            headerPane.heightProperty().addListener((obs, oldHeight, newHeight) ->
                    updateLoadingIndicatorPosition()
            );
        }
        Platform.runLater(this::updateLoadingIndicatorPosition);
    }

    public void init(StartUpService svc,
                     MusicCardActionManager musicActions,
                     ArtistCardActionManager artistActions,
                     int genreId,
                     String genreName) {

        this.svc = Objects.requireNonNull(svc, "svc");
        this.musicActions = Objects.requireNonNull(musicActions, "musicActions");
        this.artistActions = Objects.requireNonNull(artistActions, "artistActions");
        this.genreId = genreId;
        this.genreName = genreName == null ? "" : genreName;

        this.context = new GenrePageContext(
                svc,
                new GenrePageMemoryRepository(svc),
                new GenrePageDeezerRepository(),
                DeezerEndpoints.defaultGenreDetailsControllerEndpoints(),
                musicActions,
                artistActions,
                requestScope
        );

        this.registry = new GenrePageSectionRegistry(context);
        this.initialized.set(true);

        renderHeader();

        refreshSections("");
    }

    public void refreshSections(String filter) {
        if (!initialized.get()) return;
        if (itemsContainer == null || registry == null || context == null) return;

        this.lastFilter = filter == null ? "" : filter;
        this.lastSnapshotTime = System.currentTimeMillis();

        final int myGen = generation.incrementAndGet();
        requestScope.restart();

        shared.clear();

        GenrePageRenderContext renderContext = new GenrePageRenderContext(
                genreId,
                genreName,
                context,
                shared,
                myGen,
                () -> generation.get() == myGen && requestScope.isActive(),
                () -> hideLoadingIndicator(myGen)
        );

        Platform.runLater(() -> {
            if (generation.get() != myGen) return;

            renderHeader();
            showLoadingIndicator(myGen);

            if (itemsContainer != null) {
                itemsContainer.getChildren().clear();
                itemsContainer.setSpacing(18);
            }

            registry.renderAll(itemsContainer, renderContext,
                    hasSections -> {
                        hideLoadingIndicator(myGen);
                        showConnectionErrorIfEmpty(myGen, hasSections);
                    });

            if (scrollPane != null) {
                scrollPane.setVvalue(0);
            }
        });
    }

    private void showLoadingIndicator(int renderGeneration) {
        if (loadingIndicator == null || generation.get() != renderGeneration) return;
        updateLoadingIndicatorPosition();
        loadingIndicator.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        loadingIndicator.setManaged(true);
        loadingIndicator.setVisible(true);
    }

    private void updateLoadingIndicatorPosition() {
        if (loadingIndicator == null) return;

        // Root center + half the header height = center of the area below it.
        double headerHeight = headerPane == null ? 0.0 : headerPane.getHeight();
        loadingIndicator.setTranslateY(Math.max(0.0, headerHeight / 2.0));
    }

    private void hideLoadingIndicator(int renderGeneration) {
        Platform.runLater(() -> {
            if (loadingIndicator == null
                    || generation.get() != renderGeneration
                    || !requestScope.isActive()) {
                return;
            }
            loadingIndicator.setVisible(false);
            loadingIndicator.setManaged(false);
        });
    }

    public Map<String, Object> captureState() {
        Map<String, Object> state = new HashMap<>();
        state.put("scrollV", scrollPane == null ? 0.0 : scrollPane.getVvalue());
        state.put("snapshotTime", lastSnapshotTime);
        state.put("genreId", genreId);
        state.put("genreName", genreName);
        state.put("filter", lastFilter);
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
            } catch (Exception ignored) {
            }
        });
    }

    private void renderHeader() {
        if (titleLabel != null) {
            titleLabel.setText(genreName == null || genreName.isBlank() ? "Genre" : genreName);
        }

        if (headerPane != null) {
            headerPane.getStyleClass().removeIf(styleClass ->
                    styleClass != null && styleClass.startsWith("genre-mood-"));
            headerPane.getStyleClass().add(GenreCard.moodStyleClassFor(genreId, genreName));
        }
    }

    private void showConnectionErrorIfEmpty(int renderGeneration, boolean hasSections) {
        Platform.runLater(() -> {
            if (hasSections || generation.get() != renderGeneration || !requestScope.isActive()
                    || itemsContainer == null || !itemsContainer.getChildren().isEmpty()) {
                return;
            }

            Node icon = SvgIconFactory.icon(CONNECTION_ERROR_ICON, 38);
            Label message = new Label(CONNECTION_ERROR_MESSAGE);
            message.getStyleClass().add("genre-page-load-error-message");
            message.setWrapText(true);
            message.setMaxWidth(540);

            VBox errorState = new VBox(14, icon, message);
            errorState.getStyleClass().add("genre-page-load-error");
            errorState.setAlignment(Pos.CENTER);
            errorState.setMaxWidth(Double.MAX_VALUE);
            errorState.setMinHeight(availableContentHeight());
            VBox.setVgrow(errorState, Priority.ALWAYS);

            itemsContainer.getChildren().setAll(errorState);
        });
    }

    private double availableContentHeight() {
        double viewportHeight = scrollPane == null ? 0 : scrollPane.getViewportBounds().getHeight();
        double headerHeight = headerPane == null ? 0 : headerPane.getHeight();
        return Math.max(280, viewportHeight - headerHeight - 56);
    }

    private void attachStylesheetSafely() {
        if (rootPane == null) return;
        URL stylesheet = getClass().getResource("/io/github/guillermodubon/musicplayer/Views/screens/genrePage/genre-page.css");
        if (stylesheet == null) return;

        String css = stylesheet.toExternalForm();
        if (!rootPane.getStylesheets().contains(css)) {
            rootPane.getStylesheets().add(css);
        }
    }
}
