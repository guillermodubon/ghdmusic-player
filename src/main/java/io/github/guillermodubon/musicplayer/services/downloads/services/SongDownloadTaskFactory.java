package io.github.guillermodubon.musicplayer.services.downloads.services;

import javafx.scene.image.Image;
import io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.services.ArtistLinksBuilder;
import io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.services.SongArtistResolver;
import io.github.guillermodubon.musicplayer.models.*;
import io.github.guillermodubon.musicplayer.services.downloads.DownloadTask;
import io.github.guillermodubon.musicplayer.services.downloads.context.DownloadTaskContext;
import io.github.guillermodubon.musicplayer.services.downloads.helpers.cache.DownloadUiCache;
import io.github.guillermodubon.musicplayer.services.downloads.preferences.DownloadPreferences;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class SongDownloadTaskFactory {

    public static final String SOURCE_TYPE_ALBUM = "ALBUM";
    public static final String SOURCE_TYPE_PLAYLIST = "PLAYLIST";
    public static final String SOURCE_TYPE_SINGLE = "SINGLE";
    public static final String SOURCE_TYPE_UNKNOWN = "UNKNOWN";

    private static final double DOWNLOAD_COVER_DECODE_SIZE = 320;
    private static final String DOWNLOAD_COVER_PREFERRED_TYPE = "xl";

    private static final ArtistLinksBuilder ARTIST_FORMATTER =
            new ArtistLinksBuilder();
    private static final Set<String> COMPACT_QUERY_STOP_WORDS = Set.of(
            "a", "an", "and", "at", "audio", "de", "del", "el", "feat",
            "for", "from", "ft", "in", "la", "las", "los", "me", "mi",
            "my", "of", "official", "on", "the", "to", "un", "una", "y"
    );

    private SongDownloadTaskFactory() {
    }

    /**
     * Creates a download task using the default download directory.
     *
     * The source collection is inferred from the song because the caller
     * did not provide an exact PlayerMenu source.
     */
    public static DownloadTask create(Song song) {
        return create(
                song,
                resolveTargetDir()
        );
    }

    /**
     * Creates a download task and infers its album or single context.
     *
     * No exact PlayerMenu Playlist model is available through this overload.
     */
    public static DownloadTask create(
            Song song,
            File targetDir
    ) {
        return create(
                song,
                targetDir,
                inferSourceCollectionId(song),
                inferSourceCollectionTitle(song),
                inferSourceCollectionType(song),
                null
        );
    }

    /**
     * Preferred compatibility overload for downloads launched from screens
     * that know the source ID, title and type, but do not have the complete
     * Playlist model.
     *
     * Existing callers can continue using this method unchanged.
     */
    public static DownloadTask create(
            Song song,
            File targetDir,
            Long sourceCollectionId,
            String sourceCollectionTitle,
            String sourceCollectionType
    ) {
        return create(
                song,
                targetDir,
                sourceCollectionId,
                sourceCollectionTitle,
                sourceCollectionType,
                null
        );
    }

    /**
     * Preferred overload for downloads launched directly from
     * PlayerMenuController.
     *
     * Besides preserving the source ID and type, this overload keeps the
     * complete Playlist model that was shown by PlayerMenuController when the
     * download started.
     *
     * The saved model can later be used by DownloadSidebarMenuController to
     * reopen the exact album, playlist or single without reconstructing an
     * incomplete PlayerMenu using only the source ID.
     */
    public static DownloadTask create(
            Song song,
            File targetDir,
            Long sourceCollectionId,
            String sourceCollectionTitle,
            String sourceCollectionType,
            Playlist sourcePlaylistModel
    ) {
        if (song == null) {
            return null;
        }

        List<Artist> participants =
                SongArtistResolver.resolveParticipants(song);

        List<String> searchQueries =
                buildDownloadQueries(
                        song,
                        participants
                );

        String query = searchQueries.isEmpty()
                ? ""
                : searchQueries.getFirst();

        String cleanTitle =
                DownloadTask.cleanTitle(song.getTitle());

        DownloadTaskContext context =
                new DownloadTaskContext(
                        query,
                        targetDir,
                        cleanTitle
                );

        context.setSearchQueries(searchQueries);

        context.setArtistForFile(
                ARTIST_FORMATTER.formatArtists(participants)
        );

        context.setFetchedTitle(song.getTitle());
        context.setCoverImage(resolveCover(song));
        context.setSourceIsSongItem(true);
        context.setMaxAttempts(3);
        context.setMetadataHint(buildMetadataHint(song));

        /*
         * Keep a view-specific snapshot instead of retaining the mutable
         * global Song instance. The same Deezer track can be rendered in a
         * single and in several album editions, each with its own Album and
         * trackOrder. Download persistence may refresh the canonical cache,
         * but it must never rewrite the edition from which this task started.
         */
        context.setSourceSong(snapshotSourceSong(song));

        /*
         * Basic identity of the album, playlist or single from which the
         * download originated.
         */
        context.setSourceCollectionId(sourceCollectionId);
        context.setSourceCollectionTitle(sourceCollectionTitle);
        context.setSourceCollectionType(
                normalizeSourceType(sourceCollectionType)
        );

        /*
         * Complete source model shown by PlayerMenuController.
         *
         * This intentionally stores the model only. It does not retain a
         * PlayerMenuController, ListView, Scene or any other JavaFX Node.
         */
        context.setSourcePlaylistModel(sourcePlaylistModel);

        DownloadUiCache.putAll(
                query,
                song.getTitle(),
                cleanTitle,
                context.getCoverImage()
        );

        return new DownloadTask(context);
    }

    private static Song snapshotSourceSong(Song source) {
        if (source == null) return null;

        Album sourceAlbum = source.getAlbum();
        Album albumSnapshot = null;
        if (sourceAlbum != null) {
            albumSnapshot = new Album(
                    sourceAlbum.getAlbumID(),
                    sourceAlbum.getName(),
                    sourceAlbum.getArtist() == null
                            ? new ArrayList<>()
                            : new ArrayList<>(sourceAlbum.getArtist()),
                    sourceAlbum.getGenre(),
                    sourceAlbum.getRecordType(),
                    sourceAlbum.getReleaseDate(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    sourceAlbum.getNumberOfTracks()
            );
            albumSnapshot.setCoverUrl(sourceAlbum.getCoverUrl());
        }

        return new Song(
                source.getSongID(),
                source.getTitle(),
                source.getArtist() == null
                        ? new ArrayList<>()
                        : new ArrayList<>(source.getArtist()),
                albumSnapshot,
                source.getFilePath(),
                source.getTrackOrder(),
                source.isLocal()
        );
    }

    /**
     * Convenience overload for playlist downloads without an exact source
     * model.
     */
    public static DownloadTask createForPlaylist(
            Song song,
            File targetDir,
            Long playlistId,
            String playlistTitle
    ) {
        return createForPlaylist(
                song,
                targetDir,
                playlistId,
                playlistTitle,
                null
        );
    }

    /**
     * Convenience overload for playlist downloads launched from a fully
     * hydrated PlayerMenu playlist.
     */
    public static DownloadTask createForPlaylist(
            Song song,
            File targetDir,
            Long playlistId,
            String playlistTitle,
            Playlist sourcePlaylistModel
    ) {
        return create(
                song,
                targetDir,
                playlistId,
                playlistTitle,
                SOURCE_TYPE_PLAYLIST,
                sourcePlaylistModel
        );
    }

    /**
     * Convenience overload for album downloads without an exact source model.
     */
    public static DownloadTask createForAlbum(
            Song song,
            File targetDir,
            Long albumId,
            String albumTitle
    ) {
        return createForAlbum(
                song,
                targetDir,
                albumId,
                albumTitle,
                null
        );
    }

    /**
     * Convenience overload for album downloads launched from a fully hydrated
     * PlayerMenu album model.
     */
    public static DownloadTask createForAlbum(
            Song song,
            File targetDir,
            Long albumId,
            String albumTitle,
            Playlist sourcePlaylistModel
    ) {
        return create(
                song,
                targetDir,
                albumId,
                albumTitle,
                SOURCE_TYPE_ALBUM,
                sourcePlaylistModel
        );
    }

    /**
     * Convenience overload for single-song contexts without the exact
     * PlayerMenu model.
     */
    public static DownloadTask createSingle(
            Song song,
            File targetDir
    ) {
        return createSingle(
                song,
                targetDir,
                null
        );
    }

    /**
     * Convenience overload for a single-song PlayerMenu that has a complete
     * source model.
     */
    public static DownloadTask createSingle(
            Song song,
            File targetDir,
            Playlist sourcePlaylistModel
    ) {
        return create(
                song,
                targetDir,
                song == null
                        ? null
                        : safePositiveLong(song.getSongID()),
                song == null
                        ? null
                        : song.getTitle(),
                SOURCE_TYPE_SINGLE,
                sourcePlaylistModel
        );
    }

    public static File resolveTargetDir() {
        File targetDir =
                DownloadPreferences.getDefaultDownloadsDirectory();

        File saved =
                DownloadPreferences.loadDownloadDirectory();

        if (saved != null
                && saved.exists()
                && saved.isDirectory()) {
            targetDir = saved;
        }

        return targetDir;
    }

    private static Image resolveCover(Song song) {
        Image cover = MediaImageResolver.songAlbumCover(
                song,
                DOWNLOAD_COVER_PREFERRED_TYPE,
                DOWNLOAD_COVER_DECODE_SIZE,
                DOWNLOAD_COVER_DECODE_SIZE
        );

        if (cover != null && !cover.isError()) {
            return cover;
        }

        return MediaImageResolver.defaultCover(
                DOWNLOAD_COVER_DECODE_SIZE,
                DOWNLOAD_COVER_DECODE_SIZE
        );
    }

    private static List<String> buildDownloadQueries(
            Song song,
            List<Artist> participants
    ) {
        String title =
                song != null && song.getTitle() != null
                        ? song.getTitle()
                        : "";

        LinkedHashSet<String> queries =
                new LinkedHashSet<>();

        List<String> artistNames =
                participants == null
                        ? List.of()
                        : participants.stream()
                          .filter(java.util.Objects::nonNull)
                          .map(Artist::getName)
                          .filter(name ->
                                  name != null
                                  && !name.isBlank()
                          )
                          .map(String::trim)
                          .limit(3)
                          .toList();

        if (!artistNames.isEmpty()) {
            String primaryArtist =
                    artistNames.getFirst();

            String qualifyingArtist =
                    requiresArtistQualifier(primaryArtist)
                            ? artistNames.stream()
                              .skip(1)
                              .filter(
                                      SongDownloadTaskFactory
                                      ::isSearchableArtistName
                              )
                              .findFirst()
                              .orElse("")
                            : "";

            queries.add(
                    buildDownloadQuery(
                            title,
                            primaryArtist,
                            qualifyingArtist
                    )
            );

            /*
             * Some YouTube searches return no entries when the complete song
             * title contains words that are commonly rewritten or restricted
             * by the platform. Keep the normal query first and add one compact
             * artist/title variant only as a fallback for those rare cases.
             */
            String compactArtist = isSearchableArtistName(primaryArtist)
                    ? primaryArtist
                    : qualifyingArtist;
            String compactQuery = buildCompactDownloadQuery(title, compactArtist);
            if (!compactQuery.isBlank()) {
                queries.add(compactQuery);
            }

            /*
             * These are fallback searches only. They do not modify the first
             * query when the primary artist already has a searchable name.
             */
            artistNames.stream()
                    .skip(1)
                    .map(name ->
                            buildDownloadQuery(
                                    title,
                                    name,
                                    ""
                            )
                    )
                    .forEach(queries::add);
        }

        if (queries.isEmpty()) {
            queries.add(
                    (title + " official audio").trim()
            );
        }

        return List.copyOf(queries);
    }

    private static String buildDownloadQuery(
            String title,
            String primaryArtist,
            String qualifyingArtist
    ) {
        String artistPart =
                qualifyingArtist == null
                        || qualifyingArtist.isBlank()
                        ? primaryArtist
                        : primaryArtist
                          + " "
                          + qualifyingArtist;

        return (
                title
                        + " official "
                        + artistPart
                        + " audio"
        ).trim();
    }

    private static String buildCompactDownloadQuery(
            String title,
            String artist
    ) {
        if (!isSearchableArtistName(artist)) {
            return "";
        }

        String keyword = firstSignificantTitleWord(title);
        return keyword.isBlank()
                ? artist.trim()
                : artist.trim() + " " + keyword;
    }

    private static String firstSignificantTitleWord(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }

        for (String token : title.trim().split("\\s+")) {
            String word = token.replaceAll("[^\\p{L}\\p{N}]", "");
            if (word.isBlank()) {
                continue;
            }

            String normalized = word.toLowerCase(Locale.ROOT);
            int length = word.codePointCount(0, word.length());
            if (length < 4
                    || COMPACT_QUERY_STOP_WORDS.contains(normalized)
                    || word.codePoints().allMatch(Character::isDigit)) {
                continue;
            }

            return word;
        }

        return "";
    }

    private static boolean requiresArtistQualifier(
            String artistName
    ) {
        return !isSearchableArtistName(artistName);
    }

    private static boolean isSearchableArtistName(
            String artistName
    ) {
        return artistName != null
                && artistName
                .codePoints()
                .anyMatch(Character::isLetterOrDigit);
    }

    private static DeezerApiMetaData buildMetadataHint(
            Song song
    ) {
        if (song == null) {
            return null;
        }

        Album album = song.getAlbum();

        List<Artist> albumArtists =
                album != null
                        && album.getArtist() != null
                        ? album.getArtist()
                        : List.of();

        /*
         * Keep owner names and Deezer IDs in the same positional list.
         * Filtering the two streams independently can associate artist A's
         * name with artist B's ID when one owner has no valid ID.
         */
        List<String> albumArtistNames = new ArrayList<>();
        List<Long> albumArtistIds = new ArrayList<>();
        Set<String> albumArtistIdentityKeys = new LinkedHashSet<>();

        for (Artist artist : albumArtists) {
            if (artist == null || artist.getName() == null || artist.getName().isBlank()) {
                continue;
            }

            String name = artist.getName().trim();
            long id = Math.max(0L, artist.getArtistID());
            String nameKey = normalizeArtistName(name);
            String identityKey = id > 0
                    ? "id:" + id
                    : "name:" + nameKey;

            if (nameKey.isBlank() || !albumArtistIdentityKeys.add(identityKey)) {
                continue;
            }

            albumArtistNames.add(name);
            albumArtistIds.add(id);
        }

        /*
         * A single without a Deezer track/album id can still arrive with the
         * complete artist list that was rendered by PlayerMenu.  In that
         * situation there are no album owners to use as the durable identity
         * of the release, so use the song artists as the single owners.  This
         * keeps the fallback searchable after restart without changing the
         * ID=0 behavior for files that have no artist metadata at all.
         */
        boolean hasRealAlbumArtist = albumArtistNames.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(name -> name.trim().toLowerCase(Locale.ROOT))
                .anyMatch(name -> !name.equals("unknown")
                        && !name.equals("unknown artist")
                        && !name.equals("desconocido"));

        if (!hasRealAlbumArtist && song.getArtist() != null) {
            for (Artist artist : song.getArtist()) {
                if (artist == null || artist.getName() == null || artist.getName().isBlank()) {
                    continue;
                }

                String name = artist.getName().trim();
                long id = Math.max(0L, artist.getArtistID());
                String identityKey = id > 0
                        ? "id:" + id
                        : "name:" + normalizeArtistName(name);

                if (normalizeArtistName(name).isBlank() || !albumArtistIdentityKeys.add(identityKey)) {
                    continue;
                }

                albumArtistNames.add(name);
                albumArtistIds.add(id);
            }
        }

        Set<String> albumArtistNameKeys =
                albumArtistNames.stream()
                        .map(
                                SongDownloadTaskFactory
                                        ::normalizeArtistName
                        )
                        .filter(name -> !name.isBlank())
                        .collect(
                                Collectors.toCollection(
                                        LinkedHashSet::new
                                )
                        );

        Set<Long> albumArtistIdSet =
                new LinkedHashSet<>(albumArtistIds);

        List<String> contributorNames =
                new ArrayList<>();

        List<Long> contributorIds =
                new ArrayList<>();

        Set<String> contributorNameKeys =
                new LinkedHashSet<>();

        if (song.getArtist() != null) {
            for (Artist artist : song.getArtist()) {
                if (artist == null) {
                    continue;
                }

                String name = artist.getName();
                String nameKey =
                        normalizeArtistName(name);

                long id = artist.getArtistID();

                boolean albumOwner =
                        (id > 0
                                && albumArtistIdSet.contains(id))
                                || (!nameKey.isBlank()
                                && albumArtistNameKeys.contains(
                                nameKey
                        ));

                if (!albumOwner
                        && !nameKey.isBlank()
                        && contributorNameKeys.add(nameKey)) {
                    contributorNames.add(name);
                    // Keep ids positionally aligned with contributor names;
                    // zero means that Deezer did not provide an id.
                    contributorIds.add(Math.max(0L, id));
                }
            }
        }

        Genre genre =
                album == null
                        ? null
                        : album.getGenre();

        String title =
                song.getTitle() == null
                        ? ""
                        : song.getTitle();

        return new DeezerApiMetaData(
                album == null
                        ? 0L
                        : album.getAlbumID(),
                title,
                albumArtistNames,
                new ArrayList<>(),
                album == null
                        || album.getName() == null
                        ? ""
                        : album.getName(),
                new ArrayList<>(),
                album == null
                        || album.getReleaseDate() == null
                        ? ""
                        : album.getReleaseDate(),
                album == null
                        || album.getRecordType() == null
                        ? ""
                        : album.getRecordType(),
                genre == null
                        || genre.getName() == null
                        ? ""
                        : genre.getName(),
                title,
                contributorNames,
                new ArrayList<>(),
                Math.max(
                        0,
                        song.getTrackOrder()
                ),
                album == null
                        ? 0
                        : Math.max(
                        0,
                        album.getNumberOfTracks()
                ),
                albumArtistIds,
                contributorIds,
                Math.max(
                        0L,
                        song.getSongID()
                ),
                album == null
                        ? null
                        : album.getCoverUrl(),
                genre == null
                        ? 0
                        : Math.max(
                        0,
                        genre.getGenreID()
                )
        );
    }

    private static Long inferSourceCollectionId(
            Song song
    ) {
        if (song == null) {
            return null;
        }

        Album album = song.getAlbum();

        if (album != null
                && album.getAlbumID() > 0) {
            return album.getAlbumID();
        }

        long songId = song.getSongID();

        return songId > 0
                ? songId
                : null;
    }

    private static String inferSourceCollectionTitle(
            Song song
    ) {
        if (song == null) {
            return null;
        }

        Album album = song.getAlbum();

        if (album != null
                && album.getName() != null
                && !album.getName().isBlank()) {
            return album.getName();
        }

        return song.getTitle();
    }

    private static String inferSourceCollectionType(
            Song song
    ) {
        if (song == null) {
            return SOURCE_TYPE_UNKNOWN;
        }

        Album album = song.getAlbum();

        if (album != null
                && album.getAlbumID() > 0) {
            return SOURCE_TYPE_ALBUM;
        }

        return SOURCE_TYPE_SINGLE;
    }

    private static String normalizeSourceType(
            String sourceType
    ) {
        if (sourceType == null
                || sourceType.isBlank()) {
            return SOURCE_TYPE_UNKNOWN;
        }

        String normalized =
                sourceType.trim()
                        .toUpperCase(Locale.ROOT);

        return switch (normalized) {
            case SOURCE_TYPE_ALBUM ->
                    SOURCE_TYPE_ALBUM;

            case SOURCE_TYPE_PLAYLIST ->
                    SOURCE_TYPE_PLAYLIST;

            case SOURCE_TYPE_SINGLE ->
                    SOURCE_TYPE_SINGLE;

            default ->
                    SOURCE_TYPE_UNKNOWN;
        };
    }

    private static Long safePositiveLong(
            long value
    ) {
        return value > 0
                ? value
                : null;
    }

    private static String normalizeArtistName(
            String name
    ) {
        return name == null
                ? ""
                : name.trim()
                  .toLowerCase(Locale.ROOT);
    }
}
