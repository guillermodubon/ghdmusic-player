package io.github.guillermodubon.musicplayer.services.startup.hydration;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import io.github.guillermodubon.musicplayer.utils.SongDataHelper;
import io.github.guillermodubon.musicplayer.utils.FileNameUtils;
import io.github.guillermodubon.musicplayer.models.*;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class ModelHydrationService {

    private final StartUpService owner;
    private final AlbumModelHydrationService albumModelHydrationService;

    public ModelHydrationService(StartUpService owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.albumModelHydrationService = new AlbumModelHydrationService(this.owner);
    }

    private void ensureTrackArtistsLoadedAsync(long trackId, Song targetSong, Runnable onComplete) {
        owner.ensureTrackArtistsLoadedAsync(trackId, targetSong, onComplete);
    }

    public void loadModels(Connection conn, Map<String, String> providedTitleToPath) throws SQLException, IOException {
        List<Genre> genres = owner.getGenres();
        List<Album> albums = owner.getAlbums();
        List<Song> songs = owner.getSongs();
        List<Artist> artists = owner.getArtists();
        ObservableList<Playlist> playlists = owner.getPlaylists();
        Map<String, String> titleToPath = providedTitleToPath == null ? Collections.emptyMap() : providedTitleToPath;
        System.out.println("loadModels: starting to populate in-memory caches from DB");
        // defensive: ensure we operate on provided connection only (no new connections)
        if (conn == null || conn.isClosed()) throw new SQLException("loadModels: connection is null/closed");
        // Apply per-connection PRAGMAs to reduce SQLITE_BUSY windows
        try (Statement st = conn.createStatement()) {
            try { st.execute("PRAGMA busy_timeout = 5000"); } catch (Exception ignore) {}
            try { st.execute("PRAGMA journal_mode = WAL"); } catch (Exception ignore) {}
        } catch (SQLException e) {
            System.out.println("loadModels: warning applying PRAGMA -> " + Optional.ofNullable(e.getMessage()).orElse("null"));
        }
        // temp lookup maps
        Map<Integer, Genre> genreById = new HashMap<>();
        Map<Long, Artist> artistById = new HashMap<>();
        Map<Long, Album> albumById = new HashMap<>();
        Map<Long, Song> songById = new HashMap<>();
        // --- 1) Genres ---
        genres.clear();
        String sqlGenres = "SELECT GenreID, Name FROM Genre";
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sqlGenres)) {
            while (rs.next()) {
                int id = rs.getInt("GenreID");
                String name = rs.getString("Name");
                Genre g = new Genre(id, name);
                genres.add(g);
                genreById.put(id, g);
            }
        }
        System.out.println("loadModels: loaded genres count=" + genres.size());
        // --- 2) Artists (no portraits yet) ---
        artists.clear();
        String sqlArtists = "SELECT ArtistID, Name, Biography FROM Artist "
                + "WHERE lower(trim(Name)) NOT IN ('varios artistas', 'various artists')";
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sqlArtists)) {
            while (rs.next()) {
                long id = rs.getLong("ArtistID");
                String name = rs.getString("Name");
                String biography = rs.getString("Biography");
                Artist a = new Artist(id, name, biography, new ArrayList<>());
                artists.add(a);
                artistById.put(id, a);
            }
        }
        System.out.println("loadModels: loaded artists count=" + artists.size());
        // Images stay out of long-lived memory. UI layers resolve them from DB/Deezer on demand.
        System.out.println("loadModels: skipped artist image hydration");
        // --- 4) Albums (no covers/artists yet) ---
        albums.clear();
        String sqlAlbums = """
SELECT AlbumID, GenreID, Name, RecordType, ReleaseDate, NumberOfTracks FROM Album
""";
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sqlAlbums)) {
            while (rs.next()) {
                long id = rs.getLong("AlbumID");
                int genreId = rs.getInt("GenreID");
                String name = rs.getString("Name");
                String recordType = rs.getString("RecordType");
                String releaseDate = rs.getString("ReleaseDate");
                int numberOfTracks = rs.getInt("NumberOfTracks");
                Genre g = genreById.get(genreId);
                Album alb = new Album(id, name, new ArrayList<>(), g, recordType, releaseDate, new ArrayList<>(), new ArrayList<>(), numberOfTracks);
                albums.add(alb);
                albumById.put(id, alb);
            }
        }
        System.out.println("loadModels: loaded albums count=" + albums.size());
        // Images stay out of long-lived memory. UI layers resolve them from DB/Deezer on demand.
        System.out.println("loadModels: skipped album image hydration");
        // --- 6) AlbumArtist relations (bulk attach) ---
        String sqlAlbumArtist = "SELECT AlbumID, ArtistID FROM AlbumArtist";
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sqlAlbumArtist)) {
            while (rs.next()) {
                long albumId = rs.getLong("AlbumID");
                long artistId = rs.getLong("ArtistID");
                Album alb = albumById.get(albumId);
                Artist art = artistById.get(artistId);
                if (alb != null && art != null) alb.getArtist().add(art);
            }
        }
        // --- 7) Songs (populate, compute path when local) ---
        songs.clear();
        String sqlSongs = "SELECT SongID, Title, Album, TrackOrder, IsLocal, FilePath FROM Song";
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sqlSongs)) {
            while (rs.next()) {
                long id = rs.getLong("SongID");
                String title = rs.getString("Title");
                long albumId = rs.getLong("Album");
                int order = rs.getInt("TrackOrder");
                boolean isLocal = rs.getInt("IsLocal") == 1;
                Album alb = albumById.get(albumId);
                String path = null;
                try { path = rs.getString("FilePath"); } catch (Exception ignored) {}
                if (isLocal && title != null) {
                    // lookup normalized keys with minimal allocations
                    if (path == null || path.isBlank()) path = titleToPath.get(title);
                    if (path == null) path = titleToPath.get(SongDataHelper.sanitizeForFileKey(title));
                    if (path == null) path = titleToPath.get(SongDataHelper.fallbackKey(title));
                    if (path == null) path = titleToPath.get(title.toLowerCase());
                    if (path == null) path = titleToPath.get(SongDataHelper.sanitizeForFileKey(title).toLowerCase());
                }
                Song s = new Song(id, title, new ArrayList<>(), alb, path, order, isLocal);
                songs.add(s);
                songById.put(id, s);
            }
        }
        System.out.println("loadModels: loaded songs count=" + songs.size());
        // --- 8) Fill song artists from album owners as initial step ---
        for (Song s : songs) {
            Album alb = s.getAlbum();
            if (alb != null && alb.getArtist() != null && !alb.getArtist().isEmpty()) {
                s.getArtist().addAll(alb.getArtist());
            }
        }
        System.out.println("loadModels: filled song artist lists from album owners");
        // --- 9) Merge SongArtist relations in bulk (avoid per-row DB hit) ---
        Map<Long, List<Long>> songToArtistIds = new HashMap<>();
        String sqlSongArtist = "SELECT SongID, ArtistID FROM SongArtist";
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sqlSongArtist)) {
            while (rs.next()) {
                long songId = rs.getLong("SongID");
                long artistId = rs.getLong("ArtistID");
                songToArtistIds.computeIfAbsent(songId, k -> new ArrayList<>()).add(artistId);
            }
        }
        // collect missing artist ids to fetch in one batch
        Set<Long> missingArtistIds = new HashSet<>();
        for (var entry : songToArtistIds.entrySet()) {
            long songId = entry.getKey();
            Song s = songById.get(songId);
            if (s == null) continue; // skip if no song in-memory
            for (long aid : entry.getValue()) {
                if (!artistById.containsKey(aid)) missingArtistIds.add(aid);
            }
        }
        // batch load missing artists (IN (...) ), chunk to safe size
        if (!missingArtistIds.isEmpty()) {
            List<Long> ids = new ArrayList<>(missingArtistIds);
            final int CHUNK = 500;
            for (int i = 0; i < ids.size(); i += CHUNK) {
                int to = Math.min(ids.size(), i + CHUNK);
                List<Long> sub = ids.subList(i, to);
                String placeholders = sub.stream().map(x -> "?").collect(Collectors.joining(","));
                String q = "SELECT ArtistID, Name, Biography FROM Artist WHERE ArtistID IN (" + placeholders + ") "
                        + "AND lower(trim(Name)) NOT IN ('varios artistas', 'various artists')";
                try (PreparedStatement ps = conn.prepareStatement(q)) {
                    int idx = 1;
                    for (Long v : sub) ps.setLong(idx++, v);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            long aid = rs.getLong("ArtistID");
                            String name = rs.getString("Name");
                            String bio = rs.getString("Biography");
                            if (!artistById.containsKey(aid)) {
                                Artist a = new Artist(aid, name, bio, new ArrayList<>());
                                artists.add(a);
                                artistById.put(aid, a);
                            }
                        }
                    }
                }
            }
        }
        // Now attach artists to songs, avoiding duplicates
        for (var entry : songToArtistIds.entrySet()) {
            long songId = entry.getKey();
            Song s = songById.get(songId);
            if (s == null) continue;
            for (long aid : entry.getValue()) {
                Artist a = artistById.get(aid);
                if (a == null) continue;
                boolean dup = s.getArtist().stream().anyMatch(ar -> (ar.getArtistID() > 0 && ar.getArtistID() == a.getArtistID()) || (ar.getName() != null && a.getName() != null && ar.getName().equalsIgnoreCase(a.getName())) );
                if (!dup) {
                    s.getArtist().add(a);
                }
            }
        }
        System.out.println("loadModels: merged SongArtist relations (attached artists to songs)");
        // --- 10) Assign songs into album.songList (initial pass) ---
        for (Song s : songs) {
            Album alb = s.getAlbum();
            if (alb != null) alb.getSongList().add(s);
        }
        System.out.println("loadModels: assigned songs into album.songList (initial pass)");
        // --- 11) (Optional) rebuild album song lists using Deezer track ordering / merge logic ---
        // KEEP existing algorithm; ensure it uses getLong/getInt and operates over in-memory lists.
        // --- 12) Update maps used elsewhere ---
        songById.clear();
        for (Song s : songs) if (s != null && s.getSongID() > 0) songById.put(s.getSongID(), s);
        artistById.clear();
        for (Artist a : artists) if (a != null && a.getArtistID() > 0) artistById.put(a.getArtistID(), a);
        // --- 13) attach saved SongArtist to models (if you need additional processing) ---
        attachSavedSongArtistsToModels(artistById, songById, conn);
        // --- 14) Fetch missing collaborators from Deezer async (bounded parallelism) ---
        List<Long> toFetch = songs.stream()
                .filter(s -> s.getSongID() > 0)
                .filter(s -> s.getArtist() == null || s.getArtist().isEmpty())
                .map(Song::getSongID)
                .distinct()
                .collect(Collectors.toList());
        System.out.println("loadModels: tracks missing artist associations count=" + toFetch.size());
        final int MAX_PARALLEL = 6;
        ExecutorService smallPool = Executors.newFixedThreadPool(Math.min(MAX_PARALLEL, Math.max(1, toFetch.size())));
        for (Long tid : toFetch) {
            Song target = songById.get(tid);
            if (target == null) continue;
            smallPool.submit(() -> {
                try {
                    ensureTrackArtistsLoadedAsync(tid, target, () -> { });
                } catch (Exception ex) {
                    System.out.println("loadModels: ensureTrackArtistsLoadedAsync error for trackId=" + tid + " -> " + ex.getMessage());
                }
            });
        }
        smallPool.shutdown();
        // --- 15) Playlists (bulk load) ---
        playlists.clear();
        String sqlPlaylists = "SELECT PlaylistID, Title, Author, Description, CreationDate FROM Playlist";
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sqlPlaylists)) {
            String sqlSongsInPl = """
    SELECT s.SongID FROM Song s JOIN SongsPlaylists sp ON s.SongID = sp.SongID WHERE sp.PlaylistID = ? ORDER BY sp.Position, sp.CreatedAt, sp.SongID
    """;
            while (rs.next()) {
                long playlistId = rs.getLong("PlaylistID");
                String title = rs.getString("Title");
                String author = rs.getString("Author");
                String description = rs.getString("Description");
                String date = rs.getString("CreationDate");
                List<Song> playlistSongs = new ArrayList<>();
                try (PreparedStatement ps2 = conn.prepareStatement(sqlSongsInPl)) {
                    ps2.setLong(1, playlistId);
                    try (ResultSet rs2 = ps2.executeQuery()) {
                        while (rs2.next()) {
                            long songId = rs2.getLong("SongID");
                            Song s = songById.get(songId);
                            if (s != null) playlistSongs.add(s);
                        }
                    }
                }
                ObservableList<Song> singleList = FXCollections.observableArrayList(playlistSongs);
                Playlist pl = new Playlist(playlistId, title, author == null ? "CustomPlaylist" : author, description, date, null, singleList);
                playlists.add(pl);
            }
        }
        System.out.println("loadModels: finished loading playlists count=" + playlists.size());
    }

    public void attachSavedSongArtistsToModels(Map<Long, Artist> artistById, Map<Long, Song> songById, Connection conn) {
        List<Artist> artists = owner.getArtists();
        System.out.println("attachSavedSongArtistsToModels: entry");
        if (artistById == null || songById == null || conn == null) {
            System.out.println("attachSavedSongArtistsToModels: missing args -> aborting");
            return;
        }

        if (songById.isEmpty()) {
            System.out.println("attachSavedSongArtistsToModels: no songs to attach -> exit");
            return;
        }

        // Build IN clause only for existing song IDs (avoid scanning whole SongArtist)
        List<Long> songIds = new ArrayList<>(songById.keySet());
        String placeholders = songIds.stream().map(x -> "?").collect(Collectors.joining(","));
        String sql = "SELECT SongID, ArtistID FROM SongArtist WHERE SongID IN (" + placeholders + ")";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            for (Long id : songIds) ps.setLong(idx++, id);
            try (ResultSet rs = ps.executeQuery()) {
                // collect artist IDs that we need to load
                Set<Long> missingArtistIds = new HashSet<>();
                Map<Long, List<Long>> songToArtistIds = new HashMap<>();

                while (rs.next()) {
                    long songId = rs.getLong("SongID");
                    long artistId = rs.getLong("ArtistID");

                    songToArtistIds.computeIfAbsent(songId, k -> new ArrayList<>()).add(artistId);
                    if (!artistById.containsKey(artistId)) missingArtistIds.add(artistId);
                }

                // Batch load missing artists
                if (!missingArtistIds.isEmpty()) {
                    List<Long> ids = new ArrayList<>(missingArtistIds);
                    final int CHUNK = 500;
                    for (int i = 0; i < ids.size(); i += CHUNK) {
                        int to = Math.min(ids.size(), i + CHUNK);
                        List<Long> sub = ids.subList(i, to);
                        String ph = sub.stream().map(x -> "?").collect(Collectors.joining(","));
                        String q = "SELECT ArtistID, Name, Biography FROM Artist WHERE ArtistID IN (" + ph + ") "
                                + "AND lower(trim(Name)) NOT IN ('varios artistas', 'various artists')";
                        try (PreparedStatement p2 = conn.prepareStatement(q)) {
                            int j = 1;
                            for (Long v : sub) p2.setLong(j++, v);
                            try (ResultSet r2 = p2.executeQuery()) {
                                while (r2.next()) {
                                    long aid = r2.getLong(1);
                                    String name = r2.getString(2);
                                    String bio = r2.getString(3);
                                    if (!artistById.containsKey(aid)) {
                                        Artist a = new Artist(aid, name, bio, new ArrayList<>());
                                        artists.add(a);
                                        artistById.put(aid, a);
                                    }
                                }
                            }
                        }
                    }
                }

                // Attach artists to songs
                for (Map.Entry<Long, List<Long>> e : songToArtistIds.entrySet()) {
                    long sid = e.getKey();
                    Song song = songById.get(sid);
                    if (song == null) continue;
                    if (song.getArtist() == null) song.setArtist(new ArrayList<>());
                    for (Long aid : e.getValue()) {
                        Artist a = artistById.get(aid);
                        if (a == null) continue;
                        boolean dup = song.getArtist().stream().anyMatch(ar ->
                                (ar.getArtistID() > 0 && ar.getArtistID() == a.getArtistID()) ||
                                        (ar.getName() != null && a.getName() != null && ar.getName().equalsIgnoreCase(a.getName()))
                        );
                        if (!dup) {
                            song.getArtist().add(a);
                        }
                    }
                }

                System.out.println("attachSavedSongArtistsToModels: association complete");
            }
        } catch (SQLException ex) {
            System.out.println("attachSavedSongArtistsToModels: error -> " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    public void loadModelsForAlbum(Connection connection, long albumId) throws SQLException {
        albumModelHydrationService.loadModelsForAlbum(connection, albumId);
    }

}
