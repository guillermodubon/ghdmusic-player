package io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.searchDropdown.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.scene.image.Image;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.CardArtistNameResolver;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.MusicCardData;
import io.github.guillermodubon.musicplayer.utils.MusicCardHelper;
import io.github.guillermodubon.musicplayer.services.api.DeezerArtistMetadataResolver;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.searchDropdown.context.SearchDropdownContext;
import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.SearchCandidate;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;


public class SearchDropdownService {

    private static final int MAX_ARTIST_RESULTS = 3;
    private static final double SEARCH_IMAGE_SIZE = 160;

    private final SearchDropdownContext context;

    public SearchDropdownService(SearchDropdownContext context) {
        this.context = context;
    }

    public JsonObject searchAll(String query) throws IOException {
        return context.deezer().searchAll(query);
    }

    public List<SearchCandidate> buildCandidates(String query, int maxResults) throws IOException {
        String normalizedQuery = normalize(query);
        if (normalizedQuery.isBlank() || maxResults <= 0) return List.of();

        /*
         * Keep local data ahead of Deezer data, but do not fill the whole
         * dropdown before the artist request is considered. The previous
         * early return made a local-only result hide the artist section and
         * made the number of music cards depend on the local library size.
         */
        int localPoolSize = Math.max(maxResults * 2, maxResults + MAX_ARTIST_RESULTS);
        List<SearchCandidate> localCandidates = buildLocalCandidates(normalizedQuery, localPoolSize);

        CompletableFuture<JsonArray> artistsFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return context.deezer().searchArtists(normalizedQuery);
            } catch (Exception ignored) {
                return new JsonArray();
            }
        });

        CompletableFuture<JsonObject> generalFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return searchAll(normalizedQuery);
            } catch (Exception ignored) {
                return null;
            }
        });

        JsonArray artistsJson = await(artistsFuture, new JsonArray(), 900);
        JsonObject generalJson = await(generalFuture, null, 1400);

        LinkedHashMap<String, SearchCandidate> artistsByIdentity = new LinkedHashMap<>();
        LinkedHashMap<String, SearchCandidate> musicByIdentity = new LinkedHashMap<>();

        for (SearchCandidate candidate : localCandidates) {
            if (candidate == null) continue;
            if ("artist".equalsIgnoreCase(candidate.type())) {
                addUniqueCandidate(artistsByIdentity, candidate);
            } else {
                addUniqueCandidate(musicByIdentity, candidate);
            }
        }

        if (artistsJson != null) {
            for (JsonElement element : artistsJson) {
                if (!element.isJsonObject()) continue;
                addUniqueCandidate(
                        artistsByIdentity,
                        candidateFromArtistJson(element.getAsJsonObject())
                );
            }
        }

        /* The general endpoint can also contain artist entries. They are a
         * fallback when the dedicated artist request is slow or unavailable,
         * and must be present before the three-artist ranking is calculated. */
        if (generalJson != null && generalJson.has("data") && generalJson.get("data").isJsonArray()) {
            for (JsonElement element : generalJson.getAsJsonArray("data")) {
                if (!element.isJsonObject()) continue;
                SearchCandidate candidate = candidateFromDeezerJson(element.getAsJsonObject());
                if (candidate == null) continue;
                if ("artist".equalsIgnoreCase(candidate.type())) {
                    if (score(normalizedQuery, candidate.title()) >= 0) {
                        addUniqueCandidate(artistsByIdentity, candidate);
                    }
                } else {
                    addUniqueCandidate(musicByIdentity, candidate);
                }
            }
        }

        List<SearchCandidate> rankedArtists = artistsByIdentity.values().stream()
                .filter(candidate -> candidate.localArtist() != null
                        || artistMatchScore(normalizedQuery, candidate) != Integer.MAX_VALUE)
                .sorted(Comparator
                        .comparingInt((SearchCandidate candidate) ->
                                candidate.localArtist() == null ? 1 : 0)
                        .thenComparingInt(candidate -> artistMatchScore(normalizedQuery, candidate))
                        .thenComparing(Comparator.comparingLong(this::artistFanCount).reversed())
                        .thenComparing(candidate -> safeLower(candidate.title())))
                .toList();

        List<SearchCandidate> selected = new ArrayList<>(Math.min(maxResults, 15));
        LinkedHashSet<String> selectedIdentities = new LinkedHashSet<>();
        int artistCount = Math.min(MAX_ARTIST_RESULTS, rankedArtists.size());
        for (int i = 0; i < artistCount; i++) {
            addSelected(selected, selectedIdentities, rankedArtists.get(i), maxResults);
        }

        /* Fill the remaining slots with music. Local candidates were inserted
         * first, so a matching item already in the library remains preferred. */
        for (SearchCandidate candidate : musicByIdentity.values()) {
            if (selected.size() >= maxResults) break;
            addSelected(selected, selectedIdentities, candidate, maxResults);
        }

        return selected;
    }

    private <T> T await(CompletableFuture<T> future, T fallback, long timeoutMillis) {
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            future.cancel(true);
            return fallback;
        }
    }

    public String buildCandidateKey(String type, long id, String coverUrl) {
        String coverHash = coverUrl == null ? "nocvr" : Integer.toHexString(coverUrl.hashCode());
        return (type == null ? "t" : type) + ":" + id + ":" + coverHash;
    }

    private List<SearchCandidate> buildLocalCandidates(String query, int maxResults) {
        if (context.svc() == null) return List.of();

        List<ScoredCandidate> scored = new ArrayList<>();
        List<Song> localSongs = MusicCardHelper.snapshot(context.svc().getSongs()).stream()
                .filter(song -> song != null && song.isLocal())
                .toList();
        Set<Long> localSongIds = localSongs.stream()
                .filter(song -> song.getSongID() > 0)
                .map(Song::getSongID)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> localSongTitles = localSongs.stream()
                .map(Song::getTitle)
                .filter(title -> title != null && !title.isBlank())
                .map(this::normalize)
                .collect(java.util.stream.Collectors.toSet());

        for (Artist artist : MusicCardHelper.snapshot(context.svc().getArtists())) {
            if (artist == null) continue;
            if (!hasLocalSongForArtist(artist, localSongs)) continue;
            int score = score(query, artist.getName());
            if (score >= 0) scored.add(new ScoredCandidate(score, 0, localArtistCandidate(artist)));
        }

        for (Song song : localSongs) {
            if (song == null) continue;
            int score = minPositive(
                    score(query, song.getTitle()),
                    score(query, song.getAlbum() == null ? null : song.getAlbum().getName()),
                    score(query, joinArtistNames(song))
            );
            if (score >= 0) scored.add(new ScoredCandidate(score, 1, localSongCandidate(song)));
        }

        for (Album album : MusicCardHelper.snapshot(context.svc().getAlbums())) {
            if (album == null) continue;
            if (!hasLocalSongForAlbum(album, localSongs)) continue;
            int score = minPositive(
                    score(query, album.getName()),
                    score(query, joinArtistNames(album))
            );
            if (score >= 0) scored.add(new ScoredCandidate(score, 2, localAlbumCandidate(album)));
        }

        for (Playlist playlist : MusicCardHelper.snapshot(context.svc().getPlaylists())) {
            if (playlist == null) continue;
            if (!hasLocalSongForPlaylist(playlist, localSongIds, localSongTitles)) continue;
            int score = minPositive(
                    score(query, playlist.getTitle()),
                    score(query, playlist.getAuthorName()),
                    score(query, playlist.getDescription())
            );
            if (score >= 0) scored.add(new ScoredCandidate(score, 3, localPlaylistCandidate(playlist)));
        }

        LinkedHashMap<String, SearchCandidate> local = new LinkedHashMap<>();
        scored.stream()
                .filter(sc -> sc.candidate() != null)
                .sorted(Comparator
                        .comparingInt(ScoredCandidate::score)
                        .thenComparingInt(ScoredCandidate::typePriority)
                        .thenComparing(sc -> safeLower(sc.candidate().title())))
                .forEach(sc -> local.putIfAbsent(identity(sc.candidate()), sc.candidate()));

        List<SearchCandidate> out = new ArrayList<>(maxResults);
        for (SearchCandidate candidate : local.values()) {
            if (out.size() >= maxResults) break;
            out.add(candidate);
        }
        return out;
    }

    private SearchCandidate localArtistCandidate(Artist artist) {
        if (artist == null || artist.getName() == null || artist.getName().isBlank()) return null;
        Image portrait = MediaImageResolver.artistPortrait(artist, "xl", SEARCH_IMAGE_SIZE, SEARCH_IMAGE_SIZE);
        String key = artist.getArtistID() > 0
                ? buildCandidateKey("artist", artist.getArtistID(), null)
                : "artist:local:" + Integer.toHexString(normalize(artist.getName()).hashCode());
        return new SearchCandidate(
                key,
                artist.getArtistID(),
                "artist",
                artist.getName(),
                List.of("Artist"),
                null,
                null,
                portrait,
                artist
        );
    }

    private SearchCandidate localSongCandidate(Song song) {
        if (song == null || song.getTitle() == null || song.getTitle().isBlank()) return null;
        Image cover = MediaImageResolver.songAlbumCover(song, "xl", SEARCH_IMAGE_SIZE, SEARCH_IMAGE_SIZE);
        String actionId = song.getSongID() > 0
                ? String.valueOf(song.getSongID())
                : "no_meta_" + URLEncoder.encode(song.getTitle(), StandardCharsets.UTF_8);
        String key = song.getSongID() > 0
                ? buildCandidateKey("track", song.getSongID(), null)
                : "track:no_meta:" + Integer.toHexString(normalize(song.getTitle()).hashCode());
        return new SearchCandidate(
                key,
                song.getSongID(),
                "track",
                song.getTitle(),
                CardArtistNameResolver.fromSong(song),
                null,
                null,
                cover,
                null,
                actionId
        );
    }

    private SearchCandidate localAlbumCandidate(Album album) {
        if (album == null || album.getName() == null || album.getName().isBlank()) return null;
        Image cover = MediaImageResolver.albumCover(album, "xl", SEARCH_IMAGE_SIZE, SEARCH_IMAGE_SIZE);
        String key = album.getAlbumID() > 0
                ? buildCandidateKey("album", album.getAlbumID(), null)
                : "album:local:" + Integer.toHexString(normalize(album.getName()).hashCode());
        return new SearchCandidate(
                key,
                album.getAlbumID(),
                "album",
                album.getName(),
                CardArtistNameResolver.fromAlbum(album),
                null,
                null,
                cover,
                null
        );
    }

    private SearchCandidate localPlaylistCandidate(Playlist playlist) {
        if (playlist == null || playlist.getTitle() == null || playlist.getTitle().isBlank()) return null;
        String key = playlist.getId() > 0
                ? buildCandidateKey("playlist", playlist.getId(), null)
                : "playlist:local:" + Integer.toHexString(normalize(playlist.getTitle()).hashCode());
        return new SearchCandidate(
                key,
                playlist.getId(),
                "playlist",
                playlist.getTitle(),
                List.of(MusicCardData.playlistCreatorLabel(playlist.getAuthorName())),
                null,
                null,
                MediaImageResolver.playlistCover(playlist, SEARCH_IMAGE_SIZE, SEARCH_IMAGE_SIZE),
                null
        );
    }

    private SearchCandidate candidateFromArtistJson(JsonObject item) {
        if (item == null) return null;
        long id = DeezerApiService.safeGetLong(item, "id", 0L);
        String title = item.has("name") && !item.get("name").isJsonNull()
                ? item.get("name").getAsString()
                : DeezerApiService.extractTitle(item);
        if (id <= 0 || title == null || title.isBlank()) return null;
        String coverUrl = determineCoverUrlForItem(item, "artist");
        return new SearchCandidate(
                buildCandidateKey("artist", id, coverUrl),
                id,
                "artist",
                title,
                List.of("Artist"),
                coverUrl,
                item
        );
    }

    private SearchCandidate candidateFromDeezerJson(JsonObject item) {
        if (item == null) return null;
        String type = item.has("type") && !item.get("type").isJsonNull()
                ? item.get("type").getAsString().toLowerCase(Locale.ROOT)
                : "track";
        long id = DeezerApiService.safeGetLong(item, "id", 0L);
        String title = DeezerApiService.extractTitle(item);
        if (id <= 0 || title == null || title.isBlank()) return null;

        List<String> artistNames = artistNamesForItem(item, type);
        String coverUrl = determineCoverUrlForItem(item, type);
        JsonObject artistJson = "artist".equals(type) ? item : null;

        return new SearchCandidate(
                buildCandidateKey(type, id, coverUrl),
                id,
                type,
                title,
                artistNames,
                coverUrl,
                artistJson
        );
    }

    private void addUniqueCandidate(Map<String, SearchCandidate> candidates,
                                    SearchCandidate candidate) {
        if (candidate == null) return;
        String identity = identity(candidate);
        if (identity.isBlank()) return;
        candidates.putIfAbsent(identity, candidate);
    }

    private void addSelected(List<SearchCandidate> selected,
                             Set<String> identities,
                             SearchCandidate candidate,
                             int maxResults) {
        if (candidate == null || selected.size() >= maxResults) return;
        String candidateIdentity = identity(candidate);
        if (candidateIdentity.isBlank() || !identities.add(candidateIdentity)) return;
        selected.add(candidate);
    }

    private int artistMatchScore(String query, SearchCandidate candidate) {
        int matchScore = score(query, candidate == null ? null : candidate.title());
        return matchScore < 0 ? Integer.MAX_VALUE : matchScore;
    }

    private long artistFanCount(SearchCandidate candidate) {
        if (candidate == null || candidate.artistJson() == null) return 0L;
        return DeezerApiService.safeGetLong(candidate.artistJson(), "nb_fan", 0L);
    }

    private String identity(SearchCandidate candidate) {
        if (candidate == null) return "";
        String value = candidate.id() > 0 ? String.valueOf(candidate.id()) : normalize(candidate.title());
        if (value.isBlank()) return "";
        return normalizeType(candidate.type()) + ":" + value;
    }

    private String normalizeType(String type) {
        return type == null || type.isBlank() ? "track" : type.trim().toLowerCase(Locale.ROOT);
    }

    private List<String> artistNamesForItem(JsonObject item, String type) {
        if ("playlist".equalsIgnoreCase(type)) {
            return List.of(MusicCardData.REMOTE_PLAYLIST_CREATOR_LABEL);
        }
        if ("artist".equalsIgnoreCase(type)) {
            return List.of("Artist");
        }

        LinkedHashSet<String> names = new LinkedHashSet<>(MusicCardHelper.extractArtistNamesFromTrackJson(item));
        if (!names.isEmpty()) return List.copyOf(names);

        try {
            if (item != null && item.has("artist") && item.get("artist").isJsonObject()) {
                JsonObject artist = item.getAsJsonObject("artist");
                if (artist.has("name") && !artist.get("name").isJsonNull()) {
                    String name = artist.get("name").getAsString();
                    if (name != null && !name.isBlank()) return List.of(name);
                }
            }
        } catch (Exception ignored) {
        }

        return "artist".equalsIgnoreCase(type) ? List.of("Artist") : List.of("Unknown");
    }

    private String joinArtistNames(Song song) {
        return String.join(" ", CardArtistNameResolver.fromSong(song));
    }

    private String joinArtistNames(Album album) {
        return String.join(" ", CardArtistNameResolver.fromAlbum(album));
    }

    private boolean hasLocalSongForArtist(Artist artist, List<Song> localSongs) {
        if (artist == null || localSongs == null || localSongs.isEmpty()) return false;
        for (Song song : localSongs) {
            if (song == null) continue;
            if (containsSameArtist(song.getArtist(), artist)) return true;
            if (song.getAlbum() != null && containsSameArtist(song.getAlbum().getArtist(), artist)) return true;
        }
        return false;
    }

    private boolean hasLocalSongForAlbum(Album album, List<Song> localSongs) {
        if (album == null || localSongs == null || localSongs.isEmpty()) return false;

        if (album.getSongList() != null) {
            for (Song song : album.getSongList()) {
                if (song != null && song.isLocal()) return true;
            }
        }

        long albumId = album.getAlbumID();
        String albumName = normalize(album.getName());
        for (Song song : localSongs) {
            if (song == null || song.getAlbum() == null) continue;
            Album songAlbum = song.getAlbum();
            if (albumId > 0 && songAlbum.getAlbumID() == albumId) return true;
            if (!albumName.isBlank() && albumName.equals(normalize(songAlbum.getName()))) return true;
        }
        return false;
    }

    private boolean hasLocalSongForPlaylist(Playlist playlist, Set<Long> localSongIds, Set<String> localSongTitles) {
        if (playlist == null || playlist.getSongList() == null || playlist.getSongList().isEmpty()) return false;
        for (Song song : playlist.getSongList()) {
            if (song == null) continue;
            if (song.isLocal()) return true;
            if (song.getSongID() > 0 && localSongIds != null && localSongIds.contains(song.getSongID())) return true;
            String title = normalize(song.getTitle());
            if (!title.isBlank() && localSongTitles != null && localSongTitles.contains(title)) return true;
        }
        return false;
    }

    private boolean containsSameArtist(List<Artist> artists, Artist target) {
        if (artists == null || target == null) return false;
        long targetId = target.getArtistID();
        String targetName = normalize(target.getName());
        for (Artist artist : artists) {
            if (artist == null) continue;
            if (targetId > 0 && artist.getArtistID() == targetId) return true;
            if (!targetName.isBlank() && targetName.equals(normalize(artist.getName()))) return true;
        }
        return false;
    }

    private int minPositive(int... scores) {
        int best = -1;
        if (scores == null) return best;
        for (int score : scores) {
            if (score < 0) continue;
            if (best < 0 || score < best) best = score;
        }
        return best;
    }

    private int score(String query, String value) {
        if (query == null || value == null) return -1;
        String q = normalize(query);
        String v = normalize(value);
        if (q.isBlank() || v.isBlank()) return -1;
        if (v.equals(q)) return 0;
        if (v.startsWith(q)) return 1;
        return v.contains(q) ? 2 : -1;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private record ScoredCandidate(int score, int typePriority, SearchCandidate candidate) {}

    public String determineCoverUrlForItem(JsonObject item, String type) {
        try {
            if (item == null) return null;

            if ("artist".equalsIgnoreCase(type)) {
                return DeezerArtistMetadataResolver.pictureUrl(item);
            }

            for (String k : List.of("cover_xl", "cover_big", "cover_medium", "cover_small", "cover")) {
                if (item.has(k) && !item.get(k).isJsonNull()) return item.get(k).getAsString();
            }

            if (item.has("album") && item.get("album").isJsonObject()) {
                JsonObject alb = item.getAsJsonObject("album");
                for (String k : List.of("cover_xl", "cover_big", "cover_medium", "cover_small", "cover")) {
                    if (alb.has(k) && !alb.get(k).isJsonNull()) return alb.get(k).getAsString();
                }
                for (String k : List.of("picture_xl", "picture_big", "picture_medium", "picture_small", "picture")) {
                    if (alb.has(k) && !alb.get(k).isJsonNull()) return alb.get(k).getAsString();
                }
            }

            if (item.has("picture_xl") && !item.get("picture_xl").isJsonNull()) return item.get("picture_xl").getAsString();
            if (item.has("picture_medium") && !item.get("picture_medium").isJsonNull()) return item.get("picture_medium").getAsString();
        } catch (Exception ignored) {
        }
        return null;
    }

    public Artist parseArtistFromJson(JsonObject json, String explicitPicUrl) {
        long id = 0L;
        String name = "Unknown";
        String picUrl = DeezerArtistMetadataResolver.isUsableArtistPictureUrl(explicitPicUrl)
                ? explicitPicUrl
                : null;

        try {
            if (json != null) {
                id = DeezerApiService.safeGetLong(json, "id", 0L);
                if (json.has("name") && !json.get("name").isJsonNull()) {
                    name = json.get("name").getAsString();
                }
            }

            if (picUrl == null && json != null) {
                picUrl = DeezerArtistMetadataResolver.pictureUrl(json);
            }

        } catch (Exception ignored) {
        }

        Artist artist = new Artist(id, name, null, new ArrayList<>());
        artist.setPortraitUrl(picUrl);
        return artist;
    }
}
