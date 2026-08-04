
package io.github.guillermodubon.musicplayer.services.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class DeezerHttpClient {

    private static final int MAX_ATTEMPTS = 3;

    public JsonObject fetchJsonObject(String urlStr) {
        return fetchJsonObjectStatic(urlStr);
    }

    public static JsonObject fetchJsonObjectStatic(String urlStr) {
        if (urlStr == null || urlStr.isBlank()) return null;

        int attempt = 0;
        while (attempt < MAX_ATTEMPTS) {
            attempt++;
            HttpURLConnection conn = null;
            try {
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5_000);
                conn.setReadTimeout(7_000);
                conn.setRequestProperty("User-Agent", "GHDMusic/1.0");

                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    try (InputStreamReader r = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                        JsonElement el = JsonParser.parseReader(r);
                        if (el != null && el.isJsonObject()) {
                            return el.getAsJsonObject();
                        }
                        return null;
                    }
                }

                if (code == 429 || (code >= 500 && code < 600)) {
                    sleepBackoff(attempt);
                    continue;
                }
                return null;
            } catch (Throwable t) {
                if (attempt >= MAX_ATTEMPTS) {
                    System.out.println("fetchJsonObject: failed for " + urlStr + " -> " + Optional.ofNullable(t.getMessage()).orElse("null"));
                }
                sleepBackoff(attempt);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return null;
    }

    public static byte[] downloadUrlToBytesStatic(String urlStr) {
        if (urlStr == null || urlStr.isBlank()) return null;

        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("User-Agent", "GHDMusic/1.0");

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }

            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream bout = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) {
                    bout.write(buf, 0, r);
                }
                return bout.toByteArray();
            }
        } catch (Throwable t) {
            System.out.println("downloadUrlToBytes: failed for " + urlStr + " -> " + Optional.ofNullable(t.getMessage()).orElse("null"));
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void sleepBackoff(int attempt) {
        try {
            TimeUnit.MILLISECONDS.sleep(200L * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
