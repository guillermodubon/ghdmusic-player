package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services;

import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.DeezerApiMetaData;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.services.SongArtistResolver;
import io.github.guillermodubon.musicplayer.utils.SongAudioIdentity;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Pure song identity and metadata helpers used during download integration. */
final class PlayerMenuDownloadSongMatcher {

    private PlayerMenuDownloadSongMatcher() {
    }

    static String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT)
                .trim()
                .replaceAll("\\s+", " ");
    }

    static boolean matchesDownloadedSong(Song song, DeezerApiMetaData metadata) {
        if (song == null || metadata == null) {
            return false;
        }

        long downloadedId = metadata.getTrackId();
        if (downloadedId > 0 && song.getSongID() > 0 && song.getSongID() == downloadedId) {
            return true;
        }

        return SongAudioIdentity.matches(song, metadata);
    }

    static boolean matchesSong(Song current, Song candidate) {
        if (current == null || candidate == null) {
            return false;
        }

        long currentId = current.getSongID();
        long candidateId = candidate.getSongID();
        if (currentId > 0 && candidateId > 0 && currentId == candidateId) {
            return true;
        }

        return SongAudioIdentity.matches(current, candidate);
    }

    static boolean artistsOverlap(Song current, Song candidate) {
        return SongAudioIdentity.matches(current, candidate);
    }

    static void preserveViewSpecificData(Song oldSong, Song newSong) {
        if (oldSong == null || newSong == null) {
            return;
        }

        try {
            if (oldSong.getTrackOrder() > 0 && newSong.getTrackOrder() <= 0) {
                newSong.setTrackOrder(oldSong.getTrackOrder());
            }
        } catch (Exception ignored) {
        }

        try {
            Album oldAlbum = oldSong.getAlbum();
            Album newAlbum = newSong.getAlbum();
            if (oldAlbum != null && (newAlbum == null
                    || newAlbum.getAlbumID() <= 0
                    || !hasUsableArtists(newAlbum.getArtist()))) {
                newSong.setAlbum(oldAlbum);
            }
        } catch (Exception ignored) {
        }

        try {
            if (hasUsableArtists(oldSong.getArtist()) && !hasUsableArtists(newSong.getArtist())) {
                newSong.setArtist(new ArrayList<>(oldSong.getArtist()));
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Creates a local wrapper for the current PlayerMenu view. The downloaded
     * Song is canonical, but the visible Album and track order belong to the
     * selected edition and must not be replaced by the edition stored in the
     * global startup model.
     */
    static Song copyForView(Song viewSong, Song localSong) {
        if (localSong == null) {
            return null;
        }

        // This wrapper represents the selected edition, not the album from
        // which its reusable file was downloaded. Retaining the view ID keeps
        // its contributor hydration, ordering and cover source intact.
        long songId = viewSong != null && viewSong.getSongID() > 0
                ? viewSong.getSongID()
                : localSong.getSongID();
        String title = viewSong != null && viewSong.getTitle() != null
                && !viewSong.getTitle().isBlank()
                ? viewSong.getTitle()
                : localSong.getTitle();
        Album album = viewSong != null && viewSong.getAlbum() != null
                ? viewSong.getAlbum()
                : localSong.getAlbum();
        int trackOrder = viewSong != null && viewSong.getTrackOrder() > 0
                ? viewSong.getTrackOrder()
                : localSong.getTrackOrder();
        List<Artist> artists = SongArtistResolver.merge(
                viewSong == null ? List.of() : SongArtistResolver.resolveParticipants(viewSong),
                SongArtistResolver.resolveParticipants(localSong)
        );

        return new Song(
                songId,
                title,
                artists,
                album,
                localSong.getFilePath(),
                trackOrder,
                true
        );
    }

    static boolean hasUsableArtists(List<Artist> artists) {
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

    static boolean containsMatchingSong(List<Song> songs, Song candidate) {
        if (songs == null || candidate == null) {
            return false;
        }
        for (Song song : songs) {
            if (matchesSong(song, candidate)) {
                return true;
            }
        }
        return false;
    }

    static boolean sameSongSequence(List<Song> first, List<Song> second) {
        if (first == second) {
            return true;
        }
        if (first == null || second == null || first.size() != second.size()) {
            return false;
        }
        for (int index = 0; index < first.size(); index++) {
            if (!samePlayableSongReference(first.get(index), second.get(index))) {
                return false;
            }
        }
        return true;
    }

    static boolean samePlayableSongReference(Song first, Song second) {
        if (first == second) {
            return true;
        }
        if (first == null || second == null || !matchesSong(first, second)) {
            return false;
        }
        if (first.isLocal() != second.isLocal()) {
            return false;
        }
        return Objects.equals(normalizeFilePath(first.getFilePath()), normalizeFilePath(second.getFilePath()));
    }

    static String normalizeFilePath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return "";
        }
        try {
            return new File(filePath).getAbsoluteFile().toPath().normalize().toString();
        } catch (Exception ignored) {
            return filePath.trim();
        }
    }

    static boolean isPlayableLocalSong(Song song) {
        if (song == null || !song.isLocal()) {
            return false;
        }
        String filePath = song.getFilePath();
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        try {
            File file = new File(filePath);
            return file.exists() && file.isFile();
        } catch (Exception ignored) {
            return false;
        }
    }

    static Song makePlayableLocalSong(Song localSong, File file) {
        if (localSong == null) {
            return null;
        }

        File playableFile = file;
        if ((playableFile == null || !playableFile.exists() || !playableFile.isFile())
                && localSong.getFilePath() != null && !localSong.getFilePath().isBlank()) {
            try {
                playableFile = new File(localSong.getFilePath());
            } catch (Exception ignored) {
                playableFile = null;
            }
        }

        if (playableFile == null || !playableFile.exists() || !playableFile.isFile()) {
            return null;
        }

        localSong.setLocal(true);
        localSong.setFilePath(playableFile.getAbsolutePath());
        return localSong;
    }
}
