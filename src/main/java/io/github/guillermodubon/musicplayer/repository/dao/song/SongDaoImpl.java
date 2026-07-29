package io.github.guillermodubon.musicplayer.repository.dao.song;

import io.github.guillermodubon.musicplayer.repository.dao.album.AlbumDao;
import io.github.guillermodubon.musicplayer.repository.dao.artist.ArtistDao;
import io.github.guillermodubon.musicplayer.repository.dao.support.JdbcDaoSupport;
import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.DeezerApiMetaData;
import io.github.guillermodubon.musicplayer.models.Song;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

public class SongDaoImpl extends JdbcDaoSupport implements SongDao {

    private static final Object DB_WRITE_LOCK = new Object();
    private static final int MAX_RETRY_ATTEMPTS = 6;

    public SongDaoImpl(Connection connection) {
        super(connection);
    }

    @Override
    public Optional<Song> findById(Long id) throws SQLException {
        String sql = "SELECT SongID, Title, Album, TrackOrder, IsLocal FROM Song WHERE SongID = ?";
        Connection conn = null;
        boolean close = false;
        try {
            conn = openConnection();
            close = closesConnection(conn);
            try (PreparedStatement ps = prepareStatementWithRetry(conn, sql, MAX_RETRY_ATTEMPTS)) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    return Optional.of(mapRowToSong(rs));
                }
            }
        } finally {
            if (close) try { conn.close(); } catch (SQLException ignore) {}
        }
    }

    @Override
    public void insert(Song entity) throws SQLException {
        String sql = "INSERT INTO Song(Title, Album, TrackOrder, IsLocal) VALUES (?, ?, ?, ?)";
        synchronized (DB_WRITE_LOCK) {
            Connection conn = null;
            boolean close = false;
            try {
                conn = openConnection();
                close = closesConnection(conn);

                // If caller passed sharedConnection(), assume transaction managed externally.
                boolean manageTx = (sharedConnection() == null);
                if (manageTx) conn.setAutoCommit(false);

                try (PreparedStatement ps = prepareStatementWithRetry(conn, sql, MAX_RETRY_ATTEMPTS)) {
                    ps.setString(1, entity.getTitle());
                    ps.setLong(2, entity.getAlbum().getAlbumID());
                    ps.setInt(3, entity.getTrackOrder());
                    ps.setInt(4, entity.isLocal() ? 1 : 0);
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (rs.next()) entity.setSongID(rs.getLong(1));
                    }
                    if (manageTx) conn.commit();
                } catch (SQLException ex) {
                    if (manageTx) try { conn.rollback(); } catch (SQLException ignore) {}
                    throw ex;
                } finally {
                    if (manageTx) try { conn.setAutoCommit(true); } catch (SQLException ignore) {}
                }
            } finally {
                if (close) try { conn.close(); } catch (SQLException ignore) {}
            }
        }
    }

    @Override
    public void update(Song song) throws SQLException {
        String sql = "UPDATE Song SET Title = ?, Album = ?, TrackOrder = ?, IsLocal = ? WHERE SongID = ?";
        synchronized (DB_WRITE_LOCK) {
            Connection conn = null;
            boolean close = false;
            try {
                conn = openConnection();
                close = closesConnection(conn);
                try (PreparedStatement ps = prepareStatementWithRetry(conn, sql, MAX_RETRY_ATTEMPTS)) {
                    ps.setString(1, song.getTitle());
                    ps.setLong(2, song.getAlbum().getAlbumID());
                    ps.setInt(3, song.getTrackOrder());
                    ps.setInt(4, song.isLocal() ? 1 : 0);
                    ps.setLong(5, song.getSongID());
                    ps.executeUpdate();
                }
            } finally {
                if (close) try { conn.close(); } catch (SQLException ignore) {}
            }
        }
    }

    @Override
    public List<Song> findAll() throws SQLException {
        String sql = "SELECT SongID, Title, Album, TrackOrder, IsLocal FROM Song";
        Connection conn = null;
        boolean close = false;
        try {
            conn = openConnection();
            close = closesConnection(conn);
            List<Song> result = new ArrayList<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) result.add(mapRowToSong(rs));
            }
            return result;
        } finally {
            if (close) try { conn.close(); } catch (SQLException ignore) {}
        }
    }

    @Override
    public Long insertAndGetId(Song song) throws SQLException {
        String sql = "INSERT INTO Song(Title, Album, TrackOrder, IsLocal) VALUES(?, ?, ?, ?)";
        synchronized (DB_WRITE_LOCK) {
            Connection conn = null;
            boolean close = false;
            try {
                conn = openConnection();
                close = closesConnection(conn);
                boolean manageTx = (sharedConnection() == null);
                if (manageTx) conn.setAutoCommit(false);
                try (PreparedStatement ps = prepareStatementWithRetry(conn, sql, MAX_RETRY_ATTEMPTS)) {
                    ps.setString(1, song.getTitle());
                    ps.setLong(2, song.getAlbum().getAlbumID());
                    ps.setInt(3, song.getTrackOrder());
                    ps.setInt(4, song.isLocal() ? 1 : 0);
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (rs.next()) return rs.getLong(1);
                    }
                    if (manageTx) conn.commit();
                    return null;
                } catch (SQLException ex) {
                    if (manageTx) try { conn.rollback(); } catch (SQLException ignore) {}
                    throw ex;
                } finally {
                    if (manageTx) try { conn.setAutoCommit(true); } catch (SQLException ignore) {}
                }
            } finally {
                if (close) try { conn.close(); } catch (SQLException ignore) {}
            }
        }
    }

    @Override
    public List<Long> findIdsNotIn(Set<String> titles) throws SQLException {
        if (titles == null || titles.isEmpty()) {
            return new ArrayList<>();
        }
        String placeholders = titles.stream().map(t -> "?").collect(Collectors.joining(","));
        String sql = "SELECT SongID FROM Song WHERE IsLocal = 1 AND Title NOT IN (" + placeholders + ")";
        Connection conn = null;
        boolean close = false;
        try {
            conn = openConnection();
            close = closesConnection(conn);
            try (PreparedStatement ps = prepareStatementWithRetry(conn, sql, MAX_RETRY_ATTEMPTS)) {
                int idx = 1;
                for (String t : titles) ps.setString(idx++, t);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Long> ids = new ArrayList<>();
                    while (rs.next()) ids.add(rs.getLong("SongID"));
                    return ids;
                }
            }
        } finally {
            if (close) try { conn.close(); } catch (SQLException ignore) {}
        }
    }

    @Override
    public Set<String> findAllTitles() throws SQLException {
        String sql = "SELECT Title FROM Song";
        Connection conn = null;
        boolean close = false;
        try {
            conn = openConnection();
            close = closesConnection(conn);
            Set<String> titles = new HashSet<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) titles.add(rs.getString(1));
            }
            return titles;
        } finally {
            if (close) try { conn.close(); } catch (SQLException ignore) {}
        }
    }

    @Override
    public void delete(Long id) throws SQLException {
        synchronized (DB_WRITE_LOCK) {
            Connection conn = null;
            boolean close = false;
            boolean manageTx = false;
            try {
                conn = openConnection();
                close = closesConnection(conn);
                manageTx = (sharedConnection() == null);
                if (manageTx) conn.setAutoCommit(false);

                linkCleanup(conn, id);
                try (PreparedStatement ps = prepareStatementWithRetry(conn, "DELETE FROM Song WHERE SongID = ?", MAX_RETRY_ATTEMPTS)) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }

                if (manageTx) conn.commit();
            } catch (SQLException ex) {
                if (manageTx && conn != null) try { conn.rollback(); } catch (SQLException ignore) {}
                throw ex;
            } finally {
                if (manageTx && conn != null) try { conn.setAutoCommit(true); } catch (SQLException ignore) {}
                if (close) try { conn.close(); } catch (SQLException ignore) {}
            }
        }
    }

    // accepts external connection (avoid opening new one)
    private void linkCleanup(Connection conn, long songId) throws SQLException {
        try (PreparedStatement ps = prepareStatementWithRetry(conn, "DELETE FROM SongArtist WHERE SongID = ?", MAX_RETRY_ATTEMPTS)) {
            ps.setLong(1, songId);
            ps.executeUpdate();
        }
    }

    @Override
    public void linkArtist(long songId, long artistId) throws SQLException {
        String sql = "INSERT OR IGNORE INTO SongArtist(SongID, ArtistID) VALUES(?, ?)";
        synchronized (DB_WRITE_LOCK) {
            Connection conn = null;
            boolean close = false;
            try {
                conn = openConnection();
                close = closesConnection(conn);
                try (PreparedStatement ps = prepareStatementWithRetry(conn, sql, MAX_RETRY_ATTEMPTS)) {
                    ps.setLong(1, songId);
                    ps.setLong(2, artistId);
                    ps.executeUpdate();
                }
            } finally {
                if (close) try { conn.close(); } catch (SQLException ignore) {}
            }
        }
    }

    @Override
    public List<Song> findByAlbum(long albumId) throws SQLException {
        String sql = "SELECT SongID, Title, Album, TrackOrder, IsLocal FROM Song WHERE Album = ?";
        Connection conn = null;
        boolean close = false;
        try {
            conn = openConnection();
            close = closesConnection(conn);
            try (PreparedStatement ps = prepareStatementWithRetry(conn, sql, MAX_RETRY_ATTEMPTS)) {
                ps.setLong(1, albumId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Song> list = new ArrayList<>();
                    while (rs.next()) list.add(mapRowToSong(rs));
                    return list;
                }
            }
        } finally {
            if (close) try { conn.close(); } catch (SQLException ignore) {}
        }
    }

    @Override
    public boolean isEmpty() throws SQLException {
        String sql = "SELECT COUNT(*) FROM Song";
        Connection conn = null;
        boolean close = false;
        try {
            conn = openConnection();
            close = closesConnection(conn);
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                return rs.next() && rs.getInt(1) == 0;
            }
        } finally {
            if (close) try { conn.close(); } catch (SQLException ignore) {}
        }
    }

    @Override
    public void deleteByIds(List<Long> songIds) throws SQLException {
        if (songIds == null || songIds.isEmpty()) return;
        synchronized (DB_WRITE_LOCK) {
            Connection conn = null;
            boolean close = false;
            boolean manageTx = false;
            try {
                conn = openConnection();
                close = closesConnection(conn);
                manageTx = (sharedConnection() == null);
                if (manageTx) conn.setAutoCommit(false);

                try (PreparedStatement psSP = prepareStatementWithRetry(conn, "DELETE FROM SongsPlaylists WHERE SongID = ?", MAX_RETRY_ATTEMPTS);
                     PreparedStatement psSA = prepareStatementWithRetry(conn, "DELETE FROM SongArtist WHERE SongID = ?", MAX_RETRY_ATTEMPTS);
                     PreparedStatement psS = prepareStatementWithRetry(conn, "DELETE FROM Song WHERE SongID = ?", MAX_RETRY_ATTEMPTS)) {

                    for (long id : songIds) {
                        psSP.setLong(1, id); psSP.executeUpdate();
                        psSA.setLong(1, id); psSA.executeUpdate();
                        psS.setLong(1, id); psS.executeUpdate();
                    }

                    if (manageTx) conn.commit();
                } catch (SQLException ex) {
                    if (manageTx) conn.rollback();
                    throw ex;
                } finally {
                    if (manageTx) try { conn.setAutoCommit(true); } catch (SQLException ignore) {}
                }
            } finally {
                if (close) try { conn.close(); } catch (SQLException ignore) {}
            }
        }
    }

    @Override
    public void updateAlbumAndOrderIfNeeded(long songId, long albumId, int trackOrder) throws SQLException {
        String sql = "UPDATE Song SET Album = ?, TrackOrder = ? WHERE SongID = ? AND (Album <> ? OR TrackOrder <> ?)";
        synchronized (DB_WRITE_LOCK) {
            Connection conn = null;
            boolean close = false;
            try {
                conn = openConnection();
                close = closesConnection(conn);
                try (PreparedStatement ps = prepareStatementWithRetry(conn, sql, MAX_RETRY_ATTEMPTS)) {
                    ps.setLong(1, albumId);
                    ps.setInt(2, trackOrder);
                    ps.setLong(3, songId);
                    ps.setLong(4, albumId);
                    ps.setInt(5, trackOrder);
                    ps.executeUpdate();
                }
            } finally {
                if (close) try { conn.close(); } catch (SQLException ignore) {}
            }
        }
    }

    @Override
    public void insertSongsAndArtists(List<DeezerApiMetaData> metas, AlbumDao albumDao, ArtistDao artistDao) throws SQLException {
        if (metas == null || metas.isEmpty()) return;
        String insertSongSql = "INSERT OR IGNORE INTO Song(SongID, Title, Album, TrackOrder, IsLocal) VALUES (?, ?, ?, ?, ?)";
        String updateSongSql = "UPDATE Song SET Title = ?, Album = ?, TrackOrder = ?, IsLocal = 1 WHERE SongID = ?";
        String linkSql = "INSERT OR IGNORE INTO SongArtist(SongID, ArtistID) VALUES(?, ?)";
        synchronized (DB_WRITE_LOCK) {
            Connection conn = null;
            boolean close = false;
            boolean manageTx = false;
            try {
                conn = openConnection();
                close = closesConnection(conn);
                manageTx = (sharedConnection() == null);
                if (manageTx) conn.setAutoCommit(false);

                try (PreparedStatement psSong = prepareStatementWithRetry(conn, insertSongSql, MAX_RETRY_ATTEMPTS);
                     PreparedStatement psUpdateSong = prepareStatementWithRetry(conn, updateSongSql, MAX_RETRY_ATTEMPTS);
                     PreparedStatement psLink = prepareStatementWithRetry(conn, linkSql, MAX_RETRY_ATTEMPTS)) {

                    for (var meta : metas) {
                        long deezerId = meta.getTrackId();
                        String title = meta.getSongName();
                        Long albumId = albumDao.findIdByName(meta.getAlbumName());
                        int trackOrder = meta.getTrackOrder();
                        List<String> contributors = meta.getSongContributorNames();

                        if (title == null || title.isBlank() || albumId == null) continue;

                        List<String> allArtists = new ArrayList<>(meta.getAlbumArtistNames() != null ? meta.getAlbumArtistNames() : Collections.emptyList());
                        if (contributors != null) allArtists.addAll(contributors);
                        Optional<Long> existingLocal = findLocalSongByTitleAndArtists(title, allArtists);

                        if (existingLocal.isPresent()) {
                            long existingId = existingLocal.get();
                            updateAlbumAndOrderIfNeeded(existingId, albumId, trackOrder);
                            if (contributors != null) {
                                for (var artName : contributors) {
                                    Long artId = artistDao.findIdByName(artName);
                                    if (artId != null) {
                                        psLink.setLong(1, existingId);
                                        psLink.setLong(2, artId.longValue());
                                        psLink.executeUpdate();
                                    }
                                }
                            }
                            continue;
                        }

                        psSong.setLong(1, deezerId);
                        psSong.setString(2, title);
                        psSong.setLong(3, albumId);
                        psSong.setInt(4, trackOrder);
                        psSong.setInt(5, 1); // treat inserted as local
                        psSong.executeUpdate();

                        psUpdateSong.setString(1, title);
                        psUpdateSong.setLong(2, albumId);
                        psUpdateSong.setInt(3, trackOrder);
                        psUpdateSong.setLong(4, deezerId);
                        psUpdateSong.executeUpdate();

                        if (contributors != null) {
                            for (var artName : contributors) {
                                Long artId = artistDao.findIdByName(artName);
                                if (artId != null) {
                                    psLink.setLong(1, deezerId);
                                    psLink.setLong(2, artId);
                                    psLink.executeUpdate();
                                }
                            }
                        }
                    }

                    if (manageTx) conn.commit();
                } catch (SQLException ex) {
                    if (manageTx) conn.rollback();
                    throw ex;
                } finally {
                    if (manageTx) try { conn.setAutoCommit(true); } catch (SQLException ignore) {}
                }
            } finally {
                if (close) try { conn.close(); } catch (SQLException ignore) {}
            }
        }
    }

    @Override
    public void cleanEmptyPlaylists() throws SQLException {
        synchronized (DB_WRITE_LOCK) {
            Connection conn = null;
            boolean close = false;
            boolean manageTx = false;
            try {
                conn = openConnection();
                close = closesConnection(conn);
                manageTx = (sharedConnection() == null);
                if (manageTx) conn.setAutoCommit(false);

                Set<Long> valid = new HashSet<>();
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT SongID FROM Song")) {
                    while (rs.next()) valid.add(rs.getLong(1));
                }

                try (PreparedStatement ps = prepareStatementWithRetry(conn, "DELETE FROM SongsPlaylists WHERE SongID = ? AND PlaylistID = ?", MAX_RETRY_ATTEMPTS);
                     Statement st2 = conn.createStatement();
                     ResultSet rs2 = st2.executeQuery("SELECT SongID, PlaylistID FROM SongsPlaylists")) {

                    while (rs2.next()) {
                        long sid = rs2.getLong("SongID");
                        long pid = rs2.getLong("PlaylistID");
                        if (!valid.contains(sid)) {
                            ps.setLong(1, sid);
                            ps.setLong(2, pid);
                            ps.executeUpdate();
                        }
                    }
                }

                if (manageTx) conn.commit();
            } catch (SQLException ex) {
                if (manageTx && conn != null) try { conn.rollback(); } catch (SQLException ignore) {}
                throw ex;
            } finally {
                if (manageTx && conn != null) try { conn.setAutoCommit(true); } catch (SQLException ignore) {}
                if (close) try { conn.close(); } catch (SQLException ignore) {}
            }
        }
    }

    @Override
    public void deleteByAlbum(long albumId) throws SQLException {
        synchronized (DB_WRITE_LOCK) {
            Connection conn = null;
            boolean close = false;
            boolean manageTx = false;
            try {
                conn = openConnection();
                close = closesConnection(conn);
                manageTx = (sharedConnection() == null);
                if (manageTx) conn.setAutoCommit(false);

                try (PreparedStatement ps1 = prepareStatementWithRetry(conn, "DELETE FROM SongsPlaylists WHERE SongID IN (SELECT SongID FROM Song WHERE Album = ?)", MAX_RETRY_ATTEMPTS);
                     PreparedStatement ps2 = prepareStatementWithRetry(conn, "DELETE FROM SongArtist WHERE SongID IN (SELECT SongID FROM Song WHERE Album = ?)", MAX_RETRY_ATTEMPTS);
                     PreparedStatement ps3 = prepareStatementWithRetry(conn, "DELETE FROM Song WHERE Album = ?", MAX_RETRY_ATTEMPTS)) {

                    ps1.setLong(1, albumId); ps1.executeUpdate();
                    ps2.setLong(1, albumId); ps2.executeUpdate();
                    ps3.setLong(1, albumId); ps3.executeUpdate();

                    if (manageTx) conn.commit();
                } catch (SQLException ex) {
                    if (manageTx) conn.rollback();
                    throw ex;
                } finally {
                    if (manageTx) try { conn.setAutoCommit(true); } catch (SQLException ignore) {}
                }
            } finally {
                if (close) try { conn.close(); } catch (SQLException ignore) {}
            }
        }
    }

    @Override
    public Optional<Long> findLocalSongByTitleAndArtists(String title, List<String> artistNames) throws SQLException {
        if (artistNames == null || artistNames.isEmpty()) return Optional.empty();

        String inPlaceholders = artistNames.stream().map(a -> "?").collect(Collectors.joining(","));
        String sql = "SELECT s.SongID FROM Song s " +
                "JOIN SongArtist sa ON s.SongID = sa.SongID " +
                "JOIN Artist a ON sa.ArtistID = a.ArtistID " +
                "WHERE s.Title = ? AND s.IsLocal = 1 " +
                "GROUP BY s.SongID " +
                "HAVING COUNT(DISTINCT a.Name) = ? AND SUM(a.Name IN (" + inPlaceholders + ")) = ?";

        Connection conn = null;
        boolean close = false;
        try {
            conn = openConnection();
            close = closesConnection(conn);
            try (PreparedStatement ps = prepareStatementWithRetry(conn, sql, MAX_RETRY_ATTEMPTS)) {
                int idx = 1;
                ps.setString(idx++, title);
                ps.setInt(idx++, artistNames.size());
                for (String n : artistNames) ps.setString(idx++, n);
                ps.setInt(idx++, artistNames.size());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return Optional.of(rs.getLong("SongID"));
                }
            }
            return Optional.empty();
        } finally {
            if (close) try { conn.close(); } catch (SQLException ignore) {}
        }
    }

    @Override
    public void insertLocalSongInAlbum(String title, long albumId, int trackOrder, String filePath) throws SQLException {
        String sql = "INSERT INTO Song(Title, Album, TrackOrder, IsLocal) VALUES(?, ?, ?, 1)";
        synchronized (DB_WRITE_LOCK) {
            Connection conn = null;
            boolean close = false;
            try {
                conn = openConnection();
                close = closesConnection(conn);
                try (PreparedStatement ps = prepareStatementWithRetry(conn, sql, MAX_RETRY_ATTEMPTS)) {
                    ps.setString(1, title);
                    ps.setLong(2, albumId);
                    ps.setInt(3, trackOrder);
                    ps.executeUpdate();
                }
            } finally {
                if (close) try { conn.close(); } catch (SQLException ignore) {}
            }
        }
    }

    private Song mapRowToSong(ResultSet rs) throws SQLException {
        long id = rs.getLong("SongID");
        long albumId = rs.getLong("Album");
        int trackOrder = rs.getInt("TrackOrder");
        String title = rs.getString("Title");
        boolean isLocal = rs.getInt("IsLocal") == 1;
        Album alb = new Album(albumId, null, new ArrayList<>(), null, null, null, new ArrayList<>(), new ArrayList<>(), 0);
        return new Song(id, title, new ArrayList<>(), alb, null, trackOrder, isLocal);
    }

    @Override
    public void insertVisualSong(String title, long albumId, int trackOrder) throws SQLException {
        String sql = "INSERT INTO Song(Title, Album, TrackOrder, IsLocal) VALUES(?, ?, ?, 0)";
        synchronized (DB_WRITE_LOCK) {
            Connection conn = null;
            boolean close = false;
            try {
                conn = openConnection();
                close = closesConnection(conn);
                try (PreparedStatement ps = prepareStatementWithRetry(conn, sql, MAX_RETRY_ATTEMPTS)) {
                    ps.setString(1, title);
                    ps.setLong(2, albumId);
                    ps.setInt(3, trackOrder);
                    ps.executeUpdate();
                }
            } finally {
                if (close) try { conn.close(); } catch (SQLException ignore) {}
            }
        }
    }

    @Override
    public void insertWithId(Song song) throws SQLException {
        String insertSql = "INSERT OR IGNORE INTO Song(SongID, Title, Album, TrackOrder, IsLocal, FilePath) VALUES(?, ?, ?, ?, ?, ?)";
        String updateSql = """
                UPDATE Song
                   SET Title = ?,
                       Album = ?,
                       TrackOrder = ?,
                       IsLocal = CASE WHEN ? = 1 THEN 1 ELSE IsLocal END,
                       FilePath = CASE WHEN ? IS NOT NULL THEN ? ELSE FilePath END
                 WHERE SongID = ?
                """;
        synchronized (DB_WRITE_LOCK) {
            Connection conn = null;
            boolean close = false;
            try {
                conn = openConnection();
                close = closesConnection(conn);
                try (PreparedStatement ps = prepareStatementWithRetry(conn, insertSql, MAX_RETRY_ATTEMPTS);
                     PreparedStatement upd = prepareStatementWithRetry(conn, updateSql, MAX_RETRY_ATTEMPTS)) {
                    ps.setLong(1, song.getSongID());
                    ps.setString(2, song.getTitle());
                    ps.setLong(3, song.getAlbum().getAlbumID());
                    ps.setInt(4, song.getTrackOrder());
                    ps.setInt(5, song.isLocal() ? 1 : 0);
                    if (song.getFilePath() != null && !song.getFilePath().isBlank()) ps.setString(6, song.getFilePath());
                    else ps.setNull(6, Types.VARCHAR);
                    ps.executeUpdate();

                    upd.setString(1, song.getTitle());
                    upd.setLong(2, song.getAlbum().getAlbumID());
                    upd.setInt(3, song.getTrackOrder());
                    upd.setInt(4, song.isLocal() ? 1 : 0);
                    if (song.getFilePath() != null && !song.getFilePath().isBlank()) {
                        upd.setString(5, song.getFilePath());
                        upd.setString(6, song.getFilePath());
                    } else {
                        upd.setNull(5, Types.VARCHAR);
                        upd.setNull(6, Types.VARCHAR);
                    }
                    upd.setLong(7, song.getSongID());
                    upd.executeUpdate();
                }
            } finally {
                if (close) try { conn.close(); } catch (SQLException ignore) {}
            }
        }
    }

    @Override
    public void insertOrUpdateAllWithIds(Collection<Song> songs) throws SQLException {
        if (songs == null || songs.isEmpty()) return;

        String insertSql = "INSERT OR IGNORE INTO Song(SongID, Title, Album, TrackOrder, IsLocal, FilePath) VALUES(?, ?, ?, ?, ?, ?)";
        String updateSql = """
                UPDATE Song
                   SET Title = ?,
                       Album = ?,
                       TrackOrder = ?,
                       IsLocal = CASE WHEN ? = 1 THEN 1 ELSE IsLocal END,
                       FilePath = CASE WHEN ? IS NOT NULL THEN ? ELSE FilePath END
                 WHERE SongID = ?
                """;
        final int batchSize = 200;

        synchronized (DB_WRITE_LOCK) {
            Connection conn = null;
            boolean close = false;
            try {
                conn = openConnection();
                close = closesConnection(conn);
                try (PreparedStatement insert = prepareStatementWithRetry(conn, insertSql, MAX_RETRY_ATTEMPTS);
                     PreparedStatement update = prepareStatementWithRetry(conn, updateSql, MAX_RETRY_ATTEMPTS)) {
                    int pending = 0;
                    for (Song song : songs) {
                        if (song == null || song.getAlbum() == null) continue;
                        bindSongUpsert(insert, update, song);
                        pending++;
                        if (pending == batchSize) {
                            insert.executeBatch();
                            update.executeBatch();
                            insert.clearBatch();
                            update.clearBatch();
                            pending = 0;
                        }
                    }
                    if (pending > 0) {
                        insert.executeBatch();
                        update.executeBatch();
                    }
                }
            } finally {
                if (close) try { conn.close(); } catch (SQLException ignore) {}
            }
        }
    }

    private static void bindSongUpsert(PreparedStatement insert, PreparedStatement update, Song song) throws SQLException {
        String filePath = song.getFilePath();
        boolean hasFilePath = filePath != null && !filePath.isBlank();

        insert.setLong(1, song.getSongID());
        insert.setString(2, song.getTitle());
        insert.setLong(3, song.getAlbum().getAlbumID());
        insert.setInt(4, song.getTrackOrder());
        insert.setInt(5, song.isLocal() ? 1 : 0);
        if (hasFilePath) insert.setString(6, filePath);
        else insert.setNull(6, Types.VARCHAR);
        insert.addBatch();

        update.setString(1, song.getTitle());
        update.setLong(2, song.getAlbum().getAlbumID());
        update.setInt(3, song.getTrackOrder());
        update.setInt(4, song.isLocal() ? 1 : 0);
        if (hasFilePath) {
            update.setString(5, filePath);
            update.setString(6, filePath);
        } else {
            update.setNull(5, Types.VARCHAR);
            update.setNull(6, Types.VARCHAR);
        }
        update.setLong(7, song.getSongID());
        update.addBatch();
    }

    @Override
    public void deleteVisualDuplicates() throws SQLException {
        String sql = "DELETE FROM Song AS v WHERE v.IsLocal = 0 AND EXISTS (SELECT 1 FROM Song AS l WHERE l.IsLocal = 1 AND l.Title = v.Title AND l.TrackOrder = v.TrackOrder)";
        synchronized (DB_WRITE_LOCK) {
            Connection conn = null;
            boolean close = false;
            try {
                conn = openConnection();
                close = closesConnection(conn);
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate(sql);
                }
            } finally {
                if (close) try { conn.close(); } catch (SQLException ignore) {}
            }
        }
    }

    @Override
    public boolean existsByAlbumAndTrackOrder(long albumId, int trackOrder) throws SQLException {
        String sql = "SELECT 1 FROM Song WHERE Album = ? AND TrackOrder = ? LIMIT 1";
        Connection conn = null;
        boolean close = false;
        try {
            conn = openConnection();
            close = closesConnection(conn);
            try (PreparedStatement ps = prepareStatementWithRetry(conn, sql, MAX_RETRY_ATTEMPTS)) {
                ps.setLong(1, albumId);
                ps.setInt(2, trackOrder);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        } finally {
            if (close) try { conn.close(); } catch (SQLException ignore) {}
        }
    }

    public Set<Long> findAllLocalIds() throws SQLException {
        String sql = "SELECT SongID FROM Song WHERE IsLocal = 1";
        Connection conn = null;
        boolean close = false;
        try {
            conn = openConnection();
            close = closesConnection(conn);
            Set<Long> ids = new HashSet<>();
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    ids.add(rs.getLong("SongID"));
                }
            }
            return ids;
        } finally {
            if (close) try { conn.close(); } catch (SQLException ignore) {}
        }
    }
}
