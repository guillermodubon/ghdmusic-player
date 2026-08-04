package io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import io.github.guillermodubon.musicplayer.repository.DbConnectionManager;
import io.github.guillermodubon.musicplayer.services.navigation.PlayerMenuNavigator;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playbackDialogs.RemoteContentLoadErrorDialog;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.PlayerMenuController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext.ContentType;
import io.github.guillermodubon.musicplayer.utils.MusicCardHelper;
import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Genre;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;
import io.github.guillermodubon.musicplayer.services.api.DeezerArtistMetadataResolver;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

public class PlaylistPlaybackCoordinator {

    private final StartUpService svc;
    private final PlayerMenuNavigator navigator;

    private static final ExecutorService IO_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "playlist-playback-coordinator-io");
        t.setDaemon(true);
        return t;
    });

    public PlaylistPlaybackCoordinator(StartUpService svc, PlayerMenuNavigator navigator) {
        this.svc = svc;
        this.navigator = navigator;
    }

    public void handle(String playlistIdStr, Node probe) {
        long playlistId;
        try {
            playlistId = Long.parseLong(playlistIdStr);
        } catch (NumberFormatException e) {
            e.printStackTrace();
            return;
        }

        final long requestId = navigator.beginOpenRequest();
        final java.util.concurrent.atomic.AtomicBoolean hasLocalFallback = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicBoolean loadErrorShown = new java.util.concurrent.atomic.AtomicBoolean(false);

        try {
            List<Playlist> svcPls = snapshot(svc == null ? List.of() : svc.getPlaylists());
            Playlist localPl = svcPls.stream()
                    .filter(x -> x != null && x.getId() == playlistId)
                    .findFirst()
                    .orElse(null);

            if (localPl != null) {
                hasLocalFallback.set(hasPlayableLocalSong(localPl.getSongList()));
                String author = Optional.ofNullable(localPl.getAuthorName()).orElse("").trim();
                boolean isUserPlaylist = "User".equalsIgnoreCase(author);

                if (isUserPlaylist) {
                    Platform.runLater(() -> {
                        try {
                            navigator.openPlaylistIfCurrent(playlistId, probe, requestId);
                        } catch (Exception ex) {
                            try {
                                navigator.openPlayerMenuIfCurrent(localPl, ContentType.PLAYLIST, probe, requestId);
                            } catch (Exception ignored) {}
                        }
                    });
                    return;
                }

                List<Song> allLocalSongs = snapshot(svc == null ? List.of() : svc.getSongs());
                List<Song> playableLocalSongs = allLocalSongs.stream()
                        .filter(this::isPlayableLocalSong)
                        .toList();

                Map<Long, Song> localById = playableLocalSongs.stream()
                        .filter(s -> s != null && s.getSongID() > 0)
                        .collect(Collectors.toMap(Song::getSongID, Function.identity(), (a, b) -> a));

                Map<String, Song> localByTitle = playableLocalSongs.stream()
                        .filter(s -> s != null && s.getTitle() != null)
                        .collect(Collectors.toMap(
                                s -> s.getTitle().toLowerCase(Locale.ROOT),
                                Function.identity(),
                                (a, b) -> a
                        ));

                List<Song> plist = Optional.ofNullable(localPl.getSongList()).map(ArrayList::new).orElseGet(ArrayList::new);
                int total = plist.size();
                int foundCount = 0;
                Set<Long> tidsToPersist = new HashSet<>();

                for (Song s : plist) {
                    if (s == null) continue;
                    if (s.getSongID() > 0 && localById.containsKey(s.getSongID())) {
                        foundCount++;
                        continue;
                    }
                    if (s.getTitle() != null) {
                        Song byTitle = localByTitle.get(s.getTitle().toLowerCase(Locale.ROOT));
                        if (byTitle != null) {
                            foundCount++;
                            continue;
                        }
                    }
                    if (s.getSongID() > 0) tidsToPersist.add(s.getSongID());
                }

                // A saved remote playlist can hold only placeholder metadata in
                // its own list. Count usable songs resolved from the library as
                // a local fallback as well.
                if (foundCount > 0) {
                    hasLocalFallback.set(true);
                }

                if (foundCount == total && total > 0) {
                    List<Song> merged = new ArrayList<>();
                    for (Song s : plist) {
                        if (s == null) continue;
                        Song chosen = null;
                        if (s.getSongID() > 0) chosen = localById.get(s.getSongID());
                        if (chosen == null && s.getTitle() != null) chosen = localByTitle.get(s.getTitle().toLowerCase(Locale.ROOT));
                        if (chosen == null) chosen = s;
                        merged.add(chosen);
                    }

                    Playlist plCopy = new Playlist(
                            localPl.getId(),
                            localPl.getTitle(),
                            localPl.getAuthorName(),
                            localPl.getDescription(),
                            localPl.getDate(),
                            null,
                            FXCollections.observableArrayList(merged)
                    );
                    plCopy.setCoverUrl(localPl.getCoverUrl());

                    Platform.runLater(() -> {
                        try {
                            navigator.openPlayerMenuIfCurrent(plCopy, ContentType.PLAYLIST, probe, requestId);
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    });

                }

                else if (foundCount > 0) {
                    Set<Long> toPersist = new HashSet<>(tidsToPersist);
                    toPersist.removeAll(localById.keySet());

                    Map<Long, Song> dbPlaceholders = new HashMap<>();
                    for (Long tid : toPersist) {
                        String ttitle = plist.stream()
                                .filter(ss -> ss != null && ss.getSongID() == tid)
                                .map(Song::getTitle)
                                .filter(Objects::nonNull)
                                .findFirst()
                                .orElse("");

                        Song placeholder = new Song(
                                tid,
                                ttitle == null ? "" : ttitle,
                                new ArrayList<>(),
                                new Album(0L, "", new ArrayList<>(), new Genre(0, ""), "", "", new ArrayList<>(), new ArrayList<>(), 0),
                                null,
                                0,
                                false
                        );
                        dbPlaceholders.put(tid, placeholder);
                    }

                    List<Song> merged = new ArrayList<>();
                    for (Song s : plist) {
                        if (s == null) continue;
                        Song chosen = null;
                        if (s.getSongID() > 0) chosen = localById.get(s.getSongID());
                        if (chosen == null && s.getTitle() != null) chosen = localByTitle.get(s.getTitle().toLowerCase(Locale.ROOT));
                        if (chosen == null && s.getSongID() > 0) chosen = dbPlaceholders.get(s.getSongID());
                        if (chosen == null) chosen = s;
                        merged.add(chosen);
                    }

                    Playlist plCopy = new Playlist(
                            localPl.getId(),
                            localPl.getTitle(),
                            localPl.getAuthorName(),
                            localPl.getDescription(),
                            localPl.getDate(),
                            null,
                            FXCollections.observableArrayList(merged)
                    );
                    plCopy.setCoverUrl(localPl.getCoverUrl());

                    Platform.runLater(() -> {
                        try {
                            navigator.openPlayerMenuIfCurrent(plCopy, ContentType.PLAYLIST, probe, requestId);
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    });

                    IO_POOL.submit(() -> {
                        try {
                            if (!navigator.isOpenRequestCurrent(requestId)) return;
                            if (toPersist.isEmpty()) return;

                            PlaybackManager pmCheck = PlaybackManager.getInstance();
                            if (pmCheck == null || pmCheck.getCurrentPlaylistInViewId() != playlistId) return;

                            try {
                                DbConnectionManager.getInstance().runInTransaction(conn -> {
                                    for (Long tid : toPersist) {
                                        try (PreparedStatement psIns = conn.prepareStatement(
                                                "INSERT OR IGNORE INTO Song(SongID, Title, Album, TrackOrder, IsLocal) VALUES(?, ?, ?, ?, 0)")) {
                                            String ttitle = plist.stream()
                                                    .filter(ss -> ss != null && ss.getSongID() == tid)
                                                    .map(Song::getTitle)
                                                    .filter(Objects::nonNull)
                                                    .findFirst()
                                                    .orElse("");
                                            psIns.setLong(1, tid);
                                            psIns.setString(2, ttitle);
                                            psIns.setLong(3, 0L);
                                            psIns.setInt(4, 0);
                                            try {
                                                psIns.executeUpdate();
                                            } catch (SQLException ignore) {}
                                        } catch (SQLException ignore) {}
                                    }
                                    return null;
                                });
                            } catch (Exception ignored) {}
                        } catch (Throwable t) {
                            t.printStackTrace();
                        }
                    });

                    return;
                }
            }
        } catch (Exception ignore) {
        }

        IO_POOL.submit(() -> {
            try {
                if (!navigator.isOpenRequestCurrent(requestId)) return;

                String url = "https://api.deezer.com/playlist/" + playlistId;
                JsonObject plJson = MusicCardHelper.fetchFreshJsonObject(url);
                if (MusicCardHelper.isDeezerError(plJson)) {
                    showRemoteLoadErrorIfNeeded(probe, null, hasLocalFallback, loadErrorShown);
                    return;
                }

                if (!navigator.isOpenRequestCurrent(requestId)) return;

                String title = plJson.has("title") && !plJson.get("title").isJsonNull()
                        ? plJson.get("title").getAsString()
                        : "Playlist";

                String coverUrl = DeezerApiService.extractCoverUrlFromAlbumOrPlaylist(plJson);

                List<String> creators = MusicCardHelper.extractArtistNamesFromPlaylistJson(plJson);
                String creator = creators.isEmpty() ? "CustomPlaylist" : creators.get(0);

                String description = "";
                try {
                    if (plJson.has("description") && !plJson.get("description").isJsonNull()) {
                        description = plJson.get("description").getAsString().trim();
                    }
                } catch (Exception ignored) {
                }
                if (description == null || description.isBlank()) description = "Custom Playlist";

                String creationDate = "";
                try {
                    if (plJson.has("creation_date") && !plJson.get("creation_date").isJsonNull()) {
                        creationDate = plJson.get("creation_date").getAsString().trim();
                    }
                } catch (Exception ignored) {
                }

                List<Song> allLocalSongs = snapshot(svc == null ? List.of() : svc.getSongs());
                Map<Long, Song> localById = allLocalSongs.stream()
                        .filter(s -> s != null && s.getSongID() > 0)
                        .collect(Collectors.toMap(Song::getSongID, Function.identity(), (a, b) -> a));

                Map<String, Song> localByTitle = allLocalSongs.stream()
                        .filter(s -> s != null && s.getTitle() != null)
                        .collect(Collectors.toMap(
                                s -> s.getTitle().toLowerCase(Locale.ROOT),
                                Function.identity(),
                                (a, b) -> a
                        ));

                List<Artist> allLocalArtists = snapshot(svc == null ? List.of() : svc.getArtists());
                Map<Long, Artist> localArtistById = allLocalArtists.stream()
                        .filter(a -> a != null && a.getArtistID() > 0)
                        .collect(Collectors.toMap(Artist::getArtistID, Function.identity(), (a, b) -> a));

                Map<String, Artist> localArtistByName = allLocalArtists.stream()
                        .filter(a -> a != null && a.getName() != null)
                        .collect(Collectors.toMap(
                                a -> a.getName().toLowerCase(Locale.ROOT),
                                Function.identity(),
                                (a, b) -> a
                        ));

                Set<Long> tidsToPersist = new HashSet<>();

                List<JsonObject> firstPageTracks = extractTrackData(plJson, true);
                List<Song> songs = buildSongsFromTrackJson(
                        firstPageTracks,
                        creator,
                        localById,
                        localByTitle,
                        localArtistById,
                        localArtistByName,
                        tidsToPersist
                );

                if (songs.isEmpty() && !hasLocalFallback.get()) {
                    showRemoteLoadErrorIfNeeded(probe, title, hasLocalFallback, loadErrorShown);
                    return;
                }

                Playlist firstPaintPlaylist = createPlaylistModel(
                        playlistId,
                        title,
                        creator,
                        description,
                        creationDate,
                        coverUrl,
                        songs
                );

                publishPlaylistIfCurrent(firstPaintPlaylist, probe, requestId, playlistId, true);
                hasLocalFallback.set(true);

                int expectedTrackCount = expectedPlaylistTrackCount(plJson);
                if (expectedTrackCount > 0 && songs.size() >= expectedTrackCount) {
                    persistRemoteSongPlaceholdersAsync(tidsToPersist, songs, localById, requestId, playlistId);
                    return;
                }

                try {
                    List<JsonObject> completeTrackJson = fetchCompletePlaylistTracks(playlistId, plJson);
                    if (!navigator.isOpenRequestCurrent(requestId)) return;
                    if (completeTrackJson.size() <= firstPageTracks.size()) {
                        persistRemoteSongPlaceholdersAsync(tidsToPersist, songs, localById, requestId, playlistId);
                        return;
                    }

                    Set<Long> completeTidsToPersist = new HashSet<>();
                    List<Song> completeSongs = buildSongsFromTrackJson(
                            completeTrackJson,
                            creator,
                            localById,
                            localByTitle,
                            localArtistById,
                            localArtistByName,
                            completeTidsToPersist
                    );
                    if (completeSongs.isEmpty()) {
                        persistRemoteSongPlaceholdersAsync(tidsToPersist, songs, localById, requestId, playlistId);
                        return;
                    }

                    Playlist completePlaylist = createPlaylistModel(
                            playlistId,
                            title,
                            creator,
                            description,
                            creationDate,
                            coverUrl,
                            completeSongs
                    );

                    publishPlaylistIfCurrent(completePlaylist, probe, requestId, playlistId, false);
                    persistRemoteSongPlaceholdersAsync(completeTidsToPersist, completeSongs, localById, requestId, playlistId);
                } catch (Exception hydrateEx) {
                    persistRemoteSongPlaceholdersAsync(tidsToPersist, songs, localById, requestId, playlistId);
                }

            } catch (Exception ex) {
                showRemoteLoadErrorIfNeeded(probe, null, hasLocalFallback, loadErrorShown);
                ex.printStackTrace();
            }
        });
    }

    private Playlist createPlaylistModel(long playlistId,
                                         String title,
                                         String creator,
                                         String description,
                                         String creationDate,
                                         String coverUrl,
                                         List<Song> songs) {
        Playlist playlist = new Playlist(
                playlistId,
                title != null ? title : "Playlist",
                creator,
                description,
                creationDate,
                null,
                FXCollections.observableArrayList(songs == null ? List.of() : songs)
        );
        playlist.setCoverUrl(coverUrl);
        return playlist;
    }

    private void publishPlaylistIfCurrent(Playlist playlist,
                                          Node probe,
                                          long requestId,
                                          long playlistId,
                                          boolean navigateIfInactive) {
        if (playlist == null) return;

        Platform.runLater(() -> {
            try {
                if (!navigator.isOpenRequestCurrent(requestId)) return;

                PlayerMenuController active = PlaybackManager.getInstance().getMenuController();
                if (active != null
                        && active.isCurrentCenterViewVisible()
                        && active.getCurrentContentTypeInView() == ContentType.PLAYLIST
                        && active.getCurrentPlaylistInViewId() == playlistId) {
                    active.updatePlaylistContent(playlist);
                    return;
                }

                if (navigateIfInactive) {
                    navigator.openPlayerMenuIfCurrent(playlist, ContentType.PLAYLIST, probe, requestId);
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });
    }

    private List<Song> buildSongsFromTrackJson(List<JsonObject> trackJsons,
                                               String creator,
                                               Map<Long, Song> localById,
                                               Map<String, Song> localByTitle,
                                               Map<Long, Artist> localArtistById,
                                               Map<String, Artist> localArtistByName,
                                               Set<Long> tidsToPersist) {
        List<Song> songs = new ArrayList<>();
        Set<Long> seenTrackIds = new HashSet<>();

        for (JsonObject tj : Optional.ofNullable(trackJsons).orElse(List.of())) {
            long tid = DeezerApiService.safeGetLong(tj, "id", -1L);
            if (tid > 0 && !seenTrackIds.add(tid)) continue;

            String ttitle = DeezerApiService.extractTitle(tj);

            if (tid > 0 && localById != null && localById.containsKey(tid)) {
                Song local = localById.get(tid);
                if (local != null) {
                    songs.add(local);
                    continue;
                }
            }

            if (ttitle != null && !ttitle.isBlank() && localByTitle != null) {
                Song byTitle = localByTitle.get(ttitle.toLowerCase(Locale.ROOT));
                if (byTitle != null) {
                    songs.add(byTitle);
                    continue;
                }
            }

            List<Artist> artistPlaceholders = extractTrackArtists(
                    tj,
                    creator,
                    localArtistById,
                    localArtistByName
            );

            Album alb = extractTrackAlbum(tj);

            Song s = new Song(
                    tid > 0 ? tid : 0L,
                    ttitle == null ? "Unknown" : ttitle,
                    artistPlaceholders,
                    alb,
                    null,
                    songs.size() + 1,
                    false
            );
            songs.add(s);

            if (tid > 0 && tidsToPersist != null) tidsToPersist.add(tid);
        }

        return songs;
    }

    private void persistRemoteSongPlaceholdersAsync(Set<Long> tidsToPersist,
                                                    List<Song> songs,
                                                    Map<Long, Song> localById,
                                                    long requestId,
                                                    long playlistId) {
        if (tidsToPersist == null || tidsToPersist.isEmpty()) return;

        Set<Long> toPersist = new HashSet<>(tidsToPersist);
        if (localById != null) {
            toPersist.removeAll(localById.keySet());
        }
        if (toPersist.isEmpty()) return;

        Map<Long, String> titleById = Optional.ofNullable(songs).orElse(List.of()).stream()
                .filter(Objects::nonNull)
                .filter(song -> song.getSongID() > 0)
                .collect(Collectors.toMap(Song::getSongID, song -> Optional.ofNullable(song.getTitle()).orElse(""), (a, b) -> a));

        IO_POOL.submit(() -> {
            try {
                if (!navigator.isOpenRequestCurrent(requestId)) return;

                PlaybackManager pmCheck = PlaybackManager.getInstance();
                if (pmCheck == null || pmCheck.getCurrentPlaylistInViewId() != playlistId) return;

                DbConnectionManager.getInstance().runInTransaction(conn -> {
                    for (Long id : toPersist) {
                        try (PreparedStatement psIns = conn.prepareStatement(
                                "INSERT OR IGNORE INTO Song(SongID, Title, Album, TrackOrder, IsLocal) VALUES(?, ?, ?, ?, 0)")) {
                            psIns.setLong(1, id);
                            psIns.setString(2, titleById.getOrDefault(id, ""));
                            psIns.setLong(3, 0L);
                            psIns.setInt(4, 0);
                            try {
                                psIns.executeUpdate();
                            } catch (SQLException ignore) {}
                        } catch (SQLException ignore) {}
                    }
                    return null;
                });
            } catch (Throwable t) {
                t.printStackTrace();
            }
        });
    }

    /**
     * The playlist object returned by Deezer embeds only the first page of
     * tracks. Saved remote playlists therefore need the remaining pages before
     * they can be rendered as a complete collection.
     */
    private List<JsonObject> fetchCompletePlaylistTracks(long playlistId, JsonObject playlistJson) throws java.io.IOException {
        final int pageSize = 100;
        List<JsonObject> tracks = new ArrayList<>();
        Set<Long> seenTrackIds = new HashSet<>();
        appendPlaylistTracks(tracks, seenTrackIds, extractTrackData(playlistJson, true));

        int expected = expectedPlaylistTrackCount(playlistJson);

        int offset = tracks.size();
        while (expected <= 0 || tracks.size() < expected) {
            JsonObject page = MusicCardHelper.fetchJsonObject(
                    "https://api.deezer.com/playlist/" + playlistId
                            + "/tracks?index=" + offset + "&limit=" + pageSize
            );
            List<JsonObject> pageTracks = extractTrackData(page, false);
            if (pageTracks.isEmpty()) break;

            int sizeBeforeAppend = tracks.size();
            appendPlaylistTracks(tracks, seenTrackIds, pageTracks);
            offset += pageTracks.size();

            if (pageTracks.size() < pageSize || tracks.size() == sizeBeforeAppend) break;
        }
        return tracks;
    }

    private int expectedPlaylistTrackCount(JsonObject playlistJson) {
        try {
            return playlistJson != null && playlistJson.has("nb_tracks")
                    ? Math.max(0, playlistJson.get("nb_tracks").getAsInt())
                    : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private List<JsonObject> extractTrackData(JsonObject payload, boolean nestedTracks) {
        if (payload == null) return List.of();
        try {
            JsonObject source = nestedTracks && payload.has("tracks") && payload.get("tracks").isJsonObject()
                    ? payload.getAsJsonObject("tracks")
                    : payload;
            if (!source.has("data") || !source.get("data").isJsonArray()) return List.of();

            List<JsonObject> result = new ArrayList<>();
            for (JsonElement element : source.getAsJsonArray("data")) {
                if (element != null && element.isJsonObject()) result.add(element.getAsJsonObject());
            }
            return result;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void appendPlaylistTracks(List<JsonObject> target, Set<Long> seenTrackIds, List<JsonObject> source) {
        for (JsonObject track : source) {
            if (track == null) continue;
            long id = DeezerApiService.safeGetLong(track, "id", 0L);
            if (id > 0 && !seenTrackIds.add(id)) continue;
            target.add(track);
        }
    }

    private static <T> List<T> snapshot(List<T> list) {
        return list == null ? List.of() : new ArrayList<>(list);
    }

    private List<Artist> extractTrackArtists(JsonObject trackJson,
                                             String fallbackCreator,
                                             Map<Long, Artist> localArtistById,
                                             Map<String, Artist> localArtistByName) {
        List<Artist> artists = new ArrayList<>();
        if (trackJson != null && trackJson.has("contributors") && trackJson.get("contributors").isJsonArray()) {
            for (JsonElement element : trackJson.getAsJsonArray("contributors")) {
                if (!element.isJsonObject()) continue;
                addTrackArtist(artists, element.getAsJsonObject(), localArtistById, localArtistByName);
            }
        }

        if (artists.isEmpty() && trackJson != null && trackJson.has("artist") && trackJson.get("artist").isJsonObject()) {
            addTrackArtist(artists, trackJson.getAsJsonObject("artist"), localArtistById, localArtistByName);
        }

        if (artists.isEmpty()) {
            artists.add(new Artist(0L, fallbackCreator == null || fallbackCreator.isBlank() ? "Unknown" : fallbackCreator, null, new ArrayList<>()));
        }

        return artists;
    }

    private void addTrackArtist(List<Artist> artists,
                                JsonObject artistObj,
                                Map<Long, Artist> localArtistById,
                                Map<String, Artist> localArtistByName) {
        long aid = DeezerApiService.safeGetLong(artistObj, "id", 0L);
        String name = artistObj.has("name") && !artistObj.get("name").isJsonNull()
                ? artistObj.get("name").getAsString()
                : DeezerApiService.extractTitle(artistObj);
        if (name == null || name.isBlank()) return;

        Artist found = aid > 0 && localArtistById != null ? localArtistById.get(aid) : null;
        if (found == null && aid <= 0 && localArtistByName != null) {
            found = localArtistByName.get(name.toLowerCase(Locale.ROOT));
        }
        Artist chosen = found != null ? found : new Artist(aid > 0 ? aid : 0L, name, null, new ArrayList<>());
        String portraitUrl = DeezerArtistMetadataResolver.pictureUrl(artistObj);
        if (DeezerArtistMetadataResolver.isUsableArtistPictureUrl(portraitUrl)) {
            chosen.setPortraitUrl(portraitUrl);
        }

        boolean exists = artists.stream().anyMatch(a -> sameArtistIdentity(a, chosen));
        if (!exists) artists.add(chosen);
    }

    private boolean sameArtistIdentity(Artist first, Artist second) {
        if (first == null || second == null) return false;
        if (first.getArtistID() > 0 || second.getArtistID() > 0) {
            return first.getArtistID() > 0
                    && second.getArtistID() > 0
                    && first.getArtistID() == second.getArtistID();
        }
        return first.getName() != null
                && second.getName() != null
                && first.getName().equalsIgnoreCase(second.getName());
    }

    private Album extractTrackAlbum(JsonObject trackJson) {
        long albumId = 0L;
        String albumTitle = "";
        String albumCoverUrl = null;

        try {
            if (trackJson != null && trackJson.has("album") && trackJson.get("album").isJsonObject()) {
                JsonObject albumJson = trackJson.getAsJsonObject("album");
                albumId = DeezerApiService.safeGetLong(albumJson, "id", 0L);
                albumTitle = Optional.ofNullable(DeezerApiService.extractTitle(albumJson)).orElse("");
                albumCoverUrl = DeezerApiService.extractCoverUrlFromAlbumOrPlaylist(albumJson);
            }
        } catch (Exception ignored) {
        }

        Album album = new Album(
                albumId,
                albumTitle,
                new ArrayList<>(),
                new Genre(0, ""),
                "",
                "",
                new ArrayList<>(),
                new ArrayList<>(),
                0
        );
        album.setCoverUrl(albumCoverUrl);
        return album;
    }

    private void showRemoteLoadError(Node probe, String title) {
        Platform.runLater(() -> RemoteContentLoadErrorDialog.show(
                RemoteContentLoadErrorDialog.ContentKind.PLAYLIST,
                title,
                probe
        ));
    }

    private void showRemoteLoadErrorIfNeeded(Node probe,
                                             String title,
                                             java.util.concurrent.atomic.AtomicBoolean hasLocalFallback,
                                             java.util.concurrent.atomic.AtomicBoolean loadErrorShown) {
        if (hasLocalFallback != null && hasLocalFallback.get()) return;
        if (loadErrorShown != null && !loadErrorShown.compareAndSet(false, true)) return;
        showRemoteLoadError(probe, title);
    }

    private boolean hasPlayableLocalSong(List<Song> songs) {
        return Optional.ofNullable(songs).orElse(List.of()).stream().anyMatch(this::isPlayableLocalSong);
    }

    private boolean isPlayableLocalSong(Song song) {
        return song != null
                && song.isLocal()
                && song.getFilePath() != null
                && !song.getFilePath().isBlank();
    }
}
