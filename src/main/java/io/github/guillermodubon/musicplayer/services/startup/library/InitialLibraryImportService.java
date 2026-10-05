package io.github.guillermodubon.musicplayer.services.startup.library;

import javafx.util.Pair;
import io.github.guillermodubon.musicplayer.repository.dao.album.AlbumDao;
import io.github.guillermodubon.musicplayer.repository.dao.album.AlbumDaoImpl;
import io.github.guillermodubon.musicplayer.repository.dao.artist.ArtistDao;
import io.github.guillermodubon.musicplayer.repository.dao.genre.GenreDao;
import io.github.guillermodubon.musicplayer.repository.dao.song.SongDao;
import io.github.guillermodubon.musicplayer.repository.identity.CanonicalMediaService;
import io.github.guillermodubon.musicplayer.repository.identity.CanonicalTracklistService;
import io.github.guillermodubon.musicplayer.repository.identity.ExternalIdentityDao;
import io.github.guillermodubon.musicplayer.repository.identity.ExternalMediaId;
import io.github.guillermodubon.musicplayer.repository.library.SavedMediaService;
import io.github.guillermodubon.musicplayer.utils.SongDataHelper;
import io.github.guillermodubon.musicplayer.models.*;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;
import io.github.guillermodubon.musicplayer.services.manifest.ManifestService;
import io.github.guillermodubon.musicplayer.services.manifest.ManifestSyncService;
import io.github.guillermodubon.musicplayer.services.startup.artist.ArtistBiographyService;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class InitialLibraryImportService {

    private final DeezerApiService deezerService;
    private final ArtistBiographyService artistBiographyService;
    private final ManifestSyncService manifestService;

    public InitialLibraryImportService(
            DeezerApiService deezerService,
            ArtistBiographyService artistBiographyService,
            ManifestSyncService manifestService
    ) {
        this.deezerService = Objects.requireNonNull(deezerService, "deezerService");
        this.artistBiographyService = Objects.requireNonNull(artistBiographyService, "artistBiographyService");
        this.manifestService = Objects.requireNonNull(manifestService, "manifestService");
    }
    public void createAndFetchInitialData(
            Connection conn,
            GenreDao genreDao,
            ArtistDao artistDao,
            AlbumDao albumDao,
            SongDao songDao,
            Map<String,String> titleToPath,
            List<Pair<String, String>> noMetadataSongs,
            List<DeezerApiMetaData> metas
    ) throws SQLException, IOException {
        System.out.println("createAndFetchInitialData: starting; titleToPath.size=" + (titleToPath == null ? 0 : titleToPath.size()) + " metas=" + (metas == null ? 0 : metas.size()));
        if (titleToPath == null || titleToPath.isEmpty()) {
            System.out.println("createAndFetchInitialData: no local files found, returning");
            return;
        }
        if (metas == null) metas = List.of();
        Set<String> originalLocalTitles = new HashSet<>(titleToPath.keySet()); // Capture originals before modifications
        System.out.println("createAndFetchInitialData: calling DAOs upsert/insert (in transaction)");
        // Upsert genres/artists/albums/songs using DAOs bound to 'conn'
        genreDao.upsertAll(metas);
        artistDao.insertArtistsAndImages(metas);
        if (albumDao instanceof AlbumDaoImpl) {
            ((AlbumDaoImpl) albumDao).upsertAll(conn, metas, genreDao, artistDao);
        } else {
            albumDao.upsertAll(metas, genreDao, artistDao);
        }
        songDao.insertSongsAndArtists(metas, albumDao, artistDao);
        // === NUEVA LÃ“GICA ROBUSTA PARA DETECTAR CANCIONES SIN METADATA ===
        Set<String> successfulLocalTitleKeys = new HashSet<>();
        for (DeezerApiMetaData m : metas) {
            if (m == null) continue;
            addSuccessfulTitleKeys(successfulLocalTitleKeys, m.getSongFileName());
            addSuccessfulTitleKeys(successfulLocalTitleKeys, m.getSongName());
        }
        // Build helper maps for local-detection
        Map<String, String> normalizedTitleToPath = new HashMap<>();
        for (var e : titleToPath.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            normalizedTitleToPath.put(e.getKey().toLowerCase(), e.getValue());
        }
        Map<Long, String> localTrackPaths = new LinkedHashMap<>();
        for (DeezerApiMetaData meta : metas) {
            if (meta == null || meta.getTrackId() <= 0) continue;
            String path = findLocalPathForMeta(meta, normalizedTitleToPath);
            if (path != null) localTrackPaths.put(meta.getTrackId(), path);
        }
        markSongsLocal(conn, localTrackPaths);
        // Fetch only albums introduced by this import. Existing remote album rows
        // are unrelated to the local scan and used to trigger unnecessary requests.
        Map<Long, CanonicalMediaService.AlbumMetadata> importedAlbums = new LinkedHashMap<>();
        for (DeezerApiMetaData meta : metas) {
            if (meta == null || meta.getAlbumId() <= 0
                    || meta.getAlbumName() == null || meta.getAlbumName().isBlank()) continue;
            importedAlbums.putIfAbsent(meta.getAlbumId(), new CanonicalMediaService.AlbumMetadata(
                    meta.getAlbumName(),
                    meta.getGenre(),
                    meta.getRecordType(),
                    meta.getAlbumReleaseDate(),
                    meta.getNumberOfTracks(),
                    meta.getAlbumArtistNames()
            ));
        }
        ExternalIdentityDao identities = new ExternalIdentityDao(conn);
        Map<Long, Long> internalAlbumIds = new LinkedHashMap<>();
        for (Long externalAlbumId : importedAlbums.keySet()) {
            var internalId = identities.resolve(
                    ExternalIdentityDao.EntityType.ALBUM,
                    ExternalMediaId.deezer(externalAlbumId)
            );
            if (internalId.isPresent()) internalAlbumIds.put(externalAlbumId, internalId.getAsLong());
        }
        if (internalAlbumIds.size() != importedAlbums.size()) {
            throw new SQLException("Initial import could not resolve a canonical album identity.");
        }
        List<Long> externalAlbumIds = new ArrayList<>(importedAlbums.keySet());

        // Fetch only releases introduced by this import, using provider IDs only at the API boundary.
        int parallelism = Math.min(12, Math.max(4, Runtime.getRuntime().availableProcessors()));
        ExecutorService fetchExec = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        List<Future<AbstractMap.SimpleEntry<Long, List<DeezerTrackInfo>>>> futures = new ArrayList<>();
        for (Long externalAlbumId : externalAlbumIds) {
            futures.add(fetchExec.submit(() -> {
                try {
                    List<DeezerTrackInfo> tracks = DeezerApiService.fetchAlbumTracks(externalAlbumId);
                    return new AbstractMap.SimpleEntry<>(externalAlbumId, tracks);
                } catch (Exception ex) {
                    return new AbstractMap.SimpleEntry<>(externalAlbumId, List.<DeezerTrackInfo>of());
                }
            }));
        }
        fetchExec.shutdown();
        Map<Long, List<DeezerTrackInfo>> albumTracksMap = new HashMap<>();
        for (Future<AbstractMap.SimpleEntry<Long, List<DeezerTrackInfo>>> future : futures) {
            try {
                AbstractMap.SimpleEntry<Long, List<DeezerTrackInfo>> result = future.get();
                if (result != null && result.getKey() != null && result.getValue() != null) {
                    albumTracksMap.put(result.getKey(), result.getValue());
                }
            } catch (Exception ignored) {
            }
        }
        CanonicalTracklistService tracklistService = new CanonicalTracklistService();
        for (Map.Entry<Long, CanonicalMediaService.AlbumMetadata> album : importedAlbums.entrySet()) {
            List<CanonicalTracklistService.TrackMetadata> tracks = albumTracksMap
                    .getOrDefault(album.getKey(), List.of())
                    .stream()
                    .map(info -> new CanonicalTracklistService.TrackMetadata(
                            info.getId(), info.getTitle(), info.getTrackOrder(), 0
                    ))
                    .toList();
            tracklistService.persist(
                    conn,
                    ExternalMediaId.deezer(album.getKey()),
                    album.getValue(),
                    tracks
            );
        }
        // Biographies are enriched after the library transaction commits.
        // They never alter the song/album import, so keeping the remote lookup
        // out of this critical path releases the application much sooner.
        // Build the manifest after inserts (from DB local songs + no-metadata)
        Map<String, ManifestEntry> newManifest = new HashMap<>();
        Map<Long, String> localPathBySongId = resolveLocalPathsByInternalId(conn, localTrackPaths);
        Map<Long, Long> externalTrackIdBySongId = resolveExternalTrackIdsByInternalId(conn, localTrackPaths.keySet());
        for (Song localSong : songDao.findAll().stream().filter(Song::isLocal).toList()) {
            String title = localSong.getTitle();
            if (title == null) continue;
            String path = localPathBySongId.get(localSong.getSongID());
            if (path == null) continue;
            File f = new File(path);
            String fileName = f.getName();
            String cleanedFileName = SongDataHelper.removeFileExtension(fileName).replaceAll("[\\\\/:*?\"<>|]", "").replaceAll("\\s+", " ").trim();
            if (cleanedFileName.length() > 200) cleanedFileName = cleanedFileName.substring(0, 200).trim();
            long ts = f.exists() ? f.lastModified() : System.currentTimeMillis();
            long id = externalTrackIdBySongId.getOrDefault(localSong.getSongID(), 0L);
            newManifest.put(cleanedFileName, new ManifestEntry(
                    id,
                    ts,
                    f.isFile() ? f.length() : 0L,
                    f.getName()
            ));
        }
        // Add no-metadata songs (not in DB)
        for (String localTitle : originalLocalTitles) {
            boolean matched = containsSuccessfulTitle(successfulLocalTitleKeys, localTitle);
            if (!matched) {
                String path = titleToPath.get(localTitle);
                if (path == null || path.isBlank()) continue;
                noMetadataSongs.add(new javafx.util.Pair<>(localTitle, path));
                File f = new File(path);
                String fileName = f.getName();
                String cleanedFileName = SongDataHelper.removeFileExtension(fileName).replaceAll("[\\\\/:*?\"<>|]", "").replaceAll("\\s+", " ").trim();
                if (cleanedFileName.length() > 200) cleanedFileName = cleanedFileName.substring(0, 200).trim();
                long ts = f.lastModified();
                newManifest.put(cleanedFileName, new ManifestEntry(
                        0,
                        ts,
                        f.isFile() ? f.length() : 0L,
                        f.getName()
                ));
            }
        }
        // save the new manifest
        manifestService.save(newManifest);
        System.out.println("createAndFetchInitialData: done (within transaction)");
    }

    private static String findLocalPathForMeta(DeezerApiMetaData meta, Map<String, String> normalizedTitleToPath) {
        if (meta == null || normalizedTitleToPath == null || normalizedTitleToPath.isEmpty()) return null;
        List<String> candidates = new ArrayList<>();
        candidates.add(meta.getSongFileName());
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) continue;
            String path = normalizedTitleToPath.get(candidate.toLowerCase(Locale.ROOT));
            if (path != null) return path;
            path = normalizedTitleToPath.get(SongDataHelper.sanitizeForFileKey(candidate).toLowerCase(Locale.ROOT));
            if (path != null) return path;
            path = normalizedTitleToPath.get(SongDataHelper.fallbackKey(candidate).toLowerCase(Locale.ROOT));
            if (path != null) return path;
        }
        return null;
    }

    private static void addSuccessfulTitleKeys(Set<String> keys, String value) {
        if (keys == null || value == null || value.isBlank()) return;
        keys.add(value.trim().toLowerCase(Locale.ROOT));
        keys.add(SongDataHelper.sanitizeForFileKey(value).trim().toLowerCase(Locale.ROOT));
    }

    private static boolean containsSuccessfulTitle(Set<String> successfulTitleKeys, String localTitle) {
        if (successfulTitleKeys == null || localTitle == null || localTitle.isBlank()) return false;
        String normalized = localTitle.trim().toLowerCase(Locale.ROOT);
        return successfulTitleKeys.contains(normalized)
                || successfulTitleKeys.contains(
                SongDataHelper.sanitizeForFileKey(localTitle).trim().toLowerCase(Locale.ROOT)
        );
    }

    private static void markSongsLocal(Connection conn, Map<Long, String> localTrackPaths) throws SQLException {
        if (conn == null || localTrackPaths == null || localTrackPaths.isEmpty()) return;
        ExternalIdentityDao identities = new ExternalIdentityDao(conn);
        Map<Long, String> pathsByInternalId = new LinkedHashMap<>();
        for (Map.Entry<Long, String> entry : localTrackPaths.entrySet()) {
            if (entry.getKey() == null || entry.getKey() <= 0 || entry.getValue() == null) continue;
            var internalId = identities.resolve(
                    ExternalIdentityDao.EntityType.SONG,
                    ExternalMediaId.deezer(entry.getKey())
            );
            if (internalId.isPresent()) pathsByInternalId.put(internalId.getAsLong(), entry.getValue());
        }
        updateLocalSongPaths(conn, pathsByInternalId);
    }

    private static void updateLocalSongPaths(Connection conn, Map<Long, String> pathsByInternalId) throws SQLException {
        String sql = "UPDATE Song SET IsLocal = 1, FilePath = ? WHERE SongID = ?";
        List<Long> savedSongIds = new ArrayList<>();
        try (var statement = conn.prepareStatement(sql)) {
            int pending = 0;
            for (Map.Entry<Long, String> entry : pathsByInternalId.entrySet()) {
                Long songId = entry.getKey();
                if (songId == null || songId <= 0 || !isReadableLocalFile(entry.getValue())) continue;
                statement.setString(1, entry.getValue());
                statement.setLong(2, songId);
                statement.addBatch();
                savedSongIds.add(songId);
                pending++;
                if (pending % 250 == 0) statement.executeBatch();
            }
            if (pending % 250 != 0) statement.executeBatch();
        }
        new SavedMediaService().saveLocalSongsAndReleases(conn, savedSongIds);
    }

    private static boolean isReadableLocalFile(String path) {
        if (path == null || path.isBlank()) return false;
        File file = new File(path);
        return file.isFile() && file.canRead() && file.length() > 0L;
    }

    private static Map<Long, String> resolveLocalPathsByInternalId(Connection conn,
                                                                    Map<Long, String> pathsByExternalId) throws SQLException {
        Map<Long, String> resolved = new LinkedHashMap<>();
        ExternalIdentityDao identities = new ExternalIdentityDao(conn);
        for (Map.Entry<Long, String> entry : pathsByExternalId.entrySet()) {
            if (entry.getKey() == null || entry.getKey() <= 0) continue;
            var internalId = identities.resolve(
                    ExternalIdentityDao.EntityType.SONG,
                    ExternalMediaId.deezer(entry.getKey())
            );
            if (internalId.isPresent()) resolved.put(internalId.getAsLong(), entry.getValue());
        }
        return resolved;
    }

    private static Map<Long, Long> resolveExternalTrackIdsByInternalId(Connection conn,
                                                                        Collection<Long> externalIds) throws SQLException {
        Map<Long, Long> resolved = new LinkedHashMap<>();
        ExternalIdentityDao identities = new ExternalIdentityDao(conn);
        for (Long externalId : externalIds) {
            if (externalId == null || externalId <= 0) continue;
            var internalId = identities.resolve(
                    ExternalIdentityDao.EntityType.SONG,
                    ExternalMediaId.deezer(externalId)
            );
            if (internalId.isPresent()) resolved.put(internalId.getAsLong(), externalId);
        }
        return resolved;
    }

}
