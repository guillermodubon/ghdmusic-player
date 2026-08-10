package io.github.guillermodubon.musicplayer.utils;

import okhttp3.HttpUrl;

/** Centralized LRCLIB endpoints so request paths never leak into services. */
public final class LrcLibEndpoints {

    private static final HttpUrl API_ROOT = HttpUrl.get("https://lrclib.net/api/");

    private LrcLibEndpoints() {
    }

    public static HttpUrl lookup(String trackName, String artistName, String albumName, int durationSeconds) {
        HttpUrl.Builder builder = API_ROOT.newBuilder("get")
                .addQueryParameter("track_name", trackName)
                .addQueryParameter("artist_name", artistName);
        if (albumName != null && !albumName.isBlank()) {
            builder.addQueryParameter("album_name", albumName);
        }
        if (durationSeconds > 0 && durationSeconds <= 3_600) {
            builder.addQueryParameter("duration", Integer.toString(durationSeconds));
        }
        return builder.build();
    }

    public static HttpUrl search(String trackName, String artistName, String albumName) {
        HttpUrl.Builder builder = API_ROOT.newBuilder("search")
                .addQueryParameter("track_name", trackName);
        if (artistName != null && !artistName.isBlank()) {
            builder.addQueryParameter("artist_name", artistName);
        }
        if (albumName != null && !albumName.isBlank()) {
            builder.addQueryParameter("album_name", albumName);
        }
        return builder.build();
    }
}
