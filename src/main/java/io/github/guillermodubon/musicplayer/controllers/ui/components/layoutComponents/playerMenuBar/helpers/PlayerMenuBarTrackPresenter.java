package io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.helpers;

import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import io.github.guillermodubon.musicplayer.controllers.ui.components.effects.MarqueeTextSupport;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Song;
import java.util.function.BiConsumer;

/**
 * Renders the current track area of the player bar.
 *
 * <p>The controller remains responsible for playback state while this class
 * owns the track title, artist links, artwork and marquee lifecycle.</p>
 */
public final class PlayerMenuBarTrackPresenter {

    private final StackPane titleViewport;
    private final HBox titleMarqueeBox;
    private final Label titleLabel;
    private final StackPane artistsViewport;
    private final HBox artistsContainer;
    private final VBox hoverTarget;
    private final ImageView coverImageView;
    private final PlayerMenuBarArtworkResolver artworkResolver;
    private final BiConsumer<javafx.scene.Node, Artist> artistNavigation;
    private MarqueeTextSupport marquee;
    private long artworkRequestId;

    public PlayerMenuBarTrackPresenter(StackPane titleViewport,
                                       HBox titleMarqueeBox,
                                       Label titleLabel,
                                       StackPane artistsViewport,
                                       HBox artistsContainer,
                                       VBox hoverTarget,
                                       ImageView coverImageView,
                                       PlayerMenuBarArtworkResolver artworkResolver,
                                       BiConsumer<javafx.scene.Node, Artist> artistNavigation) {
        this.titleViewport = titleViewport;
        this.titleMarqueeBox = titleMarqueeBox;
        this.titleLabel = titleLabel;
        this.artistsViewport = artistsViewport;
        this.artistsContainer = artistsContainer;
        this.hoverTarget = hoverTarget;
        this.coverImageView = coverImageView;
        this.artworkResolver = artworkResolver;
        this.artistNavigation = artistNavigation;
    }

    public void initialize() {
        marquee = new MarqueeTextSupport(
                titleViewport,
                titleMarqueeBox,
                titleLabel,
                artistsViewport,
                artistsContainer
        );
        marquee.installHover(hoverTarget);
    }

    public void updateCurrentSong(Song song) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> updateCurrentSong(song));
            return;
        }

        if (song == null) {
            artworkRequestId++;
            titleLabel.setText("");
            clearArtistLinks();
            coverImageView.setImage(artworkResolver.getDefaultCover());
            refreshMarquee();
            return;
        }

        titleLabel.setText(song.getTitle() == null ? "" : song.getTitle());
        updateArtistLinks(song);
        long currentArtworkRequest = ++artworkRequestId;
        coverImageView.setImage(artworkResolver.resolveInitialCover(song));
        artworkResolver.loadCoverAsync(song, resolved -> {
            if (currentArtworkRequest == artworkRequestId) {
                coverImageView.setImage(resolved);
            }
        });
        refreshMarquee();
    }

    private void updateArtistLinks(Song song) {
        PlayerArtistLinksRenderer.render(
                artistsContainer,
                song,
                artistNavigation,
                "player-artist-link",
                "player-artist-separator",
                "player-artist-empty"
        );
        refreshMarquee();
    }

    private void clearArtistLinks() {
        artistsContainer.getChildren().clear();
    }

    public void refreshMarquee() {
        if (marquee != null) {
            marquee.refresh();
        }
    }
}
