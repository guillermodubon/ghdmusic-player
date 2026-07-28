package io.github.guillermodubon.musicplayer.models;

import com.google.gson.JsonObject;
import javafx.scene.image.Image;

import java.util.List;

public record SearchCandidate(
        String candidateKey,
        long id,
        String type,
        String title,
        List<String> artistNames,
        String coverUrl,
        JsonObject artistJson,
        Image localCover,
        Artist localArtist,
        String actionId
) {
    public SearchCandidate(String candidateKey,
                           long id,
                           String type,
                           String title,
                           List<String> artistNames,
                           String coverUrl,
                           JsonObject artistJson) {
        this(candidateKey, id, type, title, artistNames, coverUrl, artistJson, null, null, null);
    }

    public SearchCandidate(String candidateKey,
                           long id,
                           String type,
                           String title,
                           List<String> artistNames,
                           String coverUrl,
                           JsonObject artistJson,
                           Image localCover,
                           Artist localArtist) {
        this(candidateKey, id, type, title, artistNames, coverUrl, artistJson, localCover, localArtist, null);
    }

    public String resolvedActionId() {
        return actionId == null || actionId.isBlank() ? String.valueOf(id) : actionId;
    }
}
