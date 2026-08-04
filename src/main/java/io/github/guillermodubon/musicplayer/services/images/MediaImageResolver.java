package io.github.guillermodubon.musicplayer.services.images;

import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import io.github.guillermodubon.musicplayer.repository.DbConnectionManager;
import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

public final class MediaImageResolver {

    private static final String DEFAULT_COVER_RESOURCE = "/io/github/guillermodubon/musicplayer/assets/images/byDefaultImages/defaultPlaylist.png";
    private static final String DEFAULT_ARTIST_RESOURCE = "/io/github/guillermodubon/musicplayer/assets/images/byDefaultImages/defaultArtist.png";
    /**
     * MusicCard displays covers at up to 156 logical pixels. Decoding at 320px
     * keeps them sharp on high-DPI Windows displays without loading full-size
     * artwork into memory for every card.
     */
    private static final double CARD_IMAGE_SIZE = 320;
    /**
     * User playlist covers can arrive with a portrait or landscape aspect
     * ratio. Decode them with extra headroom before MusicCard applies its
     * square center crop, keeping the shortest dimension sharp on HiDPI.
     */
    private static final double USER_PLAYLIST_CARD_IMAGE_SIZE = 640;
    private static final int CACHE_CLEANUP_THRESHOLD = 384;
    private static final Map<String, WeakReference<Image>> IMAGE_CACHE = new ConcurrentHashMap<>();
    /**
     * Keep the recently visible artwork strongly reachable. Weak references
     * alone allow the GC to discard covers between two navigation pulses,
     * which turns a cache hit into another DB decode or HTTP request.
     */
    private static final int HOT_CACHE_LIMIT = 96;
    private static final Object HOT_CACHE_LOCK = new Object();
    private static final LinkedHashMap<String, Image> HOT_IMAGE_CACHE =
            new LinkedHashMap<>(HOT_CACHE_LIMIT, 0.75f, true);
    /** Prevents several visible cells for the same album from querying SQLite at once. */
    private static final Map<String, CompletableFuture<Image>> IN_FLIGHT_TYPED_LOADS =
            new ConcurrentHashMap<>();

    private MediaImageResolver() {}

    public static Image musicCardSongCover(Song song) {
        return songAlbumCover(song, "xl", CARD_IMAGE_SIZE, CARD_IMAGE_SIZE);
    }

    public static Image musicCardAlbumCover(Album album) {
        return albumCover(album, "xl", CARD_IMAGE_SIZE, CARD_IMAGE_SIZE);
    }

    public static Image musicCardPlaylistCover(Playlist playlist) {
        double size = isUserCreatedPlaylist(playlist)
                ? USER_PLAYLIST_CARD_IMAGE_SIZE
                : CARD_IMAGE_SIZE;
        return playlistCover(playlist, size, size);
    }

    public static Image artistCardPortrait(Artist artist) {
        return artistPortrait(artist, "xl", CARD_IMAGE_SIZE, CARD_IMAGE_SIZE);
    }

    public static Image remoteCardImage(String url) {
        return remoteImage(url, CARD_IMAGE_SIZE, CARD_IMAGE_SIZE);
    }

    public static Image songAlbumCover(Song song, String preferredType, double requestedWidth, double requestedHeight) {
        return albumCover(song == null ? null : song.getAlbum(), preferredType, requestedWidth, requestedHeight);
    }

    public static Image albumCover(Album album, String preferredType, double requestedWidth, double requestedHeight) {
        long albumId = album == null ? 0L : album.getAlbumID();
        Image image = albumCover(albumId, preferredType, requestedWidth, requestedHeight);
        if (image != null) return image;
        return remoteImage(album == null ? null : album.getCoverUrl(), requestedWidth, requestedHeight);
    }

    /**
     * Returns only an already decoded album cover. No database query or
     * network request is performed, which keeps first paint off blocking I/O.
     */
    public static Image cachedAlbumCover(Album album,
                                         String preferredType,
                                         double requestedWidth,
                                         double requestedHeight) {
        long albumId = album == null ? 0L : album.getAlbumID();
        Image image = cachedAlbumCover(albumId, preferredType, requestedWidth, requestedHeight);
        if (image != null) return image;
        return cachedRemoteImage(
                album == null ? null : album.getCoverUrl(),
                requestedWidth,
                requestedHeight
        );
    }

    public static Image cachedAlbumCover(long albumId,
                                         String preferredType,
                                         double requestedWidth,
                                         double requestedHeight) {
        if (albumId <= 0) return null;
        return cachedTypedImage(
                "AlbumImage",
                albumId,
                albumPreferences(preferredType),
                requestedWidth,
                requestedHeight
        );
    }

    public static Image cachedSongAlbumCover(Song song,
                                             String preferredType,
                                             double requestedWidth,
                                             double requestedHeight) {
        return cachedAlbumCover(
                song == null ? null : song.getAlbum(),
                preferredType,
                requestedWidth,
                requestedHeight
        );
    }

    public static Image albumCover(long albumId, String preferredType, double requestedWidth, double requestedHeight) {
        if (albumId <= 0) return null;
        return loadTypedImage(
                "AlbumImage",
                "AlbumID",
                albumId,
                albumPreferences(preferredType),
                requestedWidth,
                requestedHeight
        );
    }

    public static Image artistPortrait(Artist artist, String preferredType, double requestedWidth, double requestedHeight) {
        long artistId = artist == null ? 0L : artist.getArtistID();
        Image image = artistPortrait(artistId, preferredType, requestedWidth, requestedHeight);
        if (image != null) return image;
        return remoteImage(artist == null ? null : artist.getPortraitUrl(), requestedWidth, requestedHeight);
    }

    /**
     * Returns only an already decoded artist portrait. This is safe for UI
     * first paint because it never opens the database or starts a download.
     */
    public static Image cachedArtistPortrait(Artist artist,
                                             String preferredType,
                                             double requestedWidth,
                                             double requestedHeight) {
        long artistId = artist == null ? 0L : artist.getArtistID();
        if (artistId <= 0) return null;
        return cachedTypedImage(
                "ArtistImage",
                artistId,
                artistPreferences(preferredType),
                requestedWidth,
                requestedHeight
        );
    }

    /**
     * Loads artist artwork from persisted image blobs only. Remote artwork is
     * deliberately excluded so callers can keep cache, database and API
     * fallbacks in a deterministic order.
     */
    public static Image artistPortraitFromDatabase(Artist artist,
                                                    String preferredType,
                                                    double requestedWidth,
                                                    double requestedHeight) {
        return artistPortraitFromDatabase(
                artist == null ? 0L : artist.getArtistID(),
                preferredType,
                requestedWidth,
                requestedHeight
        );
    }

    public static Image artistPortraitFromDatabase(long artistId,
                                                    String preferredType,
                                                    double requestedWidth,
                                                    double requestedHeight) {
        if (artistId <= 0) return null;
        return loadTypedImage(
                "ArtistImage",
                "ArtistID",
                artistId,
                artistPreferences(preferredType),
                requestedWidth,
                requestedHeight
        );
    }

    public static Image cachedRemoteImage(String url, double requestedWidth, double requestedHeight) {
        if (url == null || url.isBlank()) return null;
        return cached(cacheKey("remote", normalizeRemoteUrl(url), requestedWidth, requestedHeight));
    }

    public static Image artistPortrait(long artistId, String preferredType, double requestedWidth, double requestedHeight) {
        if (artistId <= 0) return null;
        return loadTypedImage(
                "ArtistImage",
                "ArtistID",
                artistId,
                artistPreferences(preferredType),
                requestedWidth,
                requestedHeight
        );
    }

    public static Image playlistCover(Playlist playlist, double requestedWidth, double requestedHeight) {
        long playlistId = playlist == null ? 0L : playlist.getId();
        Image image = playlistCover(playlistId, requestedWidth, requestedHeight);
        if (image != null) return image;
        return remoteImage(playlist == null ? null : playlist.getCoverUrl(), requestedWidth, requestedHeight);
    }

    /** Returns only a decoded playlist cover already present in memory. */
    public static Image cachedPlaylistCover(Playlist playlist,
                                            double requestedWidth,
                                            double requestedHeight) {
        long playlistId = playlist == null ? 0L : playlist.getId();
        Image image = cachedPlaylistCover(playlistId, requestedWidth, requestedHeight);
        if (image != null) return image;
        return cachedRemoteImage(
                playlist == null ? null : playlist.getCoverUrl(),
                requestedWidth,
                requestedHeight
        );
    }

    public static Image cachedPlaylistCover(long playlistId,
                                            double requestedWidth,
                                            double requestedHeight) {
        if (playlistId <= 0) return null;
        return cached(cacheKey("playlist", String.valueOf(playlistId), requestedWidth, requestedHeight));
    }

    public static Image playlistCover(long playlistId, double requestedWidth, double requestedHeight) {
        if (playlistId <= 0) return null;
        String cacheKey = cacheKey("playlist", String.valueOf(playlistId), requestedWidth, requestedHeight);
        Image cached = cached(cacheKey);
        if (cached != null) return cached;

        String sql = "SELECT CoverImage FROM Playlist WHERE PlaylistID = ? LIMIT 1";
        try (Connection conn = DbConnectionManager.getInstance().openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playlistId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                byte[] data = rs.getBytes(1);
                return imageFromBytes(data, requestedWidth, requestedHeight, cacheKey);
            }
        } catch (Exception ex) {
            System.out.println("MediaImageResolver.playlistCover: playlistId=" + playlistId + " -> " + safeMessage(ex));
            return null;
        }
    }

    public static void invalidatePlaylistCover(long playlistId) {
        if (playlistId <= 0) return;
        String prefix = "playlist:" + playlistId + ":";
        IMAGE_CACHE.keySet().removeIf(key -> key != null && key.startsWith(prefix));
        synchronized (HOT_CACHE_LOCK) {
            HOT_IMAGE_CACHE.keySet().removeIf(key -> key != null && key.startsWith(prefix));
        }
    }

    public static Image remoteImage(String url, double requestedWidth, double requestedHeight) {
        if (url == null || url.isBlank()) return null;
        String normalized = normalizeRemoteUrl(url);
        String cacheKey = cacheKey("remote", normalized, requestedWidth, requestedHeight);
        synchronized (HOT_CACHE_LOCK) {
            Image cached = cached(cacheKey);
            if (cached != null) return cached;

            try {
                return remember(cacheKey, new Image(normalized, requestedWidth, requestedHeight, true, true, true));
            } catch (Exception ex) {
                return null;
            }
        }
    }

    public static Image defaultCover() {
        return resourceImage(DEFAULT_COVER_RESOURCE, 0, 0);
    }

    public static Image defaultCover(double requestedWidth, double requestedHeight) {
        return resourceImage(DEFAULT_COVER_RESOURCE, requestedWidth, requestedHeight);
    }

    public static Image defaultArtist(double requestedWidth, double requestedHeight) {
        return resourceImage(DEFAULT_ARTIST_RESOURCE, requestedWidth, requestedHeight);
    }

    public static Image resourceImage(String resourcePath, double requestedWidth, double requestedHeight) {
        if (resourcePath == null || resourcePath.isBlank()) return new WritableImage(1, 1);
        String cacheKey = cacheKey("resource", resourcePath, requestedWidth, requestedHeight);
        Image cached = cached(cacheKey);
        if (cached != null) return cached;

        try (InputStream is = MediaImageResolver.class.getResourceAsStream(resourcePath)) {
            if (is == null) return new WritableImage(1, 1);
            return remember(cacheKey, new Image(is, requestedWidth, requestedHeight, true, true));
        } catch (Exception ex) {
            return new WritableImage(1, 1);
        }
    }

    private static boolean isUserCreatedPlaylist(Playlist playlist) {
        return playlist != null
                && playlist.getAuthorName() != null
                && playlist.getAuthorName().trim().equalsIgnoreCase("User");
    }

    private static Image loadTypedImage(String table,
                                        String idColumn,
                                        long id,
                                        List<String> preferences,
                                        double requestedWidth,
                                        double requestedHeight) {
        String cacheKey = cacheKey("db:" + table, id + ":" + String.join(",", preferences), requestedWidth, requestedHeight);
        Image cached = cached(cacheKey);
        if (cached != null) return cached;

        CompletableFuture<Image> created = new CompletableFuture<>();
        CompletableFuture<Image> pending = IN_FLIGHT_TYPED_LOADS.putIfAbsent(cacheKey, created);
        if (pending != null) {
            return joinImage(pending);
        }

        try (Connection conn = DbConnectionManager.getInstance().openConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT ImageData
                       FROM %s
                      WHERE %s = ?
                   ORDER BY CASE ImageType %s ELSE 99 END
                      LIMIT 1
                     """.formatted(table, idColumn, preferenceSql(preferences)))) {
            ps.setLong(1, id);
            Image result = null;
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    result = imageFromBytes(rs.getBytes(1), requestedWidth, requestedHeight, cacheKey);
                }
            }
            created.complete(result);
            return result;
        } catch (Exception ex) {
            System.out.println("MediaImageResolver.loadTypedImage: " + table + " id=" + id + " -> " + safeMessage(ex));
            created.complete(null);
            return null;
        } finally {
            IN_FLIGHT_TYPED_LOADS.remove(cacheKey, created);
        }
    }

    private static Image cachedTypedImage(String table,
                                          long id,
                                          List<String> preferences,
                                          double requestedWidth,
                                          double requestedHeight) {
        if (id <= 0 || preferences == null || preferences.isEmpty()) return null;
        String cacheKey = cacheKey(
                "db:" + table,
                id + ":" + String.join(",", preferences),
                requestedWidth,
                requestedHeight
        );
        return cached(cacheKey);
    }

    private static Image imageFromBytes(byte[] data,
                                        double requestedWidth,
                                        double requestedHeight,
                                        String cacheKey) {
        if (data == null || data.length == 0) return null;
        try {
            Image image = new Image(new ByteArrayInputStream(data), requestedWidth, requestedHeight, true, true);
            return cacheKey == null ? image : remember(cacheKey, image);
        } catch (Exception ex) {
            return null;
        }
    }

    private static Image cached(String key) {
        if (key == null) return null;
        synchronized (HOT_CACHE_LOCK) {
            Image hot = HOT_IMAGE_CACHE.get(key);
            if (hot != null && !hot.isError()) return hot;
            if (hot != null) HOT_IMAGE_CACHE.remove(key);
        }
        WeakReference<Image> ref = IMAGE_CACHE.get(key);
        Image image = ref == null ? null : ref.get();
        if (image == null || image.isError()) {
            IMAGE_CACHE.remove(key);
            return null;
        }
        rememberHot(key, image);
        return image;
    }

    private static Image remember(String key, Image image) {
        if (key != null && image != null && !image.isError()) {
            IMAGE_CACHE.put(key, new WeakReference<>(image));
            rememberHot(key, image);
            cleanupIfNeeded();
        }
        return image;
    }

    private static void rememberHot(String key, Image image) {
        if (key == null || image == null || image.isError()) return;
        synchronized (HOT_CACHE_LOCK) {
            HOT_IMAGE_CACHE.put(key, image);
            while (HOT_IMAGE_CACHE.size() > HOT_CACHE_LIMIT) {
                HOT_IMAGE_CACHE.remove(HOT_IMAGE_CACHE.entrySet().iterator().next().getKey());
            }
        }
    }

    private static Image joinImage(CompletableFuture<Image> future) {
        try {
            return future.join();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void cleanupIfNeeded() {
        if (IMAGE_CACHE.size() < CACHE_CLEANUP_THRESHOLD) return;
        IMAGE_CACHE.entrySet().removeIf(entry -> {
            Image image = entry.getValue() == null ? null : entry.getValue().get();
            return image == null || image.isError();
        });
    }

    private static String cacheKey(String source, String id, double requestedWidth, double requestedHeight) {
        return source + ":" + id + ":" + sizeKey(requestedWidth) + "x" + sizeKey(requestedHeight);
    }

    private static String sizeKey(double value) {
        if (value <= 0) return "0";
        return String.valueOf(Math.round(value));
    }

    private static List<String> albumPreferences(String preferredType) {
        return switch (normalize(preferredType)) {
            case "small" -> List.of("small", "medium", "xl", "big");
            case "medium" -> List.of("medium", "xl", "big", "small");
            case "big" -> List.of("xl", "big", "medium", "small");
            default -> List.of("xl", "big", "medium", "small");
        };
    }

    private static List<String> artistPreferences(String preferredType) {
        return switch (normalize(preferredType)) {
            case "small" -> List.of("small", "medium", "big", "xl");
            case "medium" -> List.of("medium", "big", "xl", "small");
            case "big" -> List.of("xl", "big", "medium", "small");
            default -> List.of("xl", "big", "medium", "small");
        };
    }

    /**
     * Deezer sometimes returns a valid but smaller CDN variant in persisted
     * metadata. The CDN supports the 1000px variant for the same artwork key;
     * upgrade only Deezer image hosts so unrelated URLs remain untouched.
     */
    private static String normalizeRemoteUrl(String url) {
        String normalized = url == null ? "" : url.trim();
        if (normalized.isBlank()) return normalized;

        String lower = normalized.toLowerCase(Locale.ROOT);
        if (!lower.contains("dzcdn.net") && !lower.contains("deezer.com")) {
            return normalized;
        }
        return normalized.replaceFirst("/\\d+x\\d+-", "/1000x1000-");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String preferenceSql(List<String> preferences) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < preferences.size(); i++) {
            sb.append(" WHEN '")
                    .append(preferences.get(i).replace("'", "''"))
                    .append("' THEN ")
                    .append(i);
        }
        return sb.toString();
    }

    private static String safeMessage(Exception ex) {
        return ex == null || ex.getMessage() == null ? "unknown" : ex.getMessage();
    }
}
