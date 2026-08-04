package io.github.guillermodubon.musicplayer.services.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.guillermodubon.musicplayer.services.api.WikipediaTextNormalizer.cacheKey;
import static io.github.guillermodubon.musicplayer.services.api.WikipediaTextNormalizer.candidateKey;
import static io.github.guillermodubon.musicplayer.services.api.WikipediaTextNormalizer.hasText;

/** Builds the ordered query variants used by the existing search flow. */
final class WikipediaSearchQueries {

    private WikipediaSearchQueries() {
    }

    static List<String> buildDirectSummaryTitles(String artistName) {
        if (!hasText(artistName)) return List.of();

        String normalized = artistName;
        LinkedHashMap<String, String> titles = new LinkedHashMap<>();
        titles.put(cacheKey(normalized), normalized);

        String withoutParenthetical = normalized.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
        if (hasText(withoutParenthetical)) {
            titles.putIfAbsent(cacheKey(withoutParenthetical), withoutParenthetical);
        }

        return List.copyOf(titles.values());
    }

    static List<String> buildSearchQueries(String artistName) {
        List<String> queries = new ArrayList<>();
        String quoted = "\"" + artistName + "\"";
        queries.add(quoted + " musician");
        queries.add(quoted + " singer OR rapper OR band OR songwriter");
        queries.add("intitle:" + quoted + " musician OR singer OR band");
        queries.add(quoted + " recording artist");
        queries.add(quoted + " musical artist");
        queries.add(quoted + " YouTuber musician");
        queries.add(quoted + " influencer musician");
        queries.add(quoted + " internet personality musician");
        queries.add(quoted + " celebrity singer");
        queries.add(artistName + " music artist");
        queries.add(artistName + " singer");
        queries.add(artistName + " band");
        queries.add(artistName + " rapper");
        queries.add(artistName + " music");
        queries.add("music " + artistName);
        queries.add("singer " + artistName);
        queries.add("artist " + artistName + " music");
        queries.add(toUnderscoreQuery("music", artistName));
        queries.add(toUnderscoreQuery("singer", artistName));
        queries.add(toUnderscoreQuery(artistName, "music"));
        return queries;
    }

    static List<String> buildWikidataSearchQueries(String artistName) {
        List<String> queries = new ArrayList<>();
        queries.add(artistName);
        queries.add(artistName + " band");
        queries.add(artistName + " musical group");
        queries.add(artistName + " singer");
        queries.add(artistName + " musician");
        queries.add(artistName + " rapper");
        queries.add(artistName + " songwriter");
        queries.add(artistName + " record producer");
        queries.add(artistName + " recording artist");
        queries.add(artistName + " YouTuber musician");
        queries.add(artistName + " influencer musician");
        queries.add(artistName + " internet personality musician");
        queries.add(artistName + " celebrity singer");
        queries.add("band " + artistName);
        queries.add("singer " + artistName);
        queries.add("musician " + artistName);
        queries.add("music " + artistName);
        return distinctQueries(queries);
    }

    static List<String> buildGeneratorSearchQueries(String artistName) {
        List<String> queries = new ArrayList<>();
        queries.add(artistName + " (band)");
        queries.add(artistName + " (singer)");
        queries.add(artistName + " (musician)");
        queries.add(artistName + " (rapper)");
        queries.add(artistName + " (record producer)");
        queries.add(artistName + " (songwriter)");
        queries.add(artistName + " YouTuber musician");
        queries.add(artistName + " influencer musician");
        queries.add(artistName + " internet personality musician");
        queries.add(artistName + " celebrity singer");
        queries.add(artistName + " band");
        queries.add(artistName + " singer");
        queries.add(artistName + " musician");
        queries.add(artistName + " musical group");
        queries.add(artistName + " music artist");
        queries.add("\"" + artistName + "\" musician");
        queries.add("\"" + artistName + "\" band");
        queries.add("\"" + artistName + "\" singer");
        return distinctQueries(queries);
    }

    static List<String> buildOpenSearchQueries(String artistName) {
        List<String> queries = new ArrayList<>();
        queries.add(artistName);
        queries.add(artistName + " musician");
        queries.add(artistName + " music");
        queries.add(artistName + " singer");
        queries.add(artistName + " rapper");
        queries.add(artistName + " band");
        queries.add(artistName + " musical artist");
        queries.add(artistName + " recording artist");
        queries.add(artistName + " YouTuber musician");
        queries.add(artistName + " influencer musician");
        queries.add(artistName + " internet personality musician");
        queries.add(artistName + " celebrity singer");
        queries.add("music " + artistName);
        queries.add("singer " + artistName);
        queries.add("musician " + artistName);
        queries.add("artist " + artistName + " music");
        queries.add(toUnderscoreQuery("music", artistName));
        queries.add(toUnderscoreQuery("singer", artistName));
        queries.add(toUnderscoreQuery("musician", artistName));
        queries.add(toUnderscoreQuery(artistName, "music"));
        queries.add(toUnderscoreQuery(artistName, "singer"));
        return distinctQueries(queries);
    }

    static void addDirectArtistTitleCandidates(Map<String, WikipediaSearchCandidate> candidates,
                                                String artistName) {
        if (candidates == null || !hasText(artistName)) return;
        String clean = artistName.strip();
        addCandidate(candidates, new WikipediaSearchCandidate(clean, "direct-title", "", 125));

        String[] suffixes = {
                " (musician)",
                " (singer)",
                " (rapper)",
                " (band)",
                " (DJ)",
                " (record producer)",
                " (songwriter)",
                " (musical artist)"
        };
        int score = 140;
        for (String suffix : suffixes) {
            addCandidate(candidates, new WikipediaSearchCandidate(clean + suffix, "direct-title", "", score--));
        }
    }

    static void addCandidate(Map<String, WikipediaSearchCandidate> candidates,
                             WikipediaSearchCandidate candidate) {
        if (candidates == null || candidate == null || !hasText(candidate.title())) return;
        String key = candidateKey(candidate.title());
        WikipediaSearchCandidate previous = candidates.get(key);
        if (previous == null || candidate.score() > previous.score()) {
            candidates.put(key, candidate);
        }
    }

    static List<String> distinctQueries(List<String> queries) {
        if (queries == null) return List.of();
        return queries.stream()
                .filter(WikipediaTextNormalizer::hasText)
                .map(String::strip)
                .distinct()
                .toList();
    }

    static String toUnderscoreQuery(String left, String right) {
        String a = left == null ? "" : left.trim().replaceAll("\\s+", "_");
        String b = right == null ? "" : right.trim().replaceAll("\\s+", "_");
        if (a.isBlank()) return b;
        if (b.isBlank()) return a;
        return a + "_" + b;
    }
}
