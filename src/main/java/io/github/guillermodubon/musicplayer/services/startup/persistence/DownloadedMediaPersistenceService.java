package io.github.guillermodubon.musicplayer.services.startup.persistence;

import javafx.util.Pair;
import io.github.guillermodubon.musicplayer.repository.DbConnectionManager;
import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.DeezerApiMetaData;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.services.startup.hydration.ModelHydrationService;
import io.github.guillermodubon.musicplayer.services.api.DeezerHttpClient;
import io.github.guillermodubon.musicplayer.services.startup.persistence.RemoteAlbumPromotionService;
import io.github.guillermodubon.musicplayer.utils.ArtistIdentity;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class DownloadedMediaPersistenceService {

    private static final String UNKNOWN = "Unknown";

    private final StartUpService owner;
    private final RemoteAlbumPromotionService remoteAlbumPromotionService;
    private final ModelHydrationService modelHydrationService;
    private final Set<Long> preparedAlbumIds = ConcurrentHashMap.newKeySet();

    public DownloadedMediaPersistenceService(
            StartUpService owner,
            RemoteAlbumPromotionService remoteAlbumPromotionService,
            ModelHydrationService modelHydrationService
    ) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.remoteAlbumPromotionService = Objects.requireNonNull(remoteAlbumPromotionService, "remoteAlbumPromotionService");
        this.modelHydrationService = Objects.requireNonNull(modelHydrationService, "modelHydrationService");
    }

    public void persist(DeezerApiMetaData metadata, File file) {
        if (metadata == null || file == null) return;

        /*
         * A fallback single can have no Deezer track id while still carrying
         * the cover URL that was rendered in PlayerMenu. Resolve that cover
         * before entering the transaction so the durable AlbumImage is never
         * dependent on the screen remaining open after the download.
         */
        ensureFallbackCoverBytes(metadata);

        long albumId = metadata.getAlbumId();
        boolean albumAlreadyPrepared = albumId > 0 && preparedAlbumIds.contains(albumId);
        boolean promoted = false;
        if (albumId > 0 && !albumAlreadyPrepared) {
            promoted = remoteAlbumPromotionService.promoteRemoteAlbumToLocalDynamic(metadata, file);
            if (promoted) {
                preparedAlbumIds.add(albumId);
            }
        }

        if (!promoted) {
            /*
             * Once an album structure has been hydrated, subsequent tracks only
             * need an atomic song/artist/path upsert. Re-hydrating the complete
             * album for every song is the main batch-download bottleneck.
             */
            persistMetadataSnapshot(metadata, file, !albumAlreadyPrepared);
            if (albumId > 0) {
                preparedAlbumIds.add(albumId);
            }
        }

        updateNoMetadataIndex(metadata, file);
    }

    private void persistMetadataSnapshot(DeezerApiMetaData metadata, File file, boolean hydrateAlbum) {
        synchronized (owner.getDbLock()) {
            PersistedIds ids;
            try {
                ids = DbConnectionManager.getInstance().runInTransaction(conn -> {
                    try {
                        applyPragmas(conn);
                        long genreId = ensureGenre(conn, metadata);
                        long albumId = ensureAlbum(conn, metadata, genreId);

                        ArtistIdentityBatch albumArtistIdentity =
                                resolveRealAlbumArtists(metadata);

                        Map<String, Long> albumArtists = ensureArtists(
                                conn,
                                albumArtistIdentity.names(),
                                albumArtistIdentity.ids()
                        );

                        insertArtistImages(
                                conn,
                                albumArtists,
                                albumArtistIdentity.names(),
                                metadata.getAlbumArtistsPortraitBytes()
                        );

                        Map<String, Long> songArtists = new LinkedHashMap<>(albumArtists);
                        Map<String, Long> contributorArtists = ensureArtists(
                                conn,
                                metadata.getSongContributorNames(),
                                metadata.getSongContributorIds()
                        );
                        insertArtistImages(
                                conn,
                                contributorArtists,
                                metadata.getSongContributorNames(),
                                metadata.getSongContributorsPortraitBytes()
                        );
                        songArtists.putAll(contributorArtists);

                        if (!albumArtists.isEmpty()) {
                            /*
                             * If this album was previously associated with the "Unknown"
                             * fallback, that association is removed before linking the
                             * actual artists.
                             */
                            removePlaceholderAlbumArtists(
                                    conn,
                                    albumId
                            );

                            linkArtists(
                                    conn,
                                    "AlbumArtist",
                                    "AlbumID",
                                    albumId,
                                    albumArtists.values()
                            );
                        }
                        insertAlbumCovers(conn, albumId, metadata.getAlbumCoverBytesList());

                        long songId = ensureSong(conn, metadata, albumId, file);
                        linkArtists(conn, "SongArtist", "SongID", songId, songArtists.values());
                        if (hydrateAlbum) {
                            modelHydrationService.loadModelsForAlbum(conn, albumId);
                        }
                        return new PersistedIds(albumId, songId);
                    } catch (Exception error) {
                        throw new RuntimeException(error);
                    }
                });
            } catch (Exception error) {
                throw new RuntimeException("Could not persist downloaded metadata", error);
            }

            owner.putTitleToPath(metadata.getSongName(), file.getAbsolutePath());
            for (String artist : allArtistNames(metadata)) {
                owner.putTitleToPath(artist + " " + metadata.getSongName(), file.getAbsolutePath());
            }
            replaceLegacyPlaceholderReferences(ids, metadata, file);
            if (!hydrateAlbum) {
                markCanonicalSongLocal(ids, metadata, file);
            }
            System.out.println("DownloadedMediaPersistenceService: persisted album=" + ids.albumId()
                    + " song=" + ids.songId() + " path=" + file.getAbsolutePath());
        }
    }

    private void markCanonicalSongLocal(PersistedIds ids, DeezerApiMetaData metadata, File file) {
        if (ids == null || metadata == null || file == null) return;
        Song canonical = null;
        synchronized (owner.getSongs()) {
            for (Song song : owner.getSongs()) {
                if (!matchesPersistedSong(song, ids, metadata)) continue;
                song.setLocal(true);
                song.setFilePath(file.getAbsolutePath());
                canonical = song;
                break;
            }
        }

        if (canonical == null) return;
        Album album = canonical.getAlbum();
        if (album == null || album.getSongList() == null) return;
        for (int i = 0; i < album.getSongList().size(); i++) {
            Song song = album.getSongList().get(i);
            if (matchesPersistedSong(song, ids, metadata)) {
                album.getSongList().set(i, canonical);
                break;
            }
        }
    }

    private boolean matchesPersistedSong(Song song, PersistedIds ids, DeezerApiMetaData metadata) {
        if (song == null || ids == null) return false;
        if (ids.songId() > 0 && song.getSongID() > 0) {
            return song.getSongID() == ids.songId();
        }
        if (song.getAlbum() == null || song.getAlbum().getAlbumID() != ids.albumId()) return false;
        return song.getTitle() != null
                && metadata.getSongName() != null
                && song.getTitle().equalsIgnoreCase(metadata.getSongName());
    }

    private long ensureGenre(Connection conn, DeezerApiMetaData metadata) throws Exception {
        String genre = nonBlank(metadata.getGenre(), UNKNOWN);
        long preferredId = Math.max(0, metadata.getAlbumGenreId());
        Long existing = selectIdByName(conn, "Genre", "GenreID", genre);
        if (existing != null) return existing;

        if (preferredId > 0) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO Genre(GenreID, Name) VALUES(?, ?)")) {
                ps.setLong(1, preferredId);
                ps.setString(2, genre);
                ps.executeUpdate();
            }
        }
        if (selectIdByName(conn, "Genre", "GenreID", genre) == null) {
            try (PreparedStatement ps = conn.prepareStatement("INSERT OR IGNORE INTO Genre(Name) VALUES(?)")) {
                ps.setString(1, genre);
                ps.executeUpdate();
            }
        }
        return requireIdByName(conn, "Genre", "GenreID", genre);
    }

    private long ensureAlbum(Connection conn, DeezerApiMetaData metadata, long genreId) throws Exception {
        String albumName = nonBlank(metadata.getAlbumName(), metadata.getSongName());
        long preferredId = Math.max(0, metadata.getAlbumId());
        Long existingByName = selectIdByName(conn, "Album", "AlbumID", albumName);
        long albumId = existingByName == null ? 0 : existingByName;

        if (albumId <= 0 && preferredId > 0) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO Album(AlbumID, GenreID, Name, RecordType, ReleaseDate, NumberOfTracks) VALUES(?, ?, ?, ?, ?, ?)")) {
                setAlbumValues(ps, 1, preferredId, genreId, albumName, metadata);
                ps.executeUpdate();
            }
            Long insertedByName = selectIdByName(conn, "Album", "AlbumID", albumName);
            albumId = insertedByName == null ? 0 : insertedByName;
        }
        if (albumId <= 0) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO Album(GenreID, Name, RecordType, ReleaseDate, NumberOfTracks) VALUES(?, ?, ?, ?, ?)")) {
                ps.setLong(1, genreId);
                ps.setString(2, albumName);
                ps.setString(3, normalizedRecordType(metadata));
                setNullableText(ps, 4, metadata.getAlbumReleaseDate());
                ps.setInt(5, Math.max(1, metadata.getNumberOfTracks()));
                ps.executeUpdate();
            }
        }
        albumId = requireIdByName(conn, "Album", "AlbumID", albumName);

        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE Album
                   SET GenreID = ?,
                       RecordType = ?,
                       ReleaseDate = CASE WHEN ? IS NOT NULL AND ? <> '' THEN ? ELSE ReleaseDate END,
                       NumberOfTracks = CASE WHEN NumberOfTracks < ? THEN ? ELSE NumberOfTracks END
                 WHERE AlbumID = ?
                """)) {
            ps.setLong(1, genreId);
            ps.setString(2, normalizedRecordType(metadata));
            setNullableText(ps, 3, metadata.getAlbumReleaseDate());
            ps.setString(4, metadata.getAlbumReleaseDate() == null ? "" : metadata.getAlbumReleaseDate());
            setNullableText(ps, 5, metadata.getAlbumReleaseDate());
            int count = Math.max(1, metadata.getNumberOfTracks());
            ps.setInt(6, count);
            ps.setInt(7, count);
            ps.setLong(8, albumId);
            ps.executeUpdate();
        }
        return albumId;
    }

    private void setAlbumValues(
            PreparedStatement ps,
            int offset,
            long albumId,
            long genreId,
            String albumName,
            DeezerApiMetaData metadata
    ) throws Exception {
        ps.setLong(offset, albumId);
        ps.setLong(offset + 1, genreId);
        ps.setString(offset + 2, albumName);
        ps.setString(offset + 3, normalizedRecordType(metadata));
        setNullableText(ps, offset + 4, metadata.getAlbumReleaseDate());
        ps.setInt(offset + 5, Math.max(1, metadata.getNumberOfTracks()));
    }

    private Map<String, Long> ensureArtists(Connection conn, List<String> names, List<Long> ids) throws Exception {
        Map<String, Long> result = new LinkedHashMap<>();
        if (names == null) return result;

        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (name == null || name.isBlank()) continue;
            String cleanName = name.trim();
            if (ArtistIdentity.isVariousArtists(cleanName)) continue;
            long preferredId = ids != null && i < ids.size() && ids.get(i) != null ? Math.max(0, ids.get(i)) : 0;
            Long existing = selectIdByName(conn, "Artist", "ArtistID", cleanName);
            if (existing == null && preferredId > 0) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT OR IGNORE INTO Artist(ArtistID, Name, Biography) VALUES(?, ?, NULL)")) {
                    ps.setLong(1, preferredId);
                    ps.setString(2, cleanName);
                    ps.executeUpdate();
                }
            }
            if (selectIdByName(conn, "Artist", "ArtistID", cleanName) == null) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT OR IGNORE INTO Artist(Name, Biography) VALUES(?, NULL)")) {
                    ps.setString(1, cleanName);
                    ps.executeUpdate();
                }
            }
            result.put(cleanName, requireIdByName(conn, "Artist", "ArtistID", cleanName));
        }
        return result;
    }

    private long ensureSong(Connection conn, DeezerApiMetaData metadata, long albumId, File file) throws Exception {
        long songId = findExistingSong(conn, metadata, albumId, file);
        if (songId > 0) {
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE Song
                       SET Title = ?, Album = ?, TrackOrder = ?, IsLocal = 1, FilePath = ?
                     WHERE SongID = ?
                    """)) {
                ps.setString(1, metadata.getSongName());
                ps.setLong(2, albumId);
                ps.setInt(3, Math.max(1, metadata.getTrackOrder()));
                ps.setString(4, file.getAbsolutePath());
                ps.setLong(5, songId);
                ps.executeUpdate();
            }
            migrateLegacyPlaceholderSong(conn, metadata, albumId, songId);
            return songId;
        }

        if (metadata.getTrackId() > 0) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO Song(SongID, Title, Album, TrackOrder, IsLocal, FilePath) VALUES(?, ?, ?, ?, 1, ?)")) {
                ps.setLong(1, metadata.getTrackId());
                ps.setString(2, metadata.getSongName());
                ps.setLong(3, albumId);
                ps.setInt(4, Math.max(1, metadata.getTrackOrder()));
                ps.setString(5, file.getAbsolutePath());
                ps.executeUpdate();
            }
            migrateLegacyPlaceholderSong(conn, metadata, albumId, metadata.getTrackId());
            return metadata.getTrackId();
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO Song(Title, Album, TrackOrder, IsLocal, FilePath) VALUES(?, ?, ?, 1, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, metadata.getSongName());
            ps.setLong(2, albumId);
            ps.setInt(3, Math.max(1, metadata.getTrackOrder()));
            ps.setString(4, file.getAbsolutePath());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long generatedId = rs.getLong(1);
                    migrateLegacyPlaceholderSong(conn, metadata, albumId, generatedId);
                    return generatedId;
                }
            }
        }
        throw new IllegalStateException("Could not create local song row for " + metadata.getSongName());
    }

    /**
     * Older scans can leave one placeholder row with SongID=0. Promote its
     * relations to the generated local id before removing it, otherwise the
     * incomplete row can win the next in-memory deduplication pass.
     */
    private void migrateLegacyPlaceholderSong(
            Connection conn,
            DeezerApiMetaData metadata,
            long albumId,
            long generatedId
    ) throws Exception {
        if (conn == null || metadata == null || generatedId <= 0) return;

        String title = metadata.getSongName();
        if (title == null || title.isBlank()) return;

        try (PreparedStatement exists = conn.prepareStatement(
                "SELECT 1 FROM Song WHERE SongID = 0 AND Album = ? AND lower(Title) = lower(?) LIMIT 1")) {
            exists.setLong(1, albumId);
            exists.setString(2, title);
            try (ResultSet rs = exists.executeQuery()) {
                if (!rs.next()) return;
            }
        }

        try (PreparedStatement copyArtists = conn.prepareStatement(
                "INSERT OR IGNORE INTO SongArtist(SongID, ArtistID) SELECT ?, ArtistID FROM SongArtist WHERE SongID = 0")) {
            copyArtists.setLong(1, generatedId);
            copyArtists.executeUpdate();
        }
        try (PreparedStatement copyPlaylistLinks = conn.prepareStatement(
                "INSERT OR IGNORE INTO SongsPlaylists(SongID, PlaylistID, Position, CustomPosition, CreatedAt) "
                        + "SELECT ?, PlaylistID, Position, CustomPosition, CreatedAt FROM SongsPlaylists WHERE SongID = 0")) {
            copyPlaylistLinks.setLong(1, generatedId);
            copyPlaylistLinks.executeUpdate();
        }
        try (Statement statement = conn.createStatement()) {
            statement.executeUpdate("DELETE FROM SongArtist WHERE SongID = 0");
            statement.executeUpdate("DELETE FROM SongsPlaylists WHERE SongID = 0");
        }
        try (PreparedStatement deleteSong = conn.prepareStatement(
                "DELETE FROM Song WHERE SongID = 0 AND Album = ? AND lower(Title) = lower(?)")) {
            deleteSong.setLong(1, albumId);
            deleteSong.setString(2, title);
            deleteSong.executeUpdate();
        }
    }

    private void replaceLegacyPlaceholderReferences(
            PersistedIds ids,
            DeezerApiMetaData metadata,
            File file
    ) {
        if (ids == null || ids.songId() <= 0 || metadata == null) return;

        Song canonical = null;
        synchronized (owner.getSongs()) {
            for (Song song : owner.getSongs()) {
                if (song != null && song.getSongID() == ids.songId()) {
                    song.setLocal(true);
                    if (file != null) song.setFilePath(file.getAbsolutePath());
                    canonical = song;
                    break;
                }
            }

            if (canonical == null) return;

            owner.getSongs().removeIf(song -> isLegacyPlaceholder(song, ids, metadata));
        }

        synchronized (owner.getAlbums()) {
            for (Album album : owner.getAlbums()) {
                if (album == null || album.getSongList() == null) continue;
                for (int index = 0; index < album.getSongList().size(); index++) {
                    Song song = album.getSongList().get(index);
                    if (isLegacyPlaceholder(song, ids, metadata)) {
                        album.getSongList().set(index, canonical);
                    }
                }
            }
        }

        synchronized (owner.getPlaylists()) {
            for (Playlist playlist : owner.getPlaylists()) {
                if (playlist == null || playlist.getSongList() == null) continue;
                for (int index = 0; index < playlist.getSongList().size(); index++) {
                    Song song = playlist.getSongList().get(index);
                    if (isLegacyPlaceholder(song, ids, metadata)) {
                        playlist.getSongList().set(index, canonical);
                    }
                }
            }
        }
    }

    private boolean isLegacyPlaceholder(
            Song song,
            PersistedIds ids,
            DeezerApiMetaData metadata
    ) {
        if (song == null || song.getSongID() != 0 || song.getTitle() == null || metadata.getSongName() == null) {
            return false;
        }
        if (!song.getTitle().equalsIgnoreCase(metadata.getSongName())) return false;
        return song.getAlbum() == null
                || song.getAlbum().getAlbumID() <= 0
                || song.getAlbum().getAlbumID() == ids.albumId();
    }

    private long findExistingSong(Connection conn, DeezerApiMetaData metadata, long albumId, File file) throws Exception {
        if (metadata.getTrackId() > 0) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT SongID FROM Song WHERE SongID = ? LIMIT 1")) {
                ps.setLong(1, metadata.getTrackId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getLong(1);
                }
            }
        }

        try (PreparedStatement ps = conn.prepareStatement("SELECT SongID FROM Song WHERE FilePath = ? LIMIT 1")) {
            ps.setString(1, file.getAbsolutePath());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT SongID FROM Song WHERE IsLocal = 1 AND Album = ? AND lower(Title) = lower(?) LIMIT 1")) {
            ps.setLong(1, albumId);
            ps.setString(2, metadata.getSongName());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return 0;
    }

    private void linkArtists(Connection conn, String table, String ownerColumn, long ownerId, Iterable<Long> artistIds) throws Exception {
        String sql = "INSERT OR IGNORE INTO " + table + "(" + ownerColumn + ", ArtistID) VALUES(?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Long artistId : artistIds) {
                if (artistId == null || artistId <= 0) continue;
                ps.setLong(1, ownerId);
                ps.setLong(2, artistId);
                ps.executeUpdate();
            }
        }
    }

    private void insertAlbumCovers(Connection conn, long albumId, List<byte[]> covers) throws Exception {
        if (covers == null || covers.isEmpty()) return;
        String[] types = {"small", "medium", "xl"};
        for (int i = 0; i < covers.size() && i < types.length; i++) {
            byte[] cover = covers.get(i);
            if (cover == null || cover.length == 0) continue;
            try (PreparedStatement check = conn.prepareStatement(
                    "SELECT 1 FROM AlbumImage WHERE AlbumID = ? AND ImageType = ? LIMIT 1")) {
                check.setLong(1, albumId);
                check.setString(2, types[i]);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) continue;
                }
            }
            try (PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO AlbumImage(AlbumID, ImageType, ImageData) VALUES(?, ?, ?)")) {
                insert.setLong(1, albumId);
                insert.setString(2, types[i]);
                insert.setBytes(3, cover);
                insert.executeUpdate();
            }
        }
    }

    private void ensureFallbackCoverBytes(DeezerApiMetaData metadata) {
        if (metadata == null
                || metadata.getTrackId() > 0
                || hasImageBytes(metadata.getAlbumCoverBytesList())) return;

        String coverUrl = metadata.getAlbumCoverUrl();
        if (coverUrl == null || coverUrl.isBlank()) return;

        try {
            byte[] cover = DeezerHttpClient.downloadUrlToBytesStatic(coverUrl.trim());
            if (cover != null && cover.length > 0) {
                metadata.setAlbumCoverBytesList(List.of(cover));
            }
        } catch (Exception ignored) {
            // A missing cover must never prevent persistence of the song itself.
        }
    }

    private boolean hasImageBytes(List<byte[]> images) {
        if (images == null || images.isEmpty()) return false;
        return images.stream().anyMatch(image -> image != null && image.length > 0);
    }

    private void insertArtistImages(
            Connection conn,
            Map<String, Long> artists,
            List<String> names,
            List<List<byte[]>> portraits
    ) throws Exception {
        if (artists == null || artists.isEmpty() || names == null || portraits == null) return;
        String[] types = {"small", "medium", "big"};
        for (int i = 0; i < names.size() && i < portraits.size(); i++) {
            String name = names.get(i);
            Long artistId = name == null ? null : artists.get(name.trim());
            List<byte[]> artistPortraits = portraits.get(i);
            if (artistId == null || artistId <= 0 || artistPortraits == null) continue;

            for (int imageIndex = 0; imageIndex < artistPortraits.size() && imageIndex < types.length; imageIndex++) {
                byte[] image = artistPortraits.get(imageIndex);
                if (image == null || image.length == 0) continue;
                try (PreparedStatement check = conn.prepareStatement(
                        "SELECT 1 FROM ArtistImage WHERE ArtistID = ? AND ImageType = ? LIMIT 1")) {
                    check.setLong(1, artistId);
                    check.setString(2, types[imageIndex]);
                    try (ResultSet rs = check.executeQuery()) {
                        if (rs.next()) continue;
                    }
                }
                try (PreparedStatement insert = conn.prepareStatement(
                        "INSERT INTO ArtistImage(ArtistID, ImageType, ImageData) VALUES(?, ?, ?)")) {
                    insert.setLong(1, artistId);
                    insert.setString(2, types[imageIndex]);
                    insert.setBytes(3, image);
                    insert.executeUpdate();
                }
            }
        }
    }

    private void updateNoMetadataIndex(DeezerApiMetaData metadata, File file) {
        synchronized (owner.noMetadataSongs) {
            String path = file.getAbsolutePath();
            String title = metadata.getSongName();
            owner.noMetadataSongs.removeIf(pair -> sameNoMetadataEntry(pair, title, path));
            if (metadata.getTrackId() <= 0) {
                owner.noMetadataSongs.add(new Pair<>(title, path));
            }
        }
    }

    private static boolean sameNoMetadataEntry(Pair<String, String> pair, String title, String path) {
        if (pair == null) return false;
        return (pair.getKey() != null && title != null && pair.getKey().equalsIgnoreCase(title))
                || (pair.getValue() != null && path != null && pair.getValue().equalsIgnoreCase(path));
    }

    private static Set<String> allArtistNames(DeezerApiMetaData metadata) {
        Set<String> names = new LinkedHashSet<>();
        if (metadata.getAlbumArtistNames() != null) names.addAll(metadata.getAlbumArtistNames());
        if (metadata.getSongContributorNames() != null) names.addAll(metadata.getSongContributorNames());
        names.removeIf(name -> name == null || name.isBlank());
        return names;
    }

    private static Long selectIdByName(Connection conn, String table, String idColumn, String name) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + idColumn + " FROM " + table + " WHERE lower(Name) = lower(?) LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private static long requireIdByName(Connection conn, String table, String idColumn, String name) throws Exception {
        Long id = selectIdByName(conn, table, idColumn, name);
        if (id == null || id <= 0) throw new IllegalStateException("Missing " + table + " row for " + name);
        return id;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String normalizedRecordType(DeezerApiMetaData metadata) {
        if (metadata == null) return "single";
        String recordType = nonBlank(metadata.getRecordType(), metadata.getNumberOfTracks() > 1 ? "album" : "single");
        return metadata.getNumberOfTracks() > 1 && "single".equalsIgnoreCase(recordType)
                ? "album"
                : recordType;
    }

    private static void setNullableText(PreparedStatement ps, int index, String value) throws Exception {
        if (value == null || value.isBlank()) ps.setNull(index, Types.VARCHAR);
        else ps.setString(index, value.trim());
    }

    private static void applyPragmas(Connection conn) {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA busy_timeout = 5000");
            st.execute("PRAGMA journal_mode = WAL");
        } catch (Exception ignored) {
        }
    }

    private record PersistedIds(long albumId, long songId) {
    }

    private ArtistIdentityBatch resolveRealAlbumArtists(
            DeezerApiMetaData metadata
    ) {
        if (metadata == null) {
            return ArtistIdentityBatch.empty();
        }

        List<String> rawNames =
                metadata.getAlbumArtistNames();

        List<Long> rawIds =
                metadata.getAlbumArtistIds();

        if (rawNames == null || rawNames.isEmpty()) {
            return ArtistIdentityBatch.empty();
        }

        List<String> names =
                new java.util.ArrayList<>();

        List<Long> ids =
                new java.util.ArrayList<>();

        for (int index = 0;
             index < rawNames.size();
             index++) {

            String name = rawNames.get(index);

            if (!isRealArtistName(name)) {
                continue;
            }

            names.add(name.trim());

            Long id = rawIds != null
                    && index < rawIds.size()
                    ? rawIds.get(index)
                    : null;

            ids.add(
                    id != null && id > 0
                            ? id
                            : 0L
            );
        }

        return new ArtistIdentityBatch(
                List.copyOf(names),
                List.copyOf(ids)
        );
    }

    private boolean isRealArtistName(
            String artistName
    ) {
        if (artistName == null
                || artistName.isBlank()) {
            return false;
        }

        String normalized =
                artistName.trim()
                        .toLowerCase(
                                java.util.Locale.ROOT
                        );

        return !ArtistIdentity.isVariousArtists(artistName)
                && !normalized.equals("unknown")
                && !normalized.equals("unknown artist")
                && !normalized.equals("desconocido");
    }

    private void removePlaceholderAlbumArtists(
            Connection connection,
            long albumId
    ) throws Exception {
        if (connection == null || albumId <= 0) {
            return;
        }

        String sql = """
            DELETE FROM AlbumArtist
             WHERE AlbumID = ?
               AND ArtistID IN (
                    SELECT ArtistID
                      FROM Artist
                     WHERE lower(trim(Name)) IN (
                         'unknown',
                         'unknown artist',
                         'desconocido'
                     )
               )
            """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setLong(
                    1,
                    albumId
            );

            statement.executeUpdate();
        }
    }

    private record ArtistIdentityBatch(
            List<String> names,
            List<Long> ids
    ) {
        private static ArtistIdentityBatch empty() {
            return new ArtistIdentityBatch(
                    List.of(),
                    List.of()
            );
        }
    }
}
