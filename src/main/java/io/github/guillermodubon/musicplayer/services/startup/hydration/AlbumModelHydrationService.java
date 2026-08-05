package io.github.guillermodubon.musicplayer.services.startup.hydration;

import javafx.collections.ObservableList;
import io.github.guillermodubon.musicplayer.models.*;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Hydrates one album and its related songs and artists after a focused database update.
 * This keeps full-library startup hydration independent from targeted album refreshes.
 */
final class AlbumModelHydrationService {

    private final StartUpService owner;

    AlbumModelHydrationService(StartUpService owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    void loadModelsForAlbum(Connection conn, long albumId) throws SQLException {
        List<Album> albums = owner.getAlbums();
        List<Song> songs = owner.getSongs();
        List<Artist> artists = owner.getArtists();
        ObservableList<Playlist> playlists = owner.getPlaylists();
        if (conn == null || conn.isClosed()) throw new SQLException("loadModelsForAlbum: connection null/closed");

        try (Statement st = conn.createStatement()) {
            try { st.execute("PRAGMA busy_timeout = 5000"); } catch (Exception ignore) {}
        } catch (SQLException ignore) {}

        Map<Long, Artist> artistById = new HashMap<>();
        Map<Long, Song> songById = new HashMap<>();
        Map<Long, Album> albumById = new HashMap<>();

        // 1) Load album base
        String sqlAlb = "SELECT AlbumID, GenreID, Name, RecordType, ReleaseDate, NumberOfTracks FROM Album WHERE AlbumID = ?";
        Album refreshed = null;
        try (PreparedStatement ps = conn.prepareStatement(sqlAlb)) {
            ps.setLong(1, albumId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong("AlbumID");
                    int genreId = rs.getInt("GenreID");
                    String name = rs.getString("Name");
                    String recordType = rs.getString("RecordType");
                    String releaseDate = rs.getString("ReleaseDate");
                    int numberOfTracks = rs.getInt("NumberOfTracks");
                    Genre g = null;
                    try (PreparedStatement psG = conn.prepareStatement("SELECT GenreID, Name FROM Genre WHERE GenreID = ?")) {
                        psG.setInt(1, genreId);
                        try (ResultSet r2 = psG.executeQuery()) {
                            if (r2.next()) g = new Genre(r2.getInt(1), r2.getString(2));
                        }
                    } catch (Exception ignore) {}
                    refreshed = new Album(id, name, new ArrayList<>(), g, recordType, releaseDate, new ArrayList<>(), new ArrayList<>(), numberOfTracks);
                    albumById.put(id, refreshed);
                }
            }
        }

        if (refreshed == null) {
            System.out.println("loadModelsForAlbum: album not found id=" + albumId);
            return;
        }

        // 2) Keep album images out of memory; cards/header resolve covers from DB on demand.

        // 3) Load album artists
        List<Artist> albumArtists = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT ar.ArtistID, ar.Name, ar.Biography FROM AlbumArtist aa JOIN Artist ar ON aa.ArtistID = ar.ArtistID WHERE aa.AlbumID = ? AND lower(trim(ar.Name)) NOT IN ('varios artistas', 'various artists')")) {
            ps.setLong(1, albumId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long aid = rs.getLong(1);
                    String name = rs.getString(2);
                    String bio = rs.getString(3);
                    Artist a = new Artist(aid, name, bio, new ArrayList<>());
                    albumArtists.add(a);
                    artistById.put(aid, a);
                }
                if (!albumArtists.isEmpty()) refreshed.setArtist(albumArtists);
            }
        } catch (Exception ignore) {}

        // 4) Load songs for the album (ordered)
        try (PreparedStatement ps = conn.prepareStatement("SELECT SongID, Title, TrackOrder, IsLocal, FilePath FROM Song WHERE Album = ? ORDER BY TrackOrder")) {
            ps.setLong(1, albumId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long sid = rs.getLong("SongID");
                    String title = rs.getString("Title");
                    int order = rs.getInt("TrackOrder");
                    boolean isLocal = rs.getInt("IsLocal") == 1;
                    String filePath = null;
                    try { filePath = rs.getString("FilePath"); } catch (Exception ignored) {}
                    Song s = new Song(sid, title, new ArrayList<>(), refreshed, filePath, order, isLocal);
                    songById.put(sid, s);
                    refreshed.getSongList().add(s);
                }
            }
        } catch (Exception ignore) {}

        // 5) Load SongArtist relations in bulk for these songs and batch-load missing artists
        if (!songById.isEmpty()) {
            String in = songById.keySet().stream().map(k -> "?").collect(Collectors.joining(","));
            String q = "SELECT SongID, ArtistID FROM SongArtist WHERE SongID IN (" + in + ")";
            try (PreparedStatement ps = conn.prepareStatement(q)) {
                int idx = 1;
                for (Long sid : songById.keySet()) ps.setLong(idx++, sid);
                Map<Long, List<Long>> songToArtistIds = new HashMap<>();
                Set<Long> missingArtistIds = new HashSet<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long songId = rs.getLong(1);
                        long aid = rs.getLong(2);
                        songToArtistIds.computeIfAbsent(songId, k -> new ArrayList<>()).add(aid);
                        if (!artistById.containsKey(aid)) missingArtistIds.add(aid);
                    }
                }

                // batch load missing artists
                if (!missingArtistIds.isEmpty()) {
                    List<Long> ids = new ArrayList<>(missingArtistIds);
                    final int CHUNK = 500;
                    for (int i = 0; i < ids.size(); i += CHUNK) {
                        int to = Math.min(ids.size(), i + CHUNK);
                        List<Long> sub = ids.subList(i, to);
                        String placeholders = sub.stream().map(x -> "?").collect(Collectors.joining(","));
                        String q2 = "SELECT ArtistID, Name, Biography FROM Artist WHERE ArtistID IN (" + placeholders + ") "
                                + "AND lower(trim(Name)) NOT IN ('varios artistas', 'various artists')";
                        try (PreparedStatement p2 = conn.prepareStatement(q2)) {
                            int j = 1;
                            for (Long v : sub) p2.setLong(j++, v);
                            try (ResultSet r2 = p2.executeQuery()) {
                                while (r2.next()) {
                                    long aid = r2.getLong(1);
                                    String name = r2.getString(2);
                                    String bio = r2.getString(3);
                                    if (!artistById.containsKey(aid)) {
                                        Artist a = new Artist(aid, name, bio, new ArrayList<>());
                                        artistById.put(aid, a);
                                    }
                                }
                            }
                        }
                    }

                    // Artist portraits are resolved from DB/Deezer on demand by the UI.
                }

                // attach artists to songs
                for (var entry : songToArtistIds.entrySet()) {
                    long songId = entry.getKey();
                    Song s = songById.get(songId);
                    if (s == null) continue;
                    for (long aid : entry.getValue()) {
                        Artist a = artistById.get(aid);
                        if (a == null) continue;
                        boolean dup = s.getArtist().stream().anyMatch(ar ->
                                (ar.getArtistID() > 0 && ar.getArtistID() == a.getArtistID())
                                        || (ar.getName() != null && a.getName() != null && ar.getName().equalsIgnoreCase(a.getName()))
                        );
                        if (!dup) s.getArtist().add(a);
                    }
                }
            } catch (Exception e) {
                System.out.println("loadModelsForAlbum: warning loading SongArtist relations -> " + e.getMessage());
            }
        }

        // 6) MERGE into StartUpService memory safely
        synchronized (albums) {
            Album finalRefreshed = refreshed;
            boolean present = albums.stream().anyMatch(a -> a.getAlbumID() == finalRefreshed.getAlbumID());
            if (!present) {
                albums.add(refreshed);
                System.out.println("loadModelsForAlbum: added album to memory id=" + refreshed.getAlbumID());
            } else {
                for (int i = 0; i < albums.size(); i++) {
                    Album ex = albums.get(i);
                    if (ex.getAlbumID() == refreshed.getAlbumID()) {
                        mergeAlbumIntoExisting(ex, refreshed);
                        System.out.println("loadModelsForAlbum: merged album id=" + refreshed.getAlbumID());
                        break;
                    }
                }
            }
        }

        synchronized (songs) {
            for (Song s : new ArrayList<>(refreshed.getSongList())) {
                Optional<Song> old = songs.stream().filter(x -> x.getSongID() == s.getSongID() && x.getSongID() > 0).findFirst();
                if (old.isPresent()) {
                    Song o = old.get();
                    o.setAlbum(refreshed);
                    o.setFilePath(s.getFilePath());
                    o.setLocal(s.isLocal());
                    o.getArtist().clear();
                    o.getArtist().addAll(s.getArtist());
                } else {
                    songs.add(s);
                }
            }
            for (Song so : songs) {
                if (so.getAlbum() != null && so.getAlbum().getAlbumID() == refreshed.getAlbumID()) so.setAlbum(refreshed);
            }
        }

        // artists: add any new artists found in artistById
        synchronized (artists) {
            for (Artist a : artistById.values()) {
                boolean exists = artists.stream().anyMatch(x -> x.getArtistID() == a.getArtistID() || (x.getName()!=null && a.getName()!=null && x.getName().equalsIgnoreCase(a.getName())));
                if (!exists) artists.add(a);
                else {
                    // merge minimal fields if needed
                    for (int i = 0; i < artists.size(); i++) {
                        Artist ex = artists.get(i);
                        if ((a.getArtistID() > 0 && ex.getArtistID() == a.getArtistID()) || (ex.getName()!=null && a.getName()!=null && ex.getName().equalsIgnoreCase(a.getName()))) {
                            try {
                                if ((ex.getBiography() == null || ex.getBiography().isBlank()) && a.getBiography() != null) ex.setBiography(a.getBiography());
                            } catch (Exception ignored) {}
                            break;
                        }
                    }
                }
            }
        }

        // playlists: replace any song entries pointing to this album with canonical songs
        synchronized (playlists) {
            for (Playlist pl : playlists) {
                List<Song> sl = pl.getSongList();
                for (int i = 0; i < sl.size(); i++) {
                    Song ps = sl.get(i);
                    if (ps != null && ps.getAlbum() != null && ps.getAlbum().getAlbumID() == refreshed.getAlbumID()) {
                        Optional<Song> canonical = songs.stream()
                                .filter(x -> x.getSongID() == ps.getSongID() && x.getSongID() > 0)
                                .findFirst();
                        int finalI = i;
                        canonical.ifPresent(sCanon -> sl.set(finalI, sCanon));
                    }
                }
            }
        }

        System.out.println("loadModelsForAlbum: finished loading album=" + albumId + " songs=" + refreshed.getSongList().size());
    }

    static void mergeAlbumIntoExisting(Album existing, Album refreshed) {
        if (existing == null || refreshed == null) return;

        existing.setName(refreshed.getName() != null ? refreshed.getName() : existing.getName());
        existing.setReleaseDate(refreshed.getReleaseDate() != null ? refreshed.getReleaseDate() : existing.getReleaseDate());
        existing.setNumberOfTracks(refreshed.getNumberOfTracks() > 0 ? refreshed.getNumberOfTracks() : existing.getNumberOfTracks());
        existing.setGenre(refreshed.getGenre() != null ? refreshed.getGenre() : existing.getGenre());

        if (refreshed.getArtist() != null && !refreshed.getArtist().isEmpty()) {
            existing.setArtist(new ArrayList<>(refreshed.getArtist()));
        }

        if (refreshed.getSongList() != null && !refreshed.getSongList().isEmpty()) {
            if (existing.getSongList() == null || existing.getSongList().isEmpty()) {
                existing.setSongList(new ArrayList<>(refreshed.getSongList()));
            } else {
                Map<Integer, Song> byOrder = existing.getSongList().stream()
                        .filter(Objects::nonNull)
                        .collect(Collectors.toMap(Song::getTrackOrder, s -> s, (a, b) -> a));
                for (Song sRef : refreshed.getSongList()) {
                    Song existSong = byOrder.get(sRef.getTrackOrder());
                    if (existSong == null) {
                        // try to find by normalized title if trackOrder didn't match
                        Optional<Song> byTitle = existing.getSongList().stream()
                                .filter(x -> x.getTitle() != null && sRef.getTitle() != null
                                        && x.getTitle().trim().equalsIgnoreCase(sRef.getTitle().trim()))
                                .findFirst();
                        if (byTitle.isPresent()) {
                            Song found = byTitle.get();
                            if (found.getAlbum() == null || found.getAlbum().getAlbumID() != refreshed.getAlbumID()) {
                                found.setAlbum(existing);
                            }
                        } else {
                            existing.getSongList().add(sRef);
                        }
                    } else {
                        if (existSong.getAlbum() == null || existSong.getAlbum().getAlbumID() != refreshed.getAlbumID()) {
                            existSong.setAlbum(existing);
                        }
                    }
                }
                existing.getSongList().sort(Comparator.comparingInt(Song::getTrackOrder));
            }
        }
    }

    static String normalizeTitleForMatch(String t) {
        if (t == null) return "";
        return t.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    static String stripExtension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(0, dot) : name;
    }

}
