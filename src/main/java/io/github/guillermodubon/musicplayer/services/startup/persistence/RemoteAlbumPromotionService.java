
package io.github.guillermodubon.musicplayer.services.startup.persistence;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import io.github.guillermodubon.musicplayer.repository.DbConnectionManager;
import io.github.guillermodubon.musicplayer.repository.identity.CanonicalMediaService;
import io.github.guillermodubon.musicplayer.repository.identity.CanonicalizationResult;
import io.github.guillermodubon.musicplayer.repository.identity.ExternalMediaId;
import io.github.guillermodubon.musicplayer.repository.dao.album.AlbumDaoImpl;
import io.github.guillermodubon.musicplayer.repository.dao.artist.ArtistDaoImpl;
import io.github.guillermodubon.musicplayer.repository.dao.genre.GenreDao;
import io.github.guillermodubon.musicplayer.repository.dao.genre.GenreDaoImpl;
import io.github.guillermodubon.musicplayer.repository.dao.song.SongDao;
import io.github.guillermodubon.musicplayer.repository.dao.song.SongDaoImpl;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.HomePageController;
import io.github.guillermodubon.musicplayer.utils.SongAudioIdentity;
import io.github.guillermodubon.musicplayer.models.*;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;
import io.github.guillermodubon.musicplayer.services.api.DeezerHttpClient;
import io.github.guillermodubon.musicplayer.utils.DeezerEndpoints;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.services.startup.hydration.ModelHydrationService;
import io.github.guillermodubon.musicplayer.services.startup.locality.SongLocalityService;
import io.github.guillermodubon.musicplayer.utils.AlbumArtistResolver;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import java.util.stream.Collectors;

import static io.github.guillermodubon.musicplayer.services.startup.persistence.RemoteArtistPersistence.ensureById;
import static io.github.guillermodubon.musicplayer.services.startup.persistence.RemoteArtistPersistence.ensureByName;
import static io.github.guillermodubon.musicplayer.services.startup.persistence.RemoteArtistPersistence.sameName;

public class RemoteAlbumPromotionService {

    private final StartUpService owner;
    private final ModelHydrationService modelHydrationService;
    private final SongLocalityService songLocalityService;

    public RemoteAlbumPromotionService(
            StartUpService owner,
            ModelHydrationService modelHydrationService,
            SongLocalityService songLocalityService
    ) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.modelHydrationService = Objects.requireNonNull(modelHydrationService, "modelHydrationService");
        this.songLocalityService = Objects.requireNonNull(songLocalityService, "songLocalityService");
    }

    public boolean promoteRemoteAlbumToLocalDynamic(DeezerApiMetaData meta, File file) {
        if (meta == null || meta.getAlbumId() <= 0) {
            System.out.println("promoteRemoteAlbumToLocalDynamic: meta nulo o albumId inválido");
            return false;
        }

        final long albumId = meta.getAlbumId();
        final long downloadedTrackId = meta.getTrackId();
        final String downloadedPath = file == null ? null : file.getAbsolutePath();

        Map<String, String> localPathByAudioIdentity = owner.getSongIdentityPathSnapshot();
        if (downloadedPath != null && !downloadedPath.isBlank()) {
            SongAudioIdentity.keyFor(meta).ifPresent(
                    identity -> localPathByAudioIdentity.put(identity, downloadedPath)
            );
        }

        JsonObject albumJson = null;
        List<DeezerTrackInfo> albumTracks = List.of();
        Map<String, byte[]> albumCoverBytes = new LinkedHashMap<>();
        Map<Long, List<byte[]>> artistImageBytes = new HashMap<>();
        List<Long> contributorArtistIds = new ArrayList<>();
        List<String> albumArtistNames = new ArrayList<>();
        List<Long> albumArtistOwnerIds = new ArrayList<>();
        Map<Long, String> artistNamesById = new HashMap<>();

        try {
            String albumUrl = DeezerEndpoints.defaultMainMenuEndpoints().albumById(albumId);
            albumJson = DeezerHttpClient.fetchJsonObjectStatic(albumUrl);
            if (albumJson != null) {
                String coverSmall = optString(albumJson, "cover");
                String coverMedium = optString(albumJson, "cover_medium");
                String coverXl = optString(albumJson, "cover_xl");

                try {
                    if (coverSmall != null) {
                        byte[] bb = DeezerHttpClient.downloadUrlToBytesStatic(coverSmall);
                        if (bb != null && bb.length > 0) albumCoverBytes.put("small", bb);
                    }
                    if (coverMedium != null) {
                        byte[] bb = DeezerHttpClient.downloadUrlToBytesStatic(coverMedium);
                        if (bb != null && bb.length > 0) albumCoverBytes.put("medium", bb);
                    }
                    if (coverXl != null) {
                        byte[] bb = DeezerHttpClient.downloadUrlToBytesStatic(coverXl);
                        if (bb != null && bb.length > 0) albumCoverBytes.put("xl", bb);
                    }
                } catch (Exception e) {
                    System.out.println("promoteRemoteAlbumToLocalDynamic: warning downloading album covers -> " + Optional.ofNullable(e.getMessage()).orElse("null"));
                }

                if (albumJson.has("contributors") && albumJson.get("contributors").isJsonArray()) {
                    for (JsonElement ce : albumJson.getAsJsonArray("contributors")) {
                        if (!ce.isJsonObject()) continue;
                        JsonObject co = ce.getAsJsonObject();
                        long aid = DeezerApiService.safeGetLong(co, "id", -1L);
                        String role = optString(co, "role");
                        String name = DeezerApiService.extractTitle(co);
                        if (role == null || role.toLowerCase(Locale.ROOT).contains("main")) {
                            if (aid > 0) contributorArtistIds.add(aid);
                            if (aid > 0 && name != null) artistNamesById.put(aid, name);
                            if (name != null && !albumArtistNames.contains(name)) albumArtistNames.add(name);
                        }
                    }
                }
                if (albumJson.has("artist") && albumJson.get("artist").isJsonObject()) {
                    JsonObject aobj = albumJson.getAsJsonObject("artist");
                    long aid = DeezerApiService.safeGetLong(aobj, "id", -1L);
                    String name = DeezerApiService.extractTitle(aobj);
                    if (aid > 0) contributorArtistIds.add(aid);
                    if (aid > 0 && name != null) artistNamesById.put(aid, name);
                    if (name != null && albumArtistNames.stream().noneMatch(n -> sameName(n, name))) {
                        albumArtistNames.add(name);
                    }
                }

                /*
                 * Rebuild the album-owner collection from all Deezer owner
                 * fields. The promotion later upserts AlbumArtist from meta,
                 * so keeping this collection complete is essential when a
                 * downloaded track is the first local file of the album.
                 */
                List<AlbumArtistResolver.ArtistReference> resolvedOwners =
                        AlbumArtistResolver.resolve(albumJson);
                if (!resolvedOwners.isEmpty()) {
                    albumArtistNames.clear();
                    albumArtistOwnerIds.clear();
                    for (AlbumArtistResolver.ArtistReference owner : resolvedOwners) {
                        if (owner == null || owner.name() == null || owner.name().isBlank()) {
                            continue;
                        }
                        albumArtistNames.add(owner.name().trim());
                        albumArtistOwnerIds.add(Math.max(0L, owner.id()));
                        if (owner.id() > 0) {
                            if (!contributorArtistIds.contains(owner.id())) {
                                contributorArtistIds.add(owner.id());
                            }
                            artistNamesById.put(owner.id(), owner.name().trim());
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("promoteRemoteAlbumToLocalDynamic: warning fetching album JSON -> " + Optional.ofNullable(e.getMessage()).orElse("null"));
        }

        try {
            albumTracks = DeezerApiService.fetchAlbumTracks(albumId);
            if (albumTracks == null) albumTracks = List.of();
            if (!albumTracks.isEmpty()) {
                meta.setNumberOfTracks(Math.max(meta.getNumberOfTracks(), albumTracks.size()));
                if (albumTracks.size() > 1
                        && (meta.getRecordType() == null
                        || meta.getRecordType().isBlank()
                        || "single".equalsIgnoreCase(meta.getRecordType()))) {
                    meta.setRecordType("album");
                }
            }
            System.out.println("promoteRemoteAlbumToLocalDynamic: fetched album tracks count=" + albumTracks.size());
        } catch (Exception e) {
            albumTracks = List.of();
            System.out.println("promoteRemoteAlbumToLocalDynamic: warning fetching tracks -> " + Optional.ofNullable(e.getMessage()).orElse("null"));
        }

        Map<Long, List<String>> contributorsByTrackId = new HashMap<>();
        try {
            if (albumJson != null && albumJson.has("tracks") && albumJson.get("tracks").isJsonObject()) {
                JsonObject tracksObj = albumJson.getAsJsonObject("tracks");
                if (tracksObj.has("data") && tracksObj.get("data").isJsonArray()) {
                    for (JsonElement te : tracksObj.getAsJsonArray("data")) {
                        if (!te.isJsonObject()) continue;
                        JsonObject to = te.getAsJsonObject();
                        long tid = DeezerApiService.safeGetLong(to, "id", -1L);
                        if (tid <= 0) continue;
                        List<String> tContribs = new ArrayList<>();
                        if (to.has("contributors") && to.get("contributors").isJsonArray()) {
                            for (JsonElement ce : to.getAsJsonArray("contributors")) {
                                if (!ce.isJsonObject()) continue;
                                String cname = DeezerApiService.extractTitle(ce.getAsJsonObject());
                                if (cname != null && !cname.isBlank() && !tContribs.contains(cname)) {
                                    tContribs.add(cname);
                                }
                            }
                        }
                        if (!tContribs.isEmpty()) contributorsByTrackId.put(tid, tContribs);
                    }
                }
            }
        } catch (Exception ignore) {
        }

        if (albumArtistNames.isEmpty() && meta.getAlbumArtistNames() != null) {
            for (String name : meta.getAlbumArtistNames()) {
                if (name != null && !name.isBlank() && !albumArtistNames.contains(name)) {
                    albumArtistNames.add(name);
                }
            }
        }

        mergeAlbumOwnersIntoMetadata(
                meta,
                albumArtistNames,
                albumArtistOwnerIds
        );

        if (meta.getAlbumArtistIds() != null) {
            List<String> names = meta.getAlbumArtistNames() == null ? List.of() : meta.getAlbumArtistNames();
            for (int i = 0; i < meta.getAlbumArtistIds().size(); i++) {
                Long aid = meta.getAlbumArtistIds().get(i);
                if (aid == null || aid <= 0) continue;
                if (!contributorArtistIds.contains(aid)) contributorArtistIds.add(aid);
                if (i < names.size()) {
                    String name = names.get(i);
                    if (name != null && !name.isBlank()) artistNamesById.putIfAbsent(aid, name);
                }
            }
        }

        if (meta.getSongContributorIds() != null) {
            List<String> names = meta.getSongContributorNames() == null ? List.of() : meta.getSongContributorNames();
            for (int i = 0; i < meta.getSongContributorIds().size(); i++) {
                Long aid = meta.getSongContributorIds().get(i);
                if (aid == null || aid <= 0) continue;
                if (!contributorArtistIds.contains(aid)) contributorArtistIds.add(aid);
                if (i < names.size()) {
                    String name = names.get(i);
                    if (name != null && !name.isBlank()) artistNamesById.putIfAbsent(aid, name);
                }
            }
        }

        if (!contributorArtistIds.isEmpty()) {
            for (Long aid : contributorArtistIds) {
                try {
                    JsonObject art = DeezerHttpClient.fetchJsonObjectStatic(DeezerEndpoints.artistById(aid));
                    if (art == null) continue;
                    String picSmall = optString(art, "picture");
                    String picMed = optString(art, "picture_medium");
                    String picBig = optString(art, "picture_big");
                    String aname = DeezerApiService.extractTitle(art);
                    if (aname != null && !artistNamesById.containsKey(aid)) artistNamesById.put(aid, aname);

                    List<byte[]> list = new ArrayList<>();
                    list.add(picSmall == null ? null : DeezerHttpClient.downloadUrlToBytesStatic(picSmall));
                    list.add(picMed == null ? null : DeezerHttpClient.downloadUrlToBytesStatic(picMed));
                    list.add(picBig == null ? null : DeezerHttpClient.downloadUrlToBytesStatic(picBig));
                    artistImageBytes.put(aid, list);
                } catch (Exception ex) {
                    System.out.println("promoteRemoteAlbumToLocalDynamic: warning fetching artist " + aid + " -> " + Optional.ofNullable(ex.getMessage()).orElse("null"));
                }
            }
        }

        java.util.function.Function<DeezerTrackInfo, Optional<String>> findLocalPathFor = track -> {
            if (track == null) {
                return Optional.empty();
            }
            if (track.getId() > 0 && track.getId() == downloadedTrackId && downloadedPath != null) {
                return Optional.of(downloadedPath);
            }
            List<String> trackArtists = trackArtistsForIdentity(
                    track.getId(),
                    contributorsByTrackId,
                    albumArtistNames
            );
            return SongAudioIdentity.keyFor(track.getTitle(), trackArtists)
                    .map(localPathByAudioIdentity::get)
                    .filter(path -> path != null && !path.isBlank());
        };

        if (meta.getAlbumId() > 0) {
            return promoteCanonicalAlbum(
                    meta,
                    file,
                    albumTracks,
                    albumCoverBytes,
                    artistImageBytes,
                    albumArtistNames,
                    contributorArtistIds,
                    artistNamesById,
                    contributorsByTrackId,
                    findLocalPathFor
            );
        }

        List<Long> localTrackIdsToMark = new ArrayList<>();

        synchronized (owner.getDbLock()) {
            try {
                List<DeezerTrackInfo> finalAlbumTracks = albumTracks;
                DbConnectionManager.getInstance().runInTransaction(conn -> {
                    System.out.println("promoteRemoteAlbumToLocalDynamic: transaction started conn=" + System.identityHashCode(conn)
                            + " thread=" + Thread.currentThread().getName());

                    try (Statement st = conn.createStatement()) {
                        try { st.execute("PRAGMA busy_timeout = 5000"); } catch (Exception ignore) {}
                    } catch (Exception ignore) {
                    }

                    GenreDao genreDao = new GenreDaoImpl(conn);
                    ArtistDaoImpl artistDao = new ArtistDaoImpl(conn);
                    AlbumDaoImpl albumDao = new AlbumDaoImpl(conn);
                    SongDao songDao = new SongDaoImpl(conn);

                    try {
                        String gname = meta.getGenre();
                        if (gname != null && !gname.isBlank()) {
                            if (genreDao.findIdByName(gname) == 0) {
                                genreDao.create(gname);
                            }
                        }
                    } catch (Exception ex) {
                        System.out.println("promoteRemoteAlbumToLocalDynamic: warn genre upsert -> " + Optional.ofNullable(ex.getMessage()).orElse("null"));
                    }

                    for (Long aid : contributorArtistIds) {
                        if (aid == null || aid <= 0) continue;
                        ensureById(conn, artistDao, aid, artistNamesById.get(aid), artistImageBytes.get(aid));
                    }

                    try {
                        for (String an : albumArtistNames) {
                            if (an == null || an.isBlank()) continue;
                            ensureByName(conn, artistDao, an, artistNamesById, artistImageBytes);
                        }
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }

                    try {
                        try {
                            albumDao.upsertFromMeta(conn, meta);
                        } catch (NoSuchMethodError | AbstractMethodError | UnsupportedOperationException nm) {
                            albumDao.upsertFromMeta(meta);
                        }

                        if (!albumCoverBytes.isEmpty()) {
                            for (Map.Entry<String, byte[]> ent : albumCoverBytes.entrySet()) {
                                String type = ent.getKey();
                                byte[] data = ent.getValue();
                                if (data == null || data.length == 0) continue;
                                try (PreparedStatement psImg = conn.prepareStatement("INSERT OR IGNORE INTO AlbumImage(AlbumID, ImageType, ImageData) VALUES(?, ?, ?)")) {
                                    psImg.setLong(1, albumId);
                                    psImg.setString(2, type);
                                    psImg.setBytes(3, data);
                                    psImg.executeUpdate();
                                } catch (Exception ex) {
                                    System.out.println("promoteRemoteAlbumToLocalDynamic: warning inserting album image -> " + Optional.ofNullable(ex.getMessage()).orElse("null"));
                                }
                            }
                        }
                    } catch (Exception ex) {
                        System.out.println("promoteRemoteAlbumToLocalDynamic: album upsert failed -> " + Optional.ofNullable(ex.getMessage()).orElse("null"));
                        throw new RuntimeException(ex);
                    }

                    try {
                        if (finalAlbumTracks != null && !finalAlbumTracks.isEmpty()) {
                            List<Long> trackIds = finalAlbumTracks.stream()
                                    .map(t -> t == null ? 0L : (t.getId() > 0 ? t.getId() : 0L))
                                    .filter(id -> id > 0)
                                    .distinct()
                                    .toList();

                            Set<Long> existingIds = new HashSet<>();
                            Map<Long, Boolean> existingLocalById = new HashMap<>();
                            if (!trackIds.isEmpty()) {
                                String ph = trackIds.stream().map(x -> "?").collect(Collectors.joining(","));
                                String q = "SELECT SongID, IsLocal FROM Song WHERE SongID IN (" + ph + ")";
                                try (PreparedStatement ps = conn.prepareStatement(q)) {
                                    int idx = 1;
                                    for (Long id : trackIds) ps.setLong(idx++, id);
                                    try (ResultSet rs = ps.executeQuery()) {
                                        while (rs.next()) {
                                            long existingId = rs.getLong(1);
                                            existingIds.add(existingId);
                                            existingLocalById.put(existingId, rs.getInt(2) != 0);
                                        }
                                    }
                                } catch (Exception ignore) {
                                }
                            }

                            List<Long> localTracksInTx = new ArrayList<>();

                            try (PreparedStatement upd = conn.prepareStatement("UPDATE Song SET IsLocal = ?, TrackOrder = ?, Album = ? WHERE SongID = ?");
                                 PreparedStatement insById = conn.prepareStatement("INSERT INTO Song(SongID, Title, Album, TrackOrder, IsLocal) VALUES(?, ?, ?, ?, ?) "
                                         + "ON CONFLICT(SongID) DO UPDATE SET Title=excluded.Title, Album=excluded.Album, "
                                         + "TrackOrder=excluded.TrackOrder, IsLocal=MAX(Song.IsLocal, excluded.IsLocal)");
                                 PreparedStatement insByVisual = conn.prepareStatement("INSERT OR IGNORE INTO Song(Title, Album, TrackOrder, IsLocal) VALUES(?, ?, ?, ?)");
                                 PreparedStatement linkStmt = conn.prepareStatement("INSERT OR IGNORE INTO SongArtist(SongID, ArtistID) VALUES(?, ?)")) {

                                for (DeezerTrackInfo info : finalAlbumTracks) {
                                    if (info == null) continue;
                                    long tid = info.getId() > 0 ? info.getId() : 0L;
                                    String title = Optional.ofNullable(info.getTitle()).orElse("");
                                    int trackOrder = info.getTrackOrder();
                                    Optional<String> localPath = findLocalPathFor.apply(info);
                                    boolean localFound = localPath.isPresent();
                                    boolean shouldBeLocal = localFound
                                            || (tid > 0 && existingLocalById.getOrDefault(tid, false))
                                            || (tid > 0 && tid == downloadedTrackId && downloadedPath != null);

                                    if (tid > 0) {
                                        if (existingIds.contains(tid)) {
                                            upd.setInt(1, shouldBeLocal ? 1 : 0);
                                            upd.setInt(2, trackOrder);
                                            upd.setLong(3, albumId);
                                            upd.setLong(4, tid);
                                            upd.executeUpdate();
                                        } else {
                                            insById.setLong(1, tid);
                                            insById.setString(2, title);
                                            insById.setLong(3, albumId);
                                            insById.setInt(4, trackOrder);
                                            insById.setInt(5, shouldBeLocal ? 1 : 0);
                                            insById.executeUpdate();
                                        }
                                        if (shouldBeLocal) localTracksInTx.add(tid);
                                    } else {
                                        insByVisual.setString(1, title);
                                        insByVisual.setLong(2, albumId);
                                        insByVisual.setInt(3, trackOrder);
                                        insByVisual.setInt(4, shouldBeLocal ? 1 : 0);
                                        try {
                                            insByVisual.executeUpdate();
                                        } catch (Exception ignore) {
                                        }
                                    }

                                    Set<String> trackArtistNames = new LinkedHashSet<>();
                                    if (albumArtistNames != null) trackArtistNames.addAll(albumArtistNames);
                                    if (contributorsByTrackId.containsKey(tid)) {
                                        trackArtistNames.addAll(contributorsByTrackId.get(tid));
                                    }
                                    if (tid > 0 && tid == downloadedTrackId) {
                                        if (meta.getSongContributorNames() != null) trackArtistNames.addAll(meta.getSongContributorNames());
                                    }

                                    for (String an : trackArtistNames) {
                                        if (an == null || an.isBlank()) continue;
                                        long aid = ensureByName(conn, artistDao, an, artistNamesById, artistImageBytes);
                                        if (aid > 0 && tid > 0) {
                                            try {
                                                linkStmt.setLong(1, tid);
                                                linkStmt.setLong(2, aid);
                                                linkStmt.executeUpdate();
                                            } catch (Exception ignore) {
                                            }
                                        }
                                    }

                                    if (shouldBeLocal && tid > 0) {
                                        localTrackIdsToMark.add(tid);
                                    }
                                }
                            }
                        } else {
                            long tid = meta.getTrackId();
                            String title = Optional.ofNullable(meta.getSongName()).orElse("");
                            int trackOrder = Math.max(0, meta.getTrackOrder());
                            if (tid > 0) {
                                boolean exists = false;
                                try (PreparedStatement chk = conn.prepareStatement("SELECT 1 FROM Song WHERE SongID = ? LIMIT 1")) {
                                    chk.setLong(1, tid);
                                    try (ResultSet rs = chk.executeQuery()) {
                                        exists = rs.next();
                                    }
                                }
                                if (exists) {
                                    try (PreparedStatement upd = conn.prepareStatement("UPDATE Song SET IsLocal = 1, TrackOrder = ?, Album = ? WHERE SongID = ?")) {
                                        upd.setInt(1, trackOrder);
                                        upd.setLong(2, albumId);
                                        upd.setLong(3, tid);
                                        upd.executeUpdate();
                                    }
                                } else {
                                    try (PreparedStatement ins = conn.prepareStatement("INSERT INTO Song(SongID, Title, Album, TrackOrder, IsLocal) VALUES(?, ?, ?, ?, 1) "
                                            + "ON CONFLICT(SongID) DO UPDATE SET Title=excluded.Title, Album=excluded.Album, "
                                            + "TrackOrder=excluded.TrackOrder, IsLocal=1")) {
                                        ins.setLong(1, tid);
                                        ins.setString(2, title);
                                        ins.setLong(3, albumId);
                                        ins.setInt(4, trackOrder);
                                        ins.executeUpdate();
                                    }
                                }
                                localTrackIdsToMark.add(tid);

                                Set<String> songArtists = new LinkedHashSet<>();
                                if (meta.getAlbumArtistNames() != null) songArtists.addAll(meta.getAlbumArtistNames());
                                if (meta.getSongContributorNames() != null) songArtists.addAll(meta.getSongContributorNames());
                                try (PreparedStatement link = conn.prepareStatement("INSERT OR IGNORE INTO SongArtist(SongID, ArtistID) VALUES(?, ?)")) {
                                    for (String an : songArtists) {
                                        if (an == null || an.isBlank()) continue;
                                        long aid = ensureByName(conn, artistDao, an, artistNamesById, artistImageBytes);
                                        if (aid > 0) {
                                            try {
                                                link.setLong(1, tid);
                                                link.setLong(2, aid);
                                                link.executeUpdate();
                                            } catch (Exception ignore) {
                                            }
                                        }
                                    }
                                } catch (Exception ignore) {
                                }
                            }
                        }
                    } catch (Exception ex) {
                        System.out.println("promoteRemoteAlbumToLocalDynamic: SQL error inserting songs -> " + Optional.ofNullable(ex.getMessage()).orElse("null"));
                        throw new RuntimeException(ex);
                    }

                    try {
                        modelHydrationService.loadModelsForAlbum(conn, albumId);
                    } catch (Exception e) {
                        System.out.println("promoteRemoteAlbumToLocalDynamic: warning loading models for album -> " + e.getMessage());
                    }

                    try {
                        List<Song> albumSongs = owner.getSongs().stream()
                                .filter(s -> s != null && s.getAlbum() != null && s.getAlbum().getAlbumID() == albumId)
                                .toList();
                        if (!albumSongs.isEmpty()) {
                            Album refreshed = owner.getAlbums().stream()
                                    .filter(a -> a != null && a.getAlbumID() == albumId)
                                    .findFirst().orElse(null);
                            if (refreshed != null) {
                                Platform.runLater(() -> {
                                    try {
                                        HomePageController mm = owner.getMainMenuController();
                                        if (mm != null) {
                                            Platform.runLater(() -> mm.refreshSections(""));
                                        }
                                    } catch (Exception ignore) {}
                                });
                            }
                        }
                    } catch (Exception ignore) {
                    }

                    return null;
                });
            } catch (RuntimeException rte) {
                System.out.println("promoteRemoteAlbumToLocalDynamic: transaction failed -> " + Optional.ofNullable(rte.getMessage()).orElse("null"));
                rte.printStackTrace();
                return false;
            } catch (Exception outer) {
                System.out.println("promoteRemoteAlbumToLocalDynamic: outer failure -> " + Optional.ofNullable(outer.getMessage()).orElse("null"));
                outer.printStackTrace();
                return false;
            }
        }

        for (Long tid : localTrackIdsToMark) {
            try {
                String p = tid != null && tid == downloadedTrackId && downloadedPath != null
                        ? downloadedPath
                        : findBestPathFromIdentityMap(
                                albumTracks,
                                contributorsByTrackId,
                                albumArtistNames,
                                localPathByAudioIdentity,
                                tid
                        );
                if (p != null) {
                    songLocalityService.markSongAsLocal(tid, p);
                }
            } catch (Exception ignore) {
            }
        }

        return true;
    }

    private boolean promoteCanonicalAlbum(
            DeezerApiMetaData metadata,
            File downloadedFile,
            List<DeezerTrackInfo> albumTracks,
            Map<String, byte[]> albumCoverBytes,
            Map<Long, List<byte[]>> artistImageBytes,
            List<String> albumArtistNames,
            List<Long> contributorArtistIds,
            Map<Long, String> artistNamesById,
            Map<Long, List<String>> contributorsByTrackId,
            java.util.function.Function<DeezerTrackInfo, Optional<String>> findLocalPathFor
    ) {
        CanonicalMediaService.AlbumMetadata release = new CanonicalMediaService.AlbumMetadata(
                Optional.ofNullable(metadata.getAlbumName()).filter(name -> !name.isBlank())
                        .orElseGet(() -> Optional.ofNullable(metadata.getSongName()).filter(name -> !name.isBlank())
                                .orElse("Unknown release")),
                Optional.ofNullable(metadata.getGenre()).filter(name -> !name.isBlank()).orElse("Unknown"),
                Optional.ofNullable(metadata.getRecordType()).filter(name -> !name.isBlank()).orElse("album"),
                metadata.getAlbumReleaseDate(),
                Math.max(0, metadata.getNumberOfTracks()),
                albumArtistNames == null ? List.of() : albumArtistNames.stream()
                        .filter(name -> name != null && !name.isBlank()).map(String::trim).distinct().toList()
        );
        Map<Long, String> canonicalLocalPaths = new LinkedHashMap<>();

        try {
            long canonicalAlbumId;
            synchronized (owner.getDbLock()) {
                canonicalAlbumId = DbConnectionManager.getInstance().runInTransaction(connection -> {
                    try {
                        ArtistDaoImpl artistDao = new ArtistDaoImpl(connection);
                        for (Long artistId : contributorArtistIds) {
                            if (artistId == null || artistId <= 0) continue;
                            try {
                                ensureById(connection, artistDao, artistId,
                                        artistNamesById.get(artistId), artistImageBytes.get(artistId));
                            } catch (Exception artistError) {
                                throw new RuntimeException(artistError);
                            }
                        }
                        for (String artistName : albumArtistNames) {
                            if (artistName == null || artistName.isBlank()) continue;
                            try {
                                ensureByName(connection, artistDao, artistName, artistNamesById, artistImageBytes);
                            } catch (Exception artistError) {
                                throw new RuntimeException(artistError);
                            }
                        }

                        CanonicalMediaService media = new CanonicalMediaService();
                        CanonicalizationResult<Long> releaseResult = media.ensureCanonicalAlbum(
                                connection,
                                ExternalMediaId.deezer(metadata.getAlbumId()),
                                release
                        );
                        if (!releaseResult.isSuccess()) {
                            throw new SQLException("Deezer release identity conflict: "
                                    + releaseResult.conflict().reason());
                        }
                        long albumId = releaseResult.value();
                        persistAlbumImages(connection, albumId, albumCoverBytes);

                        Set<Long> processedTrackIds = new HashSet<>();
                        if (albumTracks != null) {
                            for (DeezerTrackInfo track : albumTracks) {
                                if (track == null || track.getId() <= 0 || track.getTitle() == null
                                        || track.getTitle().isBlank()) continue;
                                long externalTrackId = track.getId();
                                Set<String> trackArtists = new LinkedHashSet<>();
                                if (albumArtistNames != null) {
                                    albumArtistNames.stream().filter(name -> name != null && !name.isBlank())
                                            .map(String::trim).forEach(trackArtists::add);
                                }
                                contributorsByTrackId.getOrDefault(externalTrackId, List.of()).stream()
                                        .filter(name -> name != null && !name.isBlank())
                                        .map(String::trim).forEach(trackArtists::add);
                                if (externalTrackId == metadata.getTrackId()
                                        && metadata.getSongContributorNames() != null) {
                                    metadata.getSongContributorNames().stream()
                                            .filter(name -> name != null && !name.isBlank())
                                            .map(String::trim).forEach(trackArtists::add);
                                }
                                String path = findLocalPathFor.apply(track)
                                        .filter(RemoteAlbumPromotionService::isReadableFile)
                                        .orElse(null);
                                CanonicalMediaService.SongMetadata song = new CanonicalMediaService.SongMetadata(
                                        track.getTitle(),
                                        track.getTrackOrder(),
                                        externalTrackId == metadata.getTrackId() ? metadata.getDurationSeconds() : 0,
                                        List.copyOf(trackArtists),
                                        release,
                                        path != null,
                                        path
                                );
                                CanonicalizationResult<CanonicalMediaService.CanonicalSong> songResult =
                                        media.ensureCanonicalSong(connection,
                                                ExternalMediaId.deezer(externalTrackId),
                                                ExternalMediaId.deezer(metadata.getAlbumId()),
                                                song);
                                if (!songResult.isSuccess()) {
                                    throw new SQLException("Deezer track identity conflict for track "
                                            + externalTrackId + ": " + songResult.conflict().reason());
                                }
                                if (path != null) canonicalLocalPaths.put(songResult.value().songId(), path);
                                processedTrackIds.add(externalTrackId);
                            }
                        }

                        long targetTrackId = metadata.getTrackId();
                        if (targetTrackId > 0 && !processedTrackIds.contains(targetTrackId)) {
                            Set<String> targetArtists = new LinkedHashSet<>(albumArtistNames);
                            if (metadata.getSongContributorNames() != null) {
                                metadata.getSongContributorNames().stream()
                                        .filter(name -> name != null && !name.isBlank())
                                        .map(String::trim).forEach(targetArtists::add);
                            }
                            String title = Optional.ofNullable(metadata.getSongName()).filter(name -> !name.isBlank())
                                    .orElse("Unknown track " + targetTrackId);
                            CanonicalMediaService.SongMetadata target = new CanonicalMediaService.SongMetadata(
                                    title,
                                    Math.max(0, metadata.getTrackOrder()),
                                    metadata.getDurationSeconds(),
                                    targetArtists.stream().filter(name -> name != null && !name.isBlank())
                                            .map(String::trim).distinct().toList(),
                                    release,
                                    isReadableFile(downloadedFile),
                                    isReadableFile(downloadedFile) ? downloadedFile.getAbsolutePath() : null
                            );
                            CanonicalizationResult<CanonicalMediaService.CanonicalSong> result =
                                    media.ensureCanonicalSong(connection,
                                            ExternalMediaId.deezer(targetTrackId),
                                            ExternalMediaId.deezer(metadata.getAlbumId()),
                                            target);
                            if (!result.isSuccess()) {
                                throw new SQLException("Downloaded track identity conflict: "
                                        + result.conflict().reason());
                            }
                            if (isReadableFile(downloadedFile)) {
                                canonicalLocalPaths.put(result.value().songId(), downloadedFile.getAbsolutePath());
                            }
                        }

                        modelHydrationService.loadModelsForAlbum(connection, albumId);
                        return albumId;
                    } catch (SQLException error) {
                        throw new RuntimeException(error);
                    }
                });
            }

            for (Map.Entry<Long, String> entry : canonicalLocalPaths.entrySet()) {
                songLocalityService.markSongAsLocal(entry.getKey(), entry.getValue());
            }
            try {
                Platform.runLater(() -> {
                    try {
                        HomePageController controller = owner.getMainMenuController();
                        if (controller != null) controller.refreshSections("");
                    } catch (Exception ignored) {
                    }
                });
            } catch (Exception ignored) {
            }
            return canonicalAlbumId > 0;
        } catch (Exception error) {
            System.out.println("promoteCanonicalAlbum: identity-safe promotion failed -> "
                    + Optional.ofNullable(error.getMessage()).orElse("unknown error"));
            return false;
        }
    }

    private void persistAlbumImages(Connection connection,
                                    long albumId,
                                    Map<String, byte[]> images) throws SQLException {
        if (images == null || images.isEmpty()) return;
        try (PreparedStatement exists = connection.prepareStatement(
                "SELECT 1 FROM AlbumImage WHERE AlbumID = ? AND ImageType = ? LIMIT 1");
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO AlbumImage(AlbumID, ImageType, ImageData) VALUES(?, ?, ?)")) {
            for (Map.Entry<String, byte[]> image : images.entrySet()) {
                byte[] data = image.getValue();
                if (data == null || data.length == 0) continue;
                exists.setLong(1, albumId);
                exists.setString(2, image.getKey());
                try (ResultSet row = exists.executeQuery()) {
                    if (row.next()) continue;
                }
                insert.setLong(1, albumId);
                insert.setString(2, image.getKey());
                insert.setBytes(3, data);
                insert.executeUpdate();
            }
        }
    }

    private static boolean isReadableFile(String path) {
        if (path == null || path.isBlank()) return false;
        try {
            Path candidate = Path.of(path);
            return Files.isRegularFile(candidate) && Files.isReadable(candidate) && Files.size(candidate) > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isReadableFile(File file) {
        return file != null && isReadableFile(file.getAbsolutePath());
    }

    private void mergeAlbumOwnersIntoMetadata(
            DeezerApiMetaData metadata,
            List<String> resolvedNames,
            List<Long> resolvedIds
    ) {
        if (metadata == null || resolvedNames == null || resolvedNames.isEmpty()) {
            return;
        }

        List<String> mergedNames = metadata.getAlbumArtistNames() == null
                ? new ArrayList<>()
                : new ArrayList<>(metadata.getAlbumArtistNames());
        List<Long> mergedIds = new ArrayList<>(mergedNames.size());
        List<Long> currentIds = metadata.getAlbumArtistIds();

        for (int index = 0; index < mergedNames.size(); index++) {
            Long id = currentIds != null && index < currentIds.size()
                    ? currentIds.get(index)
                    : null;
            mergedIds.add(id == null ? 0L : Math.max(0L, id));
        }

        for (int index = 0; index < resolvedNames.size(); index++) {
            String name = resolvedNames.get(index);
            if (name == null || name.isBlank()) continue;
            long id = resolvedIds != null && index < resolvedIds.size() && resolvedIds.get(index) != null
                    ? Math.max(0L, resolvedIds.get(index))
                    : 0L;

            int existingIndex = findAlbumOwnerIndex(mergedNames, mergedIds, name, id);
            if (existingIndex < 0) {
                mergedNames.add(name.trim());
                mergedIds.add(id);
            } else if (mergedIds.get(existingIndex) <= 0 && id > 0) {
                mergedIds.set(existingIndex, id);
            }
        }

        metadata.setAlbumArtistNames(mergedNames);
        metadata.setAlbumArtistIds(mergedIds);
    }

    private int findAlbumOwnerIndex(
            List<String> names,
            List<Long> ids,
            String candidateName,
            long candidateId
    ) {
        for (int index = 0; index < names.size(); index++) {
            long existingId = ids != null && index < ids.size() && ids.get(index) != null
                    ? ids.get(index)
                    : 0L;
            if (candidateId > 0 && existingId > 0) {
                if (candidateId == existingId) return index;
                continue;
            }

            if (sameName(names.get(index), candidateName)) return index;
        }
        return -1;
    }

    private static String optString(JsonObject obj, String field) {
        if (obj == null || field == null || !obj.has(field) || obj.get(field).isJsonNull()) return null;
        return obj.get(field).getAsString();
    }

    private String findBestPathFromIdentityMap(
            List<DeezerTrackInfo> tracks,
            Map<Long, List<String>> contributorsByTrackId,
            List<String> albumArtistNames,
            Map<String, String> localPathByAudioIdentity,
            long trackId
    ) {
        if (tracks == null || localPathByAudioIdentity == null || localPathByAudioIdentity.isEmpty()) return null;
        for (DeezerTrackInfo info : tracks) {
            if (info == null || info.getId() != trackId) continue;
            List<String> artists = trackArtistsForIdentity(
                    info.getId(),
                    contributorsByTrackId,
                    albumArtistNames
            );
            Optional<String> identityKey = SongAudioIdentity.keyFor(info.getTitle(), artists);
            if (identityKey.isPresent()) {
                String path = localPathByAudioIdentity.get(identityKey.get());
                if (path != null && !path.isBlank()) {
                    return path;
                }
            }
        }
        return null;
    }

    private List<String> trackArtistsForIdentity(
            long trackId,
            Map<Long, List<String>> contributorsByTrackId,
            List<String> albumArtistNames
    ) {
        if (contributorsByTrackId == null || !contributorsByTrackId.containsKey(trackId)) {
            return List.of();
        }
        Set<String> artists = new LinkedHashSet<>();
        if (albumArtistNames != null) {
            artists.addAll(albumArtistNames);
        }
        artists.addAll(contributorsByTrackId.getOrDefault(trackId, List.of()));
        return List.copyOf(artists);
    }
}
