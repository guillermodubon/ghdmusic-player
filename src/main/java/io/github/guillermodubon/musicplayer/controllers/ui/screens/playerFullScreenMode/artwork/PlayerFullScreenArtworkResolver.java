package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.artwork;

import javafx.scene.image.Image;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

/**
 * Public facade used by fullscreen playback code.
 *
 * <p>Artist and album resolution are delegated to specialized components so
 * callers keep the same API while persistence, network fallbacks and image
 * construction remain independently maintainable.</p>
 */
public final class PlayerFullScreenArtworkResolver {

    private final PlayerFullScreenImageLoader imageLoader = new PlayerFullScreenImageLoader();
    private final PlayerFullScreenArtistArtworkResolver artistArtworkResolver =
            new PlayerFullScreenArtistArtworkResolver(imageLoader);
    private final PlayerFullScreenCoverArtworkResolver coverArtworkResolver =
            new PlayerFullScreenCoverArtworkResolver(imageLoader);

    /** Resolves only local/cached artist artwork without calling Deezer. */
    public Image resolveImmediateArtistBackdrop(StartUpService service, Song song) {
        return artistArtworkResolver.resolveImmediate(service, song);
    }

    /** Resolves the best artist backdrop using persistence, cache and Deezer. */
    public Image resolveBestArtistBackdrop(StartUpService service, Song song) {
        return artistArtworkResolver.resolveBest(service, song);
    }

    public boolean isPreferredArtistResolution(Image image) {
        return artistArtworkResolver.isPreferredResolution(image);
    }

    public Image resolveImmediateCover(StartUpService service, Song song) {
        Image image = artistArtworkResolver.resolveImmediate(service, song);
        return image == null ? coverArtworkResolver.resolveLocalOrDefault(song) : image;
    }

    public Image resolveBestCover(StartUpService service, Song song) {
        Image image = artistArtworkResolver.resolveBest(service, song);
        return image == null ? coverArtworkResolver.resolveBestOrDefault(song) : image;
    }
}
