package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.managers;

import com.google.gson.JsonObject;
import javafx.scene.image.Image;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.services.api.DeezerArtistMetadataResolver;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletableFuture;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PlayerMenuArtistResolver {

    private static final String ARTIST_PORTRAIT_PREFERRED_TYPE = "xl";
    private static final double ARTIST_PORTRAIT_DECODE_SIZE = 256.0;

    private final StartUpService svc;
    private final ConcurrentMap<String, Long> artistIdByNameCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Image> smallPortraitCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<Image>> portraitRequests = new ConcurrentHashMap<>();
    private volatile List<Artist> cachedArtistListRef;
    private volatile int cachedArtistListSize = -1;
    private volatile ArtistLookup cachedArtistLookup = ArtistLookup.empty();

    public PlayerMenuArtistResolver(StartUpService svc) {
        this.svc = svc;
    }

    public long searchArtistIdByName(String name) {
        if (name == null || name.isBlank()) return -1L;
        String cacheKey = normalizeName(name);
        Long cached = artistIdByNameCache.get(cacheKey);
        if (cached != null) return cached;

        try {
            JsonObject artistJson = DeezerArtistMetadataResolver.findExactByName(name);
            long id = DeezerArtistMetadataResolver.artistId(artistJson);
            if (id > 0) {
                artistIdByNameCache.putIfAbsent(cacheKey, id);
                return id;
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return -1L;
    }

    public Image resolveSmallPortrait(Artist artist) {
        if (artist == null) return null;

        String portraitKey = portraitKey(artist);
        Image cached = cachedSmallPortrait(artist);
        if (isUsable(cached)) return cached;

        CompletableFuture<Image> created = new CompletableFuture<>();
        CompletableFuture<Image> existing = portraitRequests.putIfAbsent(portraitKey, created);
        if (existing != null) {
            try {
                return existing.join();
            } catch (Exception ignored) {
                return null;
            }
        }

        try {
            Image resolved = resolveSmallPortraitUncached(artist);
            if (isUsable(resolved)) {
                smallPortraitCache.put(portraitKey, resolved);
            }
            created.complete(resolved);
            return resolved;
        } catch (Exception ignored) {
            created.complete(null);
            return null;
        } finally {
            portraitRequests.remove(portraitKey, created);
        }
    }

    /**
     * Reads only memory and decoded-image caches. Database and network work is
     * intentionally deferred to resolveSmallPortrait on the header IO pool.
     */
    public Image cachedSmallPortrait(Artist artist) {
        if (artist == null) return null;

        String key = portraitKey(artist);
        Image cached = smallPortraitCache.get(key);
        if (isUsable(cached)) return cached;
        if (cached != null) smallPortraitCache.remove(key, cached);

        Image memoryImage = MediaImageResolver.cachedArtistPortrait(
                artist,
                ARTIST_PORTRAIT_PREFERRED_TYPE,
                ARTIST_PORTRAIT_DECODE_SIZE,
                ARTIST_PORTRAIT_DECODE_SIZE
        );
        if (isUsable(memoryImage)) return memoryImage;

        Artist memoryArtist = findLocalArtist(artist);
        if (memoryArtist != null) {
            memoryImage = MediaImageResolver.cachedArtistPortrait(
                    memoryArtist,
                    ARTIST_PORTRAIT_PREFERRED_TYPE,
                    ARTIST_PORTRAIT_DECODE_SIZE,
                    ARTIST_PORTRAIT_DECODE_SIZE
            );
            if (isUsable(memoryImage)) return memoryImage;
        }

        return null;
    }

    private Image resolveSmallPortraitUncached(Artist artist) {
        // 1. Memory/cache. This avoids repeating image work after a header refresh.
        Image local = cachedSmallPortrait(artist);
        if (isUsable(local)) return local;

        // 2. Persisted artist images. The local cache may have a different
        // artist instance, so use it as a second database lookup source.
        local = MediaImageResolver.artistPortraitFromDatabase(
                artist,
                ARTIST_PORTRAIT_PREFERRED_TYPE,
                ARTIST_PORTRAIT_DECODE_SIZE,
                ARTIST_PORTRAIT_DECODE_SIZE
        );
        if (isUsable(local)) return local;
        Artist localArtist = findLocalArtist(artist);
        local = MediaImageResolver.artistPortraitFromDatabase(
                localArtist,
                ARTIST_PORTRAIT_PREFERRED_TYPE,
                ARTIST_PORTRAIT_DECODE_SIZE,
                ARTIST_PORTRAIT_DECODE_SIZE
        );
        if (isUsable(local)) return local;

        long artistId = artist.getArtistID();
        String nameKey = normalizeName(artist.getName());

        try {
            JsonObject artistJson = DeezerArtistMetadataResolver.resolve(artistId, artist.getName());
            long resolvedId = DeezerArtistMetadataResolver.artistId(artistJson);
            if (resolvedId > 0 && resolvedId != artistId) {
                artistId = resolvedId;
                artist.setArtistID(resolvedId);
                artistIdByNameCache.putIfAbsent(nameKey, resolvedId);
            }

            Image fetched = cacheSmallPortraitFromArtistJson(artistId, nameKey, artistJson);
            if (fetched != null) return fetched;

            // Keep the existing local URL as a last-resort fallback only when
            // Deezer could not provide an authoritative portrait.
            String knownPortraitUrl = firstNonBlank(
                    localArtist == null ? null : localArtist.getPortraitUrl(),
                    artist.getPortraitUrl()
            );
            if (DeezerArtistMetadataResolver.isUsableArtistPictureUrl(knownPortraitUrl)) {
                Image knownPortrait = MediaImageResolver.remoteImage(
                        knownPortraitUrl,
                        ARTIST_PORTRAIT_DECODE_SIZE,
                        ARTIST_PORTRAIT_DECODE_SIZE
                );
                if (knownPortrait != null) return knownPortrait;
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    private Artist findLocalArtist(Artist artist) {
        if (artist == null || svc == null || svc.getArtists() == null) return null;

        long id = artist.getArtistID();
        String name = normalizeName(artist.getName());
        ArtistLookup lookup = buildArtistLookup();
        if (id > 0) return lookup.byId().get(id);
        return name.isBlank() ? null : lookup.byName().get(name);
    }

    private ArtistLookup buildArtistLookup() {
        List<Artist> source = svc == null ? null : svc.getArtists();
        int sourceSize = source == null ? 0 : source.size();
        ArtistLookup cached = cachedArtistLookup;
        if (source == cachedArtistListRef && sourceSize == cachedArtistListSize && cached != null) {
            return cached;
        }

        synchronized (this) {
            cached = cachedArtistLookup;
            if (source == cachedArtistListRef && sourceSize == cachedArtistListSize && cached != null) {
                return cached;
            }

            Map<Long, Artist> byId = new LinkedHashMap<>();
            Map<String, Artist> byName = new LinkedHashMap<>();
            try {
                for (Artist candidate : source == null ? List.<Artist>of() : new ArrayList<>(source)) {
                    if (candidate == null) continue;
                    if (candidate.getArtistID() > 0) {
                        byId.putIfAbsent(candidate.getArtistID(), candidate);
                    }
                    String key = normalizeName(candidate.getName());
                    if (!key.isBlank()) byName.putIfAbsent(key, candidate);
                }
            } catch (Exception ignored) {
            }

            ArtistLookup rebuilt = new ArtistLookup(byId, byName);
            cachedArtistListRef = source;
            cachedArtistListSize = sourceSize;
            cachedArtistLookup = rebuilt;
            return rebuilt;
        }
    }

    private Image cacheSmallPortraitFromArtistJson(long artistId, String nameKey, JsonObject artistJson) {
        if (artistJson == null) return null;

        String pictureUrl = DeezerArtistMetadataResolver.pictureUrl(artistJson);
        if (pictureUrl == null || pictureUrl.isBlank()) return null;

        try {
            long resolvedId = DeezerArtistMetadataResolver.artistId(artistJson);
            if (resolvedId > 0) {
                Image image = MediaImageResolver.remoteImage(
                        pictureUrl,
                        ARTIST_PORTRAIT_DECODE_SIZE,
                        ARTIST_PORTRAIT_DECODE_SIZE
                );
                if (image != null) smallPortraitCache.put("id:" + resolvedId, image);
                return image;
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String normalizeName(String name) {
        return DeezerArtistMetadataResolver.normalizeName(name);
    }

    private String portraitKey(Artist artist) {
        if (artist == null) return "unknown";
        long id = artist.getArtistID();
        return id > 0 ? "id:" + id : "name:" + normalizeName(artist.getName());
    }

    private boolean isUsable(Image image) {
        return image != null && !image.isError();
    }

    private record ArtistLookup(Map<Long, Artist> byId, Map<String, Artist> byName) {
        static ArtistLookup empty() {
            return new ArtistLookup(Map.of(), Map.of());
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

}
