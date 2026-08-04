package io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.helpers;

import javafx.application.Platform;
import javafx.scene.image.Image;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class PlayerMenuBarArtworkResolver {
    private static final String PREFERRED_COVER_TYPE = "xl";
    private static final double DECODE_SIZE = 160.0;
    private static final ExecutorService COVER_IO = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "player-menu-bar-cover-io");
        thread.setDaemon(true);
        return thread;
    });

    public Image getDefaultCover() {
        return MediaImageResolver.defaultCover(DECODE_SIZE, DECODE_SIZE);
    }

    public Image resolveCover(Song song) {
        Image cover = MediaImageResolver.songAlbumCover(
                song,
                PREFERRED_COVER_TYPE,
                DECODE_SIZE,
                DECODE_SIZE
        );
        return cover != null ? cover : getDefaultCover();
    }

    /** Returns only artwork already decoded in memory for the first paint. */
    public Image resolveInitialCover(Song song) {
        Image cover = MediaImageResolver.cachedSongAlbumCover(
                song,
                PREFERRED_COVER_TYPE,
                DECODE_SIZE,
                DECODE_SIZE
        );
        return cover != null ? cover : getDefaultCover();
    }

    /** Resolves DB/remote artwork without blocking the JavaFX application thread. */
    public void loadCoverAsync(Song song, Consumer<Image> onResolved) {
        if (song == null || onResolved == null) return;

        Image cached = MediaImageResolver.cachedSongAlbumCover(
                song,
                PREFERRED_COVER_TYPE,
                DECODE_SIZE,
                DECODE_SIZE
        );
        if (isUsable(cached)) return;

        CompletableFuture
                .supplyAsync(() -> resolveCover(song), COVER_IO)
                .thenAccept(image -> {
                    if (!isUsable(image)) return;
                    Platform.runLater(() -> onResolved.accept(image));
                })
                .exceptionally(ignored -> null);
    }

    private boolean isUsable(Image image) {
        return image != null && !image.isError();
    }
}
