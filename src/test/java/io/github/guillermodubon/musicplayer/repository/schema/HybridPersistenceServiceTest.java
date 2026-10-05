package io.github.guillermodubon.musicplayer.repository.schema;

import io.github.guillermodubon.musicplayer.repository.identity.CanonicalMediaService;
import io.github.guillermodubon.musicplayer.repository.identity.CanonicalizationResult;
import io.github.guillermodubon.musicplayer.repository.identity.CanonicalTracklistService;
import io.github.guillermodubon.musicplayer.repository.identity.ExternalIdentityDao;
import io.github.guillermodubon.musicplayer.repository.identity.ExternalMediaId;
import io.github.guillermodubon.musicplayer.repository.identity.IdentityResolution;
import io.github.guillermodubon.musicplayer.repository.library.SavedMediaService;
import io.github.guillermodubon.musicplayer.repository.library.SavedLibrarySnapshot;
import io.github.guillermodubon.musicplayer.repository.library.SongListeningHistoryDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HybridPersistenceServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void providerIdentityDoesNotCollideWithLegacyPrimaryKeysAndConfirmedMappingsAreReused() throws Exception {
        Path database = temporaryDirectory.resolve("identity.db");
        try (Connection connection = fresh(database)) {
            execute(connection, "INSERT INTO Genre(GenreID, Name) VALUES(1, 'Pop')");
            execute(connection, "INSERT INTO Artist(ArtistID, Name) VALUES(10, 'Artist')");
            execute(connection, "INSERT INTO Album(AlbumID, GenreID, Name, ReleaseDate) VALUES(200, 1, 'Release', '2020')");
            execute(connection, "INSERT INTO Album(AlbumID, GenreID, Name, ReleaseDate) VALUES(201, 1, 'Release', '2020')");
            execute(connection, "INSERT INTO AlbumArtist(AlbumID, ArtistID) VALUES(200, 10)");
            execute(connection, "INSERT INTO AlbumArtist(AlbumID, ArtistID) VALUES(201, 10)");
            execute(connection, "INSERT INTO Song(SongID, Title, Album, IsLocal) VALUES(900, 'Older Track', 201, 1)");
            execute(connection, "INSERT INTO SongArtist(SongID, ArtistID) VALUES(900, 10)");
            connection.setAutoCommit(false);

            CanonicalMediaService service = new CanonicalMediaService(new SavedMediaService(() -> 1234L));
            CanonicalMediaService.AlbumMetadata release = album("Release", List.of("Artist"));
            CanonicalMediaService.SongMetadata remoteTrack = song("New Track", release, false, null);
            CanonicalizationResult<CanonicalMediaService.CanonicalSong> result = service.ensureCanonicalSong(
                    connection, ExternalMediaId.deezer(900), ExternalMediaId.deezer(200), remoteTrack
            );
            assertTrue(result.isSuccess());
            assertNotEquals(900, result.value().songId());
            assertEquals("Older Track", scalarString(connection, "SELECT Title FROM Song WHERE SongID = 900"));
            assertEquals(1, scalarInt(connection, "SELECT IsLocal FROM Song WHERE SongID = 900"));
            assertEquals(result.value().songId(), scalarInt(connection,
                    "SELECT SongID FROM SongExternalIdentity WHERE Provider='DEEZER' AND ExternalId='900'"));

            CanonicalizationResult<CanonicalMediaService.CanonicalSong> same = service.ensureCanonicalSong(
                    connection, ExternalMediaId.deezer(900), ExternalMediaId.deezer(200), remoteTrack
            );
            assertEquals(result.value(), same.value());
            CanonicalizationResult<CanonicalMediaService.CanonicalSong> contradiction = service.ensureCanonicalSong(
                    connection,
                    ExternalMediaId.deezer(900),
                    ExternalMediaId.deezer(200),
                    song("Contradictory title", release, false, null)
            );
            assertFalse(contradiction.isSuccess());
            assertEquals(IdentityResolution.Reason.CONTRADICTORY_METADATA,
                    contradiction.conflict().reason());
            assertEquals("New Track", scalarString(connection,
                    "SELECT Title FROM Song WHERE SongID=" + result.value().songId()));
            assertEquals(2, scalarInt(connection, "SELECT COUNT(*) FROM Album WHERE Name='Release'"));
            assertEquals(2, scalarInt(connection, "SELECT COUNT(*) FROM Song"));

            ExternalIdentityDao identityDao = new ExternalIdentityDao(connection);
            IdentityResolution conflict = identityDao.attach(
                    ExternalIdentityDao.EntityType.SONG, ExternalMediaId.deezer(900), 900
            );
            assertInstanceOf(IdentityResolution.Conflict.class, conflict);
            assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM SongExternalIdentity"));
            connection.rollback();
        }
    }

    @Test
    void confirmedLegacyIdentityIsReusedButTitleAloneNeverMergesRows() throws Exception {
        try (Connection connection = fresh(temporaryDirectory.resolve("legacy-identity.db"))) {
            execute(connection, "INSERT INTO Genre(GenreID, Name) VALUES(1, 'Pop')");
            execute(connection, "INSERT INTO Artist(ArtistID, Name) VALUES(10, 'Artist')");
            execute(connection, "INSERT INTO Album(AlbumID, GenreID, Name, ReleaseDate) VALUES(500, 1, 'Known release', '2020')");
            execute(connection, "INSERT INTO Album(AlbumID, GenreID, Name, ReleaseDate) VALUES(501, 1, 'Other release', '2021')");
            execute(connection, "INSERT INTO AlbumArtist(AlbumID, ArtistID) VALUES(500, 10)");
            execute(connection, "INSERT INTO Song(SongID, Title, Album, IsLocal) VALUES(900, 'Known track', 500, 1)");
            execute(connection, "INSERT INTO SongArtist(SongID, ArtistID) VALUES(900, 10)");
            execute(connection, "INSERT INTO Song(SongID, Title, Album, IsLocal) VALUES(999, 'Repeated title', 501, 1)");
            execute(connection, "INSERT INTO SongArtist(SongID, ArtistID) VALUES(999, 10)");
            connection.setAutoCommit(false);

            CanonicalMediaService service = new CanonicalMediaService();
            CanonicalMediaService.AlbumMetadata knownRelease = new CanonicalMediaService.AlbumMetadata(
                    "Known release", "Pop", "album", "2020", 1, List.of("Artist"));
            CanonicalMediaService.CanonicalSong reused = service.ensureCanonicalSong(
                    connection,
                    ExternalMediaId.deezer(900),
                    ExternalMediaId.deezer(500),
                    new CanonicalMediaService.SongMetadata(
                            "Known track", 1, 180, List.of("Artist"), knownRelease, false, null)
            ).value();
            assertEquals(900, reused.songId());
            assertEquals(500, reused.albumId());

            CanonicalMediaService.CanonicalSong distinct = service.ensureCanonicalSong(
                    connection,
                    ExternalMediaId.deezer(999),
                    ExternalMediaId.deezer(500),
                    new CanonicalMediaService.SongMetadata(
                            "Repeated title", 1, 180, List.of("Artist"), knownRelease, false, null)
            ).value();
            assertNotEquals(999, distinct.songId());
            assertEquals(501, scalarInt(connection, "SELECT Album FROM Song WHERE SongID=999"));
            assertEquals("Repeated title", scalarString(connection, "SELECT Title FROM Song WHERE SongID=999"));
            assertEquals(2, scalarInt(connection, "SELECT COUNT(*) FROM Song WHERE Title='Repeated title'"));
            assertEquals(900, scalarInt(connection,
                    "SELECT SongID FROM SongExternalIdentity WHERE Provider='DEEZER' AND ExternalId='900'"));
            connection.rollback();
        }
    }

    @Test
    void concurrentSaveAndPlaylistAddRequestsRemainUniqueAndOrdered() throws Exception {
        Path database = temporaryDirectory.resolve("concurrent-library.db");
        try (Connection connection = fresh(database)) {
            execute(connection, "INSERT INTO Genre(GenreID, Name) VALUES(1, 'Pop')");
            execute(connection, "INSERT INTO Album(AlbumID, GenreID, Name) VALUES(10, 1, 'Local release')");
            execute(connection, "INSERT INTO Song(SongID, Title, Album, IsLocal) VALUES(1, 'Local song', 10, 1)");
            execute(connection, "INSERT INTO Playlist(PlaylistID, Title, Author, Origin) VALUES(20, 'My mix', 'User', 'USER')");
            execute(connection, "PRAGMA journal_mode=WAL");
        }

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstSave = executor.submit(() -> saveAfterBarrier(database, ready, start));
            Future<?> secondSave = executor.submit(() -> saveAfterBarrier(database, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            firstSave.get(10, TimeUnit.SECONDS);
            secondSave.get(10, TimeUnit.SECONDS);

            CountDownLatch addReady = new CountDownLatch(2);
            CountDownLatch addStart = new CountDownLatch(1);
            Future<Integer> firstAdd = executor.submit(() -> addRemoteTrackAfterBarrier(
                    database, 4000, "First remote", addReady, addStart));
            Future<Integer> secondAdd = executor.submit(() -> addRemoteTrackAfterBarrier(
                    database, 4001, "Second remote", addReady, addStart));
            assertTrue(addReady.await(5, TimeUnit.SECONDS));
            addStart.countDown();
            int firstPosition = firstAdd.get(10, TimeUnit.SECONDS);
            int secondPosition = secondAdd.get(10, TimeUnit.SECONDS);

            assertEquals(1, scalarIntFromFile(database, "SELECT COUNT(*) FROM SavedSong WHERE SongID=1"));
            assertEquals(Set.of(0, 1), Set.of(firstPosition, secondPosition));
            assertEquals(2, scalarIntFromFile(database, "SELECT COUNT(*) FROM SongsPlaylists WHERE PlaylistID=20"));
            assertEquals(2, scalarIntFromFile(database,
                    "SELECT COUNT(DISTINCT Position) FROM SongsPlaylists WHERE PlaylistID=20"));
            assertEquals(2, scalarIntFromFile(database,
                    "SELECT COUNT(*) FROM SongExternalIdentity WHERE Provider='DEEZER' AND ExternalId IN ('4000','4001')"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void canonicalRemoteRowsSaveStateAndPlaylistMembershipAreIndependentAndIdempotent() throws Exception {
        Path database = temporaryDirectory.resolve("saved-media.db");
        try (Connection connection = fresh(database)) {
            execute(connection, "INSERT INTO Genre(GenreID, Name) VALUES(1, 'Pop')");
            execute(connection, "INSERT INTO Playlist(PlaylistID, Title, Author, Origin) VALUES(20, 'My Mix', 'User', 'USER')");
            connection.setAutoCommit(false);

            SavedMediaService saved = new SavedMediaService(() -> 777L);
            CanonicalMediaService service = new CanonicalMediaService(saved);
            ExternalMediaId songId = ExternalMediaId.deezer(4000);
            ExternalMediaId albumId = ExternalMediaId.deezer(5000);
            CanonicalMediaService.AlbumMetadata album = album("Remote Release", List.of("Remote Artist"));
            CanonicalMediaService.SongMetadata metadata = song("Remote Song", album, false, null);
            CanonicalizationResult<CanonicalMediaService.CanonicalSong> added = service.addRemoteSongToUserPlaylist(
                    connection, 20, songId, albumId, metadata
            );
            assertTrue(added.isSuccess());
            long canonicalSongId = added.value().songId();
            long canonicalAlbumId = added.value().albumId();
            assertFalse(saved.isSongSavedDirectly(connection, canonicalSongId));
            assertFalse(saved.isReleaseSaved(connection, canonicalAlbumId));
            assertFalse(saved.isSongInLibrary(connection, canonicalSongId));
            assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM SongsPlaylists WHERE PlaylistID=20"));
            assertEquals(0, scalarInt(connection, "SELECT Position FROM SongsPlaylists WHERE PlaylistID=20"));

            service.addRemoteSongToUserPlaylist(connection, 20, songId, albumId, metadata);
            assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM SongsPlaylists WHERE PlaylistID=20"));
            assertEquals(0, scalarInt(connection, "SELECT Position FROM SongsPlaylists WHERE PlaylistID=20"));
            CanonicalMediaService.CanonicalSong second = service.addRemoteSongToUserPlaylist(
                    connection,
                    20,
                    ExternalMediaId.deezer(4001),
                    albumId,
                    song("Second song", album, false, null)
            ).value();
            assertEquals(1, scalarInt(connection,
                    "SELECT Position FROM SongsPlaylists WHERE PlaylistID=20 AND SongID=" + second.songId()));
            assertEquals(1, scalarInt(connection,
                    "SELECT CustomPosition FROM SongsPlaylists WHERE PlaylistID=20 AND SongID=" + second.songId()));

            saved.saveSong(connection, canonicalSongId);
            saved.saveSong(connection, canonicalSongId);
            assertTrue(saved.isSongSavedDirectly(connection, canonicalSongId));
            assertTrue(saved.isSongInLibrary(connection, canonicalSongId));
            assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM SavedSong"));
            SavedLibrarySnapshot songSnapshot = saved.loadSnapshot(
                    connection, List.of(canonicalSongId), List.of(canonicalAlbumId));
            assertTrue(songSnapshot.directlySavedSongIds().contains(canonicalSongId));
            assertTrue(songSnapshot.librarySongIds().contains(canonicalSongId));
            assertFalse(songSnapshot.savedReleaseIds().contains(canonicalAlbumId));
            saved.unsaveSong(connection, canonicalSongId);
            assertFalse(saved.isSongInLibrary(connection, canonicalSongId));

            saved.saveRelease(connection, canonicalAlbumId);
            assertTrue(saved.isReleaseSaved(connection, canonicalAlbumId));
            assertTrue(saved.isSongInLibrary(connection, canonicalSongId));
            SavedLibrarySnapshot releaseSnapshot = saved.loadSnapshot(
                    connection, List.of(canonicalSongId), List.of(canonicalAlbumId));
            assertTrue(releaseSnapshot.savedReleaseIds().contains(canonicalAlbumId));
            assertTrue(releaseSnapshot.librarySongIds().contains(canonicalSongId));
            saved.unsaveRelease(connection, canonicalAlbumId);
            assertFalse(saved.isSongInLibrary(connection, canonicalSongId));
            assertEquals(2, scalarInt(connection, "SELECT COUNT(*) FROM SongsPlaylists WHERE PlaylistID=20"));

            CanonicalMediaService.AlbumMetadata single = new CanonicalMediaService.AlbumMetadata(
                    "One-track release", "Pop", "single", "2024", 1, List.of("Remote Artist"));
            long singleId = service.ensureCanonicalAlbum(
                    connection, ExternalMediaId.deezer(5001), single).value();
            saved.saveRelease(connection, singleId);
            assertEquals("single", scalarString(connection,
                    "SELECT RecordType FROM Album WHERE AlbumID=" + singleId));
            assertTrue(saved.isReleaseSaved(connection, singleId));

            CanonicalizationResult<Long> remotePlaylist = service.ensureCanonicalPlaylist(
                    connection, ExternalMediaId.deezer(20),
                    new CanonicalMediaService.PlaylistMetadata("Remote Mix", "Curator", null)
            );
            assertTrue(remotePlaylist.isSuccess());
            assertNotEquals(20, remotePlaylist.value());
            assertEquals("USER", scalarString(connection, "SELECT Origin FROM Playlist WHERE PlaylistID=20"));
            assertEquals("DEEZER", scalarString(connection,
                    "SELECT Origin FROM Playlist WHERE PlaylistID=" + remotePlaylist.value()));
            assertEquals(remotePlaylist.value(), scalarInt(connection,
                    "SELECT PlaylistID FROM PlaylistExternalIdentity WHERE Provider='DEEZER' AND ExternalId='20'"));

            new SongListeningHistoryDao().recordSuccessfulPlay(connection, canonicalSongId, 888L);
            assertEquals(1, scalarInt(connection, "SELECT PlayCount FROM SongPlayStats WHERE SongID=" + canonicalSongId));
            assertEquals(888, scalarInt(connection, "SELECT PlayedAt FROM RecentSongPlay WHERE SongID=" + canonicalSongId));
            connection.rollback();
        }
    }

    @Test
    void tracklistPersistenceUsesExternalIdentitiesAndReentryIsIdempotent() throws Exception {
        try (Connection connection = fresh(temporaryDirectory.resolve("tracklist.db"))) {
            execute(connection, "INSERT INTO Genre(GenreID, Name) VALUES(1, 'Pop')");
            connection.setAutoCommit(false);
            CanonicalMediaService.AlbumMetadata album = album("Canonical album", List.of("Artist"));
            CanonicalTracklistService service = new CanonicalTracklistService();
            List<CanonicalTracklistService.TrackMetadata> tracks = List.of(
                    new CanonicalTracklistService.TrackMetadata(700, "First track", 1, 180),
                    new CanonicalTracklistService.TrackMetadata(701, "Second track", 2, 190)
            );

            service.persist(connection, ExternalMediaId.deezer(600), album, tracks);
            service.persist(connection, ExternalMediaId.deezer(600), album, tracks);

            assertEquals(2, scalarInt(connection, "SELECT COUNT(*) FROM Song"));
            assertEquals(2, scalarInt(connection, "SELECT COUNT(*) FROM SongExternalIdentity"));
            assertNotEquals(700, scalarInt(connection,
                    "SELECT SongID FROM SongExternalIdentity WHERE ExternalId='700'"));
            assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM AlbumExternalIdentity WHERE ExternalId='600'"));
            assertEquals(0, scalarInt(connection, "SELECT COUNT(*) FROM SavedSong"));
            assertEquals(0, scalarInt(connection, "SELECT COUNT(*) FROM SavedRelease"));
            connection.rollback();
        }
    }

    @Test
    void verifiedLocalSongBatchSavesSongsAndDistinctReleasesIdempotently() throws Exception {
        try (Connection connection = fresh(temporaryDirectory.resolve("local-save-batch.db"))) {
            execute(connection, "INSERT INTO Genre(GenreID, Name) VALUES(1, 'Pop')");
            execute(connection, "INSERT INTO Album(AlbumID, GenreID, Name) VALUES(10, 1, 'Release')");
            execute(connection, "INSERT INTO Album(AlbumID, GenreID, Name) VALUES(11, 1, 'Remote')");
            execute(connection, "INSERT INTO Song(SongID, Title, Album, IsLocal) VALUES(1, 'First', 10, 1)");
            execute(connection, "INSERT INTO Song(SongID, Title, Album, IsLocal) VALUES(2, 'Second', 10, 1)");
            execute(connection, "INSERT INTO Song(SongID, Title, Album, IsLocal) VALUES(3, 'Remote', 11, 0)");

            SavedMediaService saved = new SavedMediaService(() -> 123L);
            saved.saveLocalSongsAndReleases(connection, List.of(1L, 2L, 3L, 999L, 1L));
            saved.saveLocalSongsAndReleases(connection, List.of(1L, 2L));

            assertEquals(2, scalarInt(connection, "SELECT COUNT(*) FROM SavedSong"));
            assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM SavedRelease"));
            assertEquals(10, scalarInt(connection, "SELECT AlbumID FROM SavedRelease"));
            assertEquals(0, scalarInt(connection, "SELECT COUNT(*) FROM SavedSong WHERE SongID=3"));
        }
    }

    @Test
    void downloadPromotionReusesSavedPlaylistSongAndKeepsLyrics() throws Exception {
        try (Connection connection = fresh(temporaryDirectory.resolve("promotion.db"))) {
            execute(connection, "INSERT INTO Genre(GenreID, Name) VALUES(1, 'Pop')");
            execute(connection, "INSERT INTO Playlist(PlaylistID, Title, Author, Origin) VALUES(30, 'My mix', 'User', 'USER')");
            connection.setAutoCommit(false);
            CanonicalMediaService service = new CanonicalMediaService(new SavedMediaService(() -> 1000L));
            ExternalMediaId externalSong = ExternalMediaId.deezer(9100);
            ExternalMediaId externalAlbum = ExternalMediaId.deezer(9200);
            CanonicalMediaService.AlbumMetadata album = album("Saved release", List.of("Remote Artist"));
            CanonicalMediaService.SongMetadata remoteMetadata = song("Saved track", album, false, null);
            CanonicalMediaService.CanonicalSong remote = service.addRemoteSongToUserPlaylist(
                    connection, 30, externalSong, externalAlbum, remoteMetadata
            ).value();
            execute(connection, "INSERT INTO SongLyrics(SongID, SourceKey, TrackName, ArtistName, Status) "
                    + "VALUES(" + remote.songId() + ", 'track:9100', 'Saved track', 'Remote Artist', 'FOUND')");

            CanonicalMediaService.SongMetadata downloaded = song(
                    "Saved track", album, true, temporaryDirectory.resolve("saved-track.mp3").toString());
            downloaded = new CanonicalMediaService.SongMetadata(
                    downloaded.title(),
                    downloaded.trackOrder(),
                    downloaded.durationSeconds(),
                    List.of("Remote Artist", "Featured Artist"),
                    downloaded.album(),
                    true,
                    downloaded.filePath()
            );
            CanonicalMediaService.CanonicalSong promoted = service.ensureCanonicalSong(
                    connection, externalSong, externalAlbum, downloaded
            ).value();
            service.ensureCanonicalSong(connection, externalSong, externalAlbum, downloaded);

            assertEquals(remote.songId(), promoted.songId());
            assertEquals(remote.albumId(), promoted.albumId());
            assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM Song WHERE SongID=" + remote.songId()));
            assertEquals(1, scalarInt(connection, "SELECT IsLocal FROM Song WHERE SongID=" + remote.songId()));
            assertEquals(temporaryDirectory.resolve("saved-track.mp3").toString(),
                    scalarString(connection, "SELECT FilePath FROM Song WHERE SongID=" + remote.songId()));
            assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM SavedSong WHERE SongID=" + remote.songId()));
            assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM SavedRelease WHERE AlbumID=" + remote.albumId()));
            assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM SongsPlaylists WHERE SongID=" + remote.songId()));
            assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM SongLyrics WHERE SongID=" + remote.songId()));
            assertEquals(2, scalarInt(connection,
                    "SELECT COUNT(*) FROM SongArtist WHERE SongID=" + remote.songId()));
            connection.rollback();
        }
    }

    private Connection fresh(Path database) throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
        execute(connection, "PRAGMA foreign_keys=ON");
        DatabaseSchemaManager.initializeConnection(connection, database, () -> { });
        return connection;
    }

    private static void saveAfterBarrier(Path database, CountDownLatch ready, CountDownLatch start) {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
            configureConcurrentConnection(connection);
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Save start barrier timed out.");
            new SavedMediaService(() -> 1234L).saveSong(connection, 1);
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    private static int addRemoteTrackAfterBarrier(Path database,
                                                 long externalTrackId,
                                                 String title,
                                                 CountDownLatch ready,
                                                 CountDownLatch start) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
            configureConcurrentConnection(connection);
            connection.setAutoCommit(false);
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Playlist start barrier timed out.");

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE Playlist SET Title=Title WHERE PlaylistID=20");
            }
            CanonicalMediaService.AlbumMetadata album = album("Shared remote release", List.of("Remote Artist"));
            CanonicalizationResult<CanonicalMediaService.CanonicalSong> added =
                    new CanonicalMediaService().addRemoteSongToUserPlaylist(
                            connection,
                            20,
                            ExternalMediaId.deezer(externalTrackId),
                            ExternalMediaId.deezer(5000),
                            song(title, album, false, null)
                    );
            if (!added.isSuccess()) throw new SQLException("Unexpected remote identity conflict.");
            int position;
            try (var query = connection.prepareStatement(
                    "SELECT Position FROM SongsPlaylists WHERE PlaylistID=20 AND SongID=?")) {
                query.setLong(1, added.value().songId());
                try (ResultSet result = query.executeQuery()) {
                    if (!result.next()) throw new SQLException("Playlist member was not persisted.");
                    position = result.getInt(1);
                }
            }
            connection.commit();
            return position;
        }
    }

    private static void configureConcurrentConnection(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }
    }

    private static int scalarIntFromFile(Path database, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next(), sql);
            return result.getInt(1);
        }
    }

    private static CanonicalMediaService.AlbumMetadata album(String title, List<String> artists) {
        return new CanonicalMediaService.AlbumMetadata(title, "Pop", "album", "2020", 3, artists);
    }

    private static CanonicalMediaService.SongMetadata song(String title,
                                                           CanonicalMediaService.AlbumMetadata album,
                                                           boolean local,
                                                           String path) {
        return new CanonicalMediaService.SongMetadata(title, 1, 180, List.of("Remote Artist"), album, local, path);
    }

    private static int scalarInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next(), sql);
            return result.getInt(1);
        }
    }

    private static String scalarString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next(), sql);
            return result.getString(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
    }
}
