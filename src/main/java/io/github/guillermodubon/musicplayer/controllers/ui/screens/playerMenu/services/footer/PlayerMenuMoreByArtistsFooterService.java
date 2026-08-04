package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.footer;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext.ContentType;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.managers.PlayerMenuArtistResolver;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.footer.PlayerMenuMoreByArtistsModels.FOOTER_RUN_ID_KEY;
import static io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.footer.PlayerMenuMoreByArtistsModels.LAZY_LOADED_KEY;
import static io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.footer.PlayerMenuMoreByArtistsModels.LAZY_LOADER_KEY;
import static io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.footer.PlayerMenuMoreByArtistsModels.REMOTE_ARTIST_CACHE_KEY;
import static io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.footer.PlayerMenuMoreByArtistsModels.REMOTE_FETCH_STARTED_KEY;
import static io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.footer.PlayerMenuMoreByArtistsModels.REMOTE_RETRY_COUNT_KEY;
import static io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.footer.PlayerMenuMoreByArtistsModels.REMOTE_RETRY_SCHEDULED_KEY;
import static io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.footer.PlayerMenuMoreByArtistsModels.FooterCardSpec;
import static io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.footer.PlayerMenuMoreByArtistsModels.RemoteFetchPair;

/** Coordinates the lifecycle and asynchronous loading of the more-by-artists footer. */
public class PlayerMenuMoreByArtistsFooterService {

    private static final int EAGER_ARTIST_SECTION_LIMIT = 5;
    private static final int MAX_SECTION_RETRY_ATTEMPTS = 3;
    private static final long SECTION_RETRY_BASE_DELAY_MS = 850L;
    private static final double LAZY_PRELOAD_MARGIN = 520.0;

    private final PlayerMenuMoreByArtistsDataProvider dataProvider;
    private final PlayerMenuMoreByArtistsRenderer renderer;
    private final ScheduledExecutorService retryScheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "player-menu-footer-retry");
                thread.setDaemon(true);
                return thread;
            });

    private StartUpService service;
    private MusicCardActionManager musicActions;
    private VBox moreByArtistsContainer;
    private VBox footerPane;
    private ScrollPane playerMenuScroll;
    private ChangeListener<Number> lazyScrollListener;
    private ChangeListener<Bounds> lazyViewportListener;
    private final AtomicBoolean lazyEvaluationQueued = new AtomicBoolean(false);

    public PlayerMenuMoreByArtistsFooterService(
            PlayerMenuContext context,
            StartUpService service,
            MusicCardActionManager musicActions,
            PlayerMenuArtistResolver artistResolver
    ) {
        this.service = service;
        this.musicActions = musicActions;
        this.dataProvider = new PlayerMenuMoreByArtistsDataProvider(artistResolver);
        this.renderer = new PlayerMenuMoreByArtistsRenderer(musicActions);
    }

    public void bindServices(StartUpService service, MusicCardActionManager musicActions) {
        this.service = service;
        this.musicActions = musicActions;
        renderer.bindActions(musicActions);
    }

    public void bindUi(VBox moreByArtistsContainer,
                       VBox footerPane,
                       ScrollPane playerMenuScroll) {
        this.moreByArtistsContainer = moreByArtistsContainer;
        this.footerPane = footerPane;
        this.playerMenuScroll = playerMenuScroll;
    }

    public void refreshForView(ContentType type, Playlist playlist) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> refreshForView(type, playlist));
            return;
        }

        if (isLocalSongWithoutMetadata(playlist, type)) {
            hideMoreByArtistsFooter();
            if (footerPane != null) {
                footerPane.setVisible(false);
                footerPane.setManaged(false);
            }
            return;
        }

        boolean supported = type == ContentType.SINGLE
                || type == ContentType.ALBUM
                || type == ContentType.EPISODE;
        if (supported && service != null) {
            buildMoreByArtistsFooter(type, playlist);
        } else {
            hideMoreByArtistsFooter();
        }
    }

    private boolean isLocalSongWithoutMetadata(Playlist playlist, ContentType type) {
        if (type != ContentType.SINGLE
                || playlist == null
                || playlist.getSongList() == null
                || playlist.getSongList().isEmpty()) {
            return false;
        }

        Song song = playlist.getSongList().get(0);
        return song != null && song.isLocal() && song.getSongID() == 0L;
    }

    /** Clears the footer before the deferred footer type is selected. */
    public void prepareForDeferredLoad() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::prepareForDeferredLoad);
            return;
        }
        hideMoreByArtistsFooter();
    }

    public void buildMoreByArtistsFooter(ContentType type, Playlist playlist) {
        if (moreByArtistsContainer == null) {
            return;
        }

        clearLazyLoadingObserver();
        moreByArtistsContainer.getChildren().clear();
        long footerRunId = System.nanoTime();
        moreByArtistsContainer.getProperties().put(FOOTER_RUN_ID_KEY, footerRunId);

        CompletableFuture
                .supplyAsync(() -> new FooterPreparation(
                        dataProvider.resolveArtists(type, playlist, service),
                        dataProvider.snapshot(service)
                ), dataProvider.executor())
                .thenAccept(preparation -> Platform.runLater(() ->
                        renderPreparedFooter(
                                type,
                                playlist,
                                footerRunId,
                                preparation
                        )))
                .exceptionally(error -> {
                    Platform.runLater(() -> {
                        if (hasCurrentFooterRun(footerRunId)) {
                            renderer.showLoadError(moreByArtistsContainer, footerPane);
                        }
                    });
                    return null;
                });
    }

    private void renderPreparedFooter(ContentType type,
                                      Playlist playlist,
                                      long footerRunId,
                                      FooterPreparation preparation) {
        if (!hasCurrentFooterRun(footerRunId) || preparation == null) {
            return;
        }

        List<Artist> artists = preparation.artists();
        if (artists.isEmpty()) {
            renderer.showLoadError(moreByArtistsContainer, footerPane);
            return;
        }

        long currentAlbumId = type == ContentType.ALBUM || type == ContentType.EPISODE
                ? playlist == null ? -1L : playlist.getId() : -1L;
        long currentSongId = type == ContentType.SINGLE && playlist != null
                && playlist.getSongList() != null && !playlist.getSongList().isEmpty()
                ? playlist.getSongList().get(0).getSongID() : -1L;
        PlayerMenuMoreByArtistsModels.LibrarySnapshot snapshot = preparation.snapshot();
        boolean lazy = artists.size() > EAGER_ARTIST_SECTION_LIMIT;

        for (Artist artist : artists) {
            if (artist == null) {
                continue;
            }
            String artistKey = artistKey(artist);
            if (containsArtistSection(artistKey)) {
                continue;
            }

            HBox title = renderer.createArtistTitle(artist);
            title.getProperties().put("artistKey", artistKey);
            ObservableList<Node> cards = FXCollections.observableArrayList();
            StackPane carousel = renderer.createCarousel(cards);
            carousel.getProperties().put("artistKey", artistKey);
            moreByArtistsContainer.getChildren().addAll(title, carousel);

            Runnable loader = () -> loadArtistSection(
                    artist, cards, carousel, snapshot.albums(), snapshot.songs(),
                    type, footerRunId, currentAlbumId, currentSongId
            );
            carousel.getProperties().put(LAZY_LOADER_KEY, loader);
            carousel.getProperties().put(LAZY_LOADED_KEY, false);
            if (!lazy) {
                loader.run();
            }
        }

        moreByArtistsContainer.setVisible(true);
        moreByArtistsContainer.setManaged(true);
        if (footerPane != null) {
            footerPane.setVisible(true);
            footerPane.setManaged(true);
        }
        if (lazy) {
            installLazyLoadingObserver(footerRunId);
            scheduleLazySectionEvaluation(footerRunId);
        }
    }

    private record FooterPreparation(
            List<Artist> artists,
            PlayerMenuMoreByArtistsModels.LibrarySnapshot snapshot
    ) {
    }

    private void loadArtistSection(
            Artist artist,
            ObservableList<Node> cards,
            StackPane carousel,
            List<Album> albums,
            List<Song> songs,
            ContentType type,
            long footerRunId,
            long currentAlbumId,
            long currentSongId
    ) {
        if (carousel == null || !hasCurrentFooterRun(footerRunId)
                || Boolean.TRUE.equals(carousel.getProperties().put(LAZY_LOADED_KEY, true))) {
            return;
        }

        CompletableFuture
                .supplyAsync(() -> dataProvider.buildLocalFooterSpecs(
                        artist, albums, songs, type, currentAlbumId, currentSongId
                ), dataProvider.executor())
                .thenAccept(localSpecs -> {
                    if (!hasCurrentFooterRun(footerRunId)) {
                        return;
                    }
                    Platform.runLater(() -> {
                        if (!hasCurrentFooterRun(footerRunId)) {
                            return;
                        }
                        int added = renderer.renderSpecs(
                                cards, carousel, localSpecs, currentAlbumId, currentSongId,
                                artist, target -> handleArtistClick(carousel, target)
                        );
                        if (added > 0) {
                            clearRetryState(carousel);
                        }
                    });
                    fetchRemoteContentForArtist(
                            artist, cards, carousel, footerRunId, currentAlbumId, currentSongId
                    );
                })
                .exceptionally(error -> {
                    if (hasCurrentFooterRun(footerRunId)) {
                        fetchRemoteContentForArtist(
                                artist, cards, carousel, footerRunId, currentAlbumId, currentSongId
                        );
                    }
                    return null;
                });
    }

    private void fetchRemoteContentForArtist(
            Artist artist,
            ObservableList<Node> cards,
            StackPane carousel,
            long footerRunId,
            long currentAlbumId,
            long currentSongId
    ) {
        if (artist == null || cards == null || carousel == null
                || !hasCurrentFooterRun(footerRunId)) {
            return;
        }
        if (Boolean.TRUE.equals(carousel.getProperties().get(REMOTE_FETCH_STARTED_KEY))) {
            return;
        }
        carousel.getProperties().put(REMOTE_FETCH_STARTED_KEY, true);

        dataProvider.fetchRemoteContent(artist)
                .thenAccept(result -> Platform.runLater(() ->
                        handleRemoteResult(
                                artist, cards, carousel, result, footerRunId,
                                currentAlbumId, currentSongId
                        )
                ))
                .exceptionally(error -> {
                    Platform.runLater(() -> {
                        carousel.getProperties().remove(REMOTE_FETCH_STARTED_KEY);
                        scheduleSectionRetryIfNeeded(
                                artist, cards, carousel, footerRunId, currentAlbumId, currentSongId
                        );
                    });
                    return null;
                });
    }

    private void handleRemoteResult(
            Artist artist,
            ObservableList<Node> cards,
            StackPane carousel,
            RemoteFetchPair result,
            long footerRunId,
            long currentAlbumId,
            long currentSongId
    ) {
        if (!hasCurrentFooterRun(footerRunId)) {
            return;
        }
        carousel.getProperties().put(REMOTE_ARTIST_CACHE_KEY, result.cacheKey());
        carousel.getProperties().remove(REMOTE_FETCH_STARTED_KEY);
        int added = renderer.renderSpecs(
                cards, carousel, result.albums().specs(), currentAlbumId, currentSongId,
                artist, target -> handleArtistClick(carousel, target)
        );
        added += renderer.renderSpecs(
                cards, carousel, result.tracks().specs(), currentAlbumId, currentSongId,
                artist, target -> handleArtistClick(carousel, target)
        );
        if (added > 0) {
            clearRetryState(carousel);
            return;
        }
        if (cards.isEmpty() && result.succeededWithoutResults()) {
            hideMoreByArtistsFooter();
            return;
        }
        if (cards.isEmpty() && (!result.albums().responseSucceeded()
                || !result.tracks().responseSucceeded())) {
            scheduleSectionRetryIfNeeded(
                    artist, cards, carousel, footerRunId, currentAlbumId, currentSongId
            );
        }
    }

    private void handleArtistClick(Node clickContext, Artist artist) {
        if (musicActions != null && artist != null && artist.getName() != null) {
            musicActions.artistClick(clickContext).accept(artist);
        }
    }

    private void installLazyLoadingObserver(long footerRunId) {
        if (playerMenuScroll == null) {
            loadFirstPendingSections(2);
            return;
        }
        lazyScrollListener = (obs, oldValue, newValue) -> scheduleLazySectionEvaluation(footerRunId);
        lazyViewportListener = (obs, oldValue, newValue) -> scheduleLazySectionEvaluation(footerRunId);
        playerMenuScroll.vvalueProperty().addListener(lazyScrollListener);
        playerMenuScroll.viewportBoundsProperty().addListener(lazyViewportListener);
    }

    private void clearLazyLoadingObserver() {
        if (playerMenuScroll != null && lazyScrollListener != null) {
            playerMenuScroll.vvalueProperty().removeListener(lazyScrollListener);
        }
        if (playerMenuScroll != null && lazyViewportListener != null) {
            playerMenuScroll.viewportBoundsProperty().removeListener(lazyViewportListener);
        }
        lazyScrollListener = null;
        lazyViewportListener = null;
        lazyEvaluationQueued.set(false);
    }

    private void scheduleLazySectionEvaluation(long footerRunId) {
        if (!lazyEvaluationQueued.compareAndSet(false, true)) {
            return;
        }
        Platform.runLater(() -> {
            try {
                evaluateLazySections(footerRunId);
            } finally {
                lazyEvaluationQueued.set(false);
            }
        });
    }

    private void evaluateLazySections(long footerRunId) {
        if (!hasCurrentFooterRun(footerRunId) || moreByArtistsContainer == null) {
            return;
        }
        if (playerMenuScroll == null || playerMenuScroll.getScene() == null) {
            loadFirstPendingSections(2);
            return;
        }

        Bounds viewport = playerMenuScroll.localToScene(playerMenuScroll.getBoundsInLocal());
        for (Node node : moreByArtistsContainer.getChildren()) {
            if (!(node instanceof StackPane carousel) || !hasPendingLazyLoader(carousel)) {
                continue;
            }
            Bounds section = carousel.localToScene(carousel.getBoundsInLocal());
            boolean nearViewport = section.getMaxY() >= viewport.getMinY() - LAZY_PRELOAD_MARGIN
                    && section.getMinY() <= viewport.getMaxY() + LAZY_PRELOAD_MARGIN;
            if (nearViewport) {
                runLazyLoader(carousel);
            }
        }
    }

    private void loadFirstPendingSections(int limit) {
        if (moreByArtistsContainer == null || limit <= 0) {
            return;
        }
        int loaded = 0;
        for (Node node : moreByArtistsContainer.getChildren()) {
            if (!(node instanceof StackPane carousel) || !hasPendingLazyLoader(carousel)) {
                continue;
            }
            runLazyLoader(carousel);
            if (++loaded >= limit) {
                return;
            }
        }
    }

    private boolean hasPendingLazyLoader(Node node) {
        return node != null
                && !Boolean.TRUE.equals(node.getProperties().get(LAZY_LOADED_KEY))
                && node.getProperties().get(LAZY_LOADER_KEY) instanceof Runnable;
    }

    private void runLazyLoader(Node node) {
        Object loader = node == null ? null : node.getProperties().get(LAZY_LOADER_KEY);
        if (loader instanceof Runnable runnable) {
            runnable.run();
        }
    }

    private void scheduleSectionRetryIfNeeded(
            Artist artist,
            ObservableList<Node> cards,
            StackPane carousel,
            long footerRunId,
            long currentAlbumId,
            long currentSongId
    ) {
        if (!hasCurrentFooterRun(footerRunId) || cards == null || !cards.isEmpty()
                || Boolean.TRUE.equals(carousel.getProperties().get(REMOTE_RETRY_SCHEDULED_KEY))) {
            return;
        }
        int attempt = retryAttempt(carousel);
        if (attempt >= MAX_SECTION_RETRY_ATTEMPTS) {
            renderer.showLoadError(carousel, footerPane);
            return;
        }
        carousel.getProperties().put(REMOTE_RETRY_SCHEDULED_KEY, true);
        long delay = SECTION_RETRY_BASE_DELAY_MS * (1L << attempt);
        retryScheduler.schedule(() -> Platform.runLater(() -> {
            if (!hasCurrentFooterRun(footerRunId)) {
                return;
            }
            carousel.getProperties().remove(REMOTE_RETRY_SCHEDULED_KEY);
            if (!cards.isEmpty()) {
                return;
            }
            carousel.getProperties().put(REMOTE_RETRY_COUNT_KEY, attempt + 1);
            carousel.getProperties().remove(REMOTE_FETCH_STARTED_KEY);
            dataProvider.clearEmptyCaches(
                    String.valueOf(carousel.getProperties().get(REMOTE_ARTIST_CACHE_KEY))
            );
            fetchRemoteContentForArtist(
                    artist, cards, carousel, footerRunId, currentAlbumId, currentSongId
            );
        }), delay, TimeUnit.MILLISECONDS);
    }

    private int retryAttempt(Node node) {
        Object value = node.getProperties().get(REMOTE_RETRY_COUNT_KEY);
        return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
    }

    private void clearRetryState(Node node) {
        if (node == null) {
            return;
        }
        node.getProperties().remove(REMOTE_RETRY_COUNT_KEY);
        node.getProperties().remove(REMOTE_RETRY_SCHEDULED_KEY);
        node.getProperties().remove(REMOTE_FETCH_STARTED_KEY);
    }

    private void hideMoreByArtistsFooter() {
        clearLazyLoadingObserver();
        if (moreByArtistsContainer == null) {
            return;
        }
        moreByArtistsContainer.getProperties().put(FOOTER_RUN_ID_KEY, System.nanoTime());
        moreByArtistsContainer.getChildren().clear();
        moreByArtistsContainer.setVisible(false);
        moreByArtistsContainer.setManaged(false);
    }

    private boolean containsArtistSection(String artistKey) {
        return moreByArtistsContainer.getChildren().stream()
                .filter(node -> node instanceof Parent)
                .anyMatch(node -> artistKey.equals(node.getProperties().get("artistKey")));
    }

    private String artistKey(Artist artist) {
        return artist.getArtistID() > 0
                ? "id:" + artist.getArtistID()
                : "name:" + Objects.toString(artist.getName(), "").trim().toLowerCase();
    }

    private boolean hasCurrentFooterRun(long footerRunId) {
        return moreByArtistsContainer != null
                && Objects.equals(
                moreByArtistsContainer.getProperties().get(FOOTER_RUN_ID_KEY), footerRunId
        );
    }
}
