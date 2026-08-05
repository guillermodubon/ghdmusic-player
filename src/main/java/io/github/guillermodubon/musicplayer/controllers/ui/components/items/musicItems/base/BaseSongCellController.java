package io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.base;

import javafx.fxml.FXML;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import io.github.guillermodubon.musicplayer.controllers.ui.components.effects.MarqueeTextSupport;
import io.github.guillermodubon.musicplayer.utils.NavigationHelper;
import io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.services.SongArtistResolver;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.utils.ArtistIdentity;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public abstract class BaseSongCellController {

    private static final ExecutorService COVER_IO = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "song-cover-io");
        thread.setDaemon(true);
        return thread;
    });

    @FXML
    protected ImageView coverView;
    @FXML protected Label titleLabel;
    @FXML protected StackPane titleViewport;
    @FXML protected HBox titleTrack;
    @FXML protected HBox artistContainer;
    @FXML protected StackPane artistViewport;

    protected Song song;
    protected StartUpService svc;
    private MarqueeTextSupport titleMarquee;
    private MarqueeTextSupport artistMarquee;
    protected String coverPreferredType = "small";
    protected double coverDecodeWidth = 72;
    protected double coverDecodeHeight = 72;
    private boolean deferCoverResolution;
    private long renderGeneration;

    public void setStartUpService(StartUpService svc) {
        this.svc = svc;
    }

    /**
     * Configures the source variant and decode size used by this cell.
     * The defaults preserve the behavior of cells outside PlayerMenu.
     */
    public void setCoverImageQuality(String preferredType,
                                     double requestedWidth,
                                     double requestedHeight) {
        if (preferredType != null && !preferredType.isBlank()) {
            this.coverPreferredType = preferredType.trim();
        }
        if (requestedWidth > 0) {
            this.coverDecodeWidth = requestedWidth;
        }
        if (requestedHeight > 0) {
            this.coverDecodeHeight = requestedHeight;
        }
    }

    /**
     * Uses only memory-decoded artwork during first paint. The complete
     * cache/DB/remote resolution can then be requested with loadCoverAsync.
     */
    public void setDeferCoverResolution(boolean deferCoverResolution) {
        this.deferCoverResolution = deferCoverResolution;
    }

    protected boolean isDeferCoverResolution() {
        return deferCoverResolution;
    }

    protected void bindSongBasics(Song song) {
        beginSongRender(song);
        titleLabel.setText(resolveTitle(song));
        refreshTitleMarquee();
        coverView.setImage(resolveInitialCover(song));
        renderArtists(resolveArtists(song));
    }

    /** Starts a new render so late async work cannot update a reused cell. */
    protected long beginSongRender(Song song) {
        renderGeneration++;
        this.song = song;
        return renderGeneration;
    }

    protected boolean isCurrentRender(long expectedGeneration) {
        return renderGeneration == expectedGeneration;
    }

    protected long currentRenderGeneration() {
        return renderGeneration;
    }

    protected String resolveTitle(Song song) {
        if (song == null || song.getTitle() == null) return "";
        return song.getTitle();
    }

    protected Image resolveCover(Song song) {
        Image image = MediaImageResolver.songAlbumCover(
                song,
                coverPreferredType,
                coverDecodeWidth,
                coverDecodeHeight
        );
        return image != null
                ? image
                : MediaImageResolver.defaultCover(coverDecodeWidth, coverDecodeHeight);
    }

    protected Image resolveInitialCover(Song song) {
        if (deferCoverResolution) {
            Image cached = MediaImageResolver.cachedSongAlbumCover(
                    song,
                    coverPreferredType,
                    coverDecodeWidth,
                    coverDecodeHeight
            );
            return cached != null
                    ? cached
                    : MediaImageResolver.defaultCover(coverDecodeWidth, coverDecodeHeight);
        }
        return resolveCover(song);
    }

    /** Resolves DB/API artwork off the JavaFX thread and applies it safely. */
    public void loadCoverAsync(Song expectedSong) {
        if (!deferCoverResolution || expectedSong == null || coverView == null) return;

        long expectedGeneration = renderGeneration;

        Image cached = MediaImageResolver.cachedSongAlbumCover(
                expectedSong,
                coverPreferredType,
                coverDecodeWidth,
                coverDecodeHeight
        );
        if (isUsableImage(cached)) return;

        CompletableFuture
                .supplyAsync(() -> resolveCover(expectedSong), COVER_IO)
                .thenAccept(resolved -> {
                    if (!isUsableImage(resolved)) return;
                    Platform.runLater(() -> {
                        if (isCurrentRender(expectedGeneration)
                                && isCurrentSong(expectedSong)
                                && coverView != null) {
                            coverView.setImage(resolved);
                        }
                    });
                })
                .exceptionally(ignored -> null);
    }

    protected boolean isCurrentSong(Song expectedSong) {
        if (expectedSong == null || song == null) return false;
        if (song == expectedSong) return true;

        if (song.getSongID() <= 0
                || expectedSong.getSongID() <= 0
                || song.getSongID() != expectedSong.getSongID()) {
            return false;
        }

        long currentAlbumId = song.getAlbum() == null ? 0L : song.getAlbum().getAlbumID();
        long expectedAlbumId = expectedSong.getAlbum() == null ? 0L : expectedSong.getAlbum().getAlbumID();
        if (currentAlbumId > 0 && expectedAlbumId > 0 && currentAlbumId != expectedAlbumId) {
            return false;
        }

        int currentTrackOrder = song.getTrackOrder();
        int expectedTrackOrder = expectedSong.getTrackOrder();
        return currentTrackOrder <= 0
                || expectedTrackOrder <= 0
                || currentTrackOrder == expectedTrackOrder;
    }

    private boolean isUsableImage(Image image) {
        return image != null && !image.isError();
    }

    protected List<Artist> resolveArtists(Song song) {
        return SongArtistResolver.resolveParticipants(song);
    }

    protected void renderArtists(List<Artist> artists) {
        artistContainer.getChildren().clear();

        if (artists == null || artists.isEmpty()) {
            refreshArtistMarquee();
            return;
        }

        int rendered = 0;
        for (Artist artist : artists) {
            if (artist == null) continue;

            String name = ArtistIdentity.displayName(artist.getName());

            if (rendered > 0) {
                Label separator = new Label(", ");
                separator.getStyleClass().addAll("artist-separator", "song-item-artist-separator");
                artistContainer.getChildren().add(separator);
            }

            if (shouldRenderArtistAsPlainText(artist)) {
                Label label = new Label(name);
                label.getStyleClass().addAll("artist-link", "artist-plain-label");
                label.setMouseTransparent(true);
                artistContainer.getChildren().add(label);
            } else {
                Hyperlink link = new Hyperlink(name);
                link.getStyleClass().addAll("app-hyperlink", "artist-link");
                link.setFocusTraversable(false);
                link.setOnAction(e -> onArtistClicked(artist));
                artistContainer.getChildren().add(link);
            }
            rendered++;
        }
        refreshArtistMarquee();
    }

    /** Allows a cell type to keep non-navigable artist labels as plain text. */
    protected boolean shouldRenderArtistAsPlainText(Artist artist) {
        return ArtistIdentity.isVariousArtists(artist);
    }

    protected void configureArtistMarquee(Node hoverNode) {
        if (artistViewport == null || artistContainer == null || artistMarquee != null) return;
        artistMarquee = new MarqueeTextSupport(null, null, null, artistViewport, artistContainer);
        artistMarquee.installHover(hoverNode == null ? artistViewport : hoverNode);
        artistViewport.widthProperty().addListener((obs, oldValue, newValue) -> refreshArtistMarquee());
    }

    protected void configureTitleMarquee(Node hoverNode) {
        if (titleViewport == null || titleTrack == null || titleLabel == null || titleMarquee != null) return;
        titleMarquee = new MarqueeTextSupport(titleViewport, titleTrack, titleLabel, null, null);
        titleMarquee.installHover(hoverNode == null ? titleViewport : hoverNode);
        titleViewport.widthProperty().addListener((obs, oldValue, newValue) -> refreshTitleMarquee());
        titleLabel.textProperty().addListener((obs, oldValue, newValue) -> refreshTitleMarquee());
    }

    protected void refreshTitleMarquee() {
        if (titleMarquee != null) {
            titleMarquee.refresh();
        }
    }

    protected void refreshArtistMarquee() {
        if (artistMarquee != null) {
            artistMarquee.refresh();
        }
    }

    protected void onArtistClicked(Artist artist) {
        if (artist == null) return;
        try {
            if (artist.getArtistID() > 0 && coverView.getScene() != null) {
                BorderPane root = (BorderPane) coverView.getScene().getRoot();
                NavigationHelper.showArtistScreen(artist, svc, root);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    protected void bindPlayAction(Node node, Runnable action) {
        if (node == null || action == null) return;
        node.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY || e.getClickCount() != 1) return;
            if (isInteractiveClickTarget(e.getTarget())) return;

            action.run();
            e.consume();
        });
    }

    /** Defers a row action until release so a drag gesture does not activate it. */
    protected void bindPlayActionOnRelease(Node node, Runnable action) {
        if (node == null || action == null) return;
        node.setOnMouseReleased(e -> {
            if (e.getButton() != MouseButton.PRIMARY || e.getClickCount() != 1) return;
            if (isInteractiveClickTarget(e.getTarget())) return;

            action.run();
            e.consume();
        });
    }

    /** Reuses the standard row-click behavior for song cells. */
    protected void bindSongPlayAction(Node node, Consumer<Song> onPlaySong) {
        Song current = song;
        bindPlayAction(node, () -> {
            if (current != null && onPlaySong != null) {
                onPlaySong.accept(current);
            }
        });
    }

    private boolean isInteractiveClickTarget(Object target) {
        if (!(target instanceof Node node)) return false;

        Node current = node;
        while (current != null) {
            if (current instanceof ButtonBase) {
                return true;
            }
            current = current.getParent();
        }

        return false;
    }

}
