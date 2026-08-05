package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.services.SongArtistResolver;
import io.github.guillermodubon.musicplayer.utils.MusicCardHelper;
import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Genre;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.api.DeezerArtistMetadataResolver;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

public class PlayerMenuRemoteSongDetailsService {

    private static final ExecutorService REMOTE_SONG_IO = Executors.newFixedThreadPool(5, r -> {
        Thread t = new Thread(r, "player-menu-remote-song-details");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentMap<Long, CompletableFuture<RemoteSongDetails>> detailsByTrackId = new ConcurrentHashMap<>();

    private StartUpService svc;
    private volatile List<Artist> cachedArtistListRef;
    private volatile int cachedArtistListSize = -1;
    private volatile ArtistLookup cachedArtistLookup = ArtistLookup.empty();

    public void bind(StartUpService svc) {
        if (this.svc != svc) {
            this.svc = svc;
            detailsByTrackId.clear();
            cachedArtistListRef = null;
            cachedArtistListSize = -1;
            cachedArtistLookup = ArtistLookup.empty();
        } else {
            this.svc = svc;
        }
    }

    public void prefetch(List<Song> songs, Runnable onAnySongUpdated) {
        prefetch(songs, () -> true, onAnySongUpdated);
    }

    public void prefetch(List<Song> songs,
                         BooleanSupplier isStillCurrent,
                         Runnable onAnySongUpdated) {
        if (songs == null || songs.isEmpty()) return;
        BooleanSupplier currentGuard = isStillCurrent == null ? () -> true : isStillCurrent;
        Runnable updateCallback = onAnySongUpdated == null ? () -> {} : onAnySongUpdated;

        Map<Long, Song> pendingByTrackId = new LinkedHashMap<>();
        for (Song song : songs) {
            if (requiresHydration(song)) {
                pendingByTrackId.putIfAbsent(song.getSongID(), song);
            }
        }
        List<Song> pending = new ArrayList<>(pendingByTrackId.values());
        if (pending.isEmpty()) return;

        ArtistLookup artistLookup = buildArtistLookup();

        for (Song song : pending) {
            long trackId = song.getSongID();
            detailsByTrackId
                    .computeIfAbsent(trackId, id -> CompletableFuture.supplyAsync(() -> fetchDetails(id, artistLookup), REMOTE_SONG_IO))
                    .thenAccept(details -> {
                        if (details == null || details.isEmpty()) return;
                        Platform.runLater(() -> {
                            if (!currentGuard.getAsBoolean()) return;
                            if (applyDetails(song, details)) {
                                updateCallback.run();
                            }
                        });
                    })
                    .exceptionally(ex -> null);
        }
    }

    private boolean requiresHydration(Song song) {
        // A lightweight PlayerMenu song can contain only the album creator.
        // The track endpoint is the authority for its complete contributor list.
        return song != null && !song.isLocal() && song.getSongID() > 0;
    }

    private RemoteSongDetails fetchDetails(long trackId, ArtistLookup artistLookup) {
        try {
            JsonObject trackJson = MusicCardHelper.fetchJsonObject("https://api.deezer.com/track/" + trackId);
            if (trackJson == null || trackJson.has("error")) return RemoteSongDetails.empty();

            AlbumDetails album = extractAlbumDetails(trackJson);
            List<Artist> artists = extractArtistsFast(trackJson, artistLookup);

            return new RemoteSongDetails(album, artists);
        } catch (Exception ignored) {
            return RemoteSongDetails.empty();
        }
    }

    private AlbumDetails extractAlbumDetails(JsonObject trackJson) {
        if (trackJson == null || !trackJson.has("album") || !trackJson.get("album").isJsonObject()) {
            return AlbumDetails.empty();
        }

        JsonObject albumJson = trackJson.getAsJsonObject("album");
        long albumId = DeezerApiService.safeGetLong(albumJson, "id", 0L);
        String title = DeezerApiService.extractTitle(albumJson);
        String coverUrl = DeezerApiService.extractCoverUrlFromAlbumOrPlaylist(albumJson);
        return new AlbumDetails(albumId, title == null ? "" : title, coverUrl);
    }

    private List<Artist> extractArtistsFast(JsonObject trackJson, ArtistLookup artistLookup) {
        Map<String, Artist> unique = new LinkedHashMap<>();

        if (trackJson != null && trackJson.has("contributors") && trackJson.get("contributors").isJsonArray()) {
            JsonArray contributors = trackJson.getAsJsonArray("contributors");
            for (JsonElement element : contributors) {
                if (!element.isJsonObject()) continue;
                addArtist(unique, element.getAsJsonObject(), artistLookup);
            }
        }

        if (unique.isEmpty()
                && trackJson != null
                && trackJson.has("artist")
                && trackJson.get("artist").isJsonObject()) {
            addArtist(unique, trackJson.getAsJsonObject("artist"), artistLookup);
        }

        return new ArrayList<>(unique.values());
    }

    private void addArtist(Map<String, Artist> target, JsonObject artistJson, ArtistLookup artistLookup) {
        long id = DeezerApiService.safeGetLong(artistJson, "id", 0L);
        String name = firstString(artistJson, "name", "title");
        if (name == null || name.isBlank()) return;

        Artist resolved = resolveFromMemory(id, name, artistLookup);
        String portraitUrl = DeezerArtistMetadataResolver.pictureUrl(artistJson);
        if (DeezerArtistMetadataResolver.isUsableArtistPictureUrl(portraitUrl)) {
            resolved.setPortraitUrl(portraitUrl);
        }
        String key = id > 0 ? "id:" + id : "name:" + name.trim().toLowerCase(Locale.ROOT);
        target.putIfAbsent(key, resolved);
    }

    private Artist resolveFromMemory(long id, String name, ArtistLookup artistLookup) {
        if (artistLookup != null) {
            Artist byId = id > 0 ? artistLookup.byId().get(id) : null;
            if (byId != null) return byId;

            if (id > 0) {
                return new Artist(id, name, null, new ArrayList<>());
            }

            String key = normalizeName(name);
            Artist byName = key.isBlank() ? null : artistLookup.byName().get(key);
            if (byName != null) return byName;
        }
        return new Artist(id > 0 ? id : 0L, name, null, new ArrayList<>());
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

            ArtistLookup rebuilt = rebuildArtistLookup(source);
            cachedArtistListRef = source;
            cachedArtistListSize = sourceSize;
            cachedArtistLookup = rebuilt;
            return rebuilt;
        }
    }

    private ArtistLookup rebuildArtistLookup(List<Artist> source) {
        Map<Long, Artist> byId = new LinkedHashMap<>();
        Map<String, Artist> byName = new LinkedHashMap<>();

        if (source == null) {
            return new ArtistLookup(byId, byName);
        }

        try {
            for (Artist artist : MusicCardHelper.snapshot(source)) {
                if (artist == null) continue;
                if (artist.getArtistID() > 0) byId.putIfAbsent(artist.getArtistID(), artist);

                String key = normalizeName(artist.getName());
                if (!key.isBlank()) byName.putIfAbsent(key, artist);
            }
        } catch (Exception ignored) {
        }

        return new ArtistLookup(byId, byName);
    }

    private boolean applyDetails(Song song, RemoteSongDetails details) {
        boolean changed = false;

        AlbumDetails albumDetails = details.album();
        if (albumDetails != null && albumDetails.coverUrl() != null && !albumDetails.coverUrl().isBlank()) {
            Album album = song.getAlbum();
            if (album == null) {
                album = new Album(
                        albumDetails.albumId(),
                        albumDetails.title(),
                        new ArrayList<>(),
                        new Genre(0, ""),
                        "",
                        "",
                        new ArrayList<>(),
                        new ArrayList<>(),
                        0
                );
                song.setAlbum(album);
                changed = true;
            }

            // The PlayerMenu album is the selected release. A track endpoint
            // may point to another edition, so only fill missing identity data
            // and never replace a valid view-specific album identity.
            if (album.getAlbumID() <= 0 && albumDetails.albumId() > 0) {
                album.setAlbumID(albumDetails.albumId());
                changed = true;
            }
            if ((album.getName() == null || album.getName().isBlank())
                    && albumDetails.title() != null && !albumDetails.title().isBlank()) {
                album.setName(albumDetails.title());
                changed = true;
            }
            if ((album.getCoverUrl() == null || album.getCoverUrl().isBlank())
                    && !albumDetails.coverUrl().equals(album.getCoverUrl())) {
                album.setCoverUrl(albumDetails.coverUrl());
                changed = true;
            }
        }

        List<Artist> detailArtists = details.artists();
        if (detailArtists != null && !detailArtists.isEmpty()) {
            List<Artist> merged = mergeArtists(song.getArtist(), detailArtists);
            if (!sameArtistIdentities(song.getArtist(), merged)) {
                song.setArtist(merged);
                changed = true;
            }
        }

        return changed;
    }

    private List<Artist> mergeArtists(List<Artist> current, List<Artist> fresh) {
        return SongArtistResolver.merge(current, fresh);
    }

    private boolean sameArtistIdentities(List<Artist> first, List<Artist> second) {
        if (first == second) return true;
        if (first == null || second == null || first.size() != second.size()) return false;

        for (int index = 0; index < first.size(); index++) {
            Artist left = first.get(index);
            Artist right = second.get(index);
            if (left == right) continue;
            if (left == null || right == null) return false;
            if (left.getArtistID() > 0 || right.getArtistID() > 0) {
                if (left.getArtistID() != right.getArtistID()) return false;
            } else if (!normalizeName(left.getName()).equals(normalizeName(right.getName()))) {
                return false;
            }
        }
        return true;
    }

    private String firstString(JsonObject obj, String... keys) {
        if (obj == null || keys == null) return null;
        for (String key : keys) {
            try {
                if (obj.has(key) && !obj.get(key).isJsonNull()) {
                    String value = obj.get(key).getAsString();
                    if (value != null && !value.isBlank()) return value;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String normalizeName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record AlbumDetails(long albumId, String title, String coverUrl) {
        static AlbumDetails empty() {
            return new AlbumDetails(0L, "", null);
        }
    }

    private record ArtistLookup(Map<Long, Artist> byId, Map<String, Artist> byName) {
        static ArtistLookup empty() {
            return new ArtistLookup(Map.of(), Map.of());
        }
    }

    private record RemoteSongDetails(AlbumDetails album, List<Artist> artists) {
        static RemoteSongDetails empty() {
            return new RemoteSongDetails(AlbumDetails.empty(), List.of());
        }

        boolean isEmpty() {
            return (album() == null || album().coverUrl() == null || album().coverUrl().isBlank())
                    && (artists() == null || artists().isEmpty());
        }
    }
}
