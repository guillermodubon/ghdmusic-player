package io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import io.github.guillermodubon.musicplayer.repository.dao.album.AlbumPlaybackDao;
import io.github.guillermodubon.musicplayer.repository.dao.album.AlbumPlaybackDaoImpl;
import io.github.guillermodubon.musicplayer.services.navigation.PlayerMenuNavigator;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playbackDialogs.RemoteContentLoadErrorDialog;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.PlayerMenuController;
import io.github.guillermodubon.musicplayer.utils.MusicCardHelper;
import io.github.guillermodubon.musicplayer.managers.ApiManagers.DeezerApiDataManager;
import io.github.guillermodubon.musicplayer.models.*;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;
import io.github.guillermodubon.musicplayer.services.api.DeezerArtistMetadataResolver;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.utils.AlbumArtistResolver;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext.ContentType;

public class AlbumPlaybackCoordinator {

    private final StartUpService svc;
    private final PlayerMenuNavigator navigator;
    private final AlbumPlaybackDao albumPlaybackDao;

    private final java.util.function.BiConsumer<String, Node> playSongCallback;
    private final java.util.function.BiConsumer<String, Node> playAlbumCallback;

    private static final ExecutorService IO_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "album-playback-coordinator-io");
        t.setDaemon(true);
        return t;
    });

    public AlbumPlaybackCoordinator(StartUpService svc, PlayerMenuNavigator navigator) {
        this(svc, navigator, null, null);
    }

    public AlbumPlaybackCoordinator(StartUpService svc,
                                    PlayerMenuNavigator navigator,
                                    java.util.function.BiConsumer<String, Node> playSongCallback,
                                    java.util.function.BiConsumer<String, Node> playAlbumCallback) {
        this.svc = Objects.requireNonNull(svc, "svc");
        this.navigator = Objects.requireNonNull(navigator, "navigator");
        this.albumPlaybackDao = new AlbumPlaybackDaoImpl();
        this.playSongCallback = playSongCallback;
        this.playAlbumCallback = playAlbumCallback;
    }

    public void handle(String idStr, Node probe) {
        long id;
        try {
            id = Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            return;
        }

        final long requestId = navigator.beginOpenRequest();
        final java.util.concurrent.atomic.AtomicBoolean hasLocalFallback =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        Optional<Album> maybe = snapshot(svc.getAlbums()).stream()
                .filter(a -> a.getAlbumID() == id)
                .findFirst();

        if (maybe.isPresent()) {
            Album alb = maybe.get();
            List<Song> canciones = Optional.ofNullable(alb.getSongList()).orElseGet(ArrayList::new).stream()
                    .sorted(Comparator.comparingInt(Song::getTrackOrder))
                    .collect(Collectors.toList());
            hasLocalFallback.set(canciones.stream().anyMatch(this::isPlayableLocalSong));

            String artistNames = Optional.ofNullable(alb.getArtist()).orElse(List.of()).stream()
                    .map(Artist::getName)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining(", "));

            Playlist albumPl = new Playlist(
                    alb.getAlbumID(),
                    alb.getName(),
                    "Álbum de " + artistNames,
                    "",
                    alb.getReleaseDate(),
                    null,
                    FXCollections.observableArrayList(canciones)
            );
            albumPl.setCoverUrl(alb.getCoverUrl());

            if (hasLocalFallback.get()) {
                PlaybackManager pm = PlaybackManager.getInstance();
                PlayerMenuController existing = pm.getMenuController();
                boolean existingVisible = false;
                try {
                    existingVisible = existing != null && existing.isCurrentCenterViewVisible();
                } catch (Exception ignored) {}

                if (existingVisible && pm.getCurrentPlaylistInViewId() == id) {
                    try {
                        existing.updatePlaylistContent(albumPl);
                    } catch (Throwable t) {
                        navigator.openPlayerMenuIfCurrent(albumPl, ContentType.ALBUM, probe, requestId);
                    }
                } else {
                    navigator.openPlayerMenuIfCurrent(albumPl, ContentType.ALBUM, probe, requestId);
                }
            }

            /*
             * A library album may only contain the tracks already downloaded
             * locally. Keep the fast navigation above, but do not treat that
             * partial cache as the album's complete Deezer track list.
             */
            if (hasLocalFallback.get() && hasCompleteTrackList(alb, canciones)) {
                return;
            }
        }

        IO_POOL.submit(() -> {
            try {
                if (!navigator.isOpenRequestCurrent(requestId)) return;

                JsonObject albumJson = MusicCardHelper.fetchFreshJsonObject("https://api.deezer.com/album/" + id);
                if (MusicCardHelper.isDeezerError(albumJson)) {
                    showRemoteLoadErrorIfNeeded(probe, null, hasLocalFallback);
                    return;
                }

                if (!navigator.isOpenRequestCurrent(requestId)) return;

                String albumTitle = DeezerApiService.extractTitle(albumJson);
                String releaseDate = albumJson.has("release_date") && !albumJson.get("release_date").isJsonNull()
                        ? albumJson.get("release_date").getAsString()
                        : "";
                String recordType = albumJson.has("record_type") && !albumJson.get("record_type").isJsonNull()
                        ? albumJson.get("record_type").getAsString()
                        : "";

                String coverUrl = DeezerApiService.extractCoverUrlFromAlbumOrPlaylist(albumJson);

                // Keep the complete owner collection, including IDs. The
                // previous branch preferred one primary artist and therefore
                // made the PlayerMenu header lose co-owners of the release.
                List<Artist> albumArtists = resolveAlbumArtists(albumJson);

                Album tempAlbum = new Album(
                        id,
                        albumTitle != null ? albumTitle : "",
                        albumArtists,
                        new Genre(0, ""),
                        recordType,
                        releaseDate,
                        new ArrayList<>(),
                        new ArrayList<>(),
                        albumJson.has("nb_tracks") && !albumJson.get("nb_tracks").isJsonNull()
                                ? albumJson.get("nb_tracks").getAsInt()
                                : 0
                );
                tempAlbum.setCoverUrl(coverUrl);

                List<DeezerTrackInfo> infos = extractAlbumTracksFromAlbumJson(albumJson);
                if (infos.isEmpty()) {
                    infos = DeezerApiService.fetchAlbumTracks(id);
                }

                if (!navigator.isOpenRequestCurrent(requestId)) return;

                List<Song> songSnapshot = snapshot(svc == null ? List.of() : svc.getSongs());
                Map<Long, Song> localSongById = songSnapshot.stream()
                        .filter(Objects::nonNull)
                        // Remote tracks persisted to complete an album are
                        // not downloaded local files.
                        .filter(this::isPlayableLocalSong)
                        .filter(s -> s.getSongID() > 0)
                        .collect(Collectors.toMap(Song::getSongID, Function.identity(), (a, b) -> a));
                Map<String, Song> localSongByTitleArtist = new HashMap<>();
                for (Song song : songSnapshot) {
                    if (song == null || !isPlayableLocalSong(song)
                            || song.getTitle() == null || song.getArtist() == null) continue;
                    for (Artist artist : song.getArtist()) {
                        if (artist == null || artist.getName() == null || artist.getName().isBlank()) continue;
                        localSongByTitleArtist.putIfAbsent(songTitleArtistKey(song.getTitle(), artist.getName()), song);
                    }
                }

                List<Song> resultSongs = new ArrayList<>();
                for (DeezerTrackInfo info : Optional.ofNullable(infos).orElse(List.of())) {
                    long tid = info.getId();
                    String title = info.getTitle();

                    Song localSong = tid > 0 ? localSongById.get(tid) : null;
                    if (localSong != null) {
                        Song orig = localSong;
                        Song s = new Song(orig.getSongID(), orig.getTitle(), orig.getArtist(), tempAlbum, orig.getFilePath(), info.getTrackOrder(), true);
                        resultSongs.add(s);
                        continue;
                    }

                    String firstArtistName = tempAlbum.getArtist().isEmpty() ? "" : tempAlbum.getArtist().get(0).getName();
                    // A positive Deezer ID identifies the exact track. A
                    // title fallback must never join different album editions.
                    Song maybeByTitle = tid <= 0
                            ? localSongByTitleArtist.get(songTitleArtistKey(title, firstArtistName))
                            : null;
                    if (maybeByTitle != null) {
                        Song orig = maybeByTitle;
                        Song s = new Song(orig.getSongID(), orig.getTitle(), orig.getArtist(), tempAlbum, orig.getFilePath(), info.getTrackOrder(), true);
                        resultSongs.add(s);
                    } else {
                        List<Artist> artistsForTrack = new ArrayList<>();
                        if (!tempAlbum.getArtist().isEmpty()) artistsForTrack.add(tempAlbum.getArtist().get(0));
                        else artistsForTrack.add(MusicCardHelper.resolveArtist(-1, "Unknown", svc));
                        Song v = new Song(info.getId(), info.getTitle(), artistsForTrack, tempAlbum, null, info.getTrackOrder(), false);
                        resultSongs.add(v);
                    }
                }

                resultSongs.sort(Comparator.comparingInt(Song::getTrackOrder));
                if (resultSongs.isEmpty()) {
                    showRemoteLoadErrorIfNeeded(probe, albumTitle, hasLocalFallback);
                    return;
                }

                tempAlbum.getSongList().addAll(resultSongs);

                /*
                 * Do not replace the hydrated album with the persisted object:
                 * for locally saved albums that object can intentionally hold
                 * only downloaded tracks. `tempAlbum` contains the complete
                 * remote order while reusing each local Song where available.
                 */
                Album displayAlbum = tempAlbum;
                Album persistedAlbum = svc == null ? null : snapshot(svc.getAlbums()).stream()
                        .filter(a -> a.getAlbumID() == tempAlbum.getAlbumID())
                        .findFirst().orElse(null);
                if (persistedAlbum != null) {
                    if ((displayAlbum.getCoverUrl() == null || displayAlbum.getCoverUrl().isBlank())
                            && persistedAlbum.getCoverUrl() != null && !persistedAlbum.getCoverUrl().isBlank()) {
                        displayAlbum.setCoverUrl(persistedAlbum.getCoverUrl());
                    }

                    List<Song> completeSongs = new ArrayList<>(resultSongs);
                    Platform.runLater(() -> {
                        try {
                            persistedAlbum.setSongList(completeSongs);
                            persistedAlbum.setNumberOfTracks(Math.max(
                                    persistedAlbum.getNumberOfTracks(),
                                    completeSongs.size()
                            ));
                            if ((persistedAlbum.getCoverUrl() == null || persistedAlbum.getCoverUrl().isBlank())
                                    && displayAlbum.getCoverUrl() != null && !displayAlbum.getCoverUrl().isBlank()) {
                                persistedAlbum.setCoverUrl(displayAlbum.getCoverUrl());
                            }
                        } catch (Exception ignored) {
                        }
                    });
                }

                String artistNames = Optional.ofNullable(displayAlbum.getArtist()).orElse(List.of()).stream()
                        .map(Artist::getName)
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining(", "));

                Playlist pl = new Playlist(
                        displayAlbum.getAlbumID(),
                        displayAlbum.getName(),
                        "Álbum de " + artistNames,
                        "",
                        displayAlbum.getReleaseDate(),
                        null,
                        FXCollections.observableArrayList(displayAlbum.getSongList())
                );
                pl.setCoverUrl(displayAlbum.getCoverUrl());

                Platform.runLater(() -> {
                    if (!navigator.isOpenRequestCurrent(requestId)) return;

                    /*
                     * A mixed album already opened a fast local PlayerMenu.
                     * The Deezer result enriches that controller in place. If
                     * the user has already navigated away, discard this late
                     * enrichment instead of reopening the album and adding a
                     * second history entry.
                     */
                    if (hasLocalFallback.get()) {
                        PlayerMenuController active = PlaybackManager.getInstance().getMenuController();
                        if (active == null
                                || !active.isCurrentCenterViewVisible()
                                || active.getCurrentPlaylistInViewId() != displayAlbum.getAlbumID()
                                || active.getCurrentContentTypeInView() != ContentType.ALBUM) {
                            return;
                        }
                        active.updatePlaylistContent(pl);
                        return;
                    }

                    navigator.openPlayerMenuIfCurrent(pl, ContentType.ALBUM, probe, requestId);
                });

                IO_POOL.submit(() -> {
                    try {
                        if (!navigator.isOpenRequestCurrent(requestId)) return;

                        PlayerMenuController active = PlaybackManager.getInstance().getMenuController();
                        if (active == null || active.getCurrentPlaylistModel() == null
                                || active.getCurrentPlaylistModel().getId() != displayAlbum.getAlbumID()) {
                            return;
                        }

                        try {
                            Map<Long, Song> resultSongById = resultSongs.stream()
                                    .filter(Objects::nonNull)
                                    .filter(song -> song.getSongID() > 0)
                                    .collect(Collectors.toMap(Song::getSongID, Function.identity(), (a, b) -> a));

                            if (!resultSongById.isEmpty()) {
                                albumPlaybackDao.persistRemoteSongs(displayAlbum.getAlbumID(), resultSongById.values());
                            }
                        } catch (Exception ignored) {}

                        if (displayAlbum.getReleaseDate() != null && !displayAlbum.getReleaseDate().isBlank() && displayAlbum.getAlbumID() > 0) {
                            albumPlaybackDao.updateReleaseDate(
                                    displayAlbum.getAlbumID(),
                                    displayAlbum.getName(),
                                    displayAlbum.getReleaseDate(),
                                    displayAlbum.getNumberOfTracks()
                            );
                        }

                        Platform.runLater(() -> {
                            try {
                                if (!navigator.isOpenRequestCurrent(requestId)) return;

                                PlayerMenuController curr = PlaybackManager.getInstance().getMenuController();
                                if (curr != null && curr.getCurrentPlaylistModel() != null
                                        && curr.isCurrentCenterViewVisible()
                                        && curr.getCurrentPlaylistModel().getId() == displayAlbum.getAlbumID()) {
                                    List<Song> cancionesFinal = Optional.ofNullable(displayAlbum.getSongList()).orElse(List.of()).stream()
                                            .sorted(Comparator.comparingInt(Song::getTrackOrder))
                                            .collect(Collectors.toList());

                                    Playlist albumPlFinal = new Playlist(
                                            displayAlbum.getAlbumID(),
                                            displayAlbum.getName(),
                                            "Álbum de " + artistNames,
                                            "",
                                            displayAlbum.getReleaseDate(),
                                            null,
                                            FXCollections.observableArrayList(cancionesFinal)
                                    );
                                    albumPlFinal.setCoverUrl(displayAlbum.getCoverUrl());

                                    try {
                                        curr.updatePlaylistContent(albumPlFinal);
                                    } catch (Throwable t) {
                                        try {
                                            curr.initPlaylist(ContentType.ALBUM, albumPlFinal);
                                        } catch (Exception ex) {
                                            ex.printStackTrace();
                                        }
                                    }
                                }
                            } catch (Exception ignore) {}
                        });

                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                });

            } catch (IOException ex) {
                showRemoteLoadErrorIfNeeded(probe, null, hasLocalFallback);
                ex.printStackTrace();
            }
        });
    }

    public void playFirstTrackFromAlbumAsSingle(String albumIdStr, Node probe) {
        if (albumIdStr == null || albumIdStr.isBlank()) return;

        final long requestId = navigator.beginOpenRequest();

        IO_POOL.submit(() -> {
            try {
                if (!navigator.isOpenRequestCurrent(requestId)) return;

                long albumId;
                try {
                    albumId = Long.parseLong(albumIdStr);
                } catch (NumberFormatException nfe) {
                    return;
                }

                List<DeezerTrackInfo> tracks = DeezerApiService.fetchAlbumTracks(albumId);
                if (tracks != null && !tracks.isEmpty()) {
                    long tid = tracks.get(0).getId();
                    if (tid > 0) {
                        Platform.runLater(() -> {
                            try {
                                if (!navigator.isOpenRequestCurrent(requestId)) return;
                                if (playSongCallback != null) {
                                    playSongCallback.accept(String.valueOf(tid), probe);
                                } else {
                                    new SongPlaybackCoordinator(svc, navigator).handle(String.valueOf(tid), probe);
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        });
                        return;
                    }
                }

                Platform.runLater(() -> {
                    try {
                        if (!navigator.isOpenRequestCurrent(requestId)) return;
                        if (playAlbumCallback != null) {
                            playAlbumCallback.accept(albumIdStr, probe);
                        } else {
                            handle(albumIdStr, probe);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });
    }

    private static List<DeezerTrackInfo> extractAlbumTracksFromAlbumJson(JsonObject albumJson) {
        if (albumJson == null || !albumJson.has("tracks") || !albumJson.get("tracks").isJsonObject()) {
            return List.of();
        }

        JsonObject tracks = albumJson.getAsJsonObject("tracks");
        if (!tracks.has("data") || !tracks.get("data").isJsonArray()) {
            return List.of();
        }

        JsonArray data = tracks.getAsJsonArray("data");
        List<DeezerTrackInfo> out = new ArrayList<>();
        for (JsonElement el : data) {
            if (!el.isJsonObject()) continue;
            JsonObject t = el.getAsJsonObject();
            long tid = DeezerApiService.safeGetLong(t, "id", -1L);
            if (tid <= 0) continue;

            String title = t.has("title") && !t.get("title").isJsonNull()
                    ? t.get("title").getAsString()
                    : "Untitled";
            int order = safeGetInt(t, "track_position", safeGetInt(t, "position", 0));
            out.add(new DeezerTrackInfo(tid, title, order));
        }
        return out;
    }

    private static int safeGetInt(JsonObject obj, String key, int fallback) {
        try {
            if (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) {
                return obj.get(key).getAsInt();
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static boolean hasCompleteTrackList(Album album, List<Song> songs) {
        if (album == null) return false;

        int expected = album.getNumberOfTracks();
        if (expected <= 0) return false;

        long actual = Optional.ofNullable(songs).orElse(List.of()).stream()
                .filter(Objects::nonNull)
                .map(Song::getSongID)
                .filter(id -> id > 0)
                .distinct()
                .count();
        return actual >= expected;
    }

    private Artist resolveArtistFromMemory(long artistId, String artistName) {
        String safeName = artistName == null || artistName.isBlank() ? "Unknown" : artistName.trim();

        try {
            for (Artist artist : snapshot(svc == null ? List.of() : svc.getArtists())) {
                if (artist == null) continue;
                if (artistId > 0) {
                    if (artist.getArtistID() == artistId) return artist;
                    continue;
                }
                if (artist.getName() != null && artist.getName().equalsIgnoreCase(safeName)) return artist;
            }
        } catch (Exception ignored) {
        }

        return new Artist(artistId > 0 ? artistId : 0L, safeName, null, new ArrayList<>());
    }

    private Artist resolveArtistFromMemory(long artistId, String artistName, JsonObject artistJson) {
        Artist artist = resolveArtistFromMemory(artistId, artistName);
        String portraitUrl = DeezerArtistMetadataResolver.pictureUrl(artistJson);
        if (DeezerArtistMetadataResolver.isUsableArtistPictureUrl(portraitUrl)) {
            artist.setPortraitUrl(portraitUrl);
        }
        return artist;
    }

    private List<Artist> resolveAlbumArtists(JsonObject albumJson) {
        List<Artist> artists = new ArrayList<>();
        for (AlbumArtistResolver.ArtistReference reference : AlbumArtistResolver.resolve(albumJson)) {
            if (reference == null || reference.name() == null || reference.name().isBlank()) continue;

            JsonObject artistJson = new JsonObject();
            if (reference.id() > 0) artistJson.addProperty("id", reference.id());
            artistJson.addProperty("name", reference.name());

            Artist artist = resolveArtistFromMemory(reference.id(), reference.name(), artistJson);
            if (artist == null) continue;
            boolean duplicate = artists.stream().anyMatch(existing ->
                    existing != null
                            && reference.id() > 0
                            && existing.getArtistID() == reference.id());
            if (!duplicate) artists.add(artist);
        }
        return artists;
    }

    private static String songTitleArtistKey(String title, String artistName) {
        String safeTitle = title == null ? "" : title.trim().toLowerCase(Locale.ROOT);
        String safeArtist = artistName == null ? "" : artistName.trim().toLowerCase(Locale.ROOT);
        return safeTitle + "\u0000" + safeArtist;
    }

    private void showRemoteLoadError(Node probe, String title) {
        Platform.runLater(() -> RemoteContentLoadErrorDialog.show(
                RemoteContentLoadErrorDialog.ContentKind.ALBUM,
                title,
                probe
        ));
    }

    private void showRemoteLoadErrorIfNeeded(Node probe,
                                             String title,
                                             java.util.concurrent.atomic.AtomicBoolean hasLocalFallback) {
        if (hasLocalFallback != null && hasLocalFallback.get()) return;
        showRemoteLoadError(probe, title);
    }

    private boolean isPlayableLocalSong(Song song) {
        if (song == null || !song.isLocal()
                || song.getFilePath() == null || song.getFilePath().isBlank()) {
            return false;
        }
        try {
            File file = new File(song.getFilePath());
            return file.isFile() && file.canRead();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static <T> List<T> snapshot(List<T> list) {
        return list == null ? List.of() : new ArrayList<>(list);
    }
}
