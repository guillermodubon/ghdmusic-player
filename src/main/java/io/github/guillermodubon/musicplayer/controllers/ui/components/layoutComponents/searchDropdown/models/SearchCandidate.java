package io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.searchDropdown.models;

import com.google.gson.JsonObject;
import java.util.List;

public record SearchCandidate(
        String candidateKey,
        long id,
        String type,
        String title,
        List<String> artistNames,
        String coverUrl,
        JsonObject artistJson
) {}