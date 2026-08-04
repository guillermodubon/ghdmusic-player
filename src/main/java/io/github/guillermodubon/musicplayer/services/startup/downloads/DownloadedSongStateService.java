package io.github.guillermodubon.musicplayer.services.startup.downloads;

import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.DeezerApiMetaData;
import io.github.guillermodubon.musicplayer.models.Genre;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.utils.FileNameUtils;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Encapsulates the metadata fallback and in-memory state changes performed
 * after a media download. StartUpService remains the public facade used by
 * existing callers.
 */
public final class DownloadedSongStateService {

    private final StartUpService owner;
    private final DeezerApiService deezerService;
    private final List<Album> albums;
    private final List<Song> songs;
    private final List<Playlist> playlists;
    private final Map<String, String> titleToPath;

    public DownloadedSongStateService(StartUpService owner, DeezerApiService deezerService) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.deezerService = Objects.requireNonNull(deezerService, "deezerService");
        this.albums = owner.getAlbums();
        this.songs = owner.getSongs();
        this.playlists = owner.getPlaylists();
        this.titleToPath = owner.titleToPathIndex();
    }

    public DeezerApiMetaData resolveDownloadMetadata(Song sourceSong, File finalFile) {
        if (sourceSong == null && finalFile == null) {
            return null;
        }

        long sourceId = sourceSong == null ? 0L : Math.max(0L, sourceSong.getSongID());
        if (sourceId > 0) {
            Optional<Song> local = findSongById(sourceId);
            if (local.isPresent()) {
                return buildMetadataFallbackFromSong(local.get(), finalFile);
            }
        }

        String query = buildMetadataLookupQuery(sourceSong, finalFile);
        if (!query.isBlank()) {
            try {
                List<DeezerApiMetaData> results = deezerService.getApiObjectsList(List.of(query));
                if (results != null && !results.isEmpty()) {
                    return results.get(0);
                }
            } catch (Exception error) {
                System.out.println("resolveDownloadMetadata: Deezer lookup failed -> "
                        + Optional.ofNullable(error.getMessage()).orElse("null"));
            }
        }

        return buildMetadataFallbackFromSong(sourceSong, finalFile);
    }

    public void refreshDownloadedSongCaches(DeezerApiMetaData meta, File finalFile) {
        if (finalFile == null || !finalFile.exists() || !finalFile.isFile()) {
            return;
        }

        String path = finalFile.getAbsolutePath();
        String fileTitle = FileNameUtils.withoutExtension(finalFile.getName());
        if (!fileTitle.isBlank()) {
            owner.putTitleToPath(fileTitle, path);
        }

        if (meta != null) {
            if (meta.getSongName() != null && !meta.getSongName().isBlank()) {
                owner.putTitleToPath(meta.getSongName(), path);
            }

            long trackId = meta.getTrackId();
            if (trackId > 0) {
                markSongLocalInMemory(trackId, path);
            }
        }

        findCanonicalDownloadedSong(meta, finalFile).ifPresent(song -> {
            song.setLocal(true);
            song.setFilePath(path);
        });
    }

    public void markSongLocalInMemory(long trackId, String path) {
        if (trackId <= 0 || path == null || path.isBlank()) {
            return;
        }

        synchronized (songs) {
            for (Song song : songs) {
                if (song != null && song.getSongID() == trackId) {
                    song.setLocal(true);
                    song.setFilePath(path);
                }
            }
        }

        synchronized (albums) {
            for (Album album : albums) {
                if (album == null || album.getSongList() == null) continue;
                for (Song song : album.getSongList()) {
                    if (song != null && song.getSongID() == trackId) {
                        song.setLocal(true);
                        song.setFilePath(path);
                    }
                }
            }
        }

        synchronized (playlists) {
            for (Playlist playlist : playlists) {
                if (playlist == null || playlist.getSongList() == null) continue;
                for (Song song : playlist.getSongList()) {
                    if (song != null && song.getSongID() == trackId) {
                        song.setLocal(true);
                        song.setFilePath(path);
                    }
                }
            }
        }
    }

    public void markSongUnavailableInMemory(Song target, String missingPath) {
        if (target == null) return;

        markSongUnavailable(target);

        synchronized (songs) {
            songs.forEach(song -> markUnavailableIfMatches(song, target));
        }

        synchronized (albums) {
            for (Album album : albums) {
                if (album == null || album.getSongList() == null) continue;
                album.getSongList().forEach(song -> markUnavailableIfMatches(song, target));
            }
        }

        synchronized (playlists) {
            for (Playlist playlist : playlists) {
                if (playlist == null || playlist.getSongList() == null) continue;
                playlist.getSongList().forEach(song -> markUnavailableIfMatches(song, target));
            }
        }

        if (missingPath != null && !missingPath.isBlank()) {
            titleToPath.entrySet().removeIf(entry -> missingPath.equals(entry.getValue()));
        }
    }

    public Optional<Song> findCanonicalDownloadedSong(DeezerApiMetaData meta, File finalFile) {
        List<Song> candidates = new ArrayList<>();
        synchronized (songs) {
            candidates.addAll(songs);
        }

        Optional<Song> byId = findCanonicalByTrackId(candidates, meta, finalFile);
        if (byId.isPresent()) return byId;

        Optional<Song> byPath = findCanonicalByPath(candidates, finalFile);
        if (byPath.isPresent()) return byPath;

        return findCanonicalByTitle(candidates, meta, finalFile);
    }

    private void markUnavailableIfMatches(Song candidate, Song target) {
        if (sameSongReference(candidate, target)) {
            markSongUnavailable(candidate);
        }
    }

    private void markSongUnavailable(Song song) {
        if (song == null) return;
        song.setLocal(false);
        song.setFilePath(null);
    }

    private boolean sameSongReference(Song left, Song right) {
        if (left == null || right == null) return false;
        if (left.getSongID() > 0 && right.getSongID() > 0) {
            return left.getSongID() == right.getSongID();
        }

        String leftPath = left.getFilePath();
        String rightPath = right.getFilePath();
        if (leftPath != null && rightPath != null && !leftPath.isBlank() && !rightPath.isBlank()) {
            return leftPath.equalsIgnoreCase(rightPath);
        }

        return Objects.equals(left.getTitle(), right.getTitle());
    }

    private Optional<Song> findCanonicalByTrackId(List<Song> candidates,
                                                   DeezerApiMetaData meta,
                                                   File finalFile) {
        if (meta == null || meta.getTrackId() <= 0) return Optional.empty();

        long trackId = meta.getTrackId();
        String path = finalFile == null ? "" : finalFile.getAbsolutePath();
        return candidates.stream()
                .filter(Objects::nonNull)
                .filter(song -> song.getSongID() == trackId)
                .peek(song -> {
                    song.setLocal(true);
                    if (!path.isBlank()) song.setFilePath(path);
                })
                .findFirst();
    }

    private Optional<Song> findCanonicalByPath(List<Song> candidates, File finalFile) {
        if (finalFile == null) return Optional.empty();

        String path = normalizePath(finalFile.getAbsolutePath());
        if (path.isBlank()) return Optional.empty();

        return candidates.stream()
                .filter(Objects::nonNull)
                .filter(song -> normalizePath(song.getFilePath()).equals(path))
                .peek(song -> {
                    song.setLocal(true);
                    song.setFilePath(finalFile.getAbsolutePath());
                })
                .findFirst();
    }

    private Optional<Song> findCanonicalByTitle(List<Song> candidates,
                                                 DeezerApiMetaData meta,
                                                 File finalFile) {
        String metaTitle = meta == null ? "" : normalizeTitle(meta.getSongName());
        String fileTitle = finalFile == null ? "" : normalizeTitle(FileNameUtils.withoutExtension(finalFile.getName()));
        if (metaTitle.isBlank() && fileTitle.isBlank()) return Optional.empty();

        return candidates.stream()
                .filter(Objects::nonNull)
                .filter(song -> {
                    String songTitle = normalizeTitle(song.getTitle());
                    return !songTitle.isBlank() && (songTitle.equals(metaTitle) || songTitle.equals(fileTitle));
                })
                .sorted(Comparator.comparing(Song::isLocal).reversed())
                .peek(song -> {
                    song.setLocal(true);
                    if (finalFile != null) song.setFilePath(finalFile.getAbsolutePath());
                })
                .findFirst();
    }

    private Optional<Song> findSongById(long trackId) {
        if (trackId <= 0) return Optional.empty();
        synchronized (songs) {
            return songs.stream()
                    .filter(Objects::nonNull)
                    .filter(song -> song.getSongID() == trackId)
                    .findFirst();
        }
    }

    private String buildMetadataLookupQuery(Song sourceSong, File finalFile) {
        if (sourceSong != null) {
            String title = Optional.ofNullable(sourceSong.getTitle()).orElse("").trim();
            String artist = sourceSong.getArtist() == null || sourceSong.getArtist().isEmpty()
                    ? ""
                    : sourceSong.getArtist().stream()
                    .filter(Objects::nonNull)
                    .map(Artist::getName)
                    .filter(name -> name != null && !name.isBlank())
                    .findFirst()
                    .orElse("");

            String query = (title + " " + artist).trim();
            if (!query.isBlank()) return query;
        }

        return finalFile == null ? "" : FileNameUtils.withoutExtension(finalFile.getName()).trim();
    }

    private DeezerApiMetaData buildMetadataFallbackFromSong(Song sourceSong, File finalFile) {
        if (sourceSong == null) return null;

        Album album = sourceSong.getAlbum();
        Genre genre = album == null ? null : album.getGenre();
        List<Artist> albumArtists = album != null && album.getArtist() != null ? album.getArtist() : List.of();

        /* Keep each owner name paired with its own Deezer ID. */
        List<String> albumArtistNames = new ArrayList<>();
        List<Long> albumArtistIds = new ArrayList<>();
        java.util.Set<String> albumArtistIdentityKeys = new LinkedHashSet<>();
        for (Artist artist : albumArtists) {
            if (artist == null || artist.getName() == null || artist.getName().isBlank()) {
                continue;
            }

            String name = artist.getName().trim();
            long id = Math.max(0L, artist.getArtistID());
            String identityKey = id > 0
                    ? "id:" + id
                    : "name:" + name.toLowerCase(Locale.ROOT);
            if (!albumArtistIdentityKeys.add(identityKey)) {
                continue;
            }

            albumArtistNames.add(name);
            albumArtistIds.add(id);
        }

        // Keep singles with track id 0 discoverable when the source song still
        // carries artist data from the screen that initiated the download.
        boolean hasRealAlbumArtist = albumArtistNames.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(name -> name.trim().toLowerCase(Locale.ROOT))
                .anyMatch(name -> !name.equals("unknown")
                        && !name.equals("unknown artist")
                        && !name.equals("desconocido"));

        if (!hasRealAlbumArtist && sourceSong.getArtist() != null) {
            for (Artist artist : sourceSong.getArtist()) {
                if (artist == null || artist.getName() == null || artist.getName().isBlank()) {
                    continue;
                }

                String name = artist.getName().trim();
                long id = Math.max(0L, artist.getArtistID());
                String identityKey = id > 0
                        ? "id:" + id
                        : "name:" + name.toLowerCase(Locale.ROOT);
                if (!albumArtistIdentityKeys.add(identityKey)) continue;

                albumArtistNames.add(name);
                albumArtistIds.add(id);
            }
        }

        List<String> contributorNames = artistNames(sourceSong);
        List<Long> contributorIds = artistIds(sourceSong);

        String title = Optional.ofNullable(sourceSong.getTitle()).orElse("").trim();
        if (title.isBlank() && finalFile != null) title = FileNameUtils.withoutExtension(finalFile.getName());

        return new DeezerApiMetaData(
                album == null ? 0L : album.getAlbumID(),
                title,
                albumArtistNames,
                new ArrayList<>(),
                album == null || album.getName() == null ? "" : album.getName(),
                new ArrayList<>(),
                album == null || album.getReleaseDate() == null ? "" : album.getReleaseDate(),
                album == null || album.getRecordType() == null ? "" : album.getRecordType(),
                genre == null || genre.getName() == null ? "" : genre.getName(),
                title,
                contributorNames,
                new ArrayList<>(),
                Math.max(0, sourceSong.getTrackOrder()),
                album == null ? 0 : Math.max(0, album.getNumberOfTracks()),
                albumArtistIds,
                contributorIds,
                Math.max(0L, sourceSong.getSongID()),
                album == null ? null : album.getCoverUrl(),
                genre == null ? 0 : Math.max(0, genre.getGenreID())
        );
    }

    private List<String> artistNames(Song song) {
        if (song.getArtist() == null) return List.of();
        return song.getArtist().stream()
                .filter(Objects::nonNull)
                .map(Artist::getName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
    }

    private List<Long> artistIds(Song song) {
        if (song.getArtist() == null) return List.of();
        return song.getArtist().stream()
                .filter(Objects::nonNull)
                .map(artist -> Math.max(0L, artist.getArtistID()))
                .toList();
    }

    private static String normalizeTitle(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    private static String normalizePath(String value) {
        return value == null ? "" : value.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

}
