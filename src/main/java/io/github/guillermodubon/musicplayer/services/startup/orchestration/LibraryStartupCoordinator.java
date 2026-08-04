package io.github.guillermodubon.musicplayer.services.startup.orchestration;

import io.github.guillermodubon.musicplayer.models.ManifestEntry;

import io.github.guillermodubon.musicplayer.repository.DataBaseConfig;
import io.github.guillermodubon.musicplayer.repository.DbConnectionManager;
import io.github.guillermodubon.musicplayer.repository.dao.album.AlbumDao;
import io.github.guillermodubon.musicplayer.repository.dao.album.AlbumDaoImpl;
import io.github.guillermodubon.musicplayer.repository.dao.artist.ArtistDao;
import io.github.guillermodubon.musicplayer.repository.dao.artist.ArtistDaoImpl;
import io.github.guillermodubon.musicplayer.repository.dao.genre.GenreDao;
import io.github.guillermodubon.musicplayer.repository.dao.genre.GenreDaoImpl;
import io.github.guillermodubon.musicplayer.repository.dao.playlist.PlaylistDaoImpl;
import io.github.guillermodubon.musicplayer.repository.dao.song.SongDao;
import io.github.guillermodubon.musicplayer.repository.dao.song.SongDaoImpl;
import io.github.guillermodubon.musicplayer.models.DeezerApiMetaData;
import io.github.guillermodubon.musicplayer.services.manifest.ManifestService;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Coordinates device scanning and the initial or incremental library import. */
public final class LibraryStartupCoordinator {

    private final StartUpService owner;

    public LibraryStartupCoordinator(StartUpService owner) {
        this.owner = java.util.Objects.requireNonNull(owner, "owner");
    }
    public void runStartup() throws SQLException {
    System.out.println("runStartup: initializing DB and loading models...");
    owner.reportStartupStatus("Preparing your music library...");
    owner.reportStartupProgress(0.03);
    DataBaseConfig.initializeDatabase();

    owner.reportStartupStatus("Looking for music on this device...");
    owner.reportStartupProgress(0.10);
    Map<String, String> scanned = owner.scannerService().getAllSongsMapFromLocalDevice();

    synchronized (owner.titleToPathIndex()) {
        owner.titleToPathIndex().clear();
        if (scanned != null) {
            owner.titleToPathIndex().putAll(scanned);
        }
    }

    System.out.println("runStartup: scanned owner.titleToPathIndex() size=" + owner.titleToPathIndex().size());
    owner.reportStartupStatus(owner.titleToPathIndex().isEmpty()
            ? "Checking your music library..."
            : "We found " + owner.titleToPathIndex().size() + " songs. Checking your library...");
    owner.reportStartupProgress(0.30);

    Map<String, ManifestEntry> oldManifest = owner.getManifestService().load();
    owner.reportStartupProgress(0.36);

    boolean dbHasNoLocalSongs;

    try (Connection quickConn = DbConnectionManager.getInstance().openConnection()) {
        dbHasNoLocalSongs = countLocalSongs(quickConn) == 0;
    }

    boolean manifestEmpty = oldManifest == null || oldManifest.isEmpty();

    if (dbHasNoLocalSongs) {
        System.out.println("runStartup: First time run detected -> delegating to createAndFetchInitialData");
        owner.reportStartupStatus("Matching your songs with albums and artists...");
        owner.reportStartupProgress(0.42);

        owner.noMetadataSongs.clear();

        List<String> titles = owner.titleToPathIndex().isEmpty()
                ? List.of()
                : new ArrayList<>(owner.titleToPathIndex().keySet());

        List<DeezerApiMetaData> metas = List.of();

        if (!titles.isEmpty()) {
            try {
                metas = owner.deezerService().getApiObjectsList(titles);
            } catch (Exception e) {
                System.out.println(
                        "runStartup: warning fetching initial metas -> "
                                + Optional.ofNullable(e.getMessage()).orElse("null")
                );
                metas = List.of();
            }
        }
        owner.reportStartupProgress(0.66);

        List<DeezerApiMetaData> finalMetas = metas;

        owner.reportStartupStatus("Organizing your music library...");
        owner.reportStartupProgress(0.72);
        DbConnectionManager.getInstance().runInTransaction(conn -> {
            try {

                GenreDao genreDao = new GenreDaoImpl(conn);
                ArtistDao artistDao = new ArtistDaoImpl(conn);
                AlbumDao albumDao = new AlbumDaoImpl(conn);
                SongDao songDao = new SongDaoImpl(conn);

                owner.setDataAccessObjects(albumDao, new PlaylistDaoImpl(conn));

                owner.initialLibraryImportService().createAndFetchInitialData(
                        conn,
                        genreDao,
                        artistDao,
                        albumDao,
                        songDao,
                        owner.titleToPathIndex(),
                        owner.noMetadataSongs,
                        finalMetas
                );

                owner.reportStartupStatus("Loading your albums, artists, and songs...");
                owner.reportStartupProgress(0.87);
                owner.getModelHydrationService().loadModels(conn, owner.titleToPathIndex());
            } catch (SQLException | java.io.IOException e) {
                throw new RuntimeException(e);
            }

            return null;
        });

        System.out.println(
                "runStartup: initial import finished -> artists="
                        + owner.getArtists().size()
                        + " albums="
                        + owner.getAlbums().size()
                        + " songs="
                        + owner.getSongs().size()
        );

        owner.reportStartupStatus("Your music is ready.");
        owner.reportStartupProgress(0.98);

        return;
    }

    System.out.println("runStartup: Not first time -> delegating to syncExistingData");
    owner.reportStartupStatus("Checking for changes in your music library...");
    owner.reportStartupProgress(0.45);

    if (manifestEmpty) {
        System.out.println("runStartup: manifest is empty but DB has local songs -> rebuilding through incremental sync");
    }

    synchronized (owner.getDbLock()) {
        try {
            owner.reportStartupStatus("Updating your music library...");
            owner.reportStartupProgress(0.62);
            DbConnectionManager.getInstance().runInTransaction(conn -> {
                try {
                    GenreDao genreDao = new GenreDaoImpl(conn);
                    ArtistDao artistDao = new ArtistDaoImpl(conn);
                    AlbumDao albumDao = new AlbumDaoImpl(conn);
                    SongDao songDao = new SongDaoImpl(conn);

                    owner.setDataAccessObjects(albumDao, new PlaylistDaoImpl(conn));

                    owner.incrementalLibrarySyncService().syncExistingData(
                            conn,
                            genreDao,
                            artistDao,
                            albumDao,
                            songDao,
                            owner.titleToPathIndex(),
                            List.of(),
                            owner.noMetadataSongs
                    );
                } catch (SQLException | java.io.IOException e) {
                    throw new RuntimeException(e);
                }

                return null;
            });

            System.out.println(
                    "runStartup: syncExistingData finished -> artists="
                            + owner.getArtists().size()
                            + " albums="
                            + owner.getAlbums().size()
                            + " songs="
                            + owner.getSongs().size()
            );
            owner.reportStartupStatus("Your music is ready.");
            owner.reportStartupProgress(0.98);
        } catch (RuntimeException rte) {
            System.err.println(
                    "runStartup: transaction failed -> "
                            + Optional.ofNullable(rte.getMessage()).orElse("null")
            );
            rte.printStackTrace();
        }
    }
}

private int countLocalSongs(Connection conn) throws SQLException {
    if (conn == null) {
        return 0;
    }

    String songTableName = resolveExistingTableName(conn, "Song", "Songs", "SONG");

    if (songTableName == null || songTableName.isBlank()) {
        return 0;
    }

    String sql = "SELECT COUNT(*) FROM " + quoteSqliteIdentifier(songTableName) + " WHERE IsLocal = 1";

    try (var ps = conn.prepareStatement(sql);
         var rs = ps.executeQuery()) {
        return rs.next() ? rs.getInt(1) : 0;
    }
}

private String resolveExistingTableName(Connection conn, String... candidates) throws SQLException {
    if (conn == null || candidates == null || candidates.length == 0) {
        return null;
    }

    List<String> existingTables = new ArrayList<>();

    var metaData = conn.getMetaData();

    try (var rs = metaData.getTables(null, null, "%", new String[]{"TABLE"})) {
        while (rs.next()) {
            String tableName = rs.getString("TABLE_NAME");

            if (tableName != null && !tableName.isBlank()) {
                existingTables.add(tableName);
            }
        }
    }

    for (String candidate : candidates) {
        for (String existing : existingTables) {
            if (existing.equals(candidate)) {
                return existing;
            }
        }
    }

    for (String candidate : candidates) {
        for (String existing : existingTables) {
            if (existing.equalsIgnoreCase(candidate)) {
                return existing;
            }
        }
    }

    return null;
}

private String quoteSqliteIdentifier(String identifier) {
    if (identifier == null || identifier.isBlank()) {
        throw new IllegalArgumentException("SQLite identifier cannot be null or blank.");
    }

    return "\"" + identifier.replace("\"", "\"\"") + "\"";
}


}


