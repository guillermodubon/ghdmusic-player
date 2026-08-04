package io.github.guillermodubon.musicplayer.controllers.ui.components.downloadComponents.downloadCell;

import com.google.gson.JsonObject;
import javafx.scene.image.Image;
import io.github.guillermodubon.musicplayer.services.downloads.DownloadTask;
import io.github.guillermodubon.musicplayer.models.DeezerApiMetaData;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;
import io.github.guillermodubon.musicplayer.services.api.DeezerHttpClient;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.utils.DeezerEndpoints;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

final class DownloadTaskCoverResolver {

    static final double COVER_DECODE_SIZE = 320;
    private static final String COVER_PREFERRED_TYPE = "xl";
    private static final ExecutorService COVER_EXECUTOR = Executors.newFixedThreadPool(2, daemonFactory());
    private static final int CACHE_CLEANUP_THRESHOLD = 192;
    private static final ConcurrentMap<String, WeakReference<Image>> COVER_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, CompletableFuture<Image>> IN_FLIGHT = new ConcurrentHashMap<>();

    void resolveAsync(DownloadTask task, Consumer<Image> onResolved) {
        if (task == null || onResolved == null) return;

        String cacheKey = cacheKey(task);
        Image cached = cachedCover(cacheKey);
        if (isUsable(cached)) {
            onResolved.accept(cached);
            return;
        }

        CompletableFuture<Image> resolution = IN_FLIGHT.computeIfAbsent(
                cacheKey,
                ignored -> CompletableFuture
                        .supplyAsync(() -> resolve(task), COVER_EXECUTOR)
                        .exceptionally(error -> fallbackCover(task))
        );

        resolution.whenComplete((cover, error) -> {
            Image resolved = isUsable(cover) ? cover : fallbackCover(task);
            rememberCover(cacheKey, resolved);
            IN_FLIGHT.remove(cacheKey, resolution);
            onResolved.accept(resolved);
        });
    }

    private String cacheKey(DownloadTask task) {
        DeezerApiMetaData hint = task.getContext() == null ? null : task.getContext().getMetadataHint();
        long albumId = hint == null ? 0L : hint.getAlbumId();
        if (albumId > 0) {
            return "album:" + albumId;
        }

        String url = hint == null ? null : hint.getAlbumCoverUrl();
        if (url != null && !url.isBlank()) {
            return "url:" + url.trim();
        }

        String query = task.getQuery();
        return "query:" + (query == null ? "" : query.trim().toLowerCase());
    }

    private Image cachedCover(String key) {
        WeakReference<Image> reference = COVER_CACHE.get(key);
        Image image = reference == null ? null : reference.get();
        if (!isUsable(image)) {
            COVER_CACHE.remove(key);
            return null;
        }
        return image;
    }

    private void rememberCover(String key, Image image) {
        if (key == null || !isUsable(image)) return;
        COVER_CACHE.put(key, new WeakReference<>(image));
        if (COVER_CACHE.size() >= CACHE_CLEANUP_THRESHOLD) {
            COVER_CACHE.entrySet().removeIf(entry -> {
                Image cached = entry.getValue() == null ? null : entry.getValue().get();
                return !isUsable(cached);
            });
        }
    }

    private Image resolve(DownloadTask task) {
        DeezerApiMetaData hint = task.getContext() == null ? null : task.getContext().getMetadataHint();
        long albumId = hint == null ? 0L : hint.getAlbumId();

        Image cover = MediaImageResolver.albumCover(
                albumId,
                COVER_PREFERRED_TYPE,
                COVER_DECODE_SIZE,
                COVER_DECODE_SIZE
        );
        if (isUsable(cover)) return cover;

        Song knownSong = findKnownSong(hint, task);
        if (knownSong != null) {
            cover = MediaImageResolver.musicCardSongCover(knownSong);
            if (isUsable(cover)) return cover;
        }

        String coverUrl = hint == null ? null : hint.getAlbumCoverUrl();
        if ((coverUrl == null || coverUrl.isBlank()) && albumId > 0) {
            coverUrl = fetchAlbumCoverUrl(albumId);
        }

        cover = MediaImageResolver.remoteCardImage(coverUrl);
        return isUsable(cover) ? cover : fallbackCover(task);
    }

    private Song findKnownSong(DeezerApiMetaData hint, DownloadTask task) {
        StartUpService service = StartUpService.getInstance();
        if (service == null || service.getSongs() == null) return null;

        long trackId = hint == null ? 0L : hint.getTrackId();
        String title = hint != null && hint.getSongName() != null
                ? hint.getSongName()
                : task.getFetchedTitle();

        synchronized (service.getSongs()) {
            return service.getSongs().stream()
                    .filter(Objects::nonNull)
                    .filter(song -> (trackId > 0 && song.getSongID() == trackId)
                            || (title != null && song.getTitle() != null && title.equalsIgnoreCase(song.getTitle())))
                    .findFirst()
                    .orElse(null);
        }
    }

    private String fetchAlbumCoverUrl(long albumId) {
        try {
            JsonObject album = DeezerHttpClient.fetchJsonObjectStatic(
                    DeezerEndpoints.defaultMainMenuEndpoints().albumById(albumId)
            );
            return DeezerApiService.extractHighResolutionCoverUrl(album);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isUsable(Image image) {
        return image != null && !image.isError();
    }

    private Image fallbackCover(DownloadTask task) {
        Image taskCover = task == null ? null : task.getCoverImage();
        return isUsable(taskCover)
                ? taskCover
                : MediaImageResolver.defaultCover(COVER_DECODE_SIZE, COVER_DECODE_SIZE);
    }

    private static ThreadFactory daemonFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "download-cover-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
