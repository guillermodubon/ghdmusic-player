package io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.helpers;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import io.github.guillermodubon.musicplayer.controllers.ui.components.icons.SvgIconFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.context.ArtistPageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.context.ArtistPageRenderContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.context.ArtistPageSharedState;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.providers.common.ArtistPageSectionRegistry;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.common.ScreenRequestScope;
import io.github.guillermodubon.musicplayer.models.Artist;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static io.github.guillermodubon.musicplayer.utils.ArtistPageUiHelpers.setVisible;

/** Coordinates provider rendering, retry state and section-level error UI. */
public final class ArtistPageSectionCoordinator {

    private static final int MAX_EMPTY_REMOTE_RETRIES = 4;
    private static final Duration EMPTY_REMOTE_RETRY_INTERVAL = Duration.seconds(1.5);
    private static final String CONNECTION_ERROR_ICON =
            "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/wifi_off_27dp_AFAFAF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String FULL_LOAD_ERROR =
            "The screen cannot be loaded. Please check your internet connection and try again.";
    private static final String REMOTE_LOAD_ERROR =
            "The rest of the artist's sections could not be loaded. Please check your internet connection and try again.";
    private static final String LOAD_ERROR_KEY = "artistPageLoadError";

    private final ArtistPageViewBindings view;
    private final ScreenRequestScope requestScope;
    private final Supplier<Artist> artistSupplier;
    private final Supplier<Boolean> alive;
    private final ArtistPageContext context;
    private final ArtistPageSharedState sharedState;
    private final ArtistPageSectionRegistry registry;
    private final AtomicInteger generation = new AtomicInteger(0);

    private PauseTransition emptyRemoteRetryDelay;
    private int emptyRemoteRetryCount;

    public ArtistPageSectionCoordinator(
            ArtistPageViewBindings view,
            ScreenRequestScope requestScope,
            Supplier<Artist> artistSupplier,
            Supplier<Boolean> alive,
            ArtistPageContext context,
            ArtistPageSharedState sharedState,
            ArtistPageSectionRegistry registry
    ) {
        this.view = view;
        this.requestScope = requestScope;
        this.artistSupplier = artistSupplier;
        this.alive = alive;
        this.context = context;
        this.sharedState = sharedState;
        this.registry = registry;
    }

    public void resetTitlesAndVisibility() {
        clearSectionLoadError();
        clearSection(view.localTitle(), view.localCarouselHost());
        clearSection(view.localTitle(), view.localFlow());
        clearSection(view.topTracksTitle(), view.topTracksFlow());
        clearSection(view.albumsTitle(), view.albumsFlow());
        clearSection(view.singlesTitle(), view.singlesFlow());
        clearSection(view.playlistsTitle(), view.playlistsFlow());
    }

    public void renderSections(boolean resetRetryCounter) throws IOException {
        if (!isAlive() || context == null || registry == null) {
            return;
        }
        if (resetRetryCounter) {
            emptyRemoteRetryCount = 0;
        }

        int currentGeneration = generation.incrementAndGet();
        requestScope.restart();
        sharedState.setGeneration(currentGeneration);
        Artist currentArtist = artistSupplier.get();
        if (currentArtist == null) {
            return;
        }
        ArtistPageRenderContext renderContext = new ArtistPageRenderContext(
                currentArtist,
                context,
                sharedState,
                currentGeneration,
                this::isAlive
        );

        registry.renderAll(renderContext);
        scheduleEmptyRemoteRetry(currentGeneration);
    }

    public void clearAndInvalidate() {
        stopEmptyRemoteRetry();
        if (sharedState != null) {
            sharedState.setGeneration(Integer.MIN_VALUE);
        }
        if (registry != null) {
            try {
                registry.dispose();
            } catch (Exception ignored) {
            }
        }
    }

    public boolean hasAnySectionCards() {
        return hasLibrarySectionCards() || hasRemoteSectionCards();
    }

    private void scheduleEmptyRemoteRetry(int currentGeneration) {
        if (artistSupplier.get() == null) {
            return;
        }

        stopEmptyRemoteRetry();
        ArtistPageSharedState expectedState = sharedState;
        PauseTransition retryDelay = new PauseTransition(EMPTY_REMOTE_RETRY_INTERVAL);
        emptyRemoteRetryDelay = retryDelay;
        retryDelay.setOnFinished(event -> {
            if (emptyRemoteRetryDelay != retryDelay) {
                return;
            }
            if (!isAlive()
                    || sharedState != expectedState
                    || !expectedState.isCurrent(currentGeneration)) {
                return;
            }
            if (hasRemoteSectionCards()) {
                clearSectionLoadError();
                return;
            }

            emptyRemoteRetryCount++;
            if (emptyRemoteRetryCount >= MAX_EMPTY_REMOTE_RETRIES) {
                showSectionLoadError(false);
                return;
            }

            showRetryIndicator();
            scheduleEmptyRemoteRetry(currentGeneration);
        });
        retryDelay.play();
    }

    private void stopEmptyRemoteRetry() {
        if (emptyRemoteRetryDelay != null) {
            emptyRemoteRetryDelay.stop();
            emptyRemoteRetryDelay = null;
        }
    }

    private void showSectionLoadError(boolean forceRefresh) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> showSectionLoadError(forceRefresh));
            return;
        }
        if (view.mainContent() == null || hasRemoteSectionCards()) {
            return;
        }

        boolean libraryLoaded = hasLibrarySectionCards();
        clearSectionLoadError();
        Node icon = SvgIconFactory.icon(CONNECTION_ERROR_ICON, 38);
        Label message = new Label(libraryLoaded ? REMOTE_LOAD_ERROR : FULL_LOAD_ERROR);
        message.getStyleClass().add("artist-page-load-error-message");
        message.setWrapText(true);
        message.setMaxWidth(620);

        VBox error = new VBox(14, icon, message);
        error.getStyleClass().add("artist-page-load-error");
        error.setAlignment(Pos.CENTER);
        error.setMaxWidth(Double.MAX_VALUE);
        double viewportHeight = view.artistScrollPane() == null
                ? 0
                : view.artistScrollPane().getViewportBounds().getHeight();
        error.setMinHeight(libraryLoaded ? 118 : Math.max(260, viewportHeight - 430));
        error.getProperties().put(LOAD_ERROR_KEY, Boolean.TRUE);
        addTransientSection(error, libraryLoaded);
        view.mainContent().getProperties().put(LOAD_ERROR_KEY, error);
    }

    private void showRetryIndicator() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::showRetryIndicator);
            return;
        }
        if (view.mainContent() == null) {
            return;
        }

        clearSectionLoadError();
        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setPrefSize(28, 28);
        indicator.setMaxSize(28, 28);

        VBox loading = new VBox(indicator);
        loading.getStyleClass().add("artist-page-section-retry");
        loading.setAlignment(Pos.CENTER);
        loading.setMaxWidth(Double.MAX_VALUE);
        loading.setMinHeight(hasLibrarySectionCards() ? 54 : 140);
        loading.getProperties().put(LOAD_ERROR_KEY, Boolean.TRUE);
        addTransientSection(loading, hasLibrarySectionCards());
        view.mainContent().getProperties().put(LOAD_ERROR_KEY, loading);
    }

    private void clearSectionLoadError() {
        if (view.mainContent() == null) {
            return;
        }
        Object state = view.mainContent().getProperties().remove(LOAD_ERROR_KEY);
        if (state instanceof Node node) {
            view.mainContent().getChildren().remove(node);
        }
    }

    private void addTransientSection(Node node, boolean afterLibrarySection) {
        if (view.mainContent() == null || node == null) {
            return;
        }
        int index = afterLibrarySection && view.mainContent().getChildren().size() > 1
                ? 1
                : view.mainContent().getChildren().size();
        view.mainContent().getChildren().add(index, node);
    }

    private void clearSection(Label title, Pane content) {
        if (content != null) {
            content.getChildren().clear();
            setVisible(title, content, false);
        }
    }

    private boolean hasLibrarySectionCards() {
        return hasChildren(view.localCarouselHost()) || hasChildren(view.localFlow());
    }

    private boolean hasRemoteSectionCards() {
        return hasChildren(view.topTracksFlow())
                || hasChildren(view.albumsFlow())
                || hasChildren(view.singlesFlow())
                || hasChildren(view.playlistsFlow());
    }

    private boolean hasChildren(Pane pane) {
        return pane != null
                && pane.getChildren() != null
                && !pane.getChildren().isEmpty();
    }

    private boolean isAlive() {
        return alive != null && Boolean.TRUE.equals(alive.get());
    }

}
