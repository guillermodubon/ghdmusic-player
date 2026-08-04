package io.github.guillermodubon.musicplayer.services.startup.library;

import javafx.util.Pair;
import io.github.guillermodubon.musicplayer.repository.dao.album.AlbumDao;
import io.github.guillermodubon.musicplayer.repository.dao.album.AlbumDaoImpl;
import io.github.guillermodubon.musicplayer.repository.dao.artist.ArtistDao;
import io.github.guillermodubon.musicplayer.repository.dao.genre.GenreDao;
import io.github.guillermodubon.musicplayer.repository.dao.song.SongDao;
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
        // 1) Associate paths to deezer names (in-memory normalization)
        for (DeezerApiMetaData m : metas) {
            String oldName = m.getSongFileName();
            String deezerName = m.getSongName();
            String foundPath = null;
            List<String> candidates = Arrays.asList(
                    oldName,
                    SongDataHelper.sanitizeForFileKey(oldName),
                    SongDataHelper.sanitizeForFileKey(deezerName),
                    SongDataHelper.fallbackKey(deezerName),
                    deezerName,
                    deezerName != null ? deezerName.toLowerCase() : null
            );
            for (String key : candidates) {
                if (key == null) continue;
                String p = titleToPath.get(key);
                if (p != null) {
                    foundPath = p;
                    break;
                }
            }
            if (foundPath != null && deezerName != null) {
                titleToPath.put(deezerName, foundPath);
                titleToPath.put(SongDataHelper.sanitizeForFileKey(deezerName), foundPath);
                if (m.getAlbumArtistNames() != null) {
                    for (String an : m.getAlbumArtistNames()) {
                        if (an == null || an.isBlank()) continue;
                        String combo = an + " " + deezerName;
                        titleToPath.put(combo, foundPath);
                        titleToPath.put(SongDataHelper.sanitizeForFileKey(combo), foundPath);
                        titleToPath.put(combo.toLowerCase(), foundPath);
                    }
                }
            }
        }
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
        Set<Long> localTrackIds = metas.stream()
                .map(DeezerApiMetaData::getTrackId)
                .filter(id -> id > 0)
                .collect(Collectors.toSet());
        Map<String, String> normalizedTitleToPath = new HashMap<>();
        for (var e : titleToPath.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            normalizedTitleToPath.put(e.getKey().toLowerCase(), e.getValue());
        }
        for (DeezerApiMetaData meta : metas) {
            if (meta == null || meta.getTrackId() <= 0) continue;
            String path = findLocalPathForMeta(meta, normalizedTitleToPath);
            if (path != null) markSongLocal(conn, meta.getTrackId(), path);
        }
        // Fetch only albums introduced by this import. Existing remote album rows
        // are unrelated to the local scan and used to trigger unnecessary requests.
        Set<Long> importedAlbumIds = new HashSet<>();
        for (DeezerApiMetaData meta : metas) {
            if (meta == null || meta.getAlbumName() == null || meta.getAlbumName().isBlank()) continue;
            Long persistedAlbumId = albumDao.findIdByName(meta.getAlbumName());
            if (persistedAlbumId != null && persistedAlbumId > 0) {
                importedAlbumIds.add(persistedAlbumId);
            }
        }
        List<Album> importedAlbums = albumDao.findAll().stream()
                .filter(album -> album != null && importedAlbumIds.contains(album.getAlbumID()))
                .toList();
        List<Long> albumIds = importedAlbums.stream().map(Album::getAlbumID).toList();
        // Parallel fetch album tracklists (bounded)
        int parallelism = Math.min(12, Math.max(4, Runtime.getRuntime().availableProcessors()));
        ExecutorService fetchExec = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        List<Future<AbstractMap.SimpleEntry<Long, List<DeezerTrackInfo>>>> futures = new ArrayList<>();
        for (Long aid : albumIds) {
            futures.add(fetchExec.submit(() -> {
                try {
                    List<DeezerTrackInfo> tracks = DeezerApiService.fetchAlbumTracks(aid);
                    return new AbstractMap.SimpleEntry<>(aid, tracks);
                } catch (Exception ex) {
                    return new AbstractMap.SimpleEntry<>(aid, List.<DeezerTrackInfo>of());
                }
            }));
        }
        fetchExec.shutdown();
        // Gather results
        Map<Long, List<DeezerTrackInfo>> albumTracksMap = new HashMap<>();
        for (Future<AbstractMap.SimpleEntry<Long, List<DeezerTrackInfo>>> f : futures) {
            try {
                AbstractMap.SimpleEntry<Long, List<DeezerTrackInfo>> p = f.get();
                if (p != null && p.getKey() != null && p.getValue() != null) albumTracksMap.put(p.getKey(), p.getValue());
            } catch (Exception ignored) { }
        }
        // Resolve existing positions once per album and persist all missing tracks
        // in batches. This avoids one SELECT and two writes for every track.
        List<Song> pendingAlbumTracks = new ArrayList<>();
        for (Album alb : importedAlbums) {
            List<DeezerTrackInfo> tracks = albumTracksMap.getOrDefault(alb.getAlbumID(), List.of());
            if (tracks.isEmpty()) continue;
            Set<Integer> existingTrackOrders = songDao.findByAlbum(alb.getAlbumID()).stream()
                    .map(Song::getTrackOrder)
                    .collect(Collectors.toSet());
            for (DeezerTrackInfo info : tracks) {
                int pos = info.getTrackOrder();
                if (existingTrackOrders.contains(pos)) continue;

                String title = info.getTitle();
                long deezerTrackId = info.getId();
                boolean isLocal = deezerTrackId > 0 && localTrackIds.contains(deezerTrackId);
                String path = null;
                if (!isLocal && title != null) {
                    path = findPathForTitle(title, normalizedTitleToPath);
                    isLocal = path != null;
                }
                if (isLocal && path == null && title != null) {
                    path = titleToPath.get(title);
                }
                long songId = deezerTrackId > 0 ? deezerTrackId : 0L;
                pendingAlbumTracks.add(new Song(songId, title, alb.getArtist(), alb, path, pos, isLocal));
            }
        }
        songDao.insertOrUpdateAllWithIds(pendingAlbumTracks);
        artistBiographyService.hydrateMissingBiographies(conn, artistDao, null);
        // Deduplicate visual songs
        songDao.deleteVisualDuplicates();
        // Build the manifest after inserts (from DB local songs + no-metadata)
        Map<String, ManifestEntry> newManifest = new HashMap<>();
        for (Song localSong : songDao.findAll().stream().filter(Song::isLocal).toList()) {
            String title = localSong.getTitle();
            if (title == null) continue;
            String path = titleToPath.get(title);
            if (path == null) {
                String low = title.toLowerCase();
                path = normalizedTitleToPath.get(low);
            }
            if (path == null) {
                String san = SongDataHelper.sanitizeForFileKey(title);
                path = titleToPath.get(san);
            }
            if (path == null) {
                String fb = SongDataHelper.fallbackKey(title);
                path = titleToPath.get(fb);
            }
            if (path == null) continue;
            File f = new File(path);
            String fileName = f.getName();
            String cleanedFileName = SongDataHelper.removeFileExtension(fileName).replaceAll("[\\\\/:*?\"<>|]", "").replaceAll("\\s+", " ").trim();
            if (cleanedFileName.length() > 200) cleanedFileName = cleanedFileName.substring(0, 200).trim();
            long ts = f.exists() ? f.lastModified() : System.currentTimeMillis();
            long id = localSong.getSongID() > 0 ? localSong.getSongID() : 0;
            newManifest.put(cleanedFileName, new ManifestEntry(id, ts));
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
                newManifest.put(cleanedFileName, new ManifestEntry(0, ts));
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
        candidates.add(meta.getSongName());
        if (meta.getAlbumArtistNames() != null && meta.getSongName() != null) {
            for (String artist : meta.getAlbumArtistNames()) {
                if (artist != null && !artist.isBlank()) candidates.add(artist + " " + meta.getSongName());
            }
        }
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

    private static String findPathForTitle(String title, Map<String, String> normalizedTitleToPath) {
        if (title == null || normalizedTitleToPath == null) return null;
        String path = normalizedTitleToPath.get(title.toLowerCase(Locale.ROOT));
        if (path != null) return path;
        path = normalizedTitleToPath.get(SongDataHelper.sanitizeForFileKey(title).toLowerCase(Locale.ROOT));
        return path != null
                ? path
                : normalizedTitleToPath.get(SongDataHelper.fallbackKey(title).toLowerCase(Locale.ROOT));
    }

    private static void markSongLocal(Connection conn, long songId, String path) throws SQLException {
        if (conn == null || songId <= 0) return;
        try (var ps = conn.prepareStatement("UPDATE Song SET IsLocal = 1, FilePath = ? WHERE SongID = ?")) {
            ps.setString(1, path);
            ps.setLong(2, songId);
            ps.executeUpdate();
        } catch (SQLException missingFilePath) {
            try (var ps = conn.prepareStatement("UPDATE Song SET IsLocal = 1 WHERE SongID = ?")) {
                ps.setLong(1, songId);
                ps.executeUpdate();
            }
        }
    }

}


