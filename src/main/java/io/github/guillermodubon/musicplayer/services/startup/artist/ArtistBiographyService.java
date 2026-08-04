package io.github.guillermodubon.musicplayer.services.startup.artist;

import io.github.guillermodubon.musicplayer.repository.DbConnectionManager;
import io.github.guillermodubon.musicplayer.repository.dao.artist.ArtistDao;
import io.github.guillermodubon.musicplayer.repository.dao.artist.ArtistDaoImpl;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.DeezerApiMetaData;
import io.github.guillermodubon.musicplayer.models.WikipediaApiMetadata;
import io.github.guillermodubon.musicplayer.services.api.WikipediaApiService;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.utils.ArtistIdentity;

import java.sql.*;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class ArtistBiographyService {

    private final WikipediaApiService wikipediaService;

    private static final ExecutorService BIOGRAPHY_EXECUTOR = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "artist-biography-service");
        thread.setDaemon(true);
        return thread;
    });

    public ArtistBiographyService(WikipediaApiService wikipediaService) {
        this.wikipediaService = Objects.requireNonNull(wikipediaService, "wikipediaService");
    }

    public String fetchBiography(String artistName) {
        if (!hasText(artistName)) return null;
        return wikipediaService.getBiographyForArtist(artistName.strip());
    }

    public CompletableFuture<String> resolveBiographyAsync(Artist artist, StartUpService owner) {
        if (artist == null || !hasText(artist.getName())) return CompletableFuture.completedFuture(null);
        if (ArtistIdentity.isVariousArtists(artist)) return CompletableFuture.completedFuture(null);
        if (hasText(artist.getBiography())) return CompletableFuture.completedFuture(artist.getBiography());

        long artistId = artist.getArtistID();
        String artistName = artist.getName();

        return CompletableFuture.supplyAsync(() -> {
            boolean knownLocalArtist = artistExistsInMemory(owner == null ? List.of() : owner.getArtists(), artistId, artistName)
                    || artistExistsInDb(artistId, artistName);
            String biography = findBiographyInMemory(owner == null ? List.of() : owner.getArtists(), artistId, artistName);
            if (!hasText(biography)) {
                biography = findBiographyInDb(artistId, artistName);
            }
            if (!hasText(biography)) {
                biography = fetchBiography(artistName);
                if (knownLocalArtist && hasText(biography)) {
                    // Do not make the screen wait for SQLite after Wikipedia
                    // has already returned a usable description. Persistence
                    // remains guaranteed, but runs independently of the UI
                    // completion path.
                    persistBiographyAsync(artistId, artistName, biography);
                }
            }

            if (hasText(biography)) {
                artist.setBiography(biography);
                if (owner != null && knownLocalArtist) {
                    updateMemoryArtistBiography(owner.getArtists(), artistId, artistName, biography);
                }
            }
            return biography;
        }, BIOGRAPHY_EXECUTOR);
    }

    private void persistBiographyAsync(long artistId, String artistName, String biography) {
        CompletableFuture.runAsync(
                () -> persistBiography(artistId, artistName, biography),
                BIOGRAPHY_EXECUTOR
        );
    }

    public void hydrateMissingBiographies(
            Connection conn,
            ArtistDao artistDao,
            Collection<String> candidateNames
    ) {
        if (artistDao == null) return;
        boolean hydrateAllArtists = candidateNames == null;

        List<Artist> allArtists;
        try {
            allArtists = artistDao.findAll();
        } catch (Exception ex) {
            System.out.println("hydrateMissingBiographies: cannot read artists -> " + ex.getMessage());
            return;
        }

        Set<String> candidateKeySet = normalizeNameSet(candidateNames);
        if (!hydrateAllArtists && candidateKeySet.isEmpty()) return;

        List<Artist> missing = allArtists.stream()
                .filter(Objects::nonNull)
                .filter(artist -> !hasText(artist.getBiography()))
                .filter(artist -> hydrateAllArtists || candidateKeySet.contains(normalizeKey(artist.getName())))
                .filter(artist -> hasText(artist.getName()))
                .collect(Collectors.toList());

        if (missing.isEmpty()) return;

        Map<String, String> biographies = fetchBiographiesByName(
                missing.stream().map(Artist::getName).collect(Collectors.toList())
        );
        if (biographies.isEmpty()) return;

        for (Artist artist : missing) {
            String biography = biographies.get(normalizeKey(artist.getName()));
            if (!hasText(biography) || artist.getArtistID() <= 0) continue;
            try {
                artistDao.updateBiography(artist.getArtistID(), biography);
            } catch (Exception ex) {
                System.out.println("hydrateMissingBiographies: update failed for " + artist.getName() + " -> " + ex.getMessage());
            }
        }
    }

    public Map<String, String> fetchBiographiesByName(Collection<String> artistNames) {
        Map<String, String> deduped = new LinkedHashMap<>();
        if (artistNames != null) {
            for (String name : artistNames) {
                if (!hasText(name)) continue;
                deduped.putIfAbsent(normalizeKey(name), name.strip());
            }
        }
        if (deduped.isEmpty()) return Map.of();

        List<WikipediaApiMetadata> metadata = wikipediaService.getApiObjectsList(List.copyOf(deduped.values()));
        Map<String, String> out = new LinkedHashMap<>();
        for (WikipediaApiMetadata item : metadata) {
            if (item == null || !hasText(item.getArtistName()) || !hasText(item.getArtistBiography())) continue;
            out.put(normalizeKey(item.getArtistName()), item.getArtistBiography());
        }
        return out;
    }

    public Set<String> findArtistNamesNotInDb(ArtistDao artistDao, Collection<String> candidateNames) {
        Map<String, String> candidates = new LinkedHashMap<>();
        if (candidateNames != null) {
            for (String name : candidateNames) {
                if (!hasText(name)) continue;
                candidates.putIfAbsent(normalizeKey(name), name.strip());
            }
        }
        if (candidates.isEmpty() || artistDao == null) return Set.of();

        try {
            for (Artist artist : artistDao.findAll()) {
                if (artist == null || !hasText(artist.getName())) continue;
                candidates.remove(normalizeKey(artist.getName()));
            }
        } catch (Exception ex) {
            System.out.println("findArtistNamesNotInDb: cannot read artists -> " + ex.getMessage());
            return Set.of();
        }

        return new LinkedHashSet<>(candidates.values());
    }

    public void hydrateMetadataArtistBiographies(DeezerApiMetaData meta) {
        Set<String> names = collectArtistNames(meta == null ? List.of() : List.of(meta));
        if (names.isEmpty()) return;

        try {
            DbConnectionManager.getInstance().runInTransaction(conn -> {
                ArtistDaoImpl artistDao = new ArtistDaoImpl(conn);
                for (String name : names) {
                    if (!hasText(name)) continue;
                    try {
                        if (artistDao.findIdByName(name) == null) {
                            artistDao.create(name);
                        }
                    } catch (Exception ignored) {
                    }
                }
                hydrateMissingBiographies(conn, artistDao, names);
                return null;
            });
        } catch (Exception ex) {
            System.out.println("hydrateMetadataArtistBiographies: failed -> " + ex.getMessage());
        }
    }

    public CompletableFuture<Void> hydrateMetadataArtistBiographiesAsync(DeezerApiMetaData meta) {
        if (meta == null) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> hydrateMetadataArtistBiographies(meta), BIOGRAPHY_EXECUTOR);
    }

    public Set<String> collectArtistNames(Collection<DeezerApiMetaData> metas) {
        Set<String> names = new LinkedHashSet<>();
        if (metas == null) return names;

        for (DeezerApiMetaData meta : metas) {
            if (meta == null) continue;
            addNames(names, meta.getAlbumArtistNames());
            addNames(names, meta.getSongContributorNames());
        }
        return names;
    }

    public void persistBiography(long artistId, String artistName, String biography) {
        if (!hasText(biography)
                || ArtistIdentity.isVariousArtists(artistName)
                || (artistId <= 0 && !hasText(artistName))) return;

        try {
            DbConnectionManager.getInstance().runInTransaction(conn -> {
                applyPragmas(conn);

                // A memory/cache artist may not have a row yet. Insert it first so
                // the biography is not lost when the subsequent screen is opened.
                try {
                    if (artistId > 0 && hasText(artistName)) {
                        try (PreparedStatement ps = conn.prepareStatement("""
                                INSERT OR IGNORE INTO Artist(ArtistID, Name, Biography)
                                VALUES(?, ?, ?)
                                """)) {
                            ps.setLong(1, artistId);
                            ps.setString(2, artistName.strip());
                            ps.setString(3, biography);
                            ps.executeUpdate();
                        }
                    } else if (hasText(artistName)) {
                        try (PreparedStatement ps = conn.prepareStatement("""
                                INSERT OR IGNORE INTO Artist(Name, Biography)
                                VALUES(?, ?)
                                """)) {
                            ps.setString(1, artistName.strip());
                            ps.setString(2, biography);
                            ps.executeUpdate();
                        }
                    }
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }

                if (artistId > 0) {
                    try (PreparedStatement ps = conn.prepareStatement("""
                            UPDATE Artist
                               SET Biography = ?
                             WHERE ArtistID = ?
                               AND (Biography IS NULL OR TRIM(Biography) = '')
                            """)) {
                        ps.setString(1, biography);
                        ps.setLong(2, artistId);
                        ps.executeUpdate();
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                }

                if (artistId <= 0 && hasText(artistName)) {
                    try (PreparedStatement ps = conn.prepareStatement("""
                            UPDATE Artist
                               SET Biography = ?
                             WHERE lower(Name) = ?
                               AND (Biography IS NULL OR TRIM(Biography) = '')
                            """)) {
                        ps.setString(1, biography);
                        ps.setString(2, artistName.strip().toLowerCase(Locale.ROOT));
                        ps.executeUpdate();
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                }
                return null;
            });
        } catch (Exception ex) {
            System.out.println("persistBiography: failed for " + artistName + " -> " + ex.getMessage());
        }
    }

    public void updateMemoryArtistBiography(List<Artist> artists, long artistId, String artistName, String biography) {
        if (artists == null || !hasText(biography)) return;
        String key = normalizeKey(artistName);
        synchronized (artists) {
            for (Artist artist : artists) {
                if (artist == null) continue;
                boolean matches = artistId > 0
                        ? artist.getArtistID() > 0 && artist.getArtistID() == artistId
                        : !key.isBlank() && normalizeKey(artist.getName()).equals(key);
                if (matches) {
                    artist.setBiography(biography);
                }
            }
        }
    }

    private String findBiographyInMemory(List<Artist> artists, long artistId, String artistName) {
        if (artists == null) return null;
        String key = normalizeKey(artistName);
        synchronized (artists) {
            for (Artist artist : artists) {
                if (artist == null || !hasText(artist.getBiography())) continue;
                if (artistId > 0) {
                    if (artist.getArtistID() > 0 && artist.getArtistID() == artistId) {
                        return artist.getBiography();
                    }
                    continue;
                }
                if (!key.isBlank() && normalizeKey(artist.getName()).equals(key)) {
                    return artist.getBiography();
                }
            }
        }
        return null;
    }

    private boolean artistExistsInMemory(List<Artist> artists, long artistId, String artistName) {
        if (artists == null) return false;
        String key = normalizeKey(artistName);
        synchronized (artists) {
            for (Artist artist : artists) {
                if (artist == null) continue;
                if (artistId > 0) {
                    if (artist.getArtistID() > 0 && artist.getArtistID() == artistId) return true;
                    continue;
                }
                if (!key.isBlank() && normalizeKey(artist.getName()).equals(key)) return true;
            }
        }
        return false;
    }

    private boolean artistExistsInDb(long artistId, String artistName) {
        try (Connection conn = DbConnectionManager.getInstance().openConnection()) {
            applyPragmas(conn);
            if (artistId > 0 && queryExists(conn, "SELECT 1 FROM Artist WHERE ArtistID = ? LIMIT 1", artistId, null)) {
                return true;
            }
            return artistId <= 0 && hasText(artistName)
                    && queryExists(conn, "SELECT 1 FROM Artist WHERE lower(Name) = ? LIMIT 1", 0L, artistName.strip().toLowerCase(Locale.ROOT));
        } catch (Exception ex) {
            System.out.println("artistExistsInDb: failed for " + artistName + " -> " + ex.getMessage());
            return false;
        }
    }

    private boolean queryExists(Connection conn, String sql, long artistId, String artistName) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (artistId > 0) ps.setLong(1, artistId);
            else ps.setString(1, artistName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private String findBiographyInDb(long artistId, String artistName) {
        try (Connection conn = DbConnectionManager.getInstance().openConnection()) {
            applyPragmas(conn);
            if (artistId > 0) {
                return queryBiography(conn, "SELECT Biography FROM Artist WHERE ArtistID = ? LIMIT 1", artistId, null);
            }
            if (hasText(artistName)) {
                return queryBiography(conn, "SELECT Biography FROM Artist WHERE lower(Name) = ? LIMIT 1", 0L, artistName.strip().toLowerCase(Locale.ROOT));
            }
        } catch (Exception ex) {
            System.out.println("findBiographyInDb: failed for " + artistName + " -> " + ex.getMessage());
        }
        return null;
    }

    private String queryBiography(Connection conn, String sql, long artistId, String artistName) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (artistId > 0) ps.setLong(1, artistId);
            else ps.setString(1, artistName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void applyPragmas(Connection conn) {
        if (conn == null) return;
        try (Statement st = conn.createStatement()) {
            try { st.execute("PRAGMA busy_timeout = 5000"); } catch (Exception ignored) {}
            try { st.execute("PRAGMA journal_mode = WAL"); } catch (Exception ignored) {}
        } catch (Exception ignored) {
        }
    }

    private static Set<String> normalizeNameSet(Collection<String> names) {
        Set<String> out = new LinkedHashSet<>();
        addNames(out, names);
        return out.stream().map(ArtistBiographyService::normalizeKey).filter(value -> !value.isBlank()).collect(Collectors.toSet());
    }

    private static void addNames(Collection<String> target, Collection<String> names) {
        if (target == null || names == null) return;
        for (String name : names) {
            if (hasText(name)) target.add(name.strip());
        }
    }

    private static String normalizeKey(String value) {
        if (value == null) return "";
        return value
                .replaceAll("\\([^)]*\\)", " ")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
