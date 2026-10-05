package io.github.guillermodubon.musicplayer.repository.identity;

import io.github.guillermodubon.musicplayer.repository.identity.ExternalIdentityDao.EntityType;
import io.github.guillermodubon.musicplayer.repository.library.SavedMediaService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeSet;

/** Transactional canonicalization for new provider-backed persistence paths. */
public final class CanonicalMediaService {

    private final SavedMediaService savedMediaService;

    public CanonicalMediaService() {
        this(new SavedMediaService());
    }

    public CanonicalMediaService(SavedMediaService savedMediaService) {
        this.savedMediaService = Objects.requireNonNull(savedMediaService, "savedMediaService");
    }

    public CanonicalizationResult<Long> ensureCanonicalAlbum(Connection connection,
                                                              ExternalMediaId externalId,
                                                              AlbumMetadata metadata) throws SQLException {
        requireTransaction(connection);
        Savepoint savepoint = connection.setSavepoint();
        CanonicalizationResult<Long> result = ensureAlbum(connection, externalId, metadata);
        finishSavepoint(connection, savepoint, result.isSuccess());
        return result;
    }

    public CanonicalizationResult<Long> ensureCanonicalPlaylist(Connection connection,
                                                                 ExternalMediaId externalId,
                                                                 PlaylistMetadata metadata) throws SQLException {
        requireTransaction(connection);
        Savepoint savepoint = connection.setSavepoint();
        CanonicalizationResult<Long> result = ensurePlaylist(connection, externalId, metadata);
        finishSavepoint(connection, savepoint, result.isSuccess());
        return result;
    }

    public CanonicalizationResult<CanonicalSong> ensureCanonicalSong(Connection connection,
                                                                     ExternalMediaId externalSongId,
                                                                     ExternalMediaId externalAlbumId,
                                                                     SongMetadata metadata) throws SQLException {
        requireTransaction(connection);
        Savepoint savepoint = connection.setSavepoint();
        CanonicalizationResult<CanonicalSong> result = ensureSong(
                connection, externalSongId, externalAlbumId, metadata
        );
        finishSavepoint(connection, savepoint, result.isSuccess());
        return result;
    }

    public CanonicalizationResult<CanonicalSong> addRemoteSongToUserPlaylist(
            Connection connection,
            long userPlaylistId,
            ExternalMediaId externalSongId,
            ExternalMediaId externalAlbumId,
            SongMetadata metadata
    ) throws SQLException {
        requireTransaction(connection);
        Savepoint savepoint = connection.setSavepoint();
        if (!isUserPlaylist(connection, userPlaylistId)) {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
            throw new SQLException("Remote tracks can only be appended to a user-owned playlist.");
        }

        CanonicalizationResult<CanonicalSong> song = ensureSong(
                connection, externalSongId, externalAlbumId, metadata
        );
        if (!song.isSuccess()) {
            finishSavepoint(connection, savepoint, false);
            return song;
        }

        CanonicalSong canonical = song.value();
        int position = nextPosition(connection, userPlaylistId, "Position");
        int customPosition = nextPosition(connection, userPlaylistId, "CustomPosition");
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO SongsPlaylists(SongID, PlaylistID, Position, CustomPosition)
                VALUES(?, ?, ?, ?)
                ON CONFLICT(SongID, PlaylistID) DO NOTHING
                """)) {
            insert.setLong(1, canonical.songId());
            insert.setLong(2, userPlaylistId);
            insert.setInt(3, position);
            insert.setInt(4, customPosition);
            insert.executeUpdate();
        }
        finishSavepoint(connection, savepoint, true);
        return song;
    }

    private CanonicalizationResult<Long> ensureAlbum(Connection connection,
                                                     ExternalMediaId externalId,
                                                     AlbumMetadata metadata) throws SQLException {
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(metadata, "metadata");
        if (!"DEEZER".equals(externalId.provider())) {
            throw new IllegalArgumentException("Only verified Deezer releases can be persisted by this boundary.");
        }
        ExternalIdentityDao identities = new ExternalIdentityDao(connection);
        OptionalLong mapped = identities.resolve(EntityType.ALBUM, externalId);
        if (mapped.isPresent()) {
            if (!albumMetadataMatches(connection, mapped.getAsLong(), metadata)) {
                return conflict(IdentityResolution.Reason.CONTRADICTORY_METADATA, mapped.getAsLong());
            }
            persistAlbumArtists(connection, mapped.getAsLong(), metadata.artistNames());
            return CanonicalizationResult.success(mapped.getAsLong(), false);
        }

        long candidate = externalId.numericCandidateId();
        if (candidate > 0 && legacyAlbumMatches(connection, candidate, metadata)) {
            IdentityResolution attached = identities.attach(EntityType.ALBUM, externalId, candidate);
            if (attached instanceof IdentityResolution.Conflict conflict) {
                return CanonicalizationResult.conflict(conflict);
            }
            persistAlbumArtists(connection, candidate, metadata.artistNames());
            return CanonicalizationResult.success(candidate, false);
        }

        long genreId = ensureGenre(connection, metadata.genreName());
        long albumId = insertAlbum(connection, metadata, genreId);
        persistAlbumArtists(connection, albumId, metadata.artistNames());
        IdentityResolution attached = identities.attach(EntityType.ALBUM, externalId, albumId);
        if (attached instanceof IdentityResolution.Conflict conflict) {
            return CanonicalizationResult.conflict(conflict);
        }
        return CanonicalizationResult.success(albumId, true);
    }

    private CanonicalizationResult<Long> ensurePlaylist(Connection connection,
                                                        ExternalMediaId externalId,
                                                        PlaylistMetadata metadata) throws SQLException {
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(metadata, "metadata");
        if (!"DEEZER".equals(externalId.provider())) {
            throw new IllegalArgumentException("Only verified Deezer playlists can be persisted by this boundary.");
        }
        ExternalIdentityDao identities = new ExternalIdentityDao(connection);
        OptionalLong mapped = identities.resolve(EntityType.PLAYLIST, externalId);
        if (mapped.isPresent()) {
            if (!sameName(connection, "Playlist", "PlaylistID", mapped.getAsLong(), metadata.title())) {
                return conflict(IdentityResolution.Reason.CONTRADICTORY_METADATA, mapped.getAsLong());
            }
            return CanonicalizationResult.success(mapped.getAsLong(), false);
        }

        long playlistId;
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO Playlist(Title, Author, Description, Origin)
                VALUES(?, ?, ?, 'DEEZER')
                """, Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, metadata.title().trim());
            insert.setString(2, Objects.requireNonNullElse(metadata.author(), ""));
            insert.setString(3, metadata.description());
            insert.executeUpdate();
            playlistId = generatedId(connection, insert, "PlaylistID");
        }
        IdentityResolution attached = identities.attach(EntityType.PLAYLIST, externalId, playlistId);
        if (attached instanceof IdentityResolution.Conflict conflict) {
            return CanonicalizationResult.conflict(conflict);
        }
        return CanonicalizationResult.success(playlistId, true);
    }

    private CanonicalizationResult<CanonicalSong> ensureSong(Connection connection,
                                                             ExternalMediaId externalSongId,
                                                             ExternalMediaId externalAlbumId,
                                                             SongMetadata metadata) throws SQLException {
        Objects.requireNonNull(externalSongId, "externalSongId");
        Objects.requireNonNull(metadata, "metadata");

        ExternalIdentityDao identities = new ExternalIdentityDao(connection);
        OptionalLong mapped = identities.resolve(EntityType.SONG, externalSongId);
        CanonicalizationResult<Long> albumResult;
        if (externalAlbumId != null && metadata.album() != null) {
            albumResult = ensureAlbum(connection, externalAlbumId, metadata.album());
        } else if (mapped.isPresent()) {
            long existingAlbumId = findSongAlbum(connection, mapped.getAsLong());
            albumResult = existingAlbumId > 0
                    ? CanonicalizationResult.success(existingAlbumId, false)
                    : conflict(IdentityResolution.Reason.CONTRADICTORY_METADATA, mapped.getAsLong());
        } else {
            long placeholderId = ensurePlaceholderAlbum(connection, externalSongId);
            albumResult = CanonicalizationResult.success(placeholderId, false);
        }
        if (!albumResult.isSuccess()) {
            return CanonicalizationResult.conflict(albumResult.conflict());
        }
        long albumId = albumResult.value();

        if (mapped.isPresent()) {
            long songId = mapped.getAsLong();
            if (!sameName(connection, "Song", "SongID", songId, metadata.title())) {
                return conflict(IdentityResolution.Reason.CONTRADICTORY_METADATA, songId);
            }
            if (!songArtistsMatch(connection, songId, metadata.artistNames())) {
                return conflict(IdentityResolution.Reason.CONTRADICTORY_METADATA, songId);
            }
            long currentAlbumId = findSongAlbum(connection, songId);
            if (currentAlbumId > 0 && currentAlbumId != albumId
                    && !isPlaceholderAlbum(connection, currentAlbumId)) {
                Optional<ExternalMediaId> currentExternalAlbum = identities.resolveExternalIdentity(
                        EntityType.ALBUM, externalSongId.provider(), currentAlbumId
                );
                if (externalAlbumId == null || currentExternalAlbum.isEmpty()
                        || !currentExternalAlbum.get().equals(externalAlbumId)) {
                    return conflict(IdentityResolution.Reason.CONTRADICTORY_METADATA, songId);
                }
            }
            promoteLocalState(connection, songId, albumId, metadata);
            persistSongArtists(connection, songId, metadata.artistNames());
            if (metadata.local()) savedMediaService.saveDownloadedSongAndRelease(connection, songId, albumId);
            return CanonicalizationResult.success(new CanonicalSong(songId, albumId), false);
        }

        long candidate = externalSongId.numericCandidateId();
        if (candidate > 0 && legacySongMatches(connection, candidate, albumId, metadata)) {
            IdentityResolution attached = identities.attach(EntityType.SONG, externalSongId, candidate);
            if (attached instanceof IdentityResolution.Conflict conflict) {
                return CanonicalizationResult.conflict(conflict);
            }
            promoteLocalState(connection, candidate, albumId, metadata);
            persistSongArtists(connection, candidate, metadata.artistNames());
            if (metadata.local()) savedMediaService.saveDownloadedSongAndRelease(connection, candidate, albumId);
            return CanonicalizationResult.success(new CanonicalSong(candidate, albumId), false);
        }

        long songId;
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO Song(Title, Album, TrackOrder, IsLocal, FilePath, DurationSeconds)
                VALUES(?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, metadata.title().trim());
            insert.setLong(2, albumId);
            insert.setInt(3, Math.max(0, metadata.trackOrder()));
            insert.setInt(4, metadata.local() ? 1 : 0);
            insert.setString(5, metadata.local() ? metadata.filePath() : null);
            insert.setInt(6, Math.max(0, metadata.durationSeconds()));
            insert.executeUpdate();
            songId = generatedId(connection, insert, "SongID");
        }
        persistSongArtists(connection, songId, metadata.artistNames());
        IdentityResolution attached = identities.attach(EntityType.SONG, externalSongId, songId);
        if (attached instanceof IdentityResolution.Conflict conflict) {
            return CanonicalizationResult.conflict(conflict);
        }
        if (metadata.local()) savedMediaService.saveDownloadedSongAndRelease(connection, songId, albumId);
        return CanonicalizationResult.success(new CanonicalSong(songId, albumId), true);
    }

    private long ensurePlaceholderAlbum(Connection connection, ExternalMediaId songId) throws SQLException {
        String name = "Unknown release [" + songId.provider() + ":" + songId.externalId() + "]";
        try (PreparedStatement lookup = connection.prepareStatement("SELECT AlbumID FROM Album WHERE Name = ? LIMIT 1")) {
            lookup.setString(1, name);
            try (ResultSet result = lookup.executeQuery()) {
                if (result.next()) return result.getLong(1);
            }
        }
        AlbumMetadata placeholder = new AlbumMetadata(name, "Unknown", "album", null, 0, List.of());
        return insertAlbum(connection, placeholder, ensureGenre(connection, "Unknown"));
    }

    private boolean legacyAlbumMatches(Connection connection, long candidate, AlbumMetadata metadata) throws SQLException {
        if (metadata.artistNames().isEmpty() && blank(metadata.releaseDate())) return false;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT Name, RecordType, ReleaseDate FROM Album WHERE AlbumID = ?")) {
            statement.setLong(1, candidate);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !normalize(result.getString("Name")).equals(normalize(metadata.name()))) return false;
                if (!blank(metadata.recordType())
                        && !normalize(result.getString("RecordType")).equals(normalize(metadata.recordType()))) return false;
                if (!blank(metadata.releaseDate())
                        && !normalize(result.getString("ReleaseDate")).equals(normalize(metadata.releaseDate()))) return false;
            }
        }
        return metadata.artistNames().isEmpty()
                || normalizedArtists(connection, "AlbumArtist", "AlbumID", candidate).equals(normalized(metadata.artistNames()));
    }

    private boolean legacySongMatches(Connection connection,
                                      long candidate,
                                      long albumId,
                                      SongMetadata metadata) throws SQLException {
        if (metadata.artistNames().isEmpty()) return false;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT Title, Album FROM Song WHERE SongID = ?")) {
            statement.setLong(1, candidate);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()
                        || !normalize(result.getString("Title")).equals(normalize(metadata.title()))
                        || result.getLong("Album") != albumId) return false;
            }
        }
        return normalizedArtists(connection, "SongArtist", "SongID", candidate)
                .equals(normalized(metadata.artistNames()));
    }

    private long insertAlbum(Connection connection, AlbumMetadata metadata, long genreId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO Album(GenreID, Name, RecordType, ReleaseDate, NumberOfTracks)
                VALUES(?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            insert.setLong(1, genreId);
            insert.setString(2, metadata.name().trim());
            insert.setString(3, Objects.requireNonNullElse(metadata.recordType(), "album"));
            insert.setString(4, metadata.releaseDate());
            insert.setInt(5, Math.max(0, metadata.numberOfTracks()));
            insert.executeUpdate();
            return generatedId(connection, insert, "AlbumID");
        }
    }

    private long ensureGenre(Connection connection, String genreName) throws SQLException {
        String name = blank(genreName) ? "Unknown" : genreName.trim();
        OptionalLong existing = findIdByNormalizedName(connection, "Genre", "GenreID", name, "Genre");
        if (existing.isPresent()) return existing.getAsLong();
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO Genre(Name) VALUES(?)", Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, name);
            insert.executeUpdate();
            return generatedId(connection, insert, "GenreID");
        } catch (SQLException insertionFailure) {
            OptionalLong concurrent = findIdByNormalizedName(connection, "Genre", "GenreID", name, "Genre");
            if (concurrent.isPresent()) return concurrent.getAsLong();
            throw insertionFailure;
        }
    }

    private OptionalLong findIdByNormalizedName(Connection connection,
                                                String table,
                                                String idColumn,
                                                String name,
                                                String entity) throws SQLException {
        String exactSql = "SELECT " + idColumn + " FROM " + table + " WHERE Name = ? ORDER BY " + idColumn;
        try (PreparedStatement exactLookup = connection.prepareStatement(exactSql)) {
            exactLookup.setString(1, name.trim());
            try (ResultSet result = exactLookup.executeQuery()) {
                if (result.next()) return OptionalLong.of(result.getLong(idColumn));
            }
        }

        String sql = "SELECT " + idColumn + ", Name FROM " + table + " ORDER BY " + idColumn;
        long matchingId = -1;
        try (Statement lookup = connection.createStatement(); ResultSet result = lookup.executeQuery(sql)) {
            while (result.next()) {
                if (!normalize(result.getString("Name")).equals(normalize(name))) continue;
                if (matchingId > 0) throw new SQLException(entity + " metadata is ambiguous.");
                matchingId = result.getLong(idColumn);
            }
        }
        return matchingId > 0 ? OptionalLong.of(matchingId) : OptionalLong.empty();
    }

    private List<Long> ensureArtists(Connection connection, Collection<String> names) throws SQLException {
        List<Long> artistIds = new ArrayList<>();
        for (String name : names) {
            if (blank(name)) continue;
            OptionalLong existing = findIdByNormalizedName(connection, "Artist", "ArtistID", name, "Artist");
            long id;
            if (existing.isPresent()) {
                id = existing.getAsLong();
            } else {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO Artist(Name) VALUES(?)", Statement.RETURN_GENERATED_KEYS)) {
                    insert.setString(1, name.trim());
                    insert.executeUpdate();
                    id = generatedId(connection, insert, "ArtistID");
                } catch (SQLException insertionFailure) {
                    OptionalLong concurrent = findIdByNormalizedName(
                            connection, "Artist", "ArtistID", name, "Artist");
                    if (concurrent.isPresent()) {
                        id = concurrent.getAsLong();
                    } else {
                        throw insertionFailure;
                    }
                }
            }
            artistIds.add(id);
        }
        return artistIds.stream().distinct().toList();
    }

    private void persistAlbumArtists(Connection connection, long albumId, Collection<String> names) throws SQLException {
        List<Long> artistIds = ensureArtists(connection, names);
        try (PreparedStatement link = connection.prepareStatement(
                "INSERT INTO AlbumArtist(AlbumID, ArtistID) VALUES(?, ?) ON CONFLICT(AlbumID, ArtistID) DO NOTHING")) {
            for (long artistId : artistIds) {
                link.setLong(1, albumId);
                link.setLong(2, artistId);
                link.executeUpdate();
            }
        }
    }

    private void persistSongArtists(Connection connection, long songId, Collection<String> names) throws SQLException {
        List<Long> artistIds = ensureArtists(connection, names);
        try (PreparedStatement link = connection.prepareStatement(
                "INSERT INTO SongArtist(SongID, ArtistID) VALUES(?, ?) ON CONFLICT(SongID, ArtistID) DO NOTHING")) {
            for (long artistId : artistIds) {
                link.setLong(1, songId);
                link.setLong(2, artistId);
                link.executeUpdate();
            }
        }
    }

    private void promoteLocalState(Connection connection, long songId, long albumId, SongMetadata metadata) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE Song
                   SET Album = ?,
                       TrackOrder = CASE WHEN ? > 0 THEN ? ELSE TrackOrder END,
                       DurationSeconds = CASE WHEN ? > 0 THEN ? ELSE DurationSeconds END,
                       IsLocal = CASE WHEN ? = 1 THEN 1 ELSE IsLocal END,
                       FilePath = CASE WHEN ? = 1 AND trim(COALESCE(?, '')) <> '' THEN ? ELSE FilePath END
                 WHERE SongID = ?
                """)) {
            update.setLong(1, albumId);
            update.setInt(2, metadata.trackOrder());
            update.setInt(3, metadata.trackOrder());
            update.setInt(4, metadata.durationSeconds());
            update.setInt(5, metadata.durationSeconds());
            update.setInt(6, metadata.local() ? 1 : 0);
            update.setInt(7, metadata.local() ? 1 : 0);
            update.setString(8, metadata.filePath());
            update.setString(9, metadata.filePath());
            update.setLong(10, songId);
            update.executeUpdate();
        }
    }

    private static boolean sameName(Connection connection,
                                    String table,
                                    String idColumn,
                                    long id,
                                    String requestedName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + ("Song".equals(table) || "Playlist".equals(table) ? "Title" : "Name")
                        + " FROM " + table + " WHERE " + idColumn + " = ?")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return false;
                return normalize(result.getString(1)).equals(normalize(requestedName));
            }
        }
    }

    private boolean albumMetadataMatches(Connection connection,
                                         long albumId,
                                         AlbumMetadata metadata) throws SQLException {
        if (!sameName(connection, "Album", "AlbumID", albumId, metadata.name())) return false;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT RecordType, ReleaseDate FROM Album WHERE AlbumID = ?")) {
            statement.setLong(1, albumId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return false;
                String recordType = result.getString("RecordType");
                String releaseDate = result.getString("ReleaseDate");
                if (!blank(metadata.recordType()) && !blank(recordType)
                        && !normalize(metadata.recordType()).equals(normalize(recordType))) return false;
                if (!blank(metadata.releaseDate()) && !blank(releaseDate)
                        && !normalize(metadata.releaseDate()).equals(normalize(releaseDate))) return false;
            }
        }
        TreeSet<String> persistedArtists = normalizedArtists(connection, "AlbumArtist", "AlbumID", albumId);
        return compatibleArtists(persistedArtists, normalized(metadata.artistNames()));
    }

    private boolean songArtistsMatch(Connection connection,
                                     long songId,
                                     Collection<String> requestedArtists) throws SQLException {
        if (requestedArtists == null || requestedArtists.isEmpty()) return true;
        TreeSet<String> persistedArtists = normalizedArtists(connection, "SongArtist", "SongID", songId);
        return compatibleArtists(persistedArtists, normalized(requestedArtists));
    }

    private boolean compatibleArtists(TreeSet<String> persisted, TreeSet<String> requested) {
        return persisted.isEmpty() || requested.isEmpty()
                || persisted.containsAll(requested)
                || requested.containsAll(persisted);
    }

    private boolean isPlaceholderAlbum(Connection connection, long albumId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT Name FROM Album WHERE AlbumID = ?")) {
            statement.setLong(1, albumId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getString(1) != null
                        && result.getString(1).startsWith("Unknown release [");
            }
        }
    }

    private static TreeSet<String> normalizedArtists(Connection connection,
                                                     String relationTable,
                                                     String entityColumn,
                                                     long entityId) throws SQLException {
        TreeSet<String> artists = new TreeSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT artist.Name FROM " + relationTable + " relation "
                        + "JOIN Artist artist ON artist.ArtistID = relation.ArtistID WHERE relation." + entityColumn + " = ?")) {
            statement.setLong(1, entityId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) artists.add(normalize(result.getString(1)));
            }
        }
        return artists;
    }

    private static TreeSet<String> normalized(Collection<String> names) {
        TreeSet<String> values = new TreeSet<>();
        if (names != null) names.stream().filter(name -> !blank(name)).map(CanonicalMediaService::normalize).forEach(values::add);
        return values;
    }

    private static boolean isUserPlaylist(Connection connection, long playlistId) throws SQLException {
        if (playlistId <= 0) return false;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT Origin FROM Playlist WHERE PlaylistID = ?")) {
            statement.setLong(1, playlistId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && "USER".equals(result.getString(1));
            }
        }
    }

    private static long findSongAlbum(Connection connection, long songId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT Album FROM Song WHERE SongID = ?")) {
            statement.setLong(1, songId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        }
    }

    private static int nextPosition(Connection connection, long playlistId, String column) throws SQLException {
        if (!"Position".equals(column) && !"CustomPosition".equals(column)) {
            throw new IllegalArgumentException("Unsupported playlist order column.");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(" + column + ") + 1, 0) FROM SongsPlaylists WHERE PlaylistID = ?")) {
            statement.setLong(1, playlistId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    private static long generatedId(Connection connection,
                                    PreparedStatement statement,
                                    String idName) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (keys.next() && keys.getLong(1) > 0) return keys.getLong(1);
        }
        try (Statement fallback = connection.createStatement();
             ResultSet result = fallback.executeQuery("SELECT last_insert_rowid()")) {
            if (result.next() && result.getLong(1) > 0) return result.getLong(1);
        }
        throw new SQLException("Could not retrieve generated " + idName + ".");
    }

    private static void finishSavepoint(Connection connection, Savepoint savepoint, boolean commitChanges) throws SQLException {
        if (commitChanges) connection.releaseSavepoint(savepoint);
        else {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        }
    }

    private static void requireTransaction(Connection connection) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        if (connection.getAutoCommit()) {
            throw new SQLException("Canonical media operations require a caller-owned transaction.");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private static <T> CanonicalizationResult<T> conflict(IdentityResolution.Reason reason, long existingId) {
        return CanonicalizationResult.conflict(new IdentityResolution.Conflict(reason, existingId));
    }

    public record AlbumMetadata(String name,
                                String genreName,
                                String recordType,
                                String releaseDate,
                                int numberOfTracks,
                                List<String> artistNames) {
        public AlbumMetadata {
            if (blank(name)) throw new IllegalArgumentException("Album name is required.");
            artistNames = artistNames == null ? List.of() : List.copyOf(artistNames);
        }
    }

    public record SongMetadata(String title,
                               int trackOrder,
                               int durationSeconds,
                               List<String> artistNames,
                               AlbumMetadata album,
                               boolean local,
                               String filePath) {
        public SongMetadata {
            if (blank(title)) throw new IllegalArgumentException("Song title is required.");
            artistNames = artistNames == null ? List.of() : List.copyOf(artistNames);
        }
    }

    public record PlaylistMetadata(String title, String author, String description) {
        public PlaylistMetadata {
            if (blank(title)) throw new IllegalArgumentException("Playlist title is required.");
        }
    }

    public record CanonicalSong(long songId, long albumId) {
        public CanonicalSong {
            if (songId <= 0 || albumId <= 0) throw new IllegalArgumentException("Canonical IDs must be positive.");
        }
    }
}
