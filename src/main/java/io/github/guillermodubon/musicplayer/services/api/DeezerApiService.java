package io.github.guillermodubon.musicplayer.services.api;

import com.google.gson.*;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import io.github.guillermodubon.musicplayer.managers.ApiManagers.DeezerApiDataManager;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.DeezerApiMetaData;
import io.github.guillermodubon.musicplayer.models.DeezerTrackInfo;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class DeezerApiService implements ApiService<DeezerApiMetaData> {

    private static final String SEARCH_URL = "https://api.deezer.com/search?q=";
    private static final String ALBUM_URL  = "https://api.deezer.com/album/";
    private static final String TRACK_URL  = "https://api.deezer.com/track/";
    private static final long ALBUM_TRACKS_CACHE_TTL_MILLIS = 5 * 60 * 1000L;
    private static final int METADATA_REQUEST_BATCH_SIZE = 32;
    private static final ConcurrentMap<Long, AlbumTracksCacheEntry> ALBUM_TRACKS_CACHE = new ConcurrentHashMap<>();

    /** Pool de hilos para I/O */
    private static final ExecutorService HTTP_EXECUTOR =
            Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                return t;
            });

    /** Cliente OkHttp con dispatcher propio */
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .dispatcher(new Dispatcher(HTTP_EXECUTOR))
            .build();


    @Override
    public List<DeezerApiMetaData> getApiObjectsList(List<String> titles) {
        if (titles == null || titles.isEmpty()) {
            return List.of();
        }

        // A first-run import may contain thousands of files. The executor already
        // limits active HTTP calls, but an unbounded CompletableFuture queue still
        // retains every task and title in memory. Deduplicate and submit in small
        // batches to keep the startup memory footprint predictable.
        Map<String, String> uniqueTitles = new LinkedHashMap<>();
        for (String title : titles) {
            if (title == null || title.isBlank()) {
                continue;
            }
            String key = title.trim().toLowerCase(Locale.ROOT);
            uniqueTitles.putIfAbsent(key, title);
        }

        if (uniqueTitles.isEmpty()) {
            return List.of();
        }

        List<String> requests = new ArrayList<>(uniqueTitles.values());
        List<DeezerApiMetaData> metadata = new ArrayList<>(requests.size());
        for (int offset = 0; offset < requests.size(); offset += METADATA_REQUEST_BATCH_SIZE) {
            int end = Math.min(requests.size(), offset + METADATA_REQUEST_BATCH_SIZE);
            List<CompletableFuture<DeezerApiMetaData>> futures = new ArrayList<>(end - offset);
            for (int index = offset; index < end; index++) {
                String title = requests.get(index);
                futures.add(CompletableFuture.supplyAsync(
                        () -> getFetchedApiMetadataObject(title), HTTP_EXECUTOR
                ));
            }
            for (CompletableFuture<DeezerApiMetaData> future : futures) {
                DeezerApiMetaData item = future.join();
                if (item != null) {
                    metadata.add(item);
                }
            }
        }
        return metadata;
    }

    public DeezerApiMetaData getFetchedApiMetadataObject(String originalTitle) {
        try {
            System.out.println("ORIGINAL TITLE: " + originalTitle);
            // 1) /search?q={originalTitle}
            String urlSearch = SEARCH_URL + URLEncoder.encode(originalTitle, StandardCharsets.UTF_8);
            Request reqSearch = new Request.Builder().url(urlSearch).build();
            try (Response rsp = CLIENT.newCall(reqSearch).execute()) {
                if (!rsp.isSuccessful() || rsp.body() == null) {
                    return null;
                }
                JsonArray results = JsonParser.parseString(rsp.body().string())
                        .getAsJsonObject()
                        .getAsJsonArray("data");
                // 1.1) Take the first result (index 0) or look for an exact match
                JsonObject firstSongMatchDataObject = null;
                String originalNormalized = originalTitle == null ? "" : originalTitle.trim().toLowerCase();
                String candidateFromOriginal = originalNormalized;
                String[] separators = new String[] {" - ", " — ", " — ", ":", "–"};
                for (String sep : separators) {
                    if (originalNormalized.contains(sep)) {
                        int idx = originalNormalized.lastIndexOf(sep);
                        if (idx >= 0 && idx + sep.length() < originalNormalized.length()) {
                            candidateFromOriginal = originalNormalized.substring(idx + sep.length()).trim();
                            break;
                        }
                    }
                }
                if (candidateFromOriginal.equals(originalNormalized) && originalNormalized.contains(", ")) {
                    int idx = originalNormalized.lastIndexOf(", ");
                    if (idx >= 0 && idx + 2 < originalNormalized.length()) {
                        candidateFromOriginal = originalNormalized.substring(idx + 2).trim();
                    }
                }
                for (JsonElement elem : results) {
                    JsonObject possibleSongObject = elem.getAsJsonObject();
                    String candidateTitle = DeezerApiDataManager.getStringOrNull(possibleSongObject, "title");
                    if (candidateTitle == null) continue;
                    String candNorm = candidateTitle.trim().toLowerCase();
                    if (candNorm.equals(originalNormalized) || candNorm.equals(candidateFromOriginal)) {
                        firstSongMatchDataObject = possibleSongObject;
                        break;
                    }
                    if (originalNormalized.endsWith(" " + candNorm) || originalNormalized.endsWith("-" + candNorm) || originalNormalized.endsWith(":" + candNorm)) {
                        firstSongMatchDataObject = possibleSongObject;
                        break;
                    }
                    if (candNorm.length() > 3 && originalNormalized.contains(candNorm)) {
                        firstSongMatchDataObject = possibleSongObject;
                        break;
                    }
                }
                if (firstSongMatchDataObject == null && !results.isEmpty()) {
                    firstSongMatchDataObject = results.get(0).getAsJsonObject();
                }
                if (firstSongMatchDataObject == null) {
                    return null;
                }
                // 2) Extract trackId and songName
                long trackId = safeGetLong(firstSongMatchDataObject, "id", 0L);

                if (trackId == 0) {
                    System.out.println("Warning: trackId is 0 for title: " + originalTitle);
                    return null; // Skip if no valid track ID
                }
                String songName = DeezerApiDataManager.getStringOrDefault(firstSongMatchDataObject, "title", "");
                System.out.println("TRACK ID FOR: "+songName+": "+trackId);
                // 3) Extraer albumId y albumName
                JsonObject albumObj = DeezerApiDataManager.getObjectOrNull(firstSongMatchDataObject, "album");
                if (albumObj == null) {
                    return null;
                }
                long albumId = safeGetLong(albumObj, "id", 0L);
                if (albumId == 0) {
                    System.out.println("Warning: albumId is 0 for title: " + originalTitle);
                    return null; // Skip if no valid album ID
                }
                String albumName = DeezerApiDataManager.getStringOrDefault(albumObj, "title", "");
                // 4) /album/{albumId} → retrieve metadata for the entire album
                String albumEndpoint = ALBUM_URL + albumId;
                System.out.println(albumEndpoint);
                Request reqAlbum = new Request.Builder().url(albumEndpoint).build();

                String albumReleaseDate = "";
                String recordType = "";
                String albumGenre = "";
                int albumGenreId = 0;
                int numberOfTracks = 0;
                List<String> albumArtistNames = new ArrayList<>();
                List<List<byte[]>> albumArtistsPortraitBytes = new ArrayList<>();
                List<Long> albumArtistIds = new ArrayList<>();
                List<Long> songContributorIds = new ArrayList<>();
                String albumCoverUrl = null;
                List<byte[]> albumCoverBytesList = DeezerApiDataManager.fetchImageByteLists(
                        albumObj, CLIENT, "cover", "cover_medium", "cover_xl"
                );
                try (Response rspAlbum = CLIENT.newCall(reqAlbum).execute()) {
                    if (rspAlbum.isSuccessful() && rspAlbum.body() != null) {

                        String body = rspAlbum.body().string();
                        JsonObject albumDetails = JsonParser.parseString(body).getAsJsonObject();

                        try {
                            albumCoverUrl = DeezerApiService.extractCoverUrlFromAlbumOrPlaylist(albumDetails);
                        } catch (Exception ignore) {
                        }
                        // 4.2) Release date and record_type
                        Map.Entry<String, String> datesType = DeezerApiDataManager
                                .extractAlbumDatesAndType(albumDetails);
                        albumReleaseDate = datesType.getKey();
                        recordType = datesType.getValue();

                        numberOfTracks = safeGetInt(albumDetails, "nb_tracks", 0);
                        // 4.3) Genre (first element of "genres.data") -> extract name and ID
                        if (albumDetails.has("genres") && albumDetails.getAsJsonObject("genres").has("data")) {
                            JsonArray genresArray = albumDetails.getAsJsonObject("genres").getAsJsonArray("data");
                            if (!genresArray.isEmpty()) {
                                JsonObject g = genresArray.get(0).getAsJsonObject();
                                albumGenre = DeezerApiDataManager.getStringOrNull(g, "name");
                                albumGenreId = safeGetInt(g, "id", 0);
                            }
                        }
                        // 4.4) Album owners. Keep every owner from the resource,
                        // not only the first primary artist.
                        if (albumDetails.has("artists")
                                && albumDetails.get("artists").isJsonArray()) {
                            for (JsonElement elem : albumDetails.getAsJsonArray("artists")) {
                                if (elem.isJsonObject()) {
                                    appendAlbumArtist(
                                            elem.getAsJsonObject(),
                                            albumArtistNames,
                                            albumArtistIds,
                                            albumArtistsPortraitBytes
                                    );
                                }
                            }
                        }
                        if (albumDetails.has("contributors")
                                && albumDetails.get("contributors").isJsonArray()) {
                            JsonArray owners = albumDetails.getAsJsonArray("contributors");
                            for (JsonElement elem : owners) {
                                if (!elem.isJsonObject()) continue;
                                JsonObject obj = elem.getAsJsonObject();
                                String role = DeezerApiDataManager.getStringOrNull(obj, "role");
                                if (role != null && !role.toLowerCase(Locale.ROOT).contains("main")) continue;
                                appendAlbumArtist(
                                        obj,
                                        albumArtistNames,
                                        albumArtistIds,
                                        albumArtistsPortraitBytes
                                );
                            }
                        }
                        if (albumDetails.has("artist")
                                && albumDetails.get("artist").isJsonObject()) {
                            JsonObject obj = albumDetails.getAsJsonObject("artist");
                            appendAlbumArtist(
                                    obj,
                                    albumArtistNames,
                                    albumArtistIds,
                                    albumArtistsPortraitBytes
                            );
                        }
                    }
                }
                // 5) /track/{trackId} → exclusive collaborators on the track
                String trackEndpoint = TRACK_URL + trackId;
                Request reqTrack = new Request.Builder().url(trackEndpoint).build();
                int trackOrder = 0;
                List<String> songContributorNames = new ArrayList<>();
                List<List<byte[]>> songContributorsPortraitBytes = new ArrayList<>();
                try (Response rspTrack = CLIENT.newCall(reqTrack).execute()) {
                    if (rspTrack.isSuccessful() && rspTrack.body() != null) {
                        JsonObject trackDetails = JsonParser.parseString(rspTrack.body().string()).getAsJsonObject();
                        trackOrder = safeGetInt(trackDetails, "track_position", 0);
                        if (trackDetails.has("contributors")) {
                            JsonArray contribs = trackDetails.getAsJsonArray("contributors");
                            for (JsonElement elem : contribs) {
                                JsonObject obj = elem.getAsJsonObject();
                                long contribId = safeGetLong(obj, "id", 0L);
                                System.out.println("CONTRIB ID: " + contribId);
                                String contribName = DeezerApiDataManager.getStringOrNull(obj, "name");

                                if (contribName != null
                                        && !contribName.isBlank()
                                        && indexOfArtistIdentity(
                                        albumArtistNames,
                                        albumArtistIds,
                                        contribName,
                                        contribId
                                ) < 0) {
                                    songContributorIds.add(contribId);
                                    songContributorNames.add(contribName);
                                    songContributorsPortraitBytes.add(
                                            DeezerApiDataManager.fetchImageByteLists(
                                                    obj, CLIENT, "picture", "picture_medium", "picture_big"
                                            )
                                    );
                                }
                            }
                        }
                    }
                }
                System.out.println(songName + " " + numberOfTracks);
                System.out.println("DEZZER API METADATA OBJECT STORED ID: "+trackId);
                return new DeezerApiMetaData(
                        albumId, originalTitle, albumArtistNames, albumArtistsPortraitBytes, albumName, albumCoverBytesList, albumReleaseDate, recordType, albumGenre, songName, songContributorNames, songContributorsPortraitBytes, trackOrder, numberOfTracks, albumArtistIds, songContributorIds, trackId, albumCoverUrl, albumGenreId // <- new final parameter: genre id
                );
            }
        } catch (IOException e) {
            System.err.println("Error en Deezer para '" + originalTitle + "': " + e.getMessage());
            return null;
        }
    }

    public DeezerApiMetaData getTrackMetadataById(long trackId, DeezerApiMetaData fallback) {
        if (trackId <= 0) return fallback;

        try {
            Request reqTrack = new Request.Builder().url(TRACK_URL + trackId).build();
            JsonObject trackDetails;
            try (Response rspTrack = CLIENT.newCall(reqTrack).execute()) {
                if (!rspTrack.isSuccessful() || rspTrack.body() == null) return fallback;
                trackDetails = JsonParser.parseString(rspTrack.body().string()).getAsJsonObject();
            }

            String fallbackSongName = fallback == null ? "" : Optional.ofNullable(fallback.getSongName()).orElse("");
            String songName = DeezerApiDataManager.getStringOrDefault(trackDetails, "title", fallbackSongName);
            int trackOrder = safeGetInt(trackDetails, "track_position", fallback == null ? 0 : fallback.getTrackOrder());

            JsonObject albumObj = DeezerApiDataManager.getObjectOrNull(trackDetails, "album");
            long albumId = albumObj == null
                    ? (fallback == null ? 0L : fallback.getAlbumId())
                    : safeGetLong(albumObj, "id", fallback == null ? 0L : fallback.getAlbumId());

            String albumName = albumObj == null
                    ? (fallback == null ? "" : Optional.ofNullable(fallback.getAlbumName()).orElse(""))
                    : DeezerApiDataManager.getStringOrDefault(albumObj, "title", fallback == null ? "" : Optional.ofNullable(fallback.getAlbumName()).orElse(""));

            String albumReleaseDate = fallback == null ? "" : Optional.ofNullable(fallback.getAlbumReleaseDate()).orElse("");
            String recordType = fallback == null ? "" : Optional.ofNullable(fallback.getRecordType()).orElse("");
            String albumGenre = fallback == null ? "" : Optional.ofNullable(fallback.getGenre()).orElse("");
            int albumGenreId = fallback == null ? 0 : fallback.getAlbumGenreId();
            int numberOfTracks = fallback == null ? 0 : fallback.getNumberOfTracks();
            String albumCoverUrl = fallback == null ? null : fallback.getAlbumCoverUrl();

            List<String> albumArtistNames = fallback != null && fallback.getAlbumArtistNames() != null
                    ? new ArrayList<>(fallback.getAlbumArtistNames())
                    : new ArrayList<>();
            List<List<byte[]>> albumArtistsPortraitBytes = fallback != null && fallback.getAlbumArtistsPortraitBytes() != null
                    ? new ArrayList<>(fallback.getAlbumArtistsPortraitBytes())
                    : new ArrayList<>();
            List<Long> albumArtistIds = fallback != null && fallback.getAlbumArtistIds() != null
                    ? new ArrayList<>(fallback.getAlbumArtistIds())
                    : new ArrayList<>();
            List<byte[]> albumCoverBytesList = fallback != null && fallback.getAlbumCoverBytesList() != null
                    ? new ArrayList<>(fallback.getAlbumCoverBytesList())
                    : new ArrayList<>();

            if (albumObj != null) {
                String cover = extractCoverUrlFromAlbumOrPlaylist(albumObj);
                if (cover != null && !cover.isBlank()) albumCoverUrl = cover;
                if (albumCoverBytesList.isEmpty()) {
                    albumCoverBytesList = DeezerApiDataManager.fetchImageByteLists(albumObj, CLIENT, "cover", "cover_medium", "cover_xl");
                }
            }

            if (albumId > 0) {
                try {
                    Request reqAlbum = new Request.Builder().url(ALBUM_URL + albumId).build();
                    try (Response rspAlbum = CLIENT.newCall(reqAlbum).execute()) {
                        if (rspAlbum.isSuccessful() && rspAlbum.body() != null) {
                            JsonObject albumDetails = JsonParser.parseString(rspAlbum.body().string()).getAsJsonObject();
                            albumName = DeezerApiDataManager.getStringOrDefault(albumDetails, "title", albumName);
                            albumCoverUrl = Optional.ofNullable(extractCoverUrlFromAlbumOrPlaylist(albumDetails)).orElse(albumCoverUrl);
                            albumCoverBytesList = DeezerApiDataManager.fetchImageByteLists(albumDetails, CLIENT, "cover", "cover_medium", "cover_xl");

                            Map.Entry<String, String> datesType = DeezerApiDataManager.extractAlbumDatesAndType(albumDetails);
                            albumReleaseDate = Optional.ofNullable(datesType.getKey()).orElse(albumReleaseDate);
                            recordType = Optional.ofNullable(datesType.getValue()).orElse(recordType);
                            numberOfTracks = safeGetInt(albumDetails, "nb_tracks", numberOfTracks);

                            if (albumDetails.has("genres") && albumDetails.getAsJsonObject("genres").has("data")) {
                                JsonArray genresArray = albumDetails.getAsJsonObject("genres").getAsJsonArray("data");
                                if (!genresArray.isEmpty()) {
                                    JsonObject g = genresArray.get(0).getAsJsonObject();
                                    albumGenre = Optional.ofNullable(DeezerApiDataManager.getStringOrNull(g, "name")).orElse(albumGenre);
                                    albumGenreId = safeGetInt(g, "id", albumGenreId);
                                }
                            }

                            if ((albumDetails.has("contributors") && albumDetails.get("contributors").isJsonArray())
                                    || (albumDetails.has("artists") && albumDetails.get("artists").isJsonArray())) {
                                List<String> collectedNames = new ArrayList<>();
                                List<List<byte[]>> collectedPortraits = new ArrayList<>();
                                List<Long> collectedIds = new ArrayList<>();
                                if (albumDetails.has("artists")
                                        && albumDetails.get("artists").isJsonArray()) {
                                    for (JsonElement elem : albumDetails.getAsJsonArray("artists")) {
                                        if (elem.isJsonObject()) {
                                            appendAlbumArtist(
                                                    elem.getAsJsonObject(),
                                                    collectedNames,
                                                    collectedIds,
                                                    collectedPortraits
                                            );
                                        }
                                    }
                                }
                                if (albumDetails.has("contributors")
                                        && albumDetails.get("contributors").isJsonArray()) {
                                    JsonArray owners = albumDetails.getAsJsonArray("contributors");
                                    for (JsonElement elem : owners) {
                                        if (!elem.isJsonObject()) continue;
                                        JsonObject obj = elem.getAsJsonObject();
                                        String role = DeezerApiDataManager.getStringOrNull(obj, "role");
                                        if (role != null && !role.toLowerCase(Locale.ROOT).contains("main")) continue;
                                        appendAlbumArtist(
                                                obj,
                                                collectedNames,
                                                collectedIds,
                                                collectedPortraits
                                        );
                                    }
                                }
                                if (!collectedNames.isEmpty()) {
                                    albumArtistNames = collectedNames;
                                    albumArtistIds = collectedIds;
                                    albumArtistsPortraitBytes = collectedPortraits;
                                }
                            }
                            if (albumDetails.has("artist") && albumDetails.get("artist").isJsonObject()) {
                                JsonObject obj = albumDetails.getAsJsonObject("artist");
                                appendAlbumArtist(
                                        obj,
                                        albumArtistNames,
                                        albumArtistIds,
                                        albumArtistsPortraitBytes
                                );
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            List<String> songContributorNames = new ArrayList<>();
            List<List<byte[]>> songContributorsPortraitBytes = new ArrayList<>();
            List<Long> songContributorIds = new ArrayList<>();
            if (trackDetails.has("contributors") && trackDetails.get("contributors").isJsonArray()) {
                for (JsonElement elem : trackDetails.getAsJsonArray("contributors")) {
                    if (!elem.isJsonObject()) continue;
                    JsonObject obj = elem.getAsJsonObject();
                    long contribId = safeGetLong(obj, "id", 0L);
                    String contribName = DeezerApiDataManager.extractArtistName(obj);
                    if (contribName == null || contribName.isBlank()) continue;
                    boolean albumOwner = indexOfArtistIdentity(
                            albumArtistNames,
                            albumArtistIds,
                            contribName,
                            contribId
                    ) >= 0;
                    boolean duplicate = songContributorNames.stream().anyMatch(n -> n.equalsIgnoreCase(contribName));
                    if (!albumOwner && !duplicate) {
                        songContributorIds.add(contribId);
                        songContributorNames.add(contribName);
                        songContributorsPortraitBytes.add(
                                DeezerApiDataManager.fetchImageByteLists(obj, CLIENT, "picture", "picture_medium", "picture_big")
                        );
                    }
                }
            }

            String songFileName = fallback == null || fallback.getSongFileName() == null || fallback.getSongFileName().isBlank()
                    ? songName
                    : fallback.getSongFileName();

            return new DeezerApiMetaData(
                    albumId,
                    songFileName,
                    albumArtistNames,
                    albumArtistsPortraitBytes,
                    albumName,
                    albumCoverBytesList,
                    albumReleaseDate,
                    recordType,
                    albumGenre,
                    songName,
                    songContributorNames,
                    songContributorsPortraitBytes,
                    trackOrder,
                    numberOfTracks,
                    albumArtistIds,
                    songContributorIds,
                    trackId,
                    albumCoverUrl,
                    albumGenreId
            );
        } catch (IOException e) {
            return fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    public static List<DeezerTrackInfo> fetchAlbumTracks(long albumId) throws IOException {
        AlbumTracksCacheEntry cached = ALBUM_TRACKS_CACHE.get(albumId);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.loadedAtMillis <= ALBUM_TRACKS_CACHE_TTL_MILLIS) {
            return cached.tracks;
        }

        String urlStr = "https://api.deezer.com/album/" + albumId + "/tracks";
        HttpURLConnection con = null;
        try {
            URL url = new URL(urlStr);
            con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.setConnectTimeout(8000);
            con.setReadTimeout(10000);
            int code = con.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
            if (is == null) return Collections.emptyList();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                JsonElement rootEl = JsonParser.parseString(sb.toString());
                if (!rootEl.isJsonObject()) return Collections.emptyList();
                JsonObject root = rootEl.getAsJsonObject();
                JsonArray data = null;

                if (root.has("data") && root.get("data").isJsonArray()) {
                    data = root.getAsJsonArray("data");
                } else if (root.has("tracks") && root.getAsJsonObject("tracks").has("data")) {
                    data = root.getAsJsonObject("tracks").getAsJsonArray("data");
                }
                if (data == null) return Collections.emptyList();
                List<DeezerTrackInfo> out = new ArrayList<>();
                for (JsonElement el : data) {
                    if (!el.isJsonObject()) continue;
                    JsonObject t = el.getAsJsonObject();
                    long tid = safeGetLong(t, "id", -1L); // <-- usa helper robusto
                    String title = t.has("title") && !t.get("title").isJsonNull() ? t.get("title").getAsString() : "Untitled";
                    int order = safeGetInt(t, "track_position", safeGetInt(t, "position", 0));
                    if (tid <= 0) continue;
                    out.add(new DeezerTrackInfo(tid, title, order));
                }
                List<DeezerTrackInfo> result = List.copyOf(out);
                ALBUM_TRACKS_CACHE.put(albumId, new AlbumTracksCacheEntry(result, now));
                return result;
            }
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private record AlbumTracksCacheEntry(List<DeezerTrackInfo> tracks, long loadedAtMillis) {}


    public static String extractTitle(JsonObject obj) {
        String title = DeezerJsonFields.title(obj);
        return title == null ? "" : title;
    }


    public static String extractCoverUrlFromAlbumOrPlaylist(JsonObject obj) {
        return DeezerJsonFields.artwork(obj);
    }


    public static long safeGetLong(JsonObject o, String key, long fallback) {
        return DeezerJsonFields.longValue(o, key, fallback);
    }

    private static String firstString(JsonObject object, String... keys) {
        return DeezerJsonFields.firstNonBlank(object, keys);
    }

    /**
     * Resolves artwork for card-sized views without selecting Deezer's small
     * thumbnail before a higher-resolution version that is already present.
     */
    public static String extractHighResolutionCoverUrl(JsonObject obj) {
        return DeezerJsonFields.highResolutionCover(obj);
    }

    public static String extractHighResolutionPictureUrl(JsonObject obj) {
        return DeezerJsonFields.highResolutionPicture(obj);
    }

    private static void appendAlbumArtist(
            JsonObject artistObject,
            List<String> artistNames,
            List<Long> artistIds,
            List<List<byte[]>> artistPortraits
    ) {
        if (artistObject == null) return;

        String name = DeezerApiDataManager.extractArtistName(artistObject);
        if (name == null || name.isBlank()) return;

        long id = safeGetLong(artistObject, "id", 0L);
        int existingIndex = indexOfArtistIdentity(
                artistNames,
                artistIds,
                name,
                id
        );
        List<byte[]> portraits;
        try {
            portraits = DeezerApiDataManager.fetchImageByteLists(
                    artistObject,
                    CLIENT,
                    "picture",
                    "picture_medium",
                    "picture_big"
            );
        } catch (IOException ignored) {
            portraits = new ArrayList<>();
        }

        if (existingIndex < 0) {
            artistNames.add(name.trim());
            artistIds.add(Math.max(0L, id));
            artistPortraits.add(portraits == null ? new ArrayList<>() : portraits);
            return;
        }

        while (artistIds.size() <= existingIndex) artistIds.add(0L);
        if (id > 0 && artistIds.get(existingIndex) <= 0) {
            artistIds.set(existingIndex, id);
        }

        while (artistPortraits.size() <= existingIndex) artistPortraits.add(new ArrayList<>());
        List<byte[]> current = artistPortraits.get(existingIndex);
        if ((current == null || current.isEmpty()) && portraits != null && !portraits.isEmpty()) {
            artistPortraits.set(existingIndex, portraits);
        }
    }

    private static int indexOfArtistIdentity(
            List<String> artistNames,
            List<Long> artistIds,
            String candidateName,
            long candidateId
    ) {
        if (candidateName == null || candidateName.isBlank()) return -1;

        for (int index = 0; index < artistNames.size(); index++) {
            long existingId = artistIds != null
                    && index < artistIds.size()
                    && artistIds.get(index) != null
                    ? artistIds.get(index)
                    : 0L;

            if (candidateId > 0 && existingId > 0) {
                if (candidateId == existingId) return index;
                continue;
            }

            String existingName = artistNames.get(index);
            if (existingName != null
                    && existingName.trim().equalsIgnoreCase(candidateName.trim())) {
                return index;
            }
        }

        return -1;
    }

    private static int safeGetInt(JsonObject o, String key, int fallback) {
        return DeezerJsonFields.integerValue(o, key, fallback);
    }


    public static List<Artist> fetchTrackArtists(long trackId) {
        if (trackId <= 0) return Collections.emptyList();
        String endpoint = TRACK_URL + trackId;
        Request req = new Request.Builder().url(endpoint).build();
        try (Response rsp = CLIENT.newCall(req).execute()) {
            if (!rsp.isSuccessful() || rsp.body() == null) return Collections.emptyList();
            JsonObject trackDetails = JsonParser.parseString(rsp.body().string()).getAsJsonObject();
            List<Artist> out = new ArrayList<>();
            // 1) Main artist ("artist" field)
            try {
                if (trackDetails.has("artist") && trackDetails.get("artist").isJsonObject()) {
                    JsonObject main = trackDetails.getAsJsonObject("artist");
                    long aid = safeGetLong(main, "id", 0L);
                    String aname = DeezerApiDataManager.extractArtistName(main);
                    Artist artist = new Artist(aid, aname, null, new ArrayList<>());
                    artist.setPortraitUrl(firstString(main, "picture_small", "picture_medium", "picture_big", "picture_xl", "picture"));
                    out.add(artist);
                }
            } catch (Throwable t) {
                System.out.println("fetchTrackArtists: warning reading main artist: " + t.getMessage());
            }
            // 2) Contributors (if any) — add while avoiding duplicates by ID/name
            try {
                if (trackDetails.has("contributors") && trackDetails.get("contributors").isJsonArray()) {
                    JsonArray contribs = trackDetails.getAsJsonArray("contributors");
                    for (JsonElement el : contribs) {
                        if (!el.isJsonObject()) continue;
                        JsonObject obj = el.getAsJsonObject();
                        long cid = safeGetLong(obj, "id", 0L);
                        String cname = DeezerApiDataManager.extractArtistName(obj);
                        if (cname == null || cname.isBlank()) continue;
                        boolean dup = out.stream().anyMatch(a -> (a.getArtistID() > 0 && a.getArtistID() == cid) || (a.getName() != null && a.getName().equalsIgnoreCase(cname)) );
                        if (dup) continue;
                        Artist artist = new Artist(cid, cname, null, new ArrayList<>());
                        artist.setPortraitUrl(firstString(obj, "picture_small", "picture_medium", "picture_big", "picture_xl", "picture"));
                        out.add(artist);
                    }
                }
            } catch (Throwable t) {
                System.out.println("fetchTrackArtists: warning reading contributors: " + t.getMessage());
            }
            return out;
        } catch (IOException e) {
            System.out.println("fetchTrackArtists: IO error fetching track " + trackId + " -> " + e.getMessage());
            return Collections.emptyList();
        } catch (Throwable th) {
            System.out.println("fetchTrackArtists: unexpected error -> " + th.getMessage());
            return Collections.emptyList();
        }
    }

}
