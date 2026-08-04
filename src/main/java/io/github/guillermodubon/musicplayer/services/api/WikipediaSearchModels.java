package io.github.guillermodubon.musicplayer.services.api;

import java.util.List;

/** Internal value objects shared by the Wikipedia search components. */
record WikipediaSearchCandidate(String title, String sourceQuery, String snippet, int score) {
}

record WikipediaWikidataCandidate(
        String wikidataId,
        String label,
        String description,
        String wikipediaTitle,
        int score
) {
}

record WikipediaPageCandidate(
        String title,
        String extract,
        List<String> categories,
        boolean disambiguation,
        String wikidataItem
) {
}
