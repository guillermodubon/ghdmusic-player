package io.github.guillermodubon.musicplayer.services.startup.downloads;

import javafx.application.Platform;
import io.github.guillermodubon.musicplayer.repository.DbConnectionManager;
import io.github.guillermodubon.musicplayer.repository.dao.artist.ArtistDaoImpl;
import io.github.guillermodubon.musicplayer.repository.dao.artist.SongArtistDao;
import io.github.guillermodubon.musicplayer.repository.dao.artist.SongArtistDaoImpl;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.utils.ArtistIdentity;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves, persists and caches the artists associated with downloaded tracks. */
public final class DownloadedTrackArtistService {
    private final StartUpService owner;
    private final SongArtistDao songArtistDao = new SongArtistDaoImpl(null);
    private final Map<Long, List<Artist>> trackArtistsCache = new ConcurrentHashMap<>();

    public DownloadedTrackArtistService(StartUpService owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    public List<Artist> getCachedTrackArtists(long trackId) {
        if (trackId <= 0) return Collections.emptyList();

        List<Artist> cached = trackArtistsCache.get(trackId);
        if (cached != null) return Collections.unmodifiableList(cached);

        boolean isLocalSong;
        synchronized (owner.getSongs()) {
            isLocalSong = owner.getSongs().stream()
                    .anyMatch(song -> song != null
                            && song.getSongID() == trackId
                            && song.isLocal());
        }
        if (!isLocalSong) return Collections.emptyList();

        try {
            List<Artist> artists = songArtistDao.findBySongId(trackId);
            addArtistsToOwner(trackId, artists);
            return Collections.unmodifiableList(artists);
        } catch (SQLException error) {
            logSqlFailure("getCachedTrackArtists", error);
            return Collections.emptyList();
        }
    }

    public void ensureTrackArtistsLoadedAsync(long trackId,
                                              Song targetSong,
                                              Runnable onComplete) {
        if (trackId <= 0) {
            runOnFxThread(onComplete);
            return;
        }

        List<Artist> cached = trackArtistsCache.get(trackId);
        if (cached != null) {
            applyArtistsToSong(targetSong, cached);
            runOnFxThread(onComplete);
            return;
        }

        CompletableFuture.runAsync(() -> resolveAndPersist(trackId, targetSong, onComplete));
    }

    private void resolveAndPersist(long trackId, Song targetSong, Runnable onComplete) {
        try {
            boolean isLocalSong = isLocalSong(trackId, targetSong);
            if (isLocalSong) {
                List<Artist> fromDb = readArtistsFromDatabase(trackId);
                if (!fromDb.isEmpty()) {
                    addArtistsToOwner(trackId, fromDb);
                    applyArtistsToSong(targetSong, fromDb);
                    runOnFxThread(onComplete);
                    return;
                }
            }

            List<Artist> artists = normalizeArtists(fetchArtists(trackId), isLocalSong);
            if (isLocalSong && !artists.isEmpty()) {
                persistArtists(trackId, artists);
                addArtistsToOwner(trackId, artists);
            } else {
                trackArtistsCache.put(trackId, new ArrayList<>(artists));
            }

            mergeArtistsIntoSong(targetSong, artists);
            runOnFxThread(onComplete);
        } catch (Throwable error) {
            System.out.println("ensureTrackArtistsLoadedAsync: UNCAUGHT -> "
                    + Optional.ofNullable(error.getMessage()).orElse("null"));
            error.printStackTrace();
            runOnFxThread(onComplete);
        }
    }

    private boolean isLocalSong(long trackId, Song targetSong) {
        if (targetSong != null && targetSong.isLocal()) return true;
        synchronized (owner.getSongs()) {
            return owner.getSongs().stream()
                    .anyMatch(song -> song != null
                            && song.getSongID() == trackId
                            && song.isLocal());
        }
    }

    private List<Artist> readArtistsFromDatabase(long trackId) {
        try {
            return songArtistDao.findBySongId(trackId);
        } catch (SQLException error) {
            logSqlFailure("ensureTrackArtistsLoadedAsync: DB read failed", error);
            return List.of();
        }
    }

    private List<Artist> fetchArtists(long trackId) {
        try {
            List<Artist> artists = DeezerApiService.fetchTrackArtists(trackId);
            return artists == null ? List.of() : artists;
        } catch (Exception error) {
            System.out.println("ensureTrackArtistsLoadedAsync: Deezer fetch failed -> "
                    + Optional.ofNullable(error.getMessage()).orElse("null"));
            return List.of();
        }
    }

    private List<Artist> normalizeArtists(List<Artist> artists, boolean localSong) {
        List<Artist> unique = new ArrayList<>();
        for (Artist artist : artists) {
            if (artist == null || artist.getName() == null || artist.getName().isBlank()) continue;
            if (ArtistIdentity.isVariousArtists(artist)) continue;

            boolean duplicate = unique.stream().anyMatch(existing ->
                    (existing.getArtistID() > 0
                            && artist.getArtistID() > 0
                            && existing.getArtistID() == artist.getArtistID())
                            || (existing.getName() != null
                            && existing.getName().equalsIgnoreCase(artist.getName()))
            );
            if (!duplicate) {
                unique.add(localSong
                        ? artist
                        : new Artist(0, artist.getName(), null, new ArrayList<>()));
            }
        }
        return unique;
    }

    private void persistArtists(long trackId, List<Artist> artists) {
        try {
            DbConnectionManager.getInstance().runInTransaction(connection -> {
                try {
                    new SongArtistDaoImpl(connection).persistArtistsForSong(trackId, artists);
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }

                owner.getArtistBiographyService().hydrateMissingBiographies(
                        connection,
                        new ArtistDaoImpl(connection),
                        artists.stream()
                                .map(Artist::getName)
                                .filter(Objects::nonNull)
                                .toList()
                );
                try {
                    new SongArtistDaoImpl(connection).refreshBiographies(artists);
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
                return null;
            });
        } catch (Exception error) {
            System.out.println("ensureTrackArtistsLoadedAsync: persist artists failed -> "
                    + Optional.ofNullable(error.getMessage()).orElse("null"));
            error.printStackTrace();
        }
    }

    private void addArtistsToOwner(long trackId, List<Artist> artists) {
        if (artists == null || artists.isEmpty()) return;
        trackArtistsCache.put(trackId, new ArrayList<>(artists));

        synchronized (owner.getArtists()) {
            for (Artist artist : artists) {
                if (artist == null || artist.getArtistID() <= 0) continue;

                Artist existing = owner.getArtists().stream()
                        .filter(candidate -> candidate.getArtistID() == artist.getArtistID())
                        .findFirst()
                        .orElse(null);
                if (existing == null) {
                    owner.getArtists().add(artist);
                } else if (artist.getBiography() != null && !artist.getBiography().isBlank()) {
                    owner.getArtistBiographyService().updateMemoryArtistBiography(
                            owner.getArtists(),
                            artist.getArtistID(),
                            artist.getName(),
                            artist.getBiography()
                    );
                }
            }
        }
    }

    private void applyArtistsToSong(Song song, List<Artist> artists) {
        if (song == null) return;
        synchronized (song) {
            song.setArtist(new ArrayList<>(artists));
        }
    }

    private void mergeArtistsIntoSong(Song song, List<Artist> artists) {
        if (song == null) return;
        synchronized (song) {
            List<Artist> combined = new ArrayList<>();
            if (song.getArtist() != null) combined.addAll(song.getArtist());
            for (Artist artist : artists) {
                if (artist == null) continue;
                boolean duplicate = combined.stream().anyMatch(existing ->
                        (existing.getArtistID() > 0
                                && artist.getArtistID() > 0
                                && existing.getArtistID() == artist.getArtistID())
                                || (existing.getName() != null
                                && artist.getName() != null
                                && existing.getName().equalsIgnoreCase(artist.getName()))
                );
                if (!duplicate) combined.add(artist);
            }
            song.setArtist(combined);
        }
    }

    private void runOnFxThread(Runnable callback) {
        if (callback == null) return;
        if (Platform.isFxApplicationThread()) callback.run();
        else Platform.runLater(callback);
    }

    private void logSqlFailure(String operation, SQLException error) {
        System.out.println(operation + ": SQLException -> "
                + Optional.ofNullable(error.getMessage()).orElse("null")
                + " SQLState=" + error.getSQLState()
                + " code=" + error.getErrorCode());
        error.printStackTrace();
    }
}
