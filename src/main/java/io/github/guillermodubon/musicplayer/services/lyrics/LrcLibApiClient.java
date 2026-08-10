package io.github.guillermodubon.musicplayer.services.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.guillermodubon.musicplayer.models.lyrics.LrcLibLookupResult;
import io.github.guillermodubon.musicplayer.models.lyrics.LyricsLookupCandidate;
import io.github.guillermodubon.musicplayer.models.lyrics.LyricsStatus;
import io.github.guillermodubon.musicplayer.models.lyrics.PlainLyrics;
import io.github.guillermodubon.musicplayer.models.lyrics.SongLyrics;
import io.github.guillermodubon.musicplayer.models.lyrics.SyncedLyrics;
import io.github.guillermodubon.musicplayer.utils.LrcLibEndpoints;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/** Responsible, sequential client for the read-only LRCLIB lookup APIs. */
public final class LrcLibApiClient {

    private static final String USER_AGENT =
            "GHDMusic/1.0 (https://github.com/guillermodubon/musicPlayer)";
    private static final long RETRYABLE_ERROR_DELAY_MILLIS = Duration.ofMinutes(15).toMillis();
    private static final long NOT_FOUND_RECHECK_DELAY_MILLIS = Duration.ofDays(7).toMillis();

    private final OkHttpClient httpClient;
    private final LrcLibRequestThrottle throttle;

    public LrcLibApiClient() {
        this(new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(10))
                .build(), new LrcLibRequestThrottle());
    }

    LrcLibApiClient(OkHttpClient httpClient, LrcLibRequestThrottle throttle) {
        this.httpClient = httpClient;
        this.throttle = throttle;
    }

    public LrcLibLookupResult lookup(LyricsLookupCandidate candidate) {
        long now = System.currentTimeMillis();
        if (candidate == null || !candidate.hasLookupIdentity()) {
            return result(LyricsStatus.INSUFFICIENT_METADATA, now, 0L);
        }

        try {
            HttpResult exact = get(LrcLibEndpoints.lookup(
                    candidate.trackName(),
                    candidate.artistName(),
                    candidate.albumName(),
                    candidate.durationSeconds()
            ));
            if (exact.statusCode == 200 && exact.body != null && exact.body.isJsonObject()) {
                return found(exact.body.getAsJsonObject(), now);
            }
            if (exact.statusCode == 429) {
                return rateLimited(now, exact.retryAfterMillis);
            }
            if (exact.statusCode == 404) {
                return search(candidate, now);
            }
            return retryable(now);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return retryable(now);
        } catch (IOException | RuntimeException ignored) {
            return retryable(now);
        }
    }

    private LrcLibLookupResult search(LyricsLookupCandidate candidate, long now)
            throws IOException, InterruptedException {
        HttpResult response = get(LrcLibEndpoints.search(
                candidate.trackName(), candidate.artistName(), candidate.albumName()));
        if (response.statusCode == 200 && response.body != null && response.body.isJsonArray()) {
            JsonObject best = selectBest(response.body.getAsJsonArray(), candidate);
            return best == null ? result(LyricsStatus.NOT_FOUND, now, NOT_FOUND_RECHECK_DELAY_MILLIS)
                    : found(best, now);
        }
        if (response.statusCode == 429) return rateLimited(now, response.retryAfterMillis);
        if (response.statusCode == 404) {
            return result(LyricsStatus.NOT_FOUND, now, NOT_FOUND_RECHECK_DELAY_MILLIS);
        }
        return retryable(now);
    }

    private HttpResult get(okhttp3.HttpUrl url) throws IOException, InterruptedException {
        throttle.awaitTurn();
        Request request = new Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            long retryAfterMillis = retryAfterMillis(response.header("Retry-After"));
            if (response.code() == 429) throttle.deferFor(retryAfterMillis);
            String raw = response.body() == null ? "" : response.body().string();
            JsonElement body = raw.isBlank() ? null : JsonParser.parseString(raw);
            return new HttpResult(response.code(), body, retryAfterMillis);
        }
    }

    private LrcLibLookupResult found(JsonObject record, long now) {
        boolean instrumental = getBoolean(record, "instrumental");
        String plain = getString(record, "plainLyrics");
        String synced = getString(record, "syncedLyrics");
        LyricsStatus status = instrumental ? LyricsStatus.INSTRUMENTAL
                : (!plain.isBlank() || !synced.isBlank() ? LyricsStatus.FOUND : LyricsStatus.NOT_FOUND);
        long retryAt = status == LyricsStatus.NOT_FOUND ? now + NOT_FOUND_RECHECK_DELAY_MILLIS : 0L;
        return new LrcLibLookupResult(new SongLyrics(
                getLong(record, "id"), new PlainLyrics(plain), new SyncedLyrics(synced), instrumental,
                status, now, now, retryAt), 0L);
    }

    private LrcLibLookupResult rateLimited(long now, long retryAfterMillis) {
        long delay = Math.max(Duration.ofSeconds(1).toMillis(), retryAfterMillis);
        return result(LyricsStatus.RETRYABLE_ERROR, now, delay);
    }

    private LrcLibLookupResult retryable(long now) {
        return result(LyricsStatus.RETRYABLE_ERROR, now, RETRYABLE_ERROR_DELAY_MILLIS);
    }

    private static LrcLibLookupResult result(LyricsStatus status, long now, long retryDelayMillis) {
        long retryAt = retryDelayMillis <= 0L ? 0L : now + retryDelayMillis;
        return new LrcLibLookupResult(new SongLyrics(
                0L, new PlainLyrics(""), new SyncedLyrics(""), false, status,
                0L, now, retryAt), retryDelayMillis);
    }

    private static JsonObject selectBest(JsonArray records, LyricsLookupCandidate candidate) {
        JsonObject best = null;
        int highestScore = Integer.MIN_VALUE;
        for (JsonElement element : records) {
            if (!element.isJsonObject()) continue;
            JsonObject record = element.getAsJsonObject();
            int score = score(record, candidate);
            if (score > highestScore) {
                highestScore = score;
                best = record;
            }
        }
        return highestScore >= 130 ? best : null;
    }

    private static int score(JsonObject record, LyricsLookupCandidate candidate) {
        String expectedTitle = normalize(candidate.trackName());
        String actualTitle = normalize(getString(record, "trackName"));
        if (!expectedTitle.equals(actualTitle)) return Integer.MIN_VALUE;

        int duration = (int) getLong(record, "duration");
        if (candidate.durationSeconds() > 0 && duration > 0
                && Math.abs(candidate.durationSeconds() - duration) > 2) {
            return Integer.MIN_VALUE;
        }

        int score = 100;
        if (normalize(candidate.artistName()).equals(normalize(getString(record, "artistName")))) score += 50;
        if (!candidate.albumName().isBlank()
                && normalize(candidate.albumName()).equals(normalize(getString(record, "albumName")))) score += 20;
        if (candidate.durationSeconds() > 0 && duration > 0) score += 20;
        return score;
    }

    private static long retryAfterMillis(String header) {
        try {
            return Math.max(0L, Long.parseLong(Optional.ofNullable(header).orElse("0").trim()) * 1_000L);
        } catch (NumberFormatException ignored) {
            return Duration.ofMinutes(1).toMillis();
        }
    }

    private static String getString(JsonObject object, String member) {
        if (object == null || !object.has(member) || object.get(member).isJsonNull()) return "";
        try { return object.get(member).getAsString(); } catch (RuntimeException ignored) { return ""; }
    }

    private static long getLong(JsonObject object, String member) {
        if (object == null || !object.has(member) || object.get(member).isJsonNull()) return 0L;
        try { return object.get(member).getAsLong(); } catch (RuntimeException ignored) { return 0L; }
    }

    private static boolean getBoolean(JsonObject object, String member) {
        return object != null && object.has(member) && !object.get(member).isJsonNull()
                && object.get(member).getAsBoolean();
    }

    private static String normalize(String value) {
        return Optional.ofNullable(value).orElse("").trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ").replaceAll("\\s+", " ");
    }

    private record HttpResult(int statusCode, JsonElement body, long retryAfterMillis) {
    }
}
