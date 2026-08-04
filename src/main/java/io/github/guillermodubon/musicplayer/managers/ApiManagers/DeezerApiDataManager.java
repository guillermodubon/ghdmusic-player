package io.github.guillermodubon.musicplayer.managers.ApiManagers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.*;

public final class DeezerApiDataManager {

    private DeezerApiDataManager() {}

    public static List<String> extractCoverUrls(JsonObject albumObj, String smallPicture, String mediumPicture, String bigPicture) {
        if (albumObj == null) return List.of();
        String smallUrl = albumObj.has(smallPicture) && !albumObj.get(smallPicture).isJsonNull()
                ? albumObj.get(smallPicture).getAsString() : "";
        String mediumUrl = albumObj.has(mediumPicture) && !albumObj.get(mediumPicture).isJsonNull()
                ? albumObj.get(mediumPicture).getAsString() : "";
        String xlUrl = albumObj.has(bigPicture) && !albumObj.get(bigPicture).isJsonNull()
                ? albumObj.get(bigPicture).getAsString() : "";

        // Return only non-empty URLs (simplifies subsequent traversal)
        List<String> out = new ArrayList<>();
        if (!smallUrl.isBlank()) out.add(smallUrl);
        if (!mediumUrl.isBlank()) out.add(mediumUrl);
        if (!xlUrl.isBlank()) out.add(xlUrl);
        return out;
    }

    public static byte[] getImageBytesFromUrl(OkHttpClient okHttpClient, String url) throws IOException {
        if (okHttpClient == null || url == null || url.isBlank()) return new byte[0];
        Request imageRequest = new Request.Builder().url(url).build();
        try (Response imageRsp = okHttpClient.newCall(imageRequest).execute()) {
            if (imageRsp.isSuccessful() && imageRsp.body() != null) {
                return imageRsp.body().bytes();
            }
        } catch (IOException e) {

            throw e;
        }
        return new byte[0];
    }

    public static List<byte[]> fetchImageByteLists(JsonObject object, OkHttpClient CLIENT, String smallPicture, String mediumPicture, String bigPicture) throws IOException {
        List<String> picturesUrlsList = extractCoverUrls(object, smallPicture, mediumPicture, bigPicture);
        List<byte[]> picturesBytesList = new ArrayList<>();
        for (String pictureUrl : picturesUrlsList) {
            if (pictureUrl == null || pictureUrl.isBlank()) {
                // avoid nulls in the list; use byte[0] as a "no data" marker
                picturesBytesList.add(new byte[0]);
            } else {
                try {
                    picturesBytesList.add(getImageBytesFromUrl(CLIENT, pictureUrl));
                } catch (IOException e) {
                    // do not abort the entire operation due to a bad URL; log it and set to empty
                    System.out.println("fetchImageByteLists: error fetching " + pictureUrl + " -> " + e.getMessage());
                    picturesBytesList.add(new byte[0]);
                }
            }
        }
        return picturesBytesList;
    }

    public static String getStringOrDefault(JsonObject obj, String memberName, String defaultValue) {
        if (obj == null || memberName == null) return defaultValue;
        if (!obj.has(memberName) || obj.get(memberName).isJsonNull()) return defaultValue;
        try { return obj.get(memberName).getAsString(); } catch (Exception e) { return defaultValue; }
    }

    public static String getStringOrNull(JsonObject obj, String memberName) {
        if (obj == null || memberName == null) return null;
        if (obj.has(memberName) && !obj.get(memberName).isJsonNull()) {
            try { return obj.get(memberName).getAsString(); } catch (Exception ignored) {}
        }
        return null;
    }


    public static JsonObject getObjectOrNull(JsonObject obj, String memberName) {
        if (obj == null || memberName == null) return null;
        if (!obj.has(memberName) || obj.get(memberName).isJsonNull()) return null;
        JsonElement el = obj.get(memberName);
        if (el != null && el.isJsonObject()) return el.getAsJsonObject();
        return null;
    }


    public static Map.Entry<String, String> extractAlbumDatesAndType(JsonObject albumDetails) {
        String releaseDate = getStringOrDefault(albumDetails, "release_date", "");
        String recordType = getStringOrDefault(albumDetails, "record_type", "");
        return new AbstractMap.SimpleEntry<>(releaseDate, recordType);
    }


    public static String extractArtistName(JsonObject contributor) {
        return getStringOrNull(contributor, "name");
    }


}
