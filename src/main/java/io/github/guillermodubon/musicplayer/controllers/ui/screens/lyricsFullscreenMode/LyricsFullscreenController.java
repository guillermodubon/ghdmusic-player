package io.github.guillermodubon.musicplayer.controllers.ui.screens.lyricsFullscreenMode;

import io.github.guillermodubon.musicplayer.controllers.ui.screens.lyricsFullscreenMode.view.LyricsFullscreenView;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.lyricsFullscreenMode.view.LyricsFullscreenViewFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.PlayerFullScreenModeController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.artwork.PlayerFullScreenArtworkCoordinator;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.layout.PlayerFullScreenOverlayCoordinator;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.layout.PlayerFullScreenPlayerBarCoordinator;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.layout.PlayerFullScreenWindowTracker;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.state.PlayerFullScreenModeState;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.models.lyrics.LyricLine;
import io.github.guillermodubon.musicplayer.models.lyrics.SongLyrics;
import io.github.guillermodubon.musicplayer.services.lyrics.LyricsPlaybackResolver;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.models.Artist;
import javafx.application.Platform;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.value.ChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.Node;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;
import javafx.event.EventHandler;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Fullscreen lyrics overlay with synchronized and plain-text presentations. */
public final class LyricsFullscreenController {

    private static final LyricsFullscreenController INSTANCE = new LyricsFullscreenController();

    private final PlaybackManager playbackManager = PlaybackManager.getInstance();
    private final LyricsPlaybackResolver lyricsResolver = LyricsPlaybackResolver.getInstance();
    private final AtomicLong songToken = new AtomicLong();

    private StartUpService startUpService;
    private javafx.scene.layout.BorderPane hostRoot;
    private LyricsFullscreenView view;
    private PlayerFullScreenOverlayCoordinator overlayCoordinator;
    private PlayerFullScreenPlayerBarCoordinator playerBarCoordinator;
    private PlayerFullScreenArtworkCoordinator artworkCoordinator;
    private PlayerFullScreenWindowTracker windowTracker;
    private final PlayerFullScreenModeState closeButtonState = new PlayerFullScreenModeState();
    private EventHandler<MouseEvent> closeButtonMouseActivityHandler;
    private EventHandler<ScrollEvent> closeButtonScrollActivityHandler;
    private EventHandler<KeyEvent> closeButtonKeyActivityHandler;
    private MediaPlayer observedPlayer;
    private ChangeListener<Duration> timeListener;
    private ChangeListener<Number> lyricsScrollListener;
    private Timeline lyricScrollAnimation;
    private final AtomicLong lifecycleToken = new AtomicLong();
    private long pendingSeekMillis;
    private boolean seekScheduled;
    private long seekGeneration;
    private long scheduledSeekGeneration;
    private long scheduledVisualUpdateToken = -1L;
    private long scheduledCompactLayoutToken = -1L;
    private long scheduledArtworkViewportToken = -1L;
    private final Map<Node, Double> lyricOpacities = new IdentityHashMap<>();
    private List<LyricLine> syncedLines = List.of();
    private boolean displayingSyncedLyrics;
    private int activeLine = -1;
    private boolean active;
    private boolean changingState;

    private LyricsFullscreenController() {
        playbackManager.addTrackChangeListener(() -> {
            if (!active) {
                return;
            }
            Runnable refresh = () -> {
                if (active) {
                    updateCurrentSong(playbackManager.getCurrentSong());
                }
            };
            if (Platform.isFxApplicationThread()) {
                refresh.run();
            } else {
                Platform.runLater(refresh);
            }
        });
    }

    public static LyricsFullscreenController getInstance() {
        return INSTANCE;
    }

    public boolean isActive() {
        return active;
    }

    public void toggle(StartUpService service,
                       javafx.scene.layout.BorderPane root,
                       Song song) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> toggle(service, root, song));
            return;
        }
        long requestToken = lifecycleToken.incrementAndGet();
        if (active) {
            exit();
            return;
        }
        if (changingState) return;

        Runnable open = () -> {
            if (lifecycleToken.get() != requestToken || active || changingState) return;
            enter(service, root, song);
        };
        if (PlayerFullScreenModeController.getInstance().isActive()) {
            PlayerFullScreenModeController.getInstance().exitIfActive();
            Platform.runLater(open);
        } else {
            open.run();
        }
    }

    private void enter(StartUpService service,
                       javafx.scene.layout.BorderPane root,
                       Song song) {
        if (active || changingState || root == null || root.getScene() == null) return;
        changingState = true;
        try {
            this.startUpService = service != null ? service : StartUpService.getInstance();
            this.hostRoot = root;
            active = true;

            LyricsFullscreenView newView = new LyricsFullscreenViewFactory().create(
                    this::exit,
                    () -> active,
                    this::requestArtworkViewportUpdate,
                    this::openPlayerFullscreen
            );
            overlayCoordinator = new PlayerFullScreenOverlayCoordinator();
            StackPane overlay = overlayCoordinator.createOverlay(newView.playbackView());
            overlayCoordinator.installPlayerMenuBarStylesheet(newView.playbackView());
            java.net.URL lyricsStylesheetUrl = getClass().getResource(
                    "/io/github/guillermodubon/musicplayer/Views/screens/lyricsFullscreenMode/lyrics-fullscreen.css"
            );
            if (lyricsStylesheetUrl != null) {
                newView.root().getStylesheets().add(lyricsStylesheetUrl.toExternalForm());
            }

            view = newView;

            if (!overlayCoordinator.attach(overlayCoordinator.resolveHost(root), newView.playbackView(),
                    this::requestCompactLayout)) {
                active = false;
                disposeRuntimeState();
                clearRuntimeReferences();
                return;
            }

            installCloseButtonActivityTracking(newView);
            windowTracker = new PlayerFullScreenWindowTracker(
                    () -> active,
                    this::synchronizeLayoutNow
            );
            windowTracker.install(root.getScene());

            bindLyricsScroll(newView.lyricsScrollPane());
            playerBarCoordinator = new PlayerFullScreenPlayerBarCoordinator(
                    () -> playbackManager.getPlayerMenuBarController(),
                    () -> overlay,
                    newView::playbackView
            );
            playerBarCoordinator.attachToOverlay(this.startUpService);
            playerBarCoordinator.configureActionsButton(newView.playbackView().actionsMenuButton());
            playerBarCoordinator.placeLyricsButtonNextTo(newView.playbackView().actionsMenuButton());
            playerBarCoordinator.setLyricsButtonSuppressed(true);

            artworkCoordinator = new PlayerFullScreenArtworkCoordinator(
                    newView.playbackView(),
                    () -> active,
                    // changingState also covers the opening transaction. It
                    // must not suppress the initial cover/background load;
                    // active already invalidates callbacks once the view is
                    // closing.
                    () -> !active,
                    this::requestArtworkViewportUpdate,
                    this::openArtistFromLyrics
            );
            Song initialSong = playbackManager.getCurrentSong();
            if (initialSong == null) {
                initialSong = song;
            }
            updateArtworkViewport();
            updateCurrentSong(initialSong);
            syncLayout();
            schedulePostAttachLayoutSync(newView, lifecycleToken.get());
            Platform.runLater(() -> {
                if (active && view == newView) view.root().requestFocus();
            });
        } catch (RuntimeException ignored) {
            active = false;
            lifecycleToken.incrementAndGet();
            songToken.incrementAndGet();
            seekGeneration++;
            disposeRuntimeState();
            clearRuntimeReferences();
        } finally {
            changingState = false;
        }
    }

    private void updateCurrentSong(Song song) {
        if (!active || view == null) return;
        long token = songToken.incrementAndGet();
        seekGeneration++;
        activeLine = -1;
        if (artworkCoordinator != null) artworkCoordinator.updateSong(startUpService, song);
        if (playerBarCoordinator != null) playerBarCoordinator.updateCurrentSong(song);
        bindPlayer(playbackManager.getCurrentPlayer());
        clearLyrics();
        requestCompactLayout();
        if (song == null) return;

        lyricsResolver.resolve(song).thenAccept(lyrics -> Platform.runLater(() -> {
            if (!active || token != songToken.get()) return;
            renderLyrics(lyrics);
            updatePlaybackPosition(currentSeconds());
        }));
    }

    /**
     * The overlay receives its real dimensions during the first layout pulse.
     * A second, guarded pass prevents the cover, background and control bar
     * from being calculated with the temporary zero-sized view.
     */
    private void schedulePostAttachLayoutSync(LyricsFullscreenView attachedView, long token) {
        Platform.runLater(() -> {
            if (!active || view != attachedView || lifecycleToken.get() != token) return;
            updateArtworkViewport();
            Platform.runLater(() -> {
                if (!active || view != attachedView || lifecycleToken.get() != token) return;
                updateArtworkViewport();
            });
        });
    }

    private void renderLyrics(SongLyrics lyrics) {
        VBox lines = view.lyricsLines();
        syncedLines = lyrics != null && lyrics.hasSyncedLyrics()
                ? lyrics.syncedLyrics().lines() : List.of();
        displayingSyncedLyrics = !syncedLines.isEmpty();
        List<Node> renderedLines = new ArrayList<>();

        if (displayingSyncedLyrics) {
            for (int index = 0; index < syncedLines.size(); index++) {
                LyricLine lyric = syncedLines.get(index);
                Label label = createLineLabel(lyric.text(), "lyrics-line");
                final int lineIndex = index;
                label.setOnMouseClicked(event -> seekTo(syncedLines.get(lineIndex).timestampMillis()));
                renderedLines.add(label);
            }
        } else if (lyrics != null && lyrics.hasPlainLyrics()) {
            for (String text : lyrics.plainLyrics().text().replace("\r", "").split("\n", -1)) {
                renderedLines.add(createLineLabel(text, "lyrics-plain-line"));
            }
        } else {
            Label empty = createLineLabel("Lyrics are not available for this song.", "lyrics-empty");
            empty.setWrapText(true);
            renderedLines.add(empty);
        }
        lyricOpacities.clear();
        lines.getChildren().setAll(renderedLines);
        lines.setAlignment(displayingSyncedLyrics ? Pos.CENTER_LEFT : Pos.TOP_LEFT);
        view.lyricsScrollPane().setVvalue(0.0);
        requestLyricsVisualStateUpdate();
    }

    private Label createLineLabel(String text, String styleClass) {
        Label label = new Label(text == null || text.isBlank() ? " " : text);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private void clearLyrics() {
        stopLyricScrollAnimation();
        syncedLines = List.of();
        displayingSyncedLyrics = false;
        activeLine = -1;
        lyricOpacities.clear();
        if (view != null) view.lyricsLines().getChildren().clear();
    }

    /**
     * Closes the lyrics overlay before delegating to the shared artist
     * navigation path. This keeps the overlay stack consistent and avoids
     * opening an artist page over a still-active fullscreen layer.
     */
    private void openArtistFromLyrics(Node anchor, Artist artist) {
        if (!active || changingState || artist == null) {
            return;
        }

        exit();
        Platform.runLater(() -> {
            if (active || changingState) {
                return;
            }
            var playerBar = playbackManager.getPlayerMenuBarController();
            if (playerBar != null) {
                playerBar.openArtistAfterFullScreen(artist);
            }
        });
    }

    private void bindPlayer(MediaPlayer player) {
        if (observedPlayer == player && timeListener != null) {
            return;
        }
        if (observedPlayer != null && timeListener != null) {
            observedPlayer.currentTimeProperty().removeListener(timeListener);
        }
        observedPlayer = player;
        if (player == null) return;
        timeListener = (obs, oldValue, current) -> {
            if (active && displayingSyncedLyrics && current != null) {
                updatePlaybackPosition(current.toSeconds());
            }
        };
        player.currentTimeProperty().addListener(timeListener);
    }

    private void updatePlaybackPosition(double seconds) {
        if (!displayingSyncedLyrics || syncedLines.isEmpty() || view == null) return;
        int target = findActiveLine(seconds);
        if (target == activeLine) return;
        int previousLine = activeLine;
        activeLine = target;
        if (previousLine >= 0 && previousLine < view.lyricsLines().getChildren().size()) {
            view.lyricsLines().getChildren().get(previousLine)
                    .getStyleClass().remove("lyrics-line-active");
        }
        if (target >= 0 && target < view.lyricsLines().getChildren().size()) {
            view.lyricsLines().getChildren().get(target)
                    .getStyleClass().add("lyrics-line-active");
        }
        if (target >= 0) scrollToLine(target);
        requestLyricsVisualStateUpdate();
    }

    private void bindLyricsScroll(ScrollPane scrollPane) {
        if (scrollPane == null) return;
        unbindLyricsScroll();
        lyricsScrollListener = (obs, oldValue, newValue) -> requestLyricsVisualStateUpdate();
        scrollPane.vvalueProperty().addListener(lyricsScrollListener);
    }

    private void unbindLyricsScroll() {
        if (view != null && lyricsScrollListener != null) {
            view.lyricsScrollPane().vvalueProperty().removeListener(lyricsScrollListener);
        }
        lyricsScrollListener = null;
    }

    /** Applies a center-focused alpha mask to the currently visible lyric lines. */
    private void updateLyricsVisualState() {
        if (!active || view == null || view.lyricsLines().getChildren().isEmpty()) return;

        ScrollPane scrollPane = view.lyricsScrollPane();
        VBox lines = view.lyricsLines();
        ObservableList<Node> lyricNodes = lines.getChildren();
        double viewportHeight = scrollPane.getViewportBounds().getHeight();
        double contentHeight = lines.getBoundsInLocal().getHeight();
        if (viewportHeight <= 1.0 || contentHeight <= 1.0) return;

        double maxScroll = Math.max(0.0, contentHeight - viewportHeight);
        double scrollOffset = scrollPane.getVvalue() * maxScroll;
        double center = viewportHeight / 2.0;
        double focusRadius = Math.max(56.0, viewportHeight * 0.11);
        double fadeDistance = Math.max(focusRadius + 1.0, viewportHeight * 0.52);
        double edgeFadeStart = Math.max(72.0, viewportHeight * 0.24);
        double edgeFadeEnd = Math.max(18.0, viewportHeight * 0.06);
        int firstVisible = Math.max(
                0,
                firstLineAtOrAfter(lyricNodes, scrollOffset - fadeDistance) - 1
        );
        int lastVisible = Math.min(
                lyricNodes.size() - 1,
                lastLineAtOrBefore(lyricNodes, scrollOffset + viewportHeight + fadeDistance) + 1
        );

        for (int index = firstVisible; index <= lastVisible; index++) {
            Node node = lyricNodes.get(index);
            double lineCenter = node.getBoundsInParent().getCenterY() - scrollOffset;
            double distance = Math.abs(lineCenter - center);
            double opacity;
            if (distance <= focusRadius) {
                opacity = 1.0;
            } else {
                double progress = Math.min(
                        1.0,
                        (distance - focusRadius) / (fadeDistance - focusRadius)
                );
                opacity = 0.12 + (0.88 * (1.0 - progress));
            }

            double distanceToEdge = Math.min(lineCenter, viewportHeight - lineCenter);
            if (distanceToEdge <= edgeFadeEnd) {
                opacity = Math.min(opacity, 0.02);
            } else if (distanceToEdge < edgeFadeStart) {
                double edgeProgress = (distanceToEdge - edgeFadeEnd)
                        / (edgeFadeStart - edgeFadeEnd);
                double edgeOpacity = 0.02 + (0.98 * edgeProgress);
                opacity = Math.min(opacity, edgeOpacity);
            }
            double resolvedOpacity = Math.max(0.02, Math.min(1.0, opacity));
            Double previousOpacity = lyricOpacities.get(node);
            if (previousOpacity == null
                    || Math.abs(previousOpacity - resolvedOpacity) >= 0.005) {
                node.setOpacity(resolvedOpacity);
                lyricOpacities.put(node, resolvedOpacity);
            }
        }
    }

    private void requestLyricsVisualStateUpdate() {
        long requestToken = lifecycleToken.get();
        if (scheduledVisualUpdateToken == requestToken) return;
        scheduledVisualUpdateToken = requestToken;
        Platform.runLater(() -> {
            if (scheduledVisualUpdateToken == requestToken) {
                scheduledVisualUpdateToken = -1L;
            }
            if (!active || requestToken != lifecycleToken.get()) return;
            updateLyricsVisualState();
        });
    }

    private int firstLineAtOrAfter(ObservableList<Node> nodes, double y) {
        int low = 0;
        int high = nodes.size() - 1;
        int result = nodes.size();
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (nodes.get(middle).getBoundsInParent().getMaxY() >= y) {
                result = middle;
                high = middle - 1;
            } else {
                low = middle + 1;
            }
        }
        return result;
    }

    private int lastLineAtOrBefore(ObservableList<Node> nodes, double y) {
        int low = 0;
        int high = nodes.size() - 1;
        int result = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (nodes.get(middle).getBoundsInParent().getMinY() <= y) {
                result = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return result;
    }

    private void scrollToLine(int index) {
        double contentHeight = view.lyricsLines().getHeight();
        double viewportHeight = view.lyricsScrollPane().getViewportBounds().getHeight();
        if (contentHeight <= viewportHeight || index >= view.lyricsLines().getChildren().size()) return;
        javafx.scene.Node line = view.lyricsLines().getChildren().get(index);
        double center = line.getLayoutY() + line.getBoundsInParent().getHeight() / 2.0;
        double max = contentHeight - viewportHeight;
        double targetValue = Math.max(
                0.0,
                Math.min(1.0, (center - viewportHeight / 2.0) / max)
        );
        animateLyricsScroll(targetValue);
    }

    private void animateLyricsScroll(double targetValue) {
        if (view == null) return;
        ScrollPane scrollPane = view.lyricsScrollPane();
        double currentValue = scrollPane.getVvalue();
        if (Math.abs(currentValue - targetValue) < 0.002) {
            scrollPane.setVvalue(targetValue);
            return;
        }

        stopLyricScrollAnimation();
        lyricScrollAnimation = new Timeline(
                new KeyFrame(
                        Duration.millis(320),
                        new KeyValue(
                                scrollPane.vvalueProperty(),
                                targetValue,
                                Interpolator.EASE_BOTH
                        )
                )
        );
        lyricScrollAnimation.setOnFinished(event -> lyricScrollAnimation = null);
        lyricScrollAnimation.play();
    }

    private void stopLyricScrollAnimation() {
        if (lyricScrollAnimation != null) {
            lyricScrollAnimation.stop();
            lyricScrollAnimation = null;
        }
    }

    private void seekTo(long timestampMillis) {
        if (!active || changingState || view == null) return;
        pendingSeekMillis = Math.max(0L, timestampMillis);
        long requestToken = lifecycleToken.get();
        long requestGeneration = seekGeneration;
        if (seekScheduled && scheduledSeekGeneration == requestGeneration) return;

        seekScheduled = true;
        scheduledSeekGeneration = requestGeneration;
        Platform.runLater(() -> {
            if (scheduledSeekGeneration == requestGeneration) {
                seekScheduled = false;
            }
            if (!active || changingState
                    || requestToken != lifecycleToken.get()
                    || requestGeneration != seekGeneration) {
                return;
            }
            MediaPlayer player = playbackManager.getCurrentPlayer();
            if (player == null) return;
            long targetMillis = pendingSeekMillis;
            try {
                player.seek(Duration.millis(targetMillis));
            } catch (IllegalStateException ignored) {
                return;
            }
            updatePlaybackPosition(targetMillis / 1_000.0);
        });
    }

    private int findActiveLine(double seconds) {
        long timestampMillis = Math.max(0L, (long) (seconds * 1_000.0));
        int low = 0;
        int high = syncedLines.size() - 1;
        int result = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (syncedLines.get(middle).timestampMillis() <= timestampMillis) {
                result = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return result;
    }

    private double currentSeconds() {
        MediaPlayer player = playbackManager.getCurrentPlayer();
        return player == null || player.getCurrentTime() == null
                ? 0.0 : player.getCurrentTime().toSeconds();
    }

    private void syncLayout() {
        if (!active || overlayCoordinator == null) return;
        overlayCoordinator.resize();
        StackPane overlay = overlayCoordinator.overlay();
        if (overlay != null) {
            overlay.requestLayout();
            overlay.layout();
            overlay.toFront();
        }
        if (view != null) {
            view.root().requestLayout();
            view.root().layout();
        }
        if (playerBarCoordinator != null) {
            playerBarCoordinator.layoutBelowNowPlaying(
                    view == null ? null : view.playbackView()
            );
            playerBarCoordinator.bringToFront();
        }
    }

    /** Coalesces ordinary node/cover updates into one lightweight layout pass. */
    private void requestCompactLayout() {
        if (!active) return;
        long requestToken = lifecycleToken.get();
        if (scheduledCompactLayoutToken == requestToken) return;
        scheduledCompactLayoutToken = requestToken;
        Platform.runLater(() -> {
            if (scheduledCompactLayoutToken == requestToken) {
                scheduledCompactLayoutToken = -1L;
            }
            if (!active || requestToken != lifecycleToken.get()) return;
            syncLayout();
        });
    }

    /** Coalesces resize and cover-ready callbacks before recalculating artwork geometry. */
    private void requestArtworkViewportUpdate() {
        if (!active) return;
        long requestToken = lifecycleToken.get();
        if (scheduledArtworkViewportToken == requestToken) return;
        scheduledArtworkViewportToken = requestToken;
        Platform.runLater(() -> {
            if (scheduledArtworkViewportToken == requestToken) {
                scheduledArtworkViewportToken = -1L;
            }
            if (!active || requestToken != lifecycleToken.get()) return;
            updateArtworkViewport();
        });
    }

    /** Reflows the complete composition after a window or monitor change. */
    private void synchronizeLayoutNow() {
        if (!active || view == null || overlayCoordinator == null) return;
        overlayCoordinator.resize();
        javafx.scene.layout.Pane host = overlayCoordinator.host();
        if (host != null) {
            host.applyCss();
            host.requestLayout();
            host.layout();
        }
        if (overlayCoordinator.overlay() != null) {
            StackPane overlay = overlayCoordinator.overlay();
            overlay.applyCss();
            overlay.requestLayout();
            overlay.layout();
        }
        view.root().applyCss();
        view.root().requestLayout();
        view.root().layout();
        if (artworkCoordinator != null) artworkCoordinator.updateArtworkViewport();
        if (playerBarCoordinator != null) {
            playerBarCoordinator.layoutBelowNowPlaying(view.playbackView());
            playerBarCoordinator.bringToFront();
        }
        view.root().requestLayout();
    }

    private void updateArtworkViewport() {
        if (!active || artworkCoordinator == null) return;
        artworkCoordinator.updateArtworkViewport();
        syncLayout();
    }

    private void installCloseButtonActivityTracking(LyricsFullscreenView fullscreenView) {
        if (fullscreenView == null || fullscreenView.root() == null) return;

        closeButtonState.bindCloseButton(fullscreenView.playbackView().closeButton());
        closeButtonMouseActivityHandler = event -> closeButtonState.registerUserActivity();
        closeButtonScrollActivityHandler = event -> closeButtonState.registerUserActivity();
        closeButtonKeyActivityHandler = event -> closeButtonState.registerUserActivity();

        fullscreenView.root().addEventFilter(
                MouseEvent.MOUSE_MOVED, closeButtonMouseActivityHandler
        );
        fullscreenView.root().addEventFilter(
                MouseEvent.MOUSE_PRESSED, closeButtonMouseActivityHandler
        );
        fullscreenView.root().addEventFilter(
                ScrollEvent.SCROLL, closeButtonScrollActivityHandler
        );
        fullscreenView.root().addEventFilter(
                KeyEvent.KEY_PRESSED, closeButtonKeyActivityHandler
        );
    }

    private void removeCloseButtonActivityTracking() {
        if (view != null && view.root() != null) {
            if (closeButtonMouseActivityHandler != null) {
                view.root().removeEventFilter(
                        MouseEvent.MOUSE_MOVED, closeButtonMouseActivityHandler
                );
                view.root().removeEventFilter(
                        MouseEvent.MOUSE_PRESSED, closeButtonMouseActivityHandler
                );
            }
            if (closeButtonScrollActivityHandler != null) {
                view.root().removeEventFilter(
                        ScrollEvent.SCROLL, closeButtonScrollActivityHandler
                );
            }
            if (closeButtonKeyActivityHandler != null) {
                view.root().removeEventFilter(
                        KeyEvent.KEY_PRESSED, closeButtonKeyActivityHandler
                );
            }
        }
        closeButtonMouseActivityHandler = null;
        closeButtonScrollActivityHandler = null;
        closeButtonKeyActivityHandler = null;
        closeButtonState.releaseCloseButton();
    }

    private void exit() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::exit);
            return;
        }
        if (!active || changingState) return;
        changingState = true;
        active = false;
        lifecycleToken.incrementAndGet();
        songToken.incrementAndGet();
        seekGeneration++;
        try {
            disposeRuntimeState();
        } finally {
            clearRuntimeReferences();
            changingState = false;
        }
    }

    /** Switches to the regular fullscreen player without stacking overlays. */
    private void openPlayerFullscreen() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::openPlayerFullscreen);
            return;
        }
        if (!active || changingState) return;

        StartUpService service = startUpService;
        javafx.scene.layout.BorderPane root = hostRoot;
        Song currentSong = playbackManager.getCurrentSong();
        if (root == null || root.getScene() == null || currentSong == null) return;

        exit();
        Platform.runLater(() -> {
            if (root.getScene() == null) return;
            PlayerFullScreenModeController fullscreenController =
                    PlayerFullScreenModeController.getInstance();
            fullscreenController.setHostRoot(root);
            if (!fullscreenController.isActive()) {
                fullscreenController.toggle(service, currentSong);
            }
        });
    }

    private void disposeRuntimeState() {
        if (observedPlayer != null && timeListener != null) {
            observedPlayer.currentTimeProperty().removeListener(timeListener);
        }
        stopLyricScrollAnimation();
        unbindLyricsScroll();
        removeCloseButtonActivityTracking();
        observedPlayer = null;
        timeListener = null;
        if (artworkCoordinator != null) artworkCoordinator.dispose();
        if (playerBarCoordinator != null) playerBarCoordinator.dispose();
        if (windowTracker != null) windowTracker.dispose();
        if (overlayCoordinator != null) overlayCoordinator.detach();
        if (view != null) view.dispose();
        windowTracker = null;
    }

    private void clearRuntimeReferences() {
        scheduledVisualUpdateToken = -1L;
        scheduledCompactLayoutToken = -1L;
        scheduledArtworkViewportToken = -1L;
        lyricOpacities.clear();
        seekScheduled = false;
        view = null;
        artworkCoordinator = null;
        playerBarCoordinator = null;
        overlayCoordinator = null;
        hostRoot = null;
        startUpService = null;
    }
}
