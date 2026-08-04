package io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.services;

import javafx.scene.image.Image;
import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;

public class SongCoverResolver {

    private static final String DEFAULT_PREFERRED_TYPE = "small";
    private static final double DEFAULT_DECODE_SIZE = 72;
    private static final String PREVIEW_PREFERRED_TYPE = "xl";
    private static final double PREVIEW_DECODE_SIZE = 320;

    public Image resolveCover(Song song) {
        return resolveCover(song, DEFAULT_PREFERRED_TYPE, DEFAULT_DECODE_SIZE, DEFAULT_DECODE_SIZE);
    }

    /**
     * Resolves artwork for the larger preview window without changing the
     * smaller song-row artwork resolution.
     */
    public Image resolvePreviewCover(Song song) {
        return resolveCover(
                song,
                PREVIEW_PREFERRED_TYPE,
                PREVIEW_DECODE_SIZE,
                PREVIEW_DECODE_SIZE
        );
    }

    public Image resolveCover(Album album) {
        return resolveCover(album, DEFAULT_PREFERRED_TYPE, DEFAULT_DECODE_SIZE, DEFAULT_DECODE_SIZE);
    }

    public Image resolveCover(Song song,
                              String preferredType,
                              double requestedWidth,
                              double requestedHeight) {
        if (song == null) {
            return getDefaultCover(requestedWidth, requestedHeight);
        }
        return resolveCover(song.getAlbum(), preferredType, requestedWidth, requestedHeight);
    }

    public Image resolveCover(Album album,
                              String preferredType,
                              double requestedWidth,
                              double requestedHeight) {
        Image cover = MediaImageResolver.albumCover(
                album,
                preferredType,
                requestedWidth,
                requestedHeight
        );
        return cover != null
                ? cover
                : getDefaultCover(requestedWidth, requestedHeight);
    }

    public Image resolveCachedCover(Song song,
                                    String preferredType,
                                    double requestedWidth,
                                    double requestedHeight) {
        Image cover = MediaImageResolver.cachedSongAlbumCover(
                song,
                preferredType,
                requestedWidth,
                requestedHeight
        );
        return cover != null
                ? cover
                : getDefaultCover(requestedWidth, requestedHeight);
    }

    public Image getDefaultCover() {
        return getDefaultCover(DEFAULT_DECODE_SIZE, DEFAULT_DECODE_SIZE);
    }

    public Image getDefaultCover(double requestedWidth, double requestedHeight) {
        return MediaImageResolver.defaultCover(requestedWidth, requestedHeight);
    }
}
