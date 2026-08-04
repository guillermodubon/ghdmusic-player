package io.github.guillermodubon.musicplayer.services.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonReader;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.common.ScreenRequestScope;
import io.github.guillermodubon.musicplayer.models.DeezerCachedResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DeezerJsonCache {
    private static final long FRESH_TTL_MILLIS = 5 * 60 * 1000L;
    private static final DeezerJsonCache INSTANCE = new DeezerJsonCache();

    private final Map<String, DeezerCachedResource> resources = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<JsonElement>> inFlight = new ConcurrentHashMap<>();
    private final ExecutorService refreshExecutor = Executors.newFixedThreadPool(5, runnable -> {
        Thread thread = new Thread(runnable, "deezer-json-cache");
        thread.setDaemon(true);
        return thread;
    });

    private DeezerJsonCache() {
    }

    public static DeezerJsonCache getInstance() {
        return INSTANCE;
    }

    public JsonObject getJsonObject(String url) throws IOException {
        JsonElement element = getJsonElement(url);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    /**
     * Fetches the resource from Deezer even when an in-memory value exists.
     *
     * Card opening uses this for fully remote content: a stale metadata cache is
     * useful for rendering sections, but must not make an unavailable album,
     * single, or playlist look playable when none of its tracks is local.
     */
    public JsonObject getFreshJsonObject(String url) throws IOException {
        JsonElement element = fetch(url);
        if (element != null && !isTransientDeezerError(element)) {
            resource(url).publish(element, System.currentTimeMillis());
        }
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    public JsonElement getJsonElement(String url) throws IOException {
        if (url == null || url.isBlank()) return null;
        DeezerCachedResource resource = resource(url);
        JsonElement cached = resource.cached();
        long now = System.currentTimeMillis();

        if (cached != null) {
            if (now - resource.loadedAtMillis() <= FRESH_TTL_MILLIS) {
                return cached;
            }
            refreshAsync(url, null, refreshExecutor);
            return cached;
        }

        JsonElement fetched = fetch(url);
        if (fetched != null && !isTransientDeezerError(fetched)) {
            resource.publish(fetched, now);
        }
        return fetched;
    }


    public CompletableFuture<JsonElement> refreshAsync(String url,
                                                       ScreenRequestScope scope,
                                                       ExecutorService executor) {
        if (url == null || url.isBlank()) return CompletableFuture.completedFuture(null);
        ExecutorService effectiveExecutor = executor == null ? refreshExecutor : executor;

        DeezerCachedResource resource = resource(url);
        if (scope != null && !scope.isActive()) {
            return CompletableFuture.completedFuture(resource.cached());
        }

        return inFlight.computeIfAbsent(url, key -> {
            resource.setLoading(true);
            CompletableFuture<JsonElement> future;
            if (scope != null) {
                future = scope.supplyAsync(() -> fetch(key), effectiveExecutor);
            } else {
                future = CompletableFuture.supplyAsync(() -> {
                    try {
                        return fetch(key);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }, effectiveExecutor);
            }

            return future.whenComplete((element, throwable) -> {
                inFlight.remove(key);
                resource.setLoading(false);
                if (throwable == null && element != null && !isTransientDeezerError(element)) {
                    resource.publish(element, System.currentTimeMillis());
                }
            });
        });
    }

    private DeezerCachedResource resource(String url) {
        return resources.computeIfAbsent(url, ignored -> new DeezerCachedResource());
    }

    private JsonElement fetch(String urlStr) throws IOException {
        HttpURLConnection con = null;
        try {
            URL url = new URL(urlStr);
            con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.setConnectTimeout(7000);
            con.setReadTimeout(9000);

            int code = con.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
            if (is == null) return null;

            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
                String body = sb.toString();
                if (body.isBlank()) return null;

                try (StringReader sr = new StringReader(body)) {
                    JsonReader reader = new JsonReader(sr);
                    reader.setLenient(true);
                    return JsonParser.parseReader(reader);
                } catch (JsonSyntaxException | IllegalStateException ex) {
                    return JsonParser.parseString(body);
                }
            }
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private boolean isTransientDeezerError(JsonElement element) {
        try {
            return element != null
                    && element.isJsonObject()
                    && element.getAsJsonObject().has("error");
        } catch (Exception ignored) {
            return false;
        }
    }

}
