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
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;
import io.github.guillermodubon.musicplayer.services.api.DeezerArtistMetadataResolver;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.utils.AlbumArtistResolver;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SongPlaybackCoordinator {

    private final StartUpService svc;
    private final PlayerMenuNavigator navigator;

    private static final ExecutorService IO_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "song-playback-coordinator-io");
        t.setDaemon(true);
        return t;
    });

    public SongPlaybackCoordinator(StartUpService svc, PlayerMenuNavigator navigator) {
        this.svc = svc;
        this.navigator = navigator;
    }

    public void handle(String idStr, Node probe) {
        if (idStr == null) return;

        final long requestId = navigator.beginOpenRequest();
        final String NO_META_PREFIX = "no_meta_";

        if (idStr.startsWith(NO_META_PREFIX)) {
            String encodedTitle = idStr.substring(NO_META_PREFIX.length());
            String title;
            try {
                title = URLDecoder.decode(encodedTitle, StandardCharsets.UTF_8.name()).trim();
            } catch (Exception e) {
                title = encodedTitle.replace('+', ' ').trim();
            }

            if (title.isBlank()) {
                System.err.println("handleOnSingleMusicCardClick: empty title for no-metadata id: " + idStr);
                return;
            }

            javafx.util.Pair<String, String> match = null;
            try {
                if (svc != null && svc.noMetadataSongs != null) {
                    for (javafx.util.Pair<String, String> p : svc.noMetadataSongs) {
                        if (p == null || p.getKey() == null) continue;
                        if (p.getKey().equalsIgnoreCase(title)) {
                            match = p;
                            break;
                        }
                    }
                    if (match == null) {
                        String lower = title.toLowerCase(Locale.ROOT);
                        for (javafx.util.Pair<String, String> p : svc.noMetadataSongs) {
                            if (p == null || p.getKey() == null) continue;
                            if (p.getKey().toLowerCase(Locale.ROOT).contains(lower)) {
                                match = p;
                                break;
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }

            if (match == null) {
                System.err.println("handleOnSingleMusicCardClick: no-metadata song not found in svc.noMetadataSongs for title: " + title);
                return;
            }

            String path = match.getValue();
            Song temp = new Song(
                    0L,
                    title,
                    List.of(new Artist(0L, "Unknown", null, new ArrayList<>())),
                    null,
                    path,
                    1,
                    true
            );

            Playlist single = new Playlist(
                    0L,
                    temp.getTitle(),
                    "CustomPlaylist",
                    "",
                    "",
                    null,
                    FXCollections.observableArrayList(temp)
            );

            try {
                navigator.openPlayerMenuIfCurrent(single, ContentType.SINGLE, probe, requestId);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return;
        }

        final long id;
        try {
            id = Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            System.err.println("handleOnSingleMusicCardClick invalid id: " + idStr);
            return;
        }

        try {
            Optional<Song> localOpt = MusicCardHelper.snapshot(svc == null ? List.of() : svc.getSongs()).stream()
                    .filter(Objects::nonNull)
                    .filter(s -> s.getSongID() == id)
                    .filter(this::isPlayableLocalSong)
                    .findFirst();

            if (localOpt.isPresent()) {
                Song song = localOpt.get();

                Playlist single = new Playlist(
                        song.getSongID(),
                        song.getTitle(),
                        "",
                        "",
                        (song.getAlbum() != null ? Optional.ofNullable(song.getAlbum().getReleaseDate()).orElse("") : ""),
                        null,
                        FXCollections.observableArrayList(song)
                );
                if (song.getAlbum() != null) single.setCoverUrl(song.getAlbum().getCoverUrl());

                try {
                    navigator.openPlayerMenuIfCurrent(single, ContentType.SINGLE, probe, requestId);
                } catch (Exception e) {
                    e.printStackTrace();
                }

                IO_POOL.submit(() -> {
                    try {
                        PlaybackManager pm = PlaybackManager.getInstance();
                        if (pm == null || pm.getCurrentPlaylistInViewId() != id) return;
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                });

                return;
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        IO_POOL.submit(() -> {
            if (!navigator.isOpenRequestCurrent(requestId)) return;

            JsonObject trackJson = null;
            try {
                trackJson = MusicCardHelper.fetchFreshJsonObject("https://api.deezer.com/track/" + id);
            } catch (IOException ioe) {
                showRemoteLoadError(probe, null);
                return;
            }

            if (!navigator.isOpenRequestCurrent(requestId)) return;
            if (MusicCardHelper.isDeezerError(trackJson)) {
                showRemoteLoadError(probe, null);
                return;
            }

            String title = "Unknown";
            List<Artist> artists = new ArrayList<>();
            Album alb = new Album(0L, "", new ArrayList<>(), new Genre(0, ""), "", "", new ArrayList<>(), new ArrayList<>(), 0);
            int trackOrder = 0;
            String coverUrl = null;

            if (trackJson != null) {
                try {
                    title = DeezerApiService.extractTitle(trackJson);
                    artists.addAll(extractArtistsFromTrackJson(trackJson));
                    if (artists.isEmpty()) {
                        artists.add(new Artist(0L, "Unknown", null, new ArrayList<>()));
                    }

                    if (trackJson.has("album") && trackJson.get("album").isJsonObject()) {
                        JsonObject albObj = trackJson.getAsJsonObject("album");
                        long aid = DeezerApiService.safeGetLong(albObj, "id", 0L);
                        String aname = DeezerApiService.extractTitle(albObj);
                        coverUrl = DeezerApiService.extractCoverUrlFromAlbumOrPlaylist(albObj);
                        String relDate = albObj.has("release_date") && !albObj.get("release_date").isJsonNull() ? albObj.get("release_date").getAsString() : "";
                        alb = new Album(aid, aname == null ? "" : aname,
                                extractAlbumArtistsFromJson(albObj),
                                new Genre(0, ""), "", relDate,
                                new ArrayList<>(), new ArrayList<>(), 0);
                        alb.setCoverUrl(coverUrl);
                    }

                    try {
                        trackOrder = trackJson.has("track_position") && !trackJson.get("track_position").isJsonNull()
                                ? trackJson.get("track_position").getAsInt()
                                : 0;
                    } catch (Exception ignored) {}
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            final String resolvedTitle = title == null ? "Unknown" : title;
            final List<Artist> resolvedArtists = new ArrayList<>(artists);
            final Album finalAlb = alb;
            final int finalTrackOrder = trackOrder;

            Song remoteSong = new Song(id, resolvedTitle, resolvedArtists, finalAlb, null, finalTrackOrder, false);
            Playlist single = new Playlist(
                    id,
                    remoteSong.getTitle(),
                    (resolvedArtists.isEmpty() ? "CustomPlaylist" : resolvedArtists.get(0).getName()),
                    "",
                    (finalAlb != null ? Optional.ofNullable(finalAlb.getReleaseDate()).orElse("") : ""),
                    null,
                    FXCollections.observableArrayList(remoteSong)
            );
            if (finalAlb != null) single.setCoverUrl(finalAlb.getCoverUrl());

            Platform.runLater(() -> {
                try {
                    navigator.openPlayerMenuIfCurrent(single, ContentType.SINGLE, probe, requestId);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            });

            IO_POOL.submit(() -> {
                try {
                    if (!navigator.isOpenRequestCurrent(requestId)) return;

                    // Track responses often contain only the primary album
                    // artist. Resolve the album resource in the background
                    // only when the initial owner collection is incomplete,
                    // so the first render stays fast while the header can be
                    // corrected with every co-owner and its Deezer ID.
                    List<Artist> detailedAlbumOwners = finalAlb != null
                            && finalAlb.getAlbumID() > 0
                            && (finalAlb.getArtist() == null || finalAlb.getArtist().size() <= 1)
                            ? fetchDetailedAlbumArtists(finalAlb.getAlbumID())
                            : List.of();

                    List<Artist> resolved = new ArrayList<>();
                    for (Artist a : resolvedArtists) {
                        try {
                            Artist ra = (svc != null) ? MusicCardHelper.resolveArtist(a.getArtistID(), a.getName(), svc) : a;
                            if (ra != null) resolved.add(ra);
                        } catch (Exception ignored) {
                            resolved.add(a);
                        }
                    }
                    remoteSong.getArtist().clear();
                    remoteSong.getArtist().addAll(resolved);

                    PlaybackManager pm = PlaybackManager.getInstance();
                    if (!navigator.isOpenRequestCurrent(requestId)) return;
                    if (pm == null || pm.getCurrentPlaylistInViewId() != id) return;

                    Set<Long> toPersist = new HashSet<>();
                    if (remoteSong.getSongID() > 0) toPersist.add(remoteSong.getSongID());

                    if (!toPersist.isEmpty()) {
                        try {
                            DbConnectionManager.getInstance().runInTransaction(conn -> {
                                for (Long sid : toPersist) {
                                    try (PreparedStatement ps = conn.prepareStatement(
                                            "INSERT OR IGNORE INTO Song(SongID, Title, Album, TrackOrder, IsLocal) VALUES(?, ?, ?, ?, 0)")) {
                                        ps.setLong(1, sid);
                                        ps.setString(2, Optional.ofNullable(remoteSong.getTitle()).orElse(""));
                                        ps.setLong(3, remoteSong.getAlbum() != null ? remoteSong.getAlbum().getAlbumID() : 0L);
                                        ps.setInt(4, remoteSong.getTrackOrder());
                                        try {
                                            ps.executeUpdate();
                                        } catch (SQLException ignore) {}
                                    } catch (SQLException ignore) {}
                                }
                                return null;
                            });
                        } catch (Exception ignored) {}
                    }

                    if (finalAlb != null && finalAlb.getAlbumID() > 0 && finalAlb.getReleaseDate() != null && !finalAlb.getReleaseDate().isBlank()) {
                        try {
                            DbConnectionManager.getInstance().runInTransaction(conn -> {
                                try (PreparedStatement psUpd = conn.prepareStatement("UPDATE Album SET ReleaseDate = ? WHERE AlbumID = ?")) {
                                    psUpd.setString(1, finalAlb.getReleaseDate());
                                    psUpd.setLong(2, finalAlb.getAlbumID());
                                    int updated = psUpd.executeUpdate();
                                    if (updated <= 0) {
                                        try (PreparedStatement psIns = conn.prepareStatement("INSERT OR IGNORE INTO Album(AlbumID, Name, ReleaseDate, NumberOfTracks) VALUES(?, ?, ?, ?)")) {
                                            psIns.setLong(1, finalAlb.getAlbumID());
                                            psIns.setString(2, Optional.ofNullable(finalAlb.getName()).orElse(""));
                                            psIns.setString(3, finalAlb.getReleaseDate());
                                            psIns.setInt(4, finalAlb.getNumberOfTracks());
                                            try {
                                                psIns.executeUpdate();
                                            } catch (SQLException ignore) {}
                                        } catch (SQLException ignore) {}
                                    }
                                } catch (SQLException ignore) {}
                                return null;
                            });
                        } catch (Exception ignored) {}
                    }

                    Platform.runLater(() -> {
                        try {
                            if (!navigator.isOpenRequestCurrent(requestId)) return;
                            PlayerMenuController active = PlaybackManager.getInstance().getMenuController();
                            if (active != null) {
                                try {
                                    Playlist model = active.getCurrentPlaylistModel();
                                    if (model != null && model.getId() == id) {
                                        if (finalAlb != null && !detailedAlbumOwners.isEmpty()) {
                                            finalAlb.setArtist(detailedAlbumOwners);
                                        }
                                        Playlist updated = new Playlist(
                                                remoteSong.getSongID(),
                                                remoteSong.getTitle(),
                                                (remoteSong.getArtist().isEmpty() ? "" : remoteSong.getArtist().get(0).getName()),
                                                "",
                                                (remoteSong.getAlbum() != null ? Optional.ofNullable(remoteSong.getAlbum().getReleaseDate()).orElse("") : ""),
                                                null,
                                                FXCollections.observableArrayList(remoteSong)
                                        );
                                        updated.setCoverUrl(remoteSong.getAlbum() == null ? null : remoteSong.getAlbum().getCoverUrl());
                                        active.updatePlaylistContent(updated);
                                    }
                                } catch (Throwable ignore) {}
                            }
                        } catch (Exception ignored) {}
                    });

                } catch (Throwable t) {
                    t.printStackTrace();
                }
            });
        });
    }

    private void showRemoteLoadError(Node probe, String title) {
        Platform.runLater(() -> RemoteContentLoadErrorDialog.show(
                RemoteContentLoadErrorDialog.ContentKind.SINGLE,
                title,
                probe
        ));
    }

    /**
     * Keeps the artist object returned by Deezer, including its ID. Building
     * this list from display names loses the identity and makes PlayerMenu
     * resolve a homonymous artist by name on the first render.
     */
    private List<Artist> extractArtistsFromTrackJson(JsonObject trackJson) {
        List<Artist> artists = new ArrayList<>();
        if (trackJson == null) return artists;

        try {
            if (trackJson.has("contributors")
                    && trackJson.get("contributors").isJsonArray()) {
                for (JsonElement element : trackJson.getAsJsonArray("contributors")) {
                    if (element != null && element.isJsonObject()) {
                        addArtistFromJson(artists, element.getAsJsonObject());
                    }
                }
            }

            if (artists.isEmpty()
                    && trackJson.has("artist")
                    && trackJson.get("artist").isJsonObject()) {
                addArtistFromJson(artists, trackJson.getAsJsonObject("artist"));
            }
        } catch (Exception ignored) {
        }

        return artists;
    }

    private List<Artist> extractAlbumArtistsFromJson(JsonObject albumJson) {
        List<Artist> artists = new ArrayList<>();
        for (AlbumArtistResolver.ArtistReference reference : AlbumArtistResolver.resolve(albumJson)) {
            if (reference == null || reference.name() == null || reference.name().isBlank()) continue;

            JsonObject artistJson = new JsonObject();
            if (reference.id() > 0) artistJson.addProperty("id", reference.id());
            artistJson.addProperty("name", reference.name());
            addArtistFromJson(artists, artistJson);
        }
        return artists;
    }

    private List<Artist> fetchDetailedAlbumArtists(long albumId) {
        try {
            JsonObject albumJson = MusicCardHelper.fetchFreshJsonObject(
                    "https://api.deezer.com/album/" + albumId
            );
            if (MusicCardHelper.isDeezerError(albumJson)) return List.of();
            return extractAlbumArtistsFromJson(albumJson);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void addArtistFromJson(List<Artist> artists, JsonObject artistJson) {
        if (artists == null || artistJson == null) return;

        long artistId = DeezerApiService.safeGetLong(artistJson, "id", 0L);
        String artistName = artistJson.has("name") && !artistJson.get("name").isJsonNull()
                ? artistJson.get("name").getAsString()
                : null;
        if (artistName == null || artistName.isBlank()) return;

        boolean alreadyPresent = artists.stream().anyMatch(existing -> {
            if (existing == null) return false;
            if (artistId > 0 && existing.getArtistID() > 0) {
                return existing.getArtistID() == artistId;
            }
            return artistId <= 0
                    && existing.getArtistID() <= 0
                    && existing.getName() != null
                    && existing.getName().equalsIgnoreCase(artistName);
        });
        if (alreadyPresent) return;

        Artist artist = new Artist(artistId, artistName, null, new ArrayList<>());
        String pictureUrl = DeezerArtistMetadataResolver.pictureUrl(artistJson);
        if (DeezerArtistMetadataResolver.isUsableArtistPictureUrl(pictureUrl)) {
            artist.setPortraitUrl(pictureUrl);
        }
        artists.add(artist);
    }

    private boolean isPlayableLocalSong(Song song) {
        return song != null
                && song.isLocal()
                && song.getFilePath() != null
                && !song.getFilePath().isBlank();
    }
}
