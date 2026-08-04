package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.footer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.MusicCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.factory.CardFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.components.icons.SvgIconFactory;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;
import io.github.guillermodubon.musicplayer.utils.DeezerEndpoints;
import io.github.guillermodubon.musicplayer.utils.MusicCardHelper;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.footer.PlayerMenuPlaylistFooterModels.REMOTE_RETRY_COUNT_KEY;
import static io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.footer.PlayerMenuPlaylistFooterModels.REMOTE_RETRY_SCHEDULED_KEY;
import static io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.footer.PlayerMenuPlaylistFooterModels.REMOTE_SUGGESTION_RUN_ID_KEY;
import static io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.footer.PlayerMenuPlaylistFooterModels.RemotePlaylistFetchResult;
import static io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.footer.PlayerMenuPlaylistFooterModels.RemotePlaylistSpec;

/** Loads, retries and renders related remote playlists. */
public final class PlayerMenuPlaylistRemoteSuggestions {

    private static final String CONNECTION_ERROR_ICON =
            "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/wifi_off_27dp_AFAFAF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String CONNECTION_ERROR_MESSAGE =
            "This section could not be loaded. Please check your internet connection and try again.";
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_BASE_DELAY_MS = 850L;
    private static final double EMPTY_HEIGHT = 118.0;

    private final StartUpService svc;
    private final MusicCardActionManager musicActions;
    private final ExecutorService ioPool = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "player-menu-playlist-footer-io");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "player-menu-playlist-footer-retry");
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentHashMap<String, List<RemotePlaylistSpec>> cache = new ConcurrentHashMap<>();

    private VBox footerPane;
    private VBox remoteSuggestionBox;

    public PlayerMenuPlaylistRemoteSuggestions(StartUpService svc,
                                               MusicCardActionManager musicActions,
                                               VBox footerPane) {
        this.svc = svc;
        this.musicActions = musicActions;
        this.footerPane = footerPane;
    }

    public void bindUi(VBox footerPane, VBox remoteSuggestionBox) {
        this.footerPane = footerPane;
        this.remoteSuggestionBox = remoteSuggestionBox;
    }

    public void hide() {
        if (remoteSuggestionBox == null) return;
        remoteSuggestionBox.getProperties().remove(REMOTE_SUGGESTION_RUN_ID_KEY);
        remoteSuggestionBox.getProperties().remove(REMOTE_RETRY_COUNT_KEY);
        remoteSuggestionBox.getProperties().remove(REMOTE_RETRY_SCHEDULED_KEY);
        remoteSuggestionBox.setVisible(false);
        remoteSuggestionBox.setManaged(false);
    }

    public void showFor(Playlist playlist) {
        if (playlist == null || musicActions == null) return;
        ensureSuggestionBox();
        if (remoteSuggestionBox == null) return;
        FlowPane flow = findOrCreateFlow();
        if (flow == null) return;
        flow.getChildren().clear();
        remoteSuggestionBox.setVisible(true);
        remoteSuggestionBox.setManaged(true);

        String query = safeRemoteQuery(playlist);
        if (query.isBlank()) return;
        String cacheKey = query.trim().toLowerCase(Locale.ROOT) + "|" + playlist.getId();
        long runId = System.nanoTime();
        remoteSuggestionBox.getProperties().put(REMOTE_SUGGESTION_RUN_ID_KEY, runId);
        remoteSuggestionBox.getProperties().remove(REMOTE_RETRY_COUNT_KEY);
        remoteSuggestionBox.getProperties().remove(REMOTE_RETRY_SCHEDULED_KEY);

        List<RemotePlaylistSpec> cached = cache.get(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            render(cached, flow, runId);
            return;
        }
        fetchWithRetry(playlist, query, cacheKey, flow, runId);
    }

    private void fetchWithRetry(Playlist playlist,
                                String query,
                                String cacheKey,
                                FlowPane flow,
                                long runId) {
        if (playlist == null || query == null || query.isBlank() || flow == null
                || !hasCurrentRun(runId)) return;

        CompletableFuture
                .supplyAsync(() -> fetchSimilarPlaylistSpecs(query, playlist.getId()), ioPool)
                .thenAccept(result -> {
                    RemotePlaylistFetchResult safe = result == null
                            ? RemotePlaylistFetchResult.failure() : result;
                    List<RemotePlaylistSpec> specs = safe.specs() == null ? List.of() : safe.specs();
                    if (safe.successfulResponse()) {
                        if (!specs.isEmpty()) cache.putIfAbsent(cacheKey, specs);
                        Platform.runLater(() -> {
                            if (!hasCurrentRun(runId)) return;
                            clearRetryState();
                            if (specs.isEmpty()) hideSuggestionFooter(flow, runId);
                            else render(specs, flow, runId);
                        });
                        return;
                    }
                    Platform.runLater(() -> scheduleRetry(playlist, query, cacheKey, flow, runId));
                })
                .exceptionally(error -> {
                    Platform.runLater(() -> scheduleRetry(playlist, query, cacheKey, flow, runId));
                    return null;
                });
    }

    private void scheduleRetry(Playlist playlist,
                               String query,
                               String cacheKey,
                               FlowPane flow,
                               long runId) {
        if (!hasCurrentRun(runId)
                || Boolean.TRUE.equals(remoteSuggestionBox.getProperties().get(REMOTE_RETRY_SCHEDULED_KEY))) {
            return;
        }
        int attempt = retryAttempt();
        if (attempt >= MAX_RETRY_ATTEMPTS) {
            render(List.of(), flow, runId);
            return;
        }
        remoteSuggestionBox.getProperties().put(REMOTE_RETRY_SCHEDULED_KEY, true);
        long delay = RETRY_BASE_DELAY_MS * (1L << attempt);
        retryScheduler.schedule(() -> Platform.runLater(() -> {
            if (!hasCurrentRun(runId)) return;
            remoteSuggestionBox.getProperties().remove(REMOTE_RETRY_SCHEDULED_KEY);
            remoteSuggestionBox.getProperties().put(REMOTE_RETRY_COUNT_KEY, attempt + 1);
            cache.remove(cacheKey);
            fetchWithRetry(playlist, query, cacheKey, flow, runId);
        }), delay, TimeUnit.MILLISECONDS);
    }

    private RemotePlaylistFetchResult fetchSimilarPlaylistSpecs(String query, long currentPlaylistId) {
        if (query == null || query.isBlank()) return RemotePlaylistFetchResult.success(List.of());
        try {
            String url = DeezerEndpoints.defaultSearchResultsEndpoints().searchPlaylists(
                    URLEncoder.encode(query, StandardCharsets.UTF_8));
            JsonObject json = MusicCardHelper.fetchJsonObject(url);
            if (json == null || json.has("error") || !json.has("data")
                    || !json.get("data").isJsonArray()) return RemotePlaylistFetchResult.failure();

            Set<Long> existingIds = svc == null || svc.getPlaylists() == null
                    ? Set.of() : MusicCardHelper.snapshot(svc.getPlaylists()).stream()
                    .filter(Objects::nonNull)
                    .map(Playlist::getId)
                    .collect(Collectors.toSet());
            LinkedHashSet<Long> addedIds = new LinkedHashSet<>();
            List<RemotePlaylistSpec> result = new ArrayList<>();
            for (JsonElement element : json.getAsJsonArray("data")) {
                if (!element.isJsonObject()) continue;
                JsonObject playlist = element.getAsJsonObject();
                long id = playlist.has("id") && !playlist.get("id").isJsonNull()
                        ? playlist.get("id").getAsLong() : -1L;
                if (id <= 0 || id == currentPlaylistId || existingIds.contains(id) || !addedIds.add(id)) continue;
                String title = stringValue(playlist, "title", "Playlist");
                String cover = DeezerApiService.extractHighResolutionCoverUrl(playlist);
                result.add(new RemotePlaylistSpec(
                        id,
                        title == null || title.isBlank() ? "Playlist" : title,
                        cover,
                        MusicCardData.REMOTE_PLAYLIST_CREATOR_LABEL
                ));
                if (result.size() >= 12) break;
            }
            return RemotePlaylistFetchResult.success(result);
        } catch (Exception ignored) {
            return RemotePlaylistFetchResult.failure();
        }
    }

    private void render(List<RemotePlaylistSpec> specs, FlowPane flow, long runId) {
        if (!hasCurrentRun(runId) || flow == null) return;
        flow.getChildren().clear();
        if (specs == null || specs.isEmpty()) {
            showLoadError(flow);
            return;
        }
        for (RemotePlaylistSpec spec : specs) {
            if (spec == null || spec.id() <= 0) continue;
            try {
                Image cover = spec.coverUrl() == null || spec.coverUrl().isBlank()
                        ? null : MediaImageResolver.remoteImage(spec.coverUrl(), 320, 320);
                Parent card = createMusicCard(
                        String.valueOf(spec.id()), cover, spec.title(), List.of(spec.subtitle()),
                        id -> musicActions.playlistClick(flow).accept(id),
                        name -> musicActions.artistNameClick(flow).accept(name)
                );
                card.getProperties().put("playlistId", spec.id());
                card.getProperties().put("artistNames", List.of(spec.subtitle()));
                styleCard(card);
                flow.getChildren().add(card);
            } catch (Exception error) {
                Label fallback = new Label(spec.title());
                fallback.setStyle("-fx-text-fill: white; -fx-padding: 8;");
                flow.getChildren().add(fallback);
            }
        }
    }

    private Parent createMusicCard(String id,
                                   Image cover,
                                   String title,
                                   List<String> artists,
                                   java.util.function.Consumer<String> onPlay,
                                   java.util.function.Consumer<String> onArtistClick) throws IOException {
        return CardFactory.createMusicCard(MusicCardData.playlist(
                id, cover, title, artists == null ? List.of() : artists, onPlay, onArtistClick));
    }

    private void styleCard(Node card) {
        if (card instanceof Region region) {
            region.setPrefWidth(176);
            region.setMinWidth(140);
            region.setMaxWidth(176);
            region.setPrefHeight(254);
            region.setMinHeight(254);
            region.setMaxHeight(254);
        }
    }

    private void showLoadError(FlowPane flow) {
        Node icon = SvgIconFactory.icon(CONNECTION_ERROR_ICON, 28);
        Label message = new Label(CONNECTION_ERROR_MESSAGE);
        message.getStyleClass().add("player-menu-footer-load-error-message");
        message.setWrapText(true);
        message.setMaxWidth(460);
        VBox error = new VBox(10, icon, message);
        error.getStyleClass().add("player-menu-footer-load-error");
        error.setAlignment(javafx.geometry.Pos.CENTER);
        error.setMinHeight(EMPTY_HEIGHT);
        error.setMaxWidth(Double.MAX_VALUE);
        flow.getChildren().setAll(error);
        if (footerPane != null) {
            footerPane.setVisible(true);
            footerPane.setManaged(true);
        }
    }

    private void ensureSuggestionBox() {
        if (remoteSuggestionBox != null) return;
        remoteSuggestionBox = new VBox(10);
        remoteSuggestionBox.getStyleClass().add("player-menu-remote-suggestions");
        remoteSuggestionBox.setMaxWidth(Double.MAX_VALUE);
        Label title = new Label("You might also like");
        title.getStyleClass().add("player-menu-section-title");
        FlowPane flow = new FlowPane(12, 12);
        configureFlow(flow);
        remoteSuggestionBox.getChildren().addAll(title, flow);
        if (footerPane != null) {
            footerPane.getChildren().remove(remoteSuggestionBox);
            footerPane.getChildren().add(0, remoteSuggestionBox);
        }
    }

    private FlowPane findOrCreateFlow() {
        if (remoteSuggestionBox == null || remoteSuggestionBox.getChildren().isEmpty()) return null;
        for (Node node : remoteSuggestionBox.getChildren()) {
            if (node instanceof FlowPane flow) return flow;
        }
        FlowPane flow = new FlowPane(12, 12);
        configureFlow(flow);
        remoteSuggestionBox.getChildren().add(flow);
        return flow;
    }

    private void configureFlow(FlowPane flow) {
        if (flow == null) return;
        flow.getStyleClass().add("player-menu-responsive-card-grid");
        flow.setHgap(18);
        flow.setVgap(18);
        flow.setPadding(new Insets(8, 0, 16, 0));
        flow.setMaxWidth(Double.MAX_VALUE);
        flow.prefWrapLengthProperty().unbind();
        if (remoteSuggestionBox != null) flow.prefWrapLengthProperty().bind(remoteSuggestionBox.widthProperty());
    }

    private void hideSuggestionFooter(FlowPane flow, long runId) {
        if (!hasCurrentRun(runId) || flow == null) return;
        flow.getChildren().clear();
        remoteSuggestionBox.setVisible(false);
        remoteSuggestionBox.setManaged(false);
    }

    private boolean hasCurrentRun(long runId) {
        return remoteSuggestionBox != null && Objects.equals(
                remoteSuggestionBox.getProperties().get(REMOTE_SUGGESTION_RUN_ID_KEY), runId);
    }

    private int retryAttempt() {
        Object value = remoteSuggestionBox == null ? null
                : remoteSuggestionBox.getProperties().get(REMOTE_RETRY_COUNT_KEY);
        return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
    }

    private void clearRetryState() {
        if (remoteSuggestionBox == null) return;
        remoteSuggestionBox.getProperties().remove(REMOTE_RETRY_COUNT_KEY);
        remoteSuggestionBox.getProperties().remove(REMOTE_RETRY_SCHEDULED_KEY);
    }

    private String safeRemoteQuery(Playlist playlist) {
        if (playlist == null) return "";
        String title = Optional.ofNullable(playlist.getTitle()).orElse("").trim();
        if (!title.isBlank()) return title;
        if (playlist.getSongList() != null && !playlist.getSongList().isEmpty()) {
            var first = playlist.getSongList().get(0);
            if (first != null && first.getTitle() != null) return first.getTitle().trim();
        }
        return "";
    }

    private String stringValue(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : fallback;
    }
}
