package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services;

import io.github.guillermodubon.musicplayer.models.ManifestEntry;

import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext;
import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.manifest.ManifestService;
import io.github.guillermodubon.musicplayer.services.manifest.ManifestSyncService;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Owns the local/manifest view of songs shown by a PlayerMenu.
 *
 * Keeping this state outside the UI service prevents local-file reconciliation
 * from being mixed with list layout and cell rendering.
 */
final class PlayerMenuSongLocalState {
    private final PlayerMenuContext context;
    private final ManifestSyncService manifestSyncService = new ManifestSyncService();
    private final Object manifestLock = new Object();

    private volatile Map<String, ManifestEntry> cachedManifest;
    private volatile Set<Long> cachedManifestSongIds = Set.of();
    private volatile Set<String> cachedManifestKeys = Set.of();
    private StartUpService startUpService;

    PlayerMenuSongLocalState(PlayerMenuContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    void bind(StartUpService service) {
        this.startUpService = service;
    }

    void clearManifestCache() {
        synchronized (manifestLock) {
            cachedManifest = null;
            cachedManifestSongIds = Set.of();
            cachedManifestKeys = Set.of();
        }
    }

    boolean shouldUsePlayableSongItem(Song song) {
        if (song == null || !song.isLocal()) {
            return false;
        }

        if (hasUsableAudioFile(song.getFilePath())) {
            return true;
        }

        // Never recover a missing explicit path using a similarly named file.
        if (song.getFilePath() != null && !song.getFilePath().isBlank()) {
            return false;
        }

        if (!matchesManifestAsPlayable(song) || startUpService == null) {
            return false;
        }

        try {
            Optional<String> resolvedPath = startUpService.resolvePathForSong(song);
            if (resolvedPath.isPresent() && hasUsableAudioFile(resolvedPath.get())) {
                song.setFilePath(resolvedPath.get());
                return true;
            }
        } catch (Exception ignored) {
        }

        return false;
    }

    void rebuildCurrentPlayableListFromMaster() {
        List<Song> playable = new ArrayList<>();
        List<Song> master = context.getMasterSongList();
        if (master != null) {
            for (Song song : master) {
                if (shouldUsePlayableSongItem(song)) {
                    playable.add(song);
                }
            }
        }
        context.setCurrentSongList(playable);
    }

    List<Song> reconcileLocalSongsBeforeRender(List<Song> initialSongs) {
        List<Song> viewSongs = initialSongs == null
                ? new ArrayList<>()
                : new ArrayList<>(initialSongs);

        if (viewSongs.isEmpty() || startUpService == null) {
            return viewSongs;
        }

        List<Song> librarySnapshot;
        try {
            List<Song> serviceSongs = startUpService.getSongs();
            synchronized (serviceSongs) {
                librarySnapshot = new ArrayList<>(serviceSongs);
            }
        } catch (Exception ignored) {
            librarySnapshot = List.of();
        }

        Map<Long, Song> localById = new HashMap<>();
        Map<String, List<Song>> localByTitle = new HashMap<>();

        for (Song librarySong : librarySnapshot) {
            String playablePath = resolvePlayableLocalPath(librarySong);
            if (playablePath == null) {
                continue;
            }

            librarySong.setLocal(true);
            librarySong.setFilePath(playablePath);

            if (librarySong.getSongID() > 0) {
                localById.putIfAbsent(librarySong.getSongID(), librarySong);
            }

            String titleKey = normalizedSongTitle(librarySong.getTitle());
            if (!titleKey.isBlank()) {
                localByTitle.computeIfAbsent(titleKey, ignored -> new ArrayList<>()).add(librarySong);
            }
        }

        for (Song viewSong : viewSongs) {
            if (viewSong == null) {
                continue;
            }

            Song localSong = findLocalCounterpart(viewSong, localById, localByTitle);
            if (localSong == null) {
                continue;
            }

            String playablePath = resolvePlayableLocalPath(localSong);
            if (playablePath == null) {
                continue;
            }

            // Preserve the remote song object so its full playlist metadata remains intact.
            viewSong.setLocal(true);
            viewSong.setFilePath(playablePath);
            enrichViewSongFromLocal(viewSong, localSong);
        }

        return viewSongs;
    }

    boolean sameSongForImmediateRefresh(Song current, Song downloaded) {
        if (current == null || downloaded == null) {
            return false;
        }

        long currentId = current.getSongID();
        long downloadedId = downloaded.getSongID();
        if (currentId > 0 && downloadedId > 0 && currentId == downloadedId) {
            return true;
        }

        String currentTitle = normalizeImmediateRefreshKey(current.getTitle());
        String downloadedTitle = normalizeImmediateRefreshKey(downloaded.getTitle());
        if (currentTitle.isBlank() || !currentTitle.equals(downloadedTitle)) {
            return false;
        }

        if (current.getAlbum() == null || downloaded.getAlbum() == null) {
            return true;
        }

        long currentAlbumId = current.getAlbum().getAlbumID();
        long downloadedAlbumId = downloaded.getAlbum().getAlbumID();
        if (currentAlbumId > 0 && downloadedAlbumId > 0) {
            return currentAlbumId == downloadedAlbumId;
        }

        String currentAlbumName = normalizeImmediateRefreshKey(current.getAlbum().getName());
        String downloadedAlbumName = normalizeImmediateRefreshKey(downloaded.getAlbum().getName());
        return currentAlbumName.isBlank()
                || downloadedAlbumName.isBlank()
                || currentAlbumName.equals(downloadedAlbumName);
    }

    boolean hasUsableAudioFile(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        try {
            File file = new File(path);
            return file.exists() && file.isFile() && file.canRead() && file.length() > 0L;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean matchesManifestAsPlayable(Song song) {
        Map<String, ManifestEntry> manifest = loadManifestOnce();
        if (song == null || manifest.isEmpty()) {
            return false;
        }

        long songId = song.getSongID();
        if (songId > 0 && cachedManifestSongIds.contains(songId)) {
            return true;
        }
        Set<String> candidates = buildManifestCandidates(song);
        for (String manifestKey : cachedManifestKeys) {
            if (manifestKey.isBlank()) {
                continue;
            }

            for (String candidate : candidates) {
                String normalizedCandidate = normalizeKey(candidate);
                if (!normalizedCandidate.isBlank()
                        && (manifestKey.equals(normalizedCandidate)
                        || manifestKey.contains(normalizedCandidate)
                        || normalizedCandidate.contains(manifestKey))) {
                    return true;
                }
            }
        }
        return false;
    }

    private Map<String, ManifestEntry> loadManifestOnce() {
        Map<String, ManifestEntry> local = cachedManifest;
        if (local != null) {
            return local;
        }

        synchronized (manifestLock) {
            if (cachedManifest != null) {
                return cachedManifest;
            }
            try {
                Map<String, ManifestEntry> loaded = manifestSyncService.load();
                cachedManifest = loaded == null ? Collections.emptyMap() : loaded;
                Set<Long> songIds = new LinkedHashSet<>();
                Set<String> manifestKeys = new LinkedHashSet<>();
                for (Map.Entry<String, ManifestEntry> entry : cachedManifest.entrySet()) {
                    if (entry == null) continue;
                    ManifestEntry value = entry.getValue();
                    if (value != null && value.deezerId > 0) songIds.add(value.deezerId);
                    String key = normalizeKey(entry.getKey());
                    if (!key.isBlank()) manifestKeys.add(key);
                }
                cachedManifestSongIds = Set.copyOf(songIds);
                cachedManifestKeys = Set.copyOf(manifestKeys);
            } catch (Exception ignored) {
                cachedManifest = Collections.emptyMap();
                cachedManifestSongIds = Set.of();
                cachedManifestKeys = Set.of();
            }
            return cachedManifest;
        }
    }

    private Set<String> buildManifestCandidates(Song song) {
        Set<String> candidates = new LinkedHashSet<>();
        if (song == null) {
            return candidates;
        }

        String title = normalizeKey(song.getTitle());
        if (!title.isBlank()) {
            candidates.add(title);
        }

        List<String> artistNames = new ArrayList<>();
        if (song.getArtist() != null) {
            for (Artist artist : song.getArtist()) {
                if (artist != null && artist.getName() != null && !artist.getName().isBlank()) {
                    artistNames.add(artist.getName().trim());
                }
            }
        }
        if (artistNames.isEmpty() && song.getAlbum() != null && song.getAlbum().getArtist() != null) {
            for (Artist artist : song.getAlbum().getArtist()) {
                if (artist != null && artist.getName() != null && !artist.getName().isBlank()) {
                    artistNames.add(artist.getName().trim());
                }
            }
        }

        if (!artistNames.isEmpty() && !title.isBlank()) {
            for (String artist : artistNames) {
                String normalizedArtist = normalizeKey(artist);
                if (!normalizedArtist.isBlank()) {
                    candidates.add(normalizedArtist + " - " + title);
                }
            }
        }

        String path = song.getFilePath();
        if (path != null && !path.isBlank()) {
            try {
                File file = new File(path);
                String absolutePath = normalizeKey(file.getAbsolutePath());
                String fileName = normalizeKey(file.getName());
                if (!absolutePath.isBlank()) candidates.add(absolutePath);
                if (!fileName.isBlank()) candidates.add(fileName);
                if (!fileName.isBlank() && !absolutePath.isBlank()) {
                    candidates.add(fileName + ":path:" + absolutePath);
                }
            } catch (Exception ignored) {
            }
        }
        return candidates;
    }

    private Song findLocalCounterpart(Song viewSong,
                                      Map<Long, Song> localById,
                                      Map<String, List<Song>> localByTitle) {
        if (viewSong == null) {
            return null;
        }

        if (viewSong.getSongID() > 0) {
            Song byId = localById.get(viewSong.getSongID());
            if (byId != null) {
                return byId;
            }
        }

        String titleKey = normalizedSongTitle(viewSong.getTitle());
        if (titleKey.isBlank()) {
            return null;
        }

        List<Song> candidates = localByTitle.get(titleKey);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }

        long viewAlbumId = viewSong.getAlbum() == null ? 0 : viewSong.getAlbum().getAlbumID();
        if (viewAlbumId > 0) {
            for (Song candidate : candidates) {
                long candidateAlbumId = candidate.getAlbum() == null ? 0 : candidate.getAlbum().getAlbumID();
                if (candidateAlbumId == viewAlbumId) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private String resolvePlayableLocalPath(Song song) {
        if (song == null || !song.isLocal()) {
            return null;
        }

        if (hasUsableAudioFile(song.getFilePath())) {
            return new File(song.getFilePath()).getAbsolutePath();
        }

        // A non-empty path is an exact identity, even when the file has been
        // deleted. It must not be replaced with another track by title.
        if (song.getFilePath() != null && !song.getFilePath().isBlank()) {
            return null;
        }

        if (startUpService == null) {
            return null;
        }
        try {
            Optional<String> resolved = startUpService.resolvePathForSong(song);
            if (resolved.isPresent() && hasUsableAudioFile(resolved.get())) {
                return new File(resolved.get()).getAbsolutePath();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void enrichViewSongFromLocal(Song viewSong, Song localSong) {
        try {
            if (viewSong.getSongID() <= 0 && localSong.getSongID() > 0) {
                viewSong.setSongID(localSong.getSongID());
            }
            if (viewSong.getTrackOrder() <= 0 && localSong.getTrackOrder() > 0) {
                viewSong.setTrackOrder(localSong.getTrackOrder());
            }
            if (!hasRenderableArtists(viewSong.getArtist()) && hasRenderableArtists(localSong.getArtist())) {
                viewSong.setArtist(new ArrayList<>(localSong.getArtist()));
            }

            Album viewAlbum = viewSong.getAlbum();
            Album localAlbum = localSong.getAlbum();
            if (viewAlbum == null && localAlbum != null) {
                viewSong.setAlbum(localAlbum);
            } else if (viewAlbum != null
                    && localAlbum != null
                    && !hasRenderableArtists(viewAlbum.getArtist())
                    && hasRenderableArtists(localAlbum.getArtist())) {
                viewAlbum.setArtist(new ArrayList<>(localAlbum.getArtist()));
            }
        } catch (Exception ignored) {
        }
    }

    private boolean hasRenderableArtists(List<Artist> artists) {
        if (artists == null || artists.isEmpty()) {
            return false;
        }
        for (Artist artist : artists) {
            if (artist == null || artist.getName() == null) {
                continue;
            }
            String name = artist.getName().trim().toLowerCase(Locale.ROOT);
            if (!name.isBlank() && !name.equals("unknown") && !name.equals("unknown artist")) {
                return true;
            }
        }
        return false;
    }

    private String normalizedSongTitle(String title) {
        return title == null ? "" : title.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String normalizeImmediateRefreshKey(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
