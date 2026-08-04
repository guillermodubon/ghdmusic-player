package io.github.guillermodubon.musicplayer.services.startup.library;

import static io.github.guillermodubon.musicplayer.services.startup.library.LibrarySyncSupport.*;

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
import io.github.guillermodubon.musicplayer.services.startup.hydration.ModelHydrationService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class IncrementalLibrarySyncService {

    private final DeezerApiService deezerService;
    private final ManifestSyncService manifestService;
    private final ModelHydrationService modelHydrationService;
    private final ArtistBiographyService artistBiographyService;

    public IncrementalLibrarySyncService(
            DeezerApiService deezerService,
            ManifestSyncService manifestService,
            ModelHydrationService modelHydrationService,
            ArtistBiographyService artistBiographyService
    ) {
        this.deezerService = Objects.requireNonNull(deezerService, "deezerService");
        this.manifestService = Objects.requireNonNull(manifestService, "manifestService");
        this.modelHydrationService = Objects.requireNonNull(modelHydrationService, "modelHydrationService");
        this.artistBiographyService = Objects.requireNonNull(artistBiographyService, "artistBiographyService");
    }

    private void loadModels(Connection conn, Map<String, String> titleToPath) throws SQLException, IOException {
        modelHydrationService.loadModels(conn, titleToPath);
    }
    public void syncExistingData(
            Connection conn,
            GenreDao genreDao,
            ArtistDao artistDao,
            AlbumDao albumDao,
            SongDao songDao,
            Map<String, String> titleToPath,
            List<DeezerApiMetaData> metas,
            List<Pair<String, String>> noMetadataSongs
    ) throws SQLException, IOException {
        System.out.println("syncExistingData: starting (thread=" + Thread.currentThread().getName() + ")");

        // LOAD manifest
        Map<String, ManifestEntry> oldMan = manifestService.load();

        // Normalized scan maps: cleanedFileName -> lastModified / path / originalKey (titleToPath key)
        Map<String, Long> scanTsMap = new HashMap<>();
        Map<String, String> fileNameToPath = new HashMap<>();
        Map<String, String> cleanedToOriginalKey = new HashMap<>();
        for (Map.Entry<String, String> e : Optional.ofNullable(titleToPath).orElse(Map.of()).entrySet()) {
            try {
                Path p = Paths.get(e.getValue());
                String fileName = p.getFileName().toString();
                String cleaned = comparisonKey(fileName);
                long ts = p.toFile().lastModified();
                scanTsMap.put(cleaned, ts);
                fileNameToPath.put(cleaned, e.getValue());
                cleanedToOriginalKey.put(cleaned, e.getKey());
            } catch (Exception ignore) { }
        }

        // Build oldEntriesMap: cleanedFileName -> ManifestEntry and oldKeyMap: cleaned -> manifestKey
        Map<String, ManifestEntry> oldEntriesMap = new HashMap<>();
        Map<String, String> oldKeyMap = new HashMap<>();
        if (oldMan != null) {
            for (Map.Entry<String, ManifestEntry> oldE : oldMan.entrySet()) {
                String oldKey = oldE.getKey();
                String cleaned = comparisonKey(manifestDisplayName(oldKey));
                cleaned = resolveManifestKeyAgainstScan(conn, cleaned, oldE.getValue(), scanTsMap);
                oldEntriesMap.put(cleaned, oldE.getValue());
                oldKeyMap.put(cleaned, oldKey);
            }
        }

        Set<String> oldFileNames = new HashSet<>(oldEntriesMap.keySet());
        Set<String> scanFileNames = new HashSet<>(scanTsMap.keySet());

        // detect added / deleted / modified
        Set<String> addedFiles = new HashSet<>(scanFileNames); addedFiles.removeAll(oldFileNames);
        Set<String> deletedFiles = new HashSet<>(oldFileNames); deletedFiles.removeAll(scanFileNames);

        Map<String, Long> modifiedFiles = new HashMap<>();
        for (String fn : scanFileNames) {
            if (oldFileNames.contains(fn)) {
                long oldTs = oldEntriesMap.get(fn).getLastModified();
                long newTs = scanTsMap.getOrDefault(fn, 0L);
                if (newTs != oldTs) modifiedFiles.put(fn, newTs);
            }
        }

        // EARLY EXIT: nothing changed
        if (addedFiles.isEmpty() && deletedFiles.isEmpty() && modifiedFiles.isEmpty()) {
            System.out.println("syncExistingData: no changes detected -> loading models from DB");

            // Ensure noMetadataSongs is consistent with manifest even when nothing changed:
            synchronized (noMetadataSongs) {
                noMetadataSongs.clear();
                if (oldMan != null && !oldMan.isEmpty()) {
                    for (Map.Entry<String, ManifestEntry> mse : oldMan.entrySet()) {
                        ManifestEntry ent = mse.getValue();
                        if (ent != null && ent.getDeezerId() == 0) {
                            String cleaned = comparisonKey(manifestDisplayName(mse.getKey()));

                            // Prefer path found during current scan, fallback to titleToPath via original key
                            String path = fileNameToPath.get(cleaned);
                            if (path == null) {
                                String originalKey = cleanedToOriginalKey.get(cleaned);
                                if (originalKey != null) path = Optional.ofNullable(titleToPath).orElse(Map.of()).get(originalKey);
                            }
                            noMetadataSongs.add(new javafx.util.Pair<>(cleaned, path));
                        }
                    }
                }
            }

            try {
                loadModels(conn, titleToPath);
            } catch (SQLException | IOException e) {
                System.err.println("syncExistingData: warning loading models with no file changes -> " + Optional.ofNullable(e.getMessage()).orElse("null"));
            }
            return;
        }

        if (metas == null) metas = new ArrayList<>();

        // -------------------------
        // HANDLE DELETES FIRST
        // -------------------------
        if (!deletedFiles.isEmpty()) {
            // 1) Remove keys from manifest map
            for (String deletedFn : deletedFiles) {
                String oldKey = oldKeyMap.get(deletedFn);
                if (oldKey != null && oldMan != null) oldMan.remove(oldKey);
            }

            // 2) Collect explicit deezer IDs to delete (those that were present in manifest)
            List<Long> explicitDeletes = new ArrayList<>();
            // also collect cleaned keys of no-metadata deletes to remove from noMetadataSongs
            List<String> deletedNoMetaCleaned = new ArrayList<>();
            for (String deletedFn : deletedFiles) {
                ManifestEntry entry = oldEntriesMap.get(deletedFn);
                if (entry != null) {
                    if (entry.getDeezerId() > 0) {
                        explicitDeletes.add(entry.getDeezerId());
                    } else {
                        deletedNoMetaCleaned.add(deletedFn);
                    }
                }
            }

            // 3) Delete explicit ids first (safe, targeted)
            if (!explicitDeletes.isEmpty()) {
                for (Long did : explicitDeletes) {
                    try {
                        markSongRemote(conn, did);
                    } catch (SQLException ex) {
                        System.err.println("syncExistingData: warning marking deleted local song as remote id=" + did + " -> " + ex.getMessage());
                    }
                }
            }

            // 3b) Remove any corresponding noMetadataSongs entries for deleted no-meta files
            if (!deletedNoMetaCleaned.isEmpty()) {
                synchronized (noMetadataSongs) {
                    for (String cleaned : deletedNoMetaCleaned) {
                        String originalKey = cleanedToOriginalKey.get(cleaned);
                        String pathToMatch = fileNameToPath.get(cleaned);
                        final String kToMatch = originalKey != null ? originalKey : cleaned;
                        noMetadataSongs.removeIf(pair -> {
                            if (pair == null) return false;
                            try {
                                String pk = pair.getKey();
                                String pv = pair.getValue();
                                if (pk != null && pk.equalsIgnoreCase(kToMatch)) return true;
                                if (pathToMatch != null && pathToMatch.equals(pv)) return true;
                            } catch (Exception ignore) { }
                            return false;
                        });
                    }
                }
            }

            // 4) After explicit manifest deletions are done, clean albums/artists/genres.
            // Never run a broad "DB title not in scan" cleanup here: scanned filenames often include
            // artist prefixes while DB titles do not, so that path can mark the whole library as remote.
            for (Album alb : albumDao.findAll()) {
                List<Song> songs = songDao.findByAlbum(alb.getAlbumID());
                long localCount = songs.stream().filter(Song::isLocal).count();
                if (localCount == 0) {
                    try { songDao.deleteByAlbum(alb.getAlbumID()); } catch (SQLException ex) { System.err.println("syncExistingData: warning deleting songs for album " + alb.getAlbumID() + " -> " + ex.getMessage()); }
                    albumDao.deleteAlbumArtists(alb.getAlbumID());
                    albumDao.deleteAlbumImages(alb.getAlbumID());
                    albumDao.delete(alb.getAlbumID());
                }
            }
            genreDao.deleteWithoutAlbums();

            try (Statement st = conn.createStatement()) {
                st.executeUpdate("""
                        DELETE FROM SongArtist
                         WHERE SongID IN (SELECT SongID FROM Song WHERE IsLocal = 0)
                           AND ArtistID NOT IN (SELECT ArtistID FROM AlbumArtist)
                           AND ArtistID NOT IN (
                               SELECT sa.ArtistID
                                 FROM SongArtist sa
                                 JOIN Song s ON s.SongID = sa.SongID
                                WHERE s.IsLocal = 1
                           )
                        """);
            }

            String findArtistsSql = "SELECT ArtistID FROM Artist WHERE ArtistID NOT IN (SELECT ArtistID FROM AlbumArtist UNION SELECT ArtistID FROM SongArtist)";
            List<Long> artistsToDelete = new ArrayList<>();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(findArtistsSql)) {
                while (rs.next()) artistsToDelete.add(rs.getLong("ArtistID"));
            }
            if (!artistsToDelete.isEmpty()) {
                try (PreparedStatement psImages = conn.prepareStatement("DELETE FROM ArtistImage WHERE ArtistID = ?")) {
                    for (long aid : artistsToDelete) { psImages.setLong(1, aid); psImages.executeUpdate(); }
                }
                try (PreparedStatement psA = conn.prepareStatement("DELETE FROM Artist WHERE ArtistID = ?")) {
                    for (long aid : artistsToDelete) { psA.setLong(1, aid); psA.executeUpdate(); }
                }
            }

            // 7) Update manifest timestamps for modified if any
            for (Map.Entry<String, Long> mod : modifiedFiles.entrySet()) {
                String fn = mod.getKey();
                long newTs = mod.getValue();
                String oldKey = oldKeyMap.get(fn);
                if (oldKey != null && oldMan != null) {
                    ManifestEntry oldEntry = oldMan.get(oldKey);
                    if (oldEntry != null) oldMan.put(oldKey, new ManifestEntry(oldEntry.getDeezerId(), newTs));
                }
            }

            // Optional: Rebuild noMetadataSongs from manifest to guarantee absolute consistency after deletes
            synchronized (noMetadataSongs) {
                noMetadataSongs.clear();
                if (oldMan != null) {
                    for (Map.Entry<String, ManifestEntry> mse : oldMan.entrySet()) {
                        ManifestEntry ent = mse.getValue();
                        if (ent != null && ent.getDeezerId() == 0) {
                            String cleaned = comparisonKey(manifestDisplayName(mse.getKey()));
                            String path = fileNameToPath.get(cleaned);
                            if (path == null) {
                                String originalKey = cleanedToOriginalKey.get(cleaned);
                                if (originalKey != null) path = Optional.ofNullable(titleToPath).orElse(Map.of()).get(originalKey);
                            }
                            noMetadataSongs.add(new javafx.util.Pair<>(cleaned, path));
                        }
                    }
                }
            }

            if (addedFiles.isEmpty()) {
                // 8) Persist manifest and reload models
                manifestService.save(oldMan == null ? Map.of() : oldMan);

                try {
                    loadModels(conn, titleToPath);
                } catch (SQLException | IOException e) {
                    System.err.println("syncExistingData: warning reloading models after delete -> " + Optional.ofNullable(e.getMessage()).orElse("null"));
                }

                System.out.println("syncExistingData: deletions processed -> finished (no Deezer calls)");
                return;
            }

            System.out.println("syncExistingData: deletions processed -> continuing with added files");
        }

        // -------------------------
        // DETECT NEW FILES: use manifest comparison (addedFiles)
        // -------------------------
        Set<String> newFiles = new HashSet<>(addedFiles);

        // Si no hay nuevos archivos -> sÃ³lo actualizar timestamps modificados y terminar (sin llamar a Deezer)
        if (newFiles.isEmpty()) {
            for (Map.Entry<String, Long> mod : modifiedFiles.entrySet()) {
                String fn = mod.getKey();
                long newTs = mod.getValue();
                String oldKey = oldKeyMap.get(fn);
                if (oldKey != null && oldMan != null) {
                    ManifestEntry oldEntry = oldMan.get(oldKey);
                    if (oldEntry != null) oldMan.put(oldKey, new ManifestEntry(oldEntry.getDeezerId(), newTs));
                }
            }
            // Optional rebuild of noMetadataSongs to keep in-memory consistent with manifest
            synchronized (noMetadataSongs) {
                noMetadataSongs.clear();
                if (oldMan != null) {
                    for (Map.Entry<String, ManifestEntry> mse : oldMan.entrySet()) {
                        ManifestEntry ent = mse.getValue();
                        if (ent != null && ent.getDeezerId() == 0) {
                            String cleaned = comparisonKey(manifestDisplayName(mse.getKey()));
                            String path = fileNameToPath.get(cleaned);
                            if (path == null) {
                                String originalKey = cleanedToOriginalKey.get(cleaned);
                                if (originalKey != null) path = Optional.ofNullable(titleToPath).orElse(Map.of()).get(originalKey);
                            }
                            noMetadataSongs.add(new javafx.util.Pair<>(cleaned, path));
                        }
                    }
                }
            }
            manifestService.save(oldMan == null ? Map.of() : oldMan);
            try {
                loadModels(conn, titleToPath);
            } catch (SQLException | IOException e) { System.err.println("syncExistingData: warning reloading models -> " + e.getMessage()); }
            System.out.println("syncExistingData: no new files -> updated timestamps and finished");
            return;
        }

        // Build needFetchList using original keys from titleToPath when possible
        List<String> needFetchList = new ArrayList<>();
        for (String nf : newFiles) {
            String originalKey = cleanedToOriginalKey.getOrDefault(nf, nf);
            boolean foundInProvided = metas.stream().anyMatch(m ->
                    m != null && (Objects.equals(m.getSongFileName(), nf)
                            || (m.getSongName() != null && m.getSongName().equalsIgnoreCase(nf))
                            || Objects.equals(m.getSongFileName(), originalKey)
                            || (m.getSongName() != null && m.getSongName().equalsIgnoreCase(originalKey)))
            );
            if (!foundInProvided) {
                needFetchList.add(originalKey);
            }
        }

        // Fetch metadata for only the missing new files (network)
        List<DeezerApiMetaData> fetched = List.of();
        if (!needFetchList.isEmpty()) {
            try {
                System.out.println("syncExistingData: fetching metas for new files count=" + needFetchList.size() + " (only new)");
                System.out.println("DEBUG newFiles (cleaned) = " + newFiles);
                System.out.println("DEBUG needFetchList (original keys) = " + needFetchList);
                fetched = deezerService.getApiObjectsList(needFetchList);
            } catch (Exception ex) {
                System.err.println("syncExistingData: warning fetching metas for new files -> " + ex.getMessage());
                fetched = List.of();
            }
        }

        // Merge provided metas + fetched into newMetas (only for newFiles)
        List<DeezerApiMetaData> newMetas = new ArrayList<>();
        for (String nf : newFiles) {
            String originalKey = cleanedToOriginalKey.getOrDefault(nf, nf);
            DeezerApiMetaData found = metas.stream()
                    .filter(m -> matchesMeta(m, nf, originalKey))
                    .findFirst().orElse(null);
            if (found == null) {
                found = fetched.stream()
                        .filter(m -> matchesMeta(m, nf, originalKey))
                        .findFirst().orElse(null);
            }
            if (found != null) newMetas.add(found);
            else {
                DeezerApiMetaData placeholder = new DeezerApiMetaData();
                placeholder.setSongFileName(nf);
                placeholder.setSongName(nf);
                placeholder.setTrackId(0L);
                newMetas.add(placeholder);
            }
        }

        Set<String> newArtistBiographyCandidates = artistBiographyService.findArtistNamesNotInDb(
                artistDao,
                artistBiographyService.collectArtistNames(newMetas)
        );

        // Persist new metadata (genres/artists/albums/songs) inside current transaction (conn)
        if (!newMetas.isEmpty()) {
            genreDao.upsertAll(newMetas);
            artistDao.insertArtistsAndImages(newMetas);
            if (albumDao instanceof AlbumDaoImpl) {
                ((AlbumDaoImpl) albumDao).upsertAll(conn, newMetas, genreDao, artistDao);
            } else {
                albumDao.upsertAll(newMetas, genreDao, artistDao);
            }
            songDao.insertSongsAndArtists(newMetas, albumDao, artistDao);

            for (DeezerApiMetaData meta : newMetas) {
                if (meta == null || meta.getTrackId() <= 0) continue;
                String path = resolvePathForMeta(meta, newFiles, cleanedToOriginalKey, fileNameToPath);
                if (path != null) {
                    markSongLocal(conn, meta.getTrackId(), path);
                }
            }

            artistBiographyService.hydrateMissingBiographies(
                    conn,
                    artistDao,
                    newArtistBiographyCandidates
            );
        }

        // For new metas referencing albums, fetch tracklists only for those albums and insert missing tracks
        Set<Long> albumIdsToFetch = newMetas.stream()
                .filter(Objects::nonNull)
                .map(DeezerApiMetaData::getAlbumId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());

        if (!albumIdsToFetch.isEmpty()) {
            Map<String, String> normalizedTitleToPath = new HashMap<>();
            for (Map.Entry<String, String> e : Optional.ofNullable(titleToPath).orElse(Map.of()).entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                normalizedTitleToPath.put(e.getKey().toLowerCase(), e.getValue());
            }

            int parallelism = Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors()));
            ExecutorService fetchExec = Executors.newFixedThreadPool(parallelism, r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });
            List<Future<AbstractMap.SimpleEntry<Long, List<DeezerTrackInfo>>>> futures = new ArrayList<>();
            for (Long aid : albumIdsToFetch) {
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

            Map<Long, List<DeezerTrackInfo>> albumTracksMap = new HashMap<>();
            for (Future<AbstractMap.SimpleEntry<Long, List<DeezerTrackInfo>>> f : futures) {
                try {
                    AbstractMap.SimpleEntry<Long, List<DeezerTrackInfo>> p = f.get();
                    if (p != null && p.getKey() != null && p.getValue() != null) albumTracksMap.put(p.getKey(), p.getValue());
                } catch (Exception ignored) {}
            }

            Set<Long> localTrackIds = songDao.findAllLocalIds();
            List<Song> pendingAlbumTracks = new ArrayList<>();

            for (Long albId : albumIdsToFetch) {
                Album alb = albumDao.findById(albId).orElse(null);
                if (alb == null) continue;
                List<DeezerTrackInfo> tracks = albumTracksMap.getOrDefault(albId, List.of());
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
        }

        // -------------------------
        // UPDATE manifest (add new locals + update modified timestamps)
        // -------------------------
        if (oldMan == null) oldMan = new HashMap<>();

        List<DeezerApiMetaData> manifestMetadata = new ArrayList<>(
                Optional.ofNullable(metas).map(List::size).orElse(0) + newMetas.size()
        );
        if (metas != null) manifestMetadata.addAll(metas);
        manifestMetadata.addAll(newMetas);

        for (String nf : addedFiles) {
                long deezerId = 0L;
                String originalKey = cleanedToOriginalKey.getOrDefault(nf, nf);
                DeezerApiMetaData match = manifestMetadata.stream()
                        .filter(Objects::nonNull)
                        .filter(m -> matchesMeta(m, nf, originalKey))
                        .findFirst().orElse(null);
                if (match != null && match.getTrackId() > 0) deezerId = match.getTrackId();

                String path = fileNameToPath.get(nf);
                long ts = path != null ? new File(path).lastModified() : scanTsMap.getOrDefault(nf, System.currentTimeMillis());
                String manifestKey = path != null ? manifestFileKey(path) : manifestFileKey(nf);
                removeDuplicateManifestEntries(oldMan, manifestKey, deezerId);
                oldMan.put(manifestKey, new ManifestEntry(deezerId, ts));

                // If no metadata (deezerId == 0), register in noMetadataSongs (avoid duplicates)
                if (deezerId == 0) {
                    try {
                        String p = fileNameToPath.get(nf);
                        final String k = originalKey;
                        final String pathVal = p;
                        synchronized (noMetadataSongs) {
                            boolean exists = noMetadataSongs.stream().anyMatch(pair -> {
                                if (pair == null) return false;
                                try {
                                    if (pair.getKey() != null && pair.getKey().equalsIgnoreCase(k)) return true;
                                    if (pathVal != null && pathVal.equals(pair.getValue())) return true;
                                } catch (Exception ignore) { }
                                return false;
                            });
                            if (!exists) {
                                noMetadataSongs.add(new javafx.util.Pair<>(k, pathVal));
                            }
                        }
                    } catch (Exception ignore) { /* do not break flow on auxiliary maintenance */ }
                }
        }

        for (Map.Entry<String, Long> mod : modifiedFiles.entrySet()) {
            String fn = mod.getKey();
            long newTs = mod.getValue();
            String oldKey = oldKeyMap.get(fn);
            if (oldKey != null) {
                ManifestEntry oldEntry = oldMan.get(oldKey);
                if (oldEntry != null) oldMan.put(oldKey, new ManifestEntry(oldEntry.getDeezerId(), newTs));
            }
        }

        // Optional: Rebuild noMetadataSongs from manifest for absolute consistency
        synchronized (noMetadataSongs) {
            noMetadataSongs.clear();
            if (oldMan != null) {
                for (Map.Entry<String, ManifestEntry> mse : oldMan.entrySet()) {
                    ManifestEntry ent = mse.getValue();
                    if (ent != null && ent.getDeezerId() == 0) {
                        String cleaned = comparisonKey(manifestDisplayName(mse.getKey()));
                        String path = fileNameToPath.get(cleaned);
                        if (path == null) {
                            String originalKey = cleanedToOriginalKey.get(cleaned);
                            if (originalKey != null) path = Optional.ofNullable(titleToPath).orElse(Map.of()).get(originalKey);
                        }
                        noMetadataSongs.add(new javafx.util.Pair<>(cleaned, path));
                    }
                }
            }
        }

        manifestService.save(oldMan);

        // Final: reload models so in-memory matches DB (still inside transaction)
        try {
            loadModels(conn, titleToPath);
        } catch (SQLException | IOException e) {
            System.err.println("syncExistingData: warning reloading models after add -> " + Optional.ofNullable(e.getMessage()).orElse("null"));
        }

        System.out.println("syncExistingData: finished");
    }

}


