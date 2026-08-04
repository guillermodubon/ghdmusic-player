
package io.github.guillermodubon.musicplayer.services.startup.persistence;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import io.github.guillermodubon.musicplayer.repository.DbConnectionManager;
import io.github.guillermodubon.musicplayer.repository.dao.album.AlbumDaoImpl;
import io.github.guillermodubon.musicplayer.repository.dao.artist.ArtistDaoImpl;
import io.github.guillermodubon.musicplayer.repository.dao.genre.GenreDao;
import io.github.guillermodubon.musicplayer.repository.dao.genre.GenreDaoImpl;
import io.github.guillermodubon.musicplayer.repository.dao.song.SongDao;
import io.github.guillermodubon.musicplayer.repository.dao.song.SongDaoImpl;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.HomePageController;
import io.github.guillermodubon.musicplayer.utils.SongDataHelper;
import io.github.guillermodubon.musicplayer.models.*;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;
import io.github.guillermodubon.musicplayer.services.api.DeezerHttpClient;
import io.github.guillermodubon.musicplayer.utils.DeezerEndpoints;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.services.startup.hydration.ModelHydrationService;
import io.github.guillermodubon.musicplayer.services.startup.locality.SongLocalityService;
import io.github.guillermodubon.musicplayer.utils.AlbumArtistResolver;

import java.io.File;
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

        Map<String, String> titleToPathLocal = owner.getTitleToPathSnapshot();

        Map<String, String> normalizedTitleToPath = new HashMap<>();
        for (var e : titleToPathLocal.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                normalizedTitleToPath.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
            }
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

        java.util.function.BiFunction<String, Long, Optional<String>> findLocalPathFor = (title, trackId) -> {
            if (trackId != null && trackId > 0 && trackId == downloadedTrackId && downloadedPath != null) {
                return Optional.of(downloadedPath);
            }
            if (title == null) return Optional.empty();
            String key = title.toLowerCase(Locale.ROOT);
            String p = normalizedTitleToPath.get(key);
            if (p != null) return Optional.of(p);
            String sKey = SongDataHelper.sanitizeForFileKey(title).toLowerCase(Locale.ROOT);
            p = normalizedTitleToPath.get(sKey);
            if (p != null) return Optional.of(p);
            String fb = SongDataHelper.fallbackKey(title).toLowerCase(Locale.ROOT);
            p = normalizedTitleToPath.get(fb);
            if (p != null) return Optional.of(p);
            return Optional.empty();
        };

        List<Long> localTrackIdsToMark = new ArrayList<>();

        synchronized (owner.getDbLock()) {
            try {
                List<DeezerTrackInfo> finalAlbumTracks = albumTracks;
                DbConnectionManager.getInstance().runInTransaction(conn -> {
                    System.out.println("promoteRemoteAlbumToLocalDynamic: transaction started conn=" + System.identityHashCode(conn)
                            + " thread=" + Thread.currentThread().getName());

                    try (Statement st = conn.createStatement()) {
                        try { st.execute("PRAGMA busy_timeout = 5000"); } catch (Exception ignore) {}
                        try { st.execute("PRAGMA journal_mode = WAL"); } catch (Exception ignore) {}
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
                                 PreparedStatement insById = conn.prepareStatement("INSERT OR REPLACE INTO Song(SongID, Title, Album, TrackOrder, IsLocal) VALUES(?, ?, ?, ?, ?)");
                                 PreparedStatement insByVisual = conn.prepareStatement("INSERT OR IGNORE INTO Song(Title, Album, TrackOrder, IsLocal) VALUES(?, ?, ?, ?)");
                                 PreparedStatement linkStmt = conn.prepareStatement("INSERT OR IGNORE INTO SongArtist(SongID, ArtistID) VALUES(?, ?)")) {

                                for (DeezerTrackInfo info : finalAlbumTracks) {
                                    if (info == null) continue;
                                    long tid = info.getId() > 0 ? info.getId() : 0L;
                                    String title = Optional.ofNullable(info.getTitle()).orElse("");
                                    int trackOrder = info.getTrackOrder();
                                    Optional<String> localPath = findLocalPathFor.apply(title, tid);
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
                                    if (tid > 0 && tid == downloadedTrackId) {
                                        if (contributorsByTrackId.containsKey(tid)) trackArtistNames.addAll(contributorsByTrackId.get(tid));
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
                                    try (PreparedStatement ins = conn.prepareStatement("INSERT OR REPLACE INTO Song(SongID, Title, Album, TrackOrder, IsLocal) VALUES(?, ?, ?, ?, 1)")) {
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
                        : findBestPathFromMap(albumTracks, normalizedTitleToPath, tid);
                if (p != null) {
                    songLocalityService.markSongAsLocal(tid, p);
                }
            } catch (Exception ignore) {
            }
        }

        return true;
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

    private String findBestPathFromMap(List<DeezerTrackInfo> tracks,
                                       Map<String, String> normalizedTitleToPath,
                                       long trackId) {
        if (tracks == null || normalizedTitleToPath == null || normalizedTitleToPath.isEmpty()) return null;
        for (DeezerTrackInfo info : tracks) {
            if (info == null || info.getId() != trackId) continue;
            String title = info.getTitle();
            if (title == null) continue;
            String key = title.toLowerCase(Locale.ROOT);
            String p = normalizedTitleToPath.get(key);
            if (p != null) return p;
            String sKey = SongDataHelper.sanitizeForFileKey(title).toLowerCase(Locale.ROOT);
            p = normalizedTitleToPath.get(sKey);
            if (p != null) return p;
            String fb = SongDataHelper.fallbackKey(title).toLowerCase(Locale.ROOT);
            p = normalizedTitleToPath.get(fb);
            if (p != null) return p;
        }
        return null;
    }
}
