package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.footer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.CardArtistNameResolver;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext.ContentType;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.managers.PlayerMenuArtistResolver;
import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.utils.ArtistIdentity;
import io.github.guillermodubon.musicplayer.utils.AlbumArtistResolver;
import io.github.guillermodubon.musicplayer.utils.DeezerEndpoints;
import io.github.guillermodubon.musicplayer.utils.MusicCardHelper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.footer.PlayerMenuMoreByArtistsModels.FooterCardSpec;
import static io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.footer.PlayerMenuMoreByArtistsModels.RemoteFetchPair;
import static io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.footer.PlayerMenuMoreByArtistsModels.RemoteFetchResult;

/** Loads and normalizes local and Deezer content for the footer. */
public final class PlayerMenuMoreByArtistsDataProvider {

    private static final int REMOTE_ALBUM_LIMIT = 8;
    private static final int REMOTE_TRACK_LIMIT = 8;
    private static final int MAX_FOOTER_CARDS_PER_ARTIST = 12;

    private final PlayerMenuArtistResolver artistResolver;
    private final DeezerEndpoints.ArtistPageEndpoints artistEndpoints =
            DeezerEndpoints.defaultArtistPageEndpoints();
    private final ExecutorService ioPool = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "player-menu-footer-io");
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentHashMap<String, List<FooterCardSpec>> remoteAlbumCache =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<FooterCardSpec>> remoteTrackCache =
            new ConcurrentHashMap<>();

    public PlayerMenuMoreByArtistsDataProvider(PlayerMenuArtistResolver artistResolver) {
        this.artistResolver = artistResolver;
    }

    public Executor executor() {
        return ioPool;
    }

    public PlayerMenuMoreByArtistsModels.LibrarySnapshot snapshot(StartUpService service) {
        List<Album> albums = service == null || service.getAlbums() == null
                ? List.of() : MusicCardHelper.snapshot(service.getAlbums());
        List<Song> songs = service == null || service.getSongs() == null
                ? List.of() : MusicCardHelper.snapshot(service.getSongs());
        return new PlayerMenuMoreByArtistsModels.LibrarySnapshot(albums, songs);
    }

    public List<Artist> resolveArtists(ContentType type,
                                        Playlist playlist,
                                        StartUpService service) {
        List<Artist> rawArtists = switch (type) {
            case ALBUM, EPISODE -> albumArtists(playlist);
            case SINGLE -> singleArtists(playlist);
            default -> Collections.emptyList();
        };
        rawArtists = realArtists(rawArtists);
        if (rawArtists.isEmpty()) {
            rawArtists = fallbackArtistsFromPlaylist(playlist);
        }

        List<Artist> serviceArtists = service == null || service.getArtists() == null
                ? List.of() : MusicCardHelper.snapshot(service.getArtists());
        Map<Long, Artist> serviceById = serviceArtists.stream()
                .filter(Objects::nonNull)
                .filter(artist -> !ArtistIdentity.isVariousArtists(artist))
                .filter(artist -> artist.getArtistID() > 0)
                .collect(Collectors.toMap(
                        Artist::getArtistID,
                        artist -> artist,
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));
        Map<String, Artist> serviceByName = serviceArtists.stream()
                .filter(Objects::nonNull)
                .filter(artist -> !ArtistIdentity.isVariousArtists(artist))
                .filter(artist -> artist.getName() != null)
                .collect(Collectors.toMap(
                        artist -> normalizeArtistName(artist.getName()),
                        artist -> artist,
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));

        LinkedHashMap<String, Artist> uniqueArtists = new LinkedHashMap<>();
        for (Artist artist : rawArtists) {
            if (artist == null || ArtistIdentity.isVariousArtists(artist)) {
                continue;
            }
            Artist canonical = artist;
            if (artist.getArtistID() > 0) {
                // An ID is authoritative. Never replace it with the first
                // same-name artist from the service snapshot.
                canonical = serviceById.getOrDefault(artist.getArtistID(), artist);
            } else {
                String normalized = normalizeArtistName(artist.getName());
                if (!normalized.isBlank() && serviceByName.containsKey(normalized)) {
                    canonical = serviceByName.get(normalized);
                }
            }
            String key = canonical.getArtistID() > 0
                    ? "id:" + canonical.getArtistID()
                    : "name:" + normalizeArtistName(
                    Optional.ofNullable(canonical.getName()).orElse(""));
            uniqueArtists.putIfAbsent(key, canonical);
        }
        return new ArrayList<>(uniqueArtists.values());
    }

    public List<FooterCardSpec> buildLocalFooterSpecs(
            Artist artist,
            List<Album> albumSnapshot,
            List<Song> songSnapshot,
            ContentType type,
            long currentAlbumId,
            long currentSongId
    ) {
        if (artist == null) {
            return List.of();
        }

        LinkedHashMap<String, FooterCardSpec> specs = new LinkedHashMap<>();
        Map<Long, Album> albumById = albumSnapshot == null ? Map.of() : albumSnapshot.stream()
                .filter(Objects::nonNull)
                .filter(album -> album.getAlbumID() > 0)
                .collect(Collectors.toMap(
                        Album::getAlbumID,
                        album -> album,
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));

        int albumSpecsAdded = 0;
        if (albumSnapshot != null) {
            for (Album album : albumSnapshot) {
                if (albumSpecsAdded >= REMOTE_ALBUM_LIMIT) {
                    break;
                }
                if (album == null || album.getArtist() == null
                        || album.getAlbumID() <= 0
                        || album.getAlbumID() == currentAlbumId
                        || album.getArtist().stream().noneMatch(ar -> sameArtist(ar, artist))
                        || isSingleAlbum(album)) {
                    continue;
                }
                int before = specs.size();
                putSpecIfAbsent(specs, albumSpec(album));
                if (specs.size() > before) {
                    albumSpecsAdded++;
                }
            }
        }

        if (songSnapshot != null) {
            for (Song song : songSnapshot) {
                if (specs.size() >= MAX_FOOTER_CARDS_PER_ARTIST) {
                    break;
                }
                if (song == null || song.getArtist() == null
                        || (type == ContentType.SINGLE && song.getSongID() == currentSongId)
                        || song.getArtist().stream().noneMatch(ar -> sameArtist(ar, artist))) {
                    continue;
                }

                Album album = song.getAlbum();
                long albumId = album == null ? -1L : album.getAlbumID();
                if (albumId == currentAlbumId) {
                    continue;
                }

                if (album != null && albumId > 0 && !isSingleAlbum(album)) {
                    Album canonicalAlbum = albumById.getOrDefault(albumId, album);
                    if (canonicalAlbum != null && canonicalAlbum.getAlbumID() > 0
                            && canonicalAlbum.getAlbumID() != currentAlbumId) {
                        putSpecIfAbsent(specs, albumSpec(canonicalAlbum));
                    }
                } else {
                    putSpecIfAbsent(specs, songSpec(song));
                }
            }
        }
        return new ArrayList<>(specs.values());
    }

    public CompletableFuture<RemoteFetchPair> fetchRemoteContent(Artist artist) {
        return CompletableFuture.supplyAsync(() -> resolveFooterArtistId(artist), ioPool)
                .thenCompose(artistId -> {
                    if (artistId <= 0) {
                        return CompletableFuture.completedFuture(new RemoteFetchPair(
                                RemoteFetchResult.failure(), RemoteFetchResult.failure(), ""));
                    }
                    String cacheKey = footerArtistCacheKey(artistId, artist.getName());
                    CompletableFuture<RemoteFetchResult> albums = CompletableFuture.supplyAsync(
                            () -> cachedOrFetch(remoteAlbumCache, cacheKey,
                                    () -> fetchRemoteAlbumSpecs(artistId, artist)), ioPool);
                    CompletableFuture<RemoteFetchResult> tracks = CompletableFuture.supplyAsync(
                            () -> cachedOrFetch(remoteTrackCache, cacheKey,
                                    () -> fetchRemoteTrackSpecs(artistId, artist)), ioPool);
                    return albums.thenCombine(tracks,
                            (albumResult, trackResult) -> new RemoteFetchPair(
                                    albumResult, trackResult, cacheKey));
                });
    }

    public String cacheKeyFor(Artist artist, long resolvedArtistId) {
        return footerArtistCacheKey(resolvedArtistId, artist == null ? null : artist.getName());
    }

    public void clearEmptyCaches(String cacheKey) {
        if (cacheKey == null || cacheKey.isBlank()) {
            return;
        }
        List<FooterCardSpec> albums = remoteAlbumCache.get(cacheKey);
        if (albums == null || albums.isEmpty()) {
            remoteAlbumCache.remove(cacheKey);
        }
        List<FooterCardSpec> tracks = remoteTrackCache.get(cacheKey);
        if (tracks == null || tracks.isEmpty()) {
            remoteTrackCache.remove(cacheKey);
        }
    }

    private List<Artist> albumArtists(Playlist playlist) {
        if (playlist == null || playlist.getSongList() == null || playlist.getSongList().isEmpty()
                || playlist.getSongList().get(0).getAlbum() == null) {
            return List.of();
        }
        return playlist.getSongList().get(0).getAlbum().getArtist();
    }

    private List<Artist> singleArtists(Playlist playlist) {
        if (playlist == null || playlist.getSongList() == null || playlist.getSongList().isEmpty()) {
            return List.of();
        }
        return playlist.getSongList().get(0).getArtist();
    }

    private List<Artist> fallbackArtistsFromPlaylist(Playlist playlist) {
        if (playlist == null || playlist.getSongList() == null || playlist.getSongList().isEmpty()) {
            return List.of();
        }
        for (Song song : playlist.getSongList()) {
            if (song != null && song.isLocal()) {
                List<Artist> artists = artistsFromSong(song);
                if (!artists.isEmpty()) {
                    return artists;
                }
            }
        }
        for (Song song : playlist.getSongList()) {
            List<Artist> artists = artistsFromSong(song);
            if (!artists.isEmpty()) {
                return artists;
            }
        }
        return List.of();
    }

    private List<Artist> artistsFromSong(Song song) {
        if (song == null) {
            return List.of();
        }
        List<Artist> artists = realArtists(song.getArtist());
        if (!artists.isEmpty()) {
            return artists;
        }
        return song.getAlbum() == null ? List.of() : realArtists(song.getAlbum().getArtist());
    }

    private List<Artist> realArtists(List<Artist> artists) {
        if (artists == null || artists.isEmpty()) {
            return List.of();
        }
        return artists.stream()
                .filter(Objects::nonNull)
                .filter(artist -> !ArtistIdentity.isVariousArtists(artist))
                .toList();
    }

    private FooterCardSpec albumSpec(Album album) {
        if (album == null || album.getAlbumID() <= 0
                || album.getName() == null || album.getName().isBlank()) {
            return null;
        }
        return new FooterCardSpec(
                "album", album.getAlbumID(), album.getName(),
                MediaImageResolver.albumCover(album, "xl", 320, 320), null,
                CardArtistNameResolver.fromAlbum(album)
        );
    }

    private FooterCardSpec songSpec(Song song) {
        if (song == null || song.getSongID() <= 0
                || song.getTitle() == null || song.getTitle().isBlank()) {
            return null;
        }
        return new FooterCardSpec(
                "song", song.getSongID(), song.getTitle(),
                MediaImageResolver.songAlbumCover(song, "xl", 320, 320), null,
                CardArtistNameResolver.fromSong(song)
        );
    }

    private void putSpecIfAbsent(LinkedHashMap<String, FooterCardSpec> specs,
                                 FooterCardSpec spec) {
        if (spec != null && spec.id() > 0 && spec.title() != null && !spec.title().isBlank()) {
            specs.putIfAbsent(spec.key(), spec);
        }
    }

    private RemoteFetchResult cachedOrFetch(
            ConcurrentHashMap<String, List<FooterCardSpec>> cache,
            String cacheKey,
            Supplier<RemoteFetchResult> fetcher
    ) {
        List<FooterCardSpec> cached = cache.get(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            return RemoteFetchResult.success(cached);
        }
        RemoteFetchResult fetched = Optional.ofNullable(fetcher.get())
                .orElseGet(RemoteFetchResult::failure);
        if (!fetched.responseSucceeded()) {
            return fetched;
        }
        if (fetched.specs().isEmpty()) {
            cache.remove(cacheKey);
        } else {
            cache.put(cacheKey, fetched.specs());
        }
        return fetched;
    }

    private RemoteFetchResult fetchRemoteAlbumSpecs(long artistId, Artist artist) {
        if (artistId <= 0) {
            return RemoteFetchResult.failure();
        }
        try {
            JsonObject response = fetchJsonObject(artistEndpoints.artistAlbums(artistId));
            if (response == null || response.has("error") || !response.has("data")
                    || !response.get("data").isJsonArray()) {
                return RemoteFetchResult.failure();
            }
            LinkedHashMap<String, FooterCardSpec> specs = new LinkedHashMap<>();
            for (JsonElement element : response.getAsJsonArray("data")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject album = element.getAsJsonObject();
                long albumId = DeezerApiService.safeGetLong(album, "id", -1L);
                String title = DeezerApiService.extractTitle(album);
                if (albumId <= 0 || title == null || title.isBlank()) {
                    continue;
                }
                String type = isSingleRecordType(album) ? "singleAlbum" : "album";
                List<String> artists = artistNamesFromJsonOrFallback(album, artist);
                if (!AlbumArtistResolver.hasExplicitOwnerCollection(album) && artists.size() <= 1) {
                    try {
                        JsonObject detail = fetchJsonObject(artistEndpoints.albumById(albumId));
                        List<String> detailedArtists = artistNamesFromJsonOrFallback(detail, artist);
                        if (!detailedArtists.isEmpty()) artists = detailedArtists;
                    } catch (Exception ignored) {
                    }
                }
                FooterCardSpec spec = new FooterCardSpec(
                        type, albumId, title, null,
                        DeezerApiService.extractHighResolutionCoverUrl(album),
                        artists
                );
                specs.putIfAbsent(spec.key(), spec);
                if (specs.size() >= REMOTE_ALBUM_LIMIT) {
                    break;
                }
            }
            return RemoteFetchResult.success(new ArrayList<>(specs.values()));
        } catch (Exception ignored) {
            return RemoteFetchResult.failure();
        }
    }

    private RemoteFetchResult fetchRemoteTrackSpecs(long artistId, Artist artist) {
        if (artistId <= 0) {
            return RemoteFetchResult.failure();
        }
        try {
            JsonObject response = fetchJsonObject(
                    artistEndpoints.artistTopTracks(artistId, REMOTE_TRACK_LIMIT));
            if (response == null || response.has("error") || !response.has("data")
                    || !response.get("data").isJsonArray()) {
                return RemoteFetchResult.failure();
            }
            LinkedHashMap<String, FooterCardSpec> specs = new LinkedHashMap<>();
            for (JsonElement element : response.getAsJsonArray("data")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject track = element.getAsJsonObject();
                long trackId = DeezerApiService.safeGetLong(track, "id", -1L);
                String title = DeezerApiService.extractTitle(track);
                if (trackId <= 0 || title == null || title.isBlank()) {
                    continue;
                }
                List<String> names = MusicCardHelper.extractArtistNamesFromTrackJson(track);
                if (names == null || names.isEmpty()) {
                    names = fastArtistFallback(artist);
                }
                FooterCardSpec spec = new FooterCardSpec(
                        "song", trackId, title, null,
                        coverUrlFromTrackJson(track), normalizeArtistNames(names)
                );
                specs.putIfAbsent(spec.key(), spec);
                if (specs.size() >= REMOTE_TRACK_LIMIT) {
                    break;
                }
            }
            return RemoteFetchResult.success(new ArrayList<>(specs.values()));
        } catch (Exception ignored) {
            return RemoteFetchResult.failure();
        }
    }

    private long resolveFooterArtistId(Artist artist) {
        if (artist == null || artistResolver == null) {
            return -1L;
        }
        return artist.getArtistID() > 0
                ? artist.getArtistID()
                : artistResolver.searchArtistIdByName(artist.getName());
    }

    private String coverUrlFromTrackJson(JsonObject track) {
        try {
            if (track != null && track.has("album") && track.get("album").isJsonObject()) {
                return DeezerApiService.extractHighResolutionCoverUrl(
                        track.getAsJsonObject("album"));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private List<String> artistNamesFromJsonOrFallback(JsonObject json, Artist fallback) {
        LinkedHashSet<String> names = new LinkedHashSet<>(AlbumArtistResolver.names(json));
        if (names.isEmpty()) {
            names.addAll(fastArtistFallback(fallback));
        }
        List<String> normalized = normalizeArtistNames(names);
        return normalized.isEmpty() ? List.of("Unknown") : normalized;
    }

    private List<String> fastArtistFallback(Artist artist) {
        String name = artist == null ? null : artist.getName();
        return name == null || name.isBlank() ? List.of("Unknown") : List.of(name.trim());
    }

    private List<String> normalizeArtistNames(Collection<String> raw) {
        if (raw == null) {
            return List.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String value : raw) {
            if (value != null && !value.trim().isBlank()) {
                names.add(value.trim());
            }
        }
        return List.copyOf(names);
    }

    private boolean isSingleAlbum(Album album) {
        if (album == null) {
            return false;
        }
        String recordType = album.getRecordType();
        return recordType != null && recordType.toLowerCase(Locale.ROOT).contains("single")
                || album.getNumberOfTracks() == 1;
    }

    private boolean isSingleRecordType(JsonObject album) {
        try {
            if (album != null && album.has("record_type")
                    && !album.get("record_type").isJsonNull()) {
                return album.get("record_type").getAsString()
                        .toLowerCase(Locale.ROOT).contains("single");
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private String footerArtistCacheKey(long artistId, String name) {
        return artistId > 0 ? "id:" + artistId : "name:" + normalizeArtistName(name);
    }

    private String normalizeArtistName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private boolean sameArtist(Artist first, Artist second) {
        if (first == null || second == null) {
            return false;
        }
        if (first.getArtistID() > 0 || second.getArtistID() > 0) {
            return first.getArtistID() > 0
                    && second.getArtistID() > 0
                    && first.getArtistID() == second.getArtistID();
        }
        return first.getName() != null && second.getName() != null
                && first.getName().equalsIgnoreCase(second.getName());
    }

    private JsonObject fetchJsonObject(String url) throws Exception {
        return MusicCardHelper.fetchJsonObject(url);
    }
}
