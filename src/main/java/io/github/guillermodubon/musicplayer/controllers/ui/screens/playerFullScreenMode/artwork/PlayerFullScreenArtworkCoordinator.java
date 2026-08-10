package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.artwork;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.scene.image.Image;
import javafx.scene.Node;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.background.PlayerFullScreenBackgroundStyler;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.view.PlayerFullScreenView;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;
import io.github.guillermodubon.musicplayer.services.images.colors.CoverColorExtractor;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;

/** Coordinates song artwork, metadata and the ambient fullscreen background. */
public final class PlayerFullScreenArtworkCoordinator {

    private static final double COVER_DECODE_SIZE = 1000.0;
    private static final ExecutorService COVER_IO = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "fullscreen-cover-io");
        thread.setDaemon(true);
        return thread;
    });
    private static final ExecutorService PALETTE_IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "fullscreen-palette-io");
        thread.setDaemon(true);
        return thread;
    });

    private final PlayerFullScreenView view;
    private final BooleanSupplier active;
    private final BooleanSupplier exiting;
    private final Runnable updateViewport;
    private final BiConsumer<Node, Artist> artistNavigation;
    private final PlayerFullScreenBackgroundStyler backgroundStyler;

    private Image observedCoverImage;
    private ChangeListener<Number> coverProgressListener;
    private ChangeListener<Boolean> coverErrorListener;
    private volatile long updateToken;

    public PlayerFullScreenArtworkCoordinator(
            PlayerFullScreenView view,
            BooleanSupplier active,
            BooleanSupplier exiting,
            Runnable updateViewport,
            BiConsumer<Node, Artist> artistNavigation
    ) {
        this.view = view;
        this.active = active;
        this.exiting = exiting;
        this.updateViewport = updateViewport;
        this.artistNavigation = artistNavigation;
        this.backgroundStyler = new PlayerFullScreenBackgroundStyler(view.ambientBackground());
    }

    public void updateSong(StartUpService service, Song song) {
        long token = ++updateToken;
        detachCoverListeners();

        if (song == null) {
            clearSongPresentation();
            return;
        }

        setOverlayVisible(true);
        view.songTitleLabel().setText(normalizeTitle(song));
        PlayerFullScreenArtistLinksRenderer.render(
                view.artistsContainer(),
                song,
                artistNavigation
        );
        bringArtistLinksToFront();
        view.metadataMarquee().refresh();

        Image cachedCover = MediaImageResolver.cachedSongAlbumCover(
                song,
                "xl",
                COVER_DECODE_SIZE,
                COVER_DECODE_SIZE
        );
        if (isAvailableImage(cachedCover)) {
            presentCover(cachedCover, token);
            return;
        }

        presentCover(MediaImageResolver.defaultCover(700, 700), token);
        CompletableFuture
                .supplyAsync(() -> resolveSongCover(song), COVER_IO)
                .thenAccept(image -> Platform.runLater(() -> {
                    if (!isCurrent(token) || !isAvailableImage(image)) return;
                    presentCover(image, token);
                }))
                .exceptionally(error -> null);
    }

    public void updateArtworkViewport() {
        if (view.root() == null || view.artworkContainer() == null
                || view.songCoverImageView() == null) {
            return;
        }

        double width = view.root().getWidth();
        double height = view.root().getHeight();
        if (width <= 1 || height <= 1) return;

        // Reserve a stable lower band for the dedicated fullscreen controls.
        // Its cover-first composition stays balanced instead of competing
        // with the time and volume sliders on shorter displays.
        boolean narrow = width < 560.0 || height < 620.0;
        boolean compact = !narrow && (width < 900.0 || height < 820.0);
        double controlsReserve = narrow ? 156.0 : compact ? 174.0 : 190.0;
        double copyReserve = narrow ? 58.0 : 68.0;
        double compositionWidth = view.nowPlayingOverlay().getWidth();
        if (compositionWidth <= 1.0) {
            compositionWidth = view.nowPlayingOverlay().prefWidth(-1.0);
        }
        boolean splitComposition = compositionWidth > 1.0 && compositionWidth < width * 0.75;
        double layoutReserve = splitComposition ? 24.0 : 0.0;
        double availableHeight = Math.max(
                170.0,
                height - controlsReserve - copyReserve - layoutReserve
        );
        double horizontalLimit = compositionWidth > 1.0
                ? Math.max(80.0, compositionWidth - (narrow ? 28.0 : 48.0))
                : width;
        // A larger cover remains the visual anchor of the mode. The playback
        // bar reads this exact width, so its time rail grows with the artwork.
        double size = Math.min(
                width * (narrow ? 0.72 : 0.44),
                Math.min(
                        availableHeight * (narrow ? 0.78 : 0.88),
                        horizontalLimit
                )
        );
        double minimumSize = Math.min(narrow ? 165.0 : 200.0, horizontalLimit);
        size = Math.max(minimumSize, Math.min(narrow ? 440.0 : 720.0, size));

        double topPadding = narrow ? 10.0 : 16.0;
        double bottomPadding = controlsReserve + layoutReserve + (narrow ? 8.0 : 12.0);
        view.nowPlayingOverlay().setSpacing(narrow ? 12.0 : 14.0);
        view.nowPlayingOverlay().setPadding(new Insets(
                topPadding,
                narrow ? 16.0 : 24.0,
                bottomPadding,
                narrow ? 16.0 : 24.0
        ));

        view.artworkContainer().setMinSize(size, size);
        view.artworkContainer().setPrefSize(size, size);
        view.artworkContainer().setMaxSize(size, size);
        view.songCoverImageView().setFitWidth(size);
        view.songCoverImageView().setFitHeight(size);
        view.songCoverImageView().setPreserveRatio(true);
        view.songCoverImageView().setSmooth(true);
        view.songCoverImageView().setCache(false);

        // Resizing and the lyrics column are both allowed to participate in
        // layout. Reassert the interactive metadata layer after geometry is
        // recalculated so the links cannot end up behind a later-added pane.
        bringArtistLinksToFront();
    }

    public void dispose() {
        updateToken++;
        detachCoverListeners();
        backgroundStyler.dispose();
    }

    private Image resolveSongCover(Song song) {
        try {
            Image image = MediaImageResolver.songAlbumCover(
                    song,
                    "xl",
                    COVER_DECODE_SIZE,
                    COVER_DECODE_SIZE
            );
            return isAvailableImage(image)
                    ? image
                    : MediaImageResolver.defaultCover(700, 700);
        } catch (Exception ignored) {
            return MediaImageResolver.defaultCover(700, 700);
        }
    }

    private void presentCover(Image image, long token) {
        if (!isCurrent(token) || view.songCoverImageView() == null) return;

        Image safeImage = isAvailableImage(image)
                ? image
                : MediaImageResolver.defaultCover(700, 700);
        view.songCoverImageView().setImage(safeImage);
        applyBackgroundWhenReady(safeImage, token);
        requestViewportUpdate();
    }

    private void applyBackgroundWhenReady(Image image, long token) {
        if (!isCurrent(token)) return;
        detachCoverListeners();

        if (image.getProgress() >= 1.0) {
            applyBackground(image, token);
            return;
        }

        observedCoverImage = image;
        coverProgressListener = (obs, oldProgress, newProgress) -> {
            if (newProgress != null && newProgress.doubleValue() >= 1.0) {
                Platform.runLater(() -> {
                    if (isCurrent(token)) {
                        applyBackground(image, token);
                        updateViewport.run();
                    }
                });
                detachCoverListeners();
            }
        };
        coverErrorListener = (obs, wasError, isError) -> {
            if (Boolean.TRUE.equals(isError) && isCurrent(token)) {
                // A cached/remote image can fail after it was assigned to the
                // ImageView. Keep the presentation complete instead of
                // leaving the cover and ambient background empty.
                presentCover(MediaImageResolver.defaultCover(700, 700), token);
            }
        };
        image.progressProperty().addListener(coverProgressListener);
        image.errorProperty().addListener(coverErrorListener);
    }

    private void applyBackground(Image image, long token) {
        if (!isCurrent(token) || image == null || image.isError()) return;
        backgroundStyler.applyArtwork(image);
        CompletableFuture
                .supplyAsync(() -> CoverColorExtractor.extractFullscreenColors(image, 3), PALETTE_IO)
                .thenAccept(colors -> Platform.runLater(() -> {
                    if (isCurrent(token)) {
                        backgroundStyler.applyPalette(colors);
                    }
                }))
                .exceptionally(ignored -> null);
    }

    private void clearSongPresentation() {
        setOverlayVisible(false);
        view.songTitleLabel().setText("");
        view.artistsContainer().getChildren().clear();
        view.metadataMarquee().refresh();
        view.songCoverImageView().setImage(MediaImageResolver.defaultCover(700, 700));
        backgroundStyler.clear();
        requestViewportUpdate();
    }

    private void requestViewportUpdate() {
        if (Platform.isFxApplicationThread()) {
            updateViewport.run();
        } else {
            Platform.runLater(updateViewport);
        }
    }

    /**
     * The lyrics panel is appended after the reusable player composition.
     * Keep the metadata branch above that panel so hyperlinks retain their
     * hover and click target while the background remains non-interactive.
     */
    private void bringArtistLinksToFront() {
        if (view.artistsContainer() == null) {
            return;
        }

        view.artistsContainer().setMouseTransparent(false);
        view.artistsContainer().setPickOnBounds(false);
        view.artistsContainer().toFront();
        Node artistViewport = view.artistsContainer().getParent();
        if (artistViewport != null) {
            artistViewport.setMouseTransparent(false);
            artistViewport.setPickOnBounds(false);
            artistViewport.toFront();
        }
        if (view.nowPlayingOverlay() != null) {
            view.nowPlayingOverlay().toFront();
        }
        if (view.closeButton() != null) {
            view.closeButton().toFront();
        }
    }

    private void setOverlayVisible(boolean visible) {
        view.nowPlayingOverlay().setVisible(visible);
        view.nowPlayingOverlay().setManaged(visible);
    }

    private String normalizeTitle(Song song) {
        return OptionalText.value(song == null ? null : song.getTitle(), "Unknown title");
    }

    private boolean isCurrent(long token) {
        return active.getAsBoolean() && !exiting.getAsBoolean() && token == updateToken;
    }

    private boolean isAvailableImage(Image image) {
        return image != null && !image.isError()
                && (image.getProgress() < 1.0 || (image.getWidth() > 1 && image.getHeight() > 1));
    }

    private void detachCoverListeners() {
        if (observedCoverImage != null) {
            if (coverProgressListener != null) {
                observedCoverImage.progressProperty().removeListener(coverProgressListener);
            }
            if (coverErrorListener != null) {
                observedCoverImage.errorProperty().removeListener(coverErrorListener);
            }
        }
        observedCoverImage = null;
        coverProgressListener = null;
        coverErrorListener = null;
    }

    private static final class OptionalText {
        private OptionalText() {
        }

        private static String value(String value, String fallback) {
            return value == null || value.trim().isBlank() ? fallback : value.trim();
        }
    }
}
