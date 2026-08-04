package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.artwork;

import javafx.scene.image.Image;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared cache and background preloader for artist fullscreen artwork. */
public final class PlayerFullScreenArtworkStore {

    private static final ExecutorService ARTIST_IMAGE_IO =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "fullscreen-artist-image");
                thread.setDaemon(true);
                return thread;
            });

    private final PlayerFullScreenArtworkResolver resolver = new PlayerFullScreenArtworkResolver();
    private final Map<Long, Image> artistBackdropCache = new ConcurrentHashMap<>();

    public Image get(long artistId) {
        return artistId <= 0 ? null : artistBackdropCache.get(artistId);
    }

    public void put(long artistId, Image image) {
        if (artistId > 0 && image != null) {
            artistBackdropCache.put(artistId, image);
        }
    }

    public boolean contains(long artistId) {
        return artistId > 0 && artistBackdropCache.containsKey(artistId);
    }

    public void loadAsync(StartUpService service, Song song, java.util.function.Consumer<Image> onLoaded) {
        if (service == null || song == null) {
            return;
        }
        ARTIST_IMAGE_IO.submit(() -> {
            Image image = resolver.resolveBestArtistBackdrop(service, song);
            if (isUsable(image)) {
                Artist artist = firstArtist(song);
                if (artist != null && artist.getArtistID() > 0) {
                    put(artist.getArtistID(), image);
                }
                if (onLoaded != null) {
                    onLoaded.accept(image);
                }
            }
        });
    }

    public void preload(StartUpService service, Song song) {
        Artist artist = firstArtist(song);
        if (artist == null || artist.getArtistID() <= 0 || contains(artist.getArtistID())) {
            return;
        }

        long artistId = artist.getArtistID();
        loadAsync(service, song, null);
    }

    public PlayerFullScreenArtworkResolver resolver() {
        return resolver;
    }

    private Artist firstArtist(Song song) {
        if (song == null) {
            return null;
        }
        if (song.getArtist() != null) {
            for (Artist artist : song.getArtist()) {
                if (artist != null) {
                    return artist;
                }
            }
        }
        if (song.getAlbum() != null && song.getAlbum().getArtist() != null) {
            for (Artist artist : song.getAlbum().getArtist()) {
                if (artist != null) {
                    return artist;
                }
            }
        }
        return null;
    }

    private boolean isUsable(Image image) {
        return image != null && !image.isError()
                && image.getWidth() > 0 && image.getHeight() > 0;
    }
}
