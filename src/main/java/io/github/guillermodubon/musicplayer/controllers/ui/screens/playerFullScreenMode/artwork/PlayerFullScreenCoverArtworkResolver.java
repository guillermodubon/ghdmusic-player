package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.artwork;

import com.google.gson.JsonObject;
import javafx.scene.image.Image;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.repository.dao.artwork.AlbumImageDao;
import io.github.guillermodubon.musicplayer.repository.dao.artwork.AlbumImageDaoImpl;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;
import io.github.guillermodubon.musicplayer.utils.MusicCardHelper;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves album artwork from persistence first and Deezer as fallback. */
final class PlayerFullScreenCoverArtworkResolver {

    private final PlayerFullScreenImageLoader imageLoader;
    private final AlbumImageDao albumImageDao;
    private final Map<Long, String> albumCoverUrlCache = new ConcurrentHashMap<>();
    private Image defaultCover;

    PlayerFullScreenCoverArtworkResolver(PlayerFullScreenImageLoader imageLoader) {
        this(imageLoader, new AlbumImageDaoImpl());
    }

    PlayerFullScreenCoverArtworkResolver(
            PlayerFullScreenImageLoader imageLoader,
            AlbumImageDao albumImageDao
    ) {
        this.imageLoader = imageLoader;
        this.albumImageDao = albumImageDao;
    }

    Image resolveLocalOrDefault(Song song) {
        Image image = resolveDatabaseCover(song);
        return image == null ? defaultCover() : image;
    }

    Image resolveBestOrDefault(Song song) {
        Image image = resolveDatabaseCover(song);
        if (image == null) {
            image = resolveRemoteCover(song);
        }
        return image == null ? defaultCover() : image;
    }

    private Image resolveDatabaseCover(Song song) {
        long albumId = albumId(song);
        Image resolved = albumId > 0
                ? MediaImageResolver.albumCover(albumId, "xl", 0, 0)
                : null;
        if (resolved != null) {
            return resolved;
        }
        if (albumId <= 0) {
            return null;
        }

        try {
            Optional<byte[]> data = albumImageDao.findBestImageData(albumId);
            return data.isPresent() ? imageLoader.fromBytes(data.get()) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Image resolveRemoteCover(Song song) {
        String url = resolveRemoteCoverUrl(song);
        if (url == null || url.isBlank()) {
            return null;
        }
        return MediaImageResolver.remoteImage(url, 0, 0);
    }

    private String resolveRemoteCoverUrl(Song song) {
        long albumId = albumId(song);
        if (albumId > 0) {
            String url = albumCoverUrl(albumId);
            if (url != null && !url.isBlank()) {
                return url;
            }
        }

        long songId = song == null ? -1L : song.getSongID();
        if (songId <= 0) {
            return null;
        }

        try {
            JsonObject track = MusicCardHelper.fetchJsonObject(
                    "https://api.deezer.com/track/" + songId
            );
            if (track == null) {
                return null;
            }

            JsonObject album = track.has("album") && track.get("album").isJsonObject()
                    ? track.getAsJsonObject("album")
                    : null;
            long remoteAlbumId = album == null
                    ? -1L
                    : DeezerApiService.safeGetLong(album, "id", -1L);
            if (remoteAlbumId > 0) {
                String url = albumCoverUrl(remoteAlbumId);
                if (url != null && !url.isBlank()) {
                    return url;
                }
            }
            return PlayerFullScreenArtworkUrlSelector.bestCover(album);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String albumCoverUrl(long albumId) {
        String cached = albumCoverUrlCache.get(albumId);
        if (cached != null) {
            return cached;
        }

        try {
            JsonObject album = MusicCardHelper.fetchJsonObject(
                    "https://api.deezer.com/album/" + albumId
            );
            String url = PlayerFullScreenArtworkUrlSelector.bestCover(album);
            if (url != null && !url.isBlank()) {
                albumCoverUrlCache.put(albumId, url);
            }
            return url;
        } catch (Exception ignored) {
            return null;
        }
    }

    private long albumId(Song song) {
        return song == null || song.getAlbum() == null
                ? -1L
                : song.getAlbum().getAlbumID();
    }

    private Image defaultCover() {
        if (defaultCover == null) {
            defaultCover = MediaImageResolver.defaultCover();
        }
        return defaultCover;
    }
}
