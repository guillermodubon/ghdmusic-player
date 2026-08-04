package io.github.guillermodubon.musicplayer.services.downloads.services;

import io.github.guillermodubon.musicplayer.services.downloads.context.DownloadTaskContext;
import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.DeezerApiMetaData;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.utils.ImageUtils;
import io.github.guillermodubon.musicplayer.utils.FileNameUtils;
import javafx.scene.image.Image;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class DownloadMetadataNormalizer {

    private static final String UNKNOWN = "Unknown";

    private DownloadMetadataNormalizer() {
    }

    public static DeezerApiMetaData normalize(
            DeezerApiMetaData metadata,
            DownloadTaskContext context,
            String desiredBaseName,
            File finalFile
    ) {
        DeezerApiMetaData normalized = metadata == null ? new DeezerApiMetaData() : metadata;
        String title = firstNonBlank(
                normalized.getSongName(),
                context == null ? null : context.getFetchedTitle(),
                context == null ? null : context.getCleanSongName(),
                desiredBaseName,
                finalFile == null ? null : FileNameUtils.withoutExtension(finalFile.getName()),
                UNKNOWN
        );

        normalized.setSongName(title);
        normalized.setSongFileName(firstNonBlank(
                normalized.getSongFileName(),
                finalFile == null ? null : FileNameUtils.withoutExtension(finalFile.getName()),
                title
        ));
        normalized.setAlbumName(firstNonBlank(normalized.getAlbumName(), title));
        int trackCount = Math.max(1, normalized.getNumberOfTracks());
        String recordType = firstNonBlank(normalized.getRecordType(), trackCount > 1 ? "album" : "single");
        if (trackCount > 1 && "single".equalsIgnoreCase(recordType)) {
            recordType = "album";
        }
        normalized.setRecordType(recordType);
        normalized.setGenre(firstNonBlank(normalized.getGenre(), UNKNOWN));
        normalized.setAlbumReleaseDate(firstNonBlank(normalized.getAlbumReleaseDate(), ""));
        normalized.setTrackOrder(Math.max(1, normalized.getTrackOrder()));
        normalized.setNumberOfTracks(trackCount);

        normalized.setAlbumArtistNames(nonNullList(normalized.getAlbumArtistNames()));
        normalized.setAlbumArtistIds(nonNullList(normalized.getAlbumArtistIds()));
        normalized.setAlbumArtistsPortraitBytes(nonNullList(normalized.getAlbumArtistsPortraitBytes()));
        normalized.setSongContributorNames(nonNullList(normalized.getSongContributorNames()));
        normalized.setSongContributorIds(nonNullList(normalized.getSongContributorIds()));
        normalized.setSongContributorsPortraitBytes(nonNullList(normalized.getSongContributorsPortraitBytes()));
        normalized.setAlbumCoverBytesList(nonNullList(normalized.getAlbumCoverBytesList()));

        enrichFromSourceSong(normalized, context);

        if (normalized.getAlbumArtistNames().isEmpty() && normalized.getSongContributorNames().isEmpty()) {
            normalized.setAlbumArtistNames(new ArrayList<>(List.of(UNKNOWN)));
        }

        return normalized;
    }

    /**
     * Keeps the metadata already rendered by PlayerMenu as the authoritative
     * fallback for a local song whose Deezer lookup returned no track.
     */
    private static void enrichFromSourceSong(
            DeezerApiMetaData metadata,
            DownloadTaskContext context
    ) {
        if (metadata == null || context == null || context.getSourceSong() == null) return;

        Song sourceSong = context.getSourceSong();
        Album sourceAlbum = sourceSong.getAlbum();
        List<Artist> sourceArtists = sourceArtists(sourceSong);

        if (metadata.getTrackId() <= 0 && sourceSong.getSongID() > 0) {
            metadata.setTrackId(sourceSong.getSongID());
        }
        if (metadata.getAlbumId() <= 0 && sourceAlbum != null && sourceAlbum.getAlbumID() > 0) {
            metadata.setAlbumId(sourceAlbum.getAlbumID());
        }

        if (metadata.getTrackId() <= 0 && sourceSong.getTitle() != null && !sourceSong.getTitle().isBlank()) {
            metadata.setSongName(sourceSong.getTitle().trim());
        }

        boolean hasRealAlbumArtists = hasRealArtists(metadata.getAlbumArtistNames());
        if (!hasRealAlbumArtists && !sourceArtists.isEmpty()) {
            metadata.setAlbumArtistNames(namesOf(sourceArtists));
            metadata.setAlbumArtistIds(idsOf(sourceArtists));
            metadata.setSongContributorNames(new ArrayList<>());
            metadata.setSongContributorIds(new ArrayList<>());
        } else if (hasRealAlbumArtists && !sourceArtists.isEmpty()) {
            mergeSourceContributors(metadata, sourceArtists);
        }

        if (metadata.getTrackId() <= 0 && sourceAlbum != null) {
            if ((metadata.getAlbumName() == null || metadata.getAlbumName().isBlank()
                    || metadata.getAlbumName().equalsIgnoreCase(metadata.getSongName()))
                    && sourceAlbum.getName() != null && !sourceAlbum.getName().isBlank()) {
                metadata.setAlbumName(sourceAlbum.getName().trim());
            }
            if ((metadata.getRecordType() == null || metadata.getRecordType().isBlank())
                    && sourceAlbum.getRecordType() != null && !sourceAlbum.getRecordType().isBlank()) {
                metadata.setRecordType(sourceAlbum.getRecordType());
            }
            if (metadata.getNumberOfTracks() <= 1 && sourceAlbum.getNumberOfTracks() > 0) {
                metadata.setNumberOfTracks(sourceAlbum.getNumberOfTracks());
            }
        }

        /*
         * The list row already owns the best cover available to the user.
         * Convert it once on the persistence executor so AlbumImage can keep
         * it even when the source URL later becomes unavailable.
         */
        if ((metadata.getAlbumCoverBytesList() == null || metadata.getAlbumCoverBytesList().isEmpty())
                && hasSourceCover(sourceAlbum)) {
            try {
                Image coverImage = context.getCoverImage();
                if (coverImage == null
                        || coverImage.isError()
                        || coverImage.getProgress() < 1.0
                        || coverImage.getPixelReader() == null
                        || coverImage.getUrl() == null
                        || coverImage.getUrl().isBlank()
                        || coverImage.getUrl().contains("defaultPlaylist.png")) {
                    return;
                }

                byte[] cover = ImageUtils.toByteArray(coverImage);
                if (cover != null && cover.length > 0) {
                    metadata.setAlbumCoverBytesList(new ArrayList<>(List.of(cover)));
                }
            } catch (Exception ignored) {
                // DownloadedMediaPersistenceService also has a URL fallback.
            }
        }
    }

    private static List<Artist> sourceArtists(Song song) {
        Set<String> identities = new LinkedHashSet<>();
        List<Artist> result = new ArrayList<>();
        if (song == null) return result;

        List<Artist> candidates = song.getArtist();
        if (candidates == null || candidates.isEmpty()) {
            Album album = song.getAlbum();
            candidates = album == null ? List.of() : album.getArtist();
        }

        if (candidates == null) return result;
        for (Artist artist : candidates) {
            if (artist == null || artist.getName() == null || artist.getName().isBlank()) continue;
            String name = artist.getName().trim();
            String key = artist.getArtistID() > 0
                    ? "id:" + artist.getArtistID()
                    : "name:" + name.toLowerCase(Locale.ROOT);
            if (identities.add(key)) result.add(artist);
        }
        return result;
    }

    private static void mergeSourceContributors(DeezerApiMetaData metadata, List<Artist> sourceArtists) {
        List<String> albumNames = metadata.getAlbumArtistNames();
        Set<String> albumKeys = new LinkedHashSet<>();
        if (albumNames != null) {
            for (String name : albumNames) {
                if (name != null && !name.isBlank()) albumKeys.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }

        List<String> contributorNames = new ArrayList<>(metadata.getSongContributorNames());
        List<Long> contributorIds = alignedIds(contributorNames, metadata.getSongContributorIds());
        Set<String> contributorKeys = new LinkedHashSet<>();
        for (String name : contributorNames) {
            if (name != null && !name.isBlank()) contributorKeys.add(name.trim().toLowerCase(Locale.ROOT));
        }

        for (Artist artist : sourceArtists) {
            String name = artist.getName().trim();
            String key = name.toLowerCase(Locale.ROOT);
            if (albumKeys.contains(key) || !contributorKeys.add(key)) continue;
            contributorNames.add(name);
            contributorIds.add(Math.max(0L, artist.getArtistID()));
        }

        metadata.setSongContributorNames(contributorNames);
        metadata.setSongContributorIds(contributorIds);
    }

    private static List<String> namesOf(List<Artist> artists) {
        return artists.stream().map(Artist::getName).filter(name -> name != null && !name.isBlank()).map(String::trim).toList();
    }

    private static List<Long> idsOf(List<Artist> artists) {
        return artists.stream().map(artist -> Math.max(0L, artist.getArtistID())).toList();
    }

    private static List<Long> alignedIds(List<String> names, List<Long> ids) {
        List<Long> result = new ArrayList<>();
        for (int index = 0; index < names.size(); index++) {
            result.add(ids != null && index < ids.size() && ids.get(index) != null
                    ? Math.max(0L, ids.get(index)) : 0L);
        }
        return result;
    }

    private static boolean hasRealArtists(List<String> names) {
        if (names == null) return false;
        return names.stream().filter(name -> name != null && !name.isBlank())
                .map(name -> name.trim().toLowerCase(Locale.ROOT))
                .anyMatch(name -> !name.equals(UNKNOWN.toLowerCase(Locale.ROOT))
                        && !name.equals("unknown artist")
                        && !name.equals("desconocido"));
    }

    private static boolean hasSourceCover(Album album) {
        return album != null && album.getCoverUrl() != null && !album.getCoverUrl().isBlank();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private static <T> List<T> nonNullList(List<T> values) {
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }
}
