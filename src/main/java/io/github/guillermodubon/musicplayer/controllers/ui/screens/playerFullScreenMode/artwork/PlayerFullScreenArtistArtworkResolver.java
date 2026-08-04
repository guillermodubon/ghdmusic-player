package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.artwork;

import com.google.gson.JsonObject;
import javafx.scene.image.Image;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.utils.MusicCardHelper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves artist portraits without mixing them with album-cover logic. */
final class PlayerFullScreenArtistArtworkResolver {

    private final PlayerFullScreenImageLoader imageLoader;
    private final Map<Long, String> portraitUrlCache = new ConcurrentHashMap<>();

    PlayerFullScreenArtistArtworkResolver(PlayerFullScreenImageLoader imageLoader) {
        this.imageLoader = imageLoader;
    }

    Image resolveImmediate(StartUpService service, Song song) {
        Artist artist = resolvePrimaryArtist(service, song, false);
        return artist == null ? null : loadPersistedPortrait(artist);
    }

    Image resolveBest(StartUpService service, Song song) {
        Artist artist = resolvePrimaryArtist(service, song, true);
        if (artist == null) {
            return null;
        }

        long artistId = artist.getArtistID();

        // The in-memory URL cache contains only URLs selected from the
        // artist endpoint. It is the cheapest remote fallback and is kept
        // before any database or network access.
        Image fallback = null;
        Image image = imageLoader.fromUrl(portraitUrlCache.get(artistId));
        if (isPreferredResolution(image)) {
            return image;
        }
        if (imageLoader.isUsable(image)) {
            fallback = image;
        }

        // MediaImageResolver checks its decoded image cache before opening
        // the database, then loads the persisted XL portrait when needed.
        image = loadPersistedPortrait(artist);
        if (isPreferredResolution(image)) {
            return image;
        }
        if (imageLoader.isUsable(image)) {
            fallback = image;
        }

        String deezerXlUrl = resolveArtistXlUrl(artistId);
        image = imageLoader.fromUrl(deezerXlUrl);
        if (imageLoader.isUsable(image)) {
            return image;
        }

        // Keep the model URL as a non-persisted last-resort source.
        Image modelImage = imageLoader.fromUrl(artist.getPortraitUrl());
        return imageLoader.isUsable(modelImage) ? modelImage : fallback;
    }

    private Artist resolvePrimaryArtist(StartUpService service, Song song, boolean allowNetworkLookup) {
        Artist primary = firstArtist(song);
        if (primary == null) {
            return null;
        }

        Artist inMemory = findInMemory(service, primary);
        if (inMemory != null) {
            return inMemory;
        }
        if (primary.getArtistID() > 0 || !allowNetworkLookup || service == null) {
            return primary;
        }

        try {
            return MusicCardHelper.resolveArtist(0L, primary.getName(), service);
        } catch (Exception ignored) {
            return primary;
        }
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

    private Artist findInMemory(StartUpService service, Artist target) {
        if (service == null || service.getArtists() == null || target == null) {
            return null;
        }

        long targetId = target.getArtistID();
        String targetName = target.getName();
        return service.getArtists().stream()
                .filter(artist -> artist != null)
                .filter(artist -> (targetId > 0 && artist.getArtistID() == targetId)
                        || (targetName != null && !targetName.isBlank()
                        && targetName.equalsIgnoreCase(artist.getName())))
                .findFirst()
                .orElse(null);
    }

    private Image loadPersistedPortrait(Artist artist) {
        long artistId = artist == null ? 0L : artist.getArtistID();
        if (artistId <= 0) {
            return null;
        }

        Image cached = MediaImageResolver.cachedArtistPortrait(
                artist,
                "xl",
                0,
                0
        );
        if (imageLoader.isUsable(cached)) {
            return cached;
        }

        return MediaImageResolver.artistPortraitFromDatabase(
                artist,
                "xl",
                0,
                0
        );
    }

    private String resolveArtistXlUrl(long artistId) {
        if (artistId <= 0) {
            return null;
        }

        String cached = portraitUrlCache.get(artistId);
        if (cached != null && !cached.isBlank()) {
            return cached;
        }

        try {
            JsonObject artist = MusicCardHelper.fetchJsonObject(
                    "https://api.deezer.com/artist/" + artistId
            );
            String url = PlayerFullScreenArtworkUrlSelector.bestArtistPortrait(artist);
            if (url != null && !url.isBlank()) {
                portraitUrlCache.put(artistId, url);
            }
            return url;
        } catch (Exception ignored) {
            return null;
        }
    }

    boolean isPreferredResolution(Image image) {
        return imageLoader.isUsable(image)
                && image.getWidth() >= 900
                && image.getHeight() >= 900;
    }
}
