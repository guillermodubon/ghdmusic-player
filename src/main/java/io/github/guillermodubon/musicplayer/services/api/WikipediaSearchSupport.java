package io.github.guillermodubon.musicplayer.services.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static io.github.guillermodubon.musicplayer.services.api.WikipediaTextNormalizer.cacheKey;
import static io.github.guillermodubon.musicplayer.services.api.WikipediaTextNormalizer.compactKey;
import static io.github.guillermodubon.musicplayer.services.api.WikipediaTextNormalizer.elementString;
import static io.github.guillermodubon.musicplayer.services.api.WikipediaTextNormalizer.firstSentence;
import static io.github.guillermodubon.musicplayer.services.api.WikipediaTextNormalizer.hasText;
import static io.github.guillermodubon.musicplayer.services.api.WikipediaTextNormalizer.stripHtml;
import static io.github.guillermodubon.musicplayer.services.api.WikipediaTextNormalizer.stringField;
import static io.github.guillermodubon.musicplayer.services.api.WikipediaTextNormalizer.stylizedCompactKey;

/** Builds candidates and applies the existing music-focused validation rules. */
final class WikipediaSearchSupport {

    static final int MAX_VALIDATION_CANDIDATES = 14;
    static final int MIN_ACCEPT_SCORE = 150;

    private static final Set<String> WIKIDATA_MUSIC_OCCUPATIONS = Set.of(
            "Q639669",    // musician
            "Q177220",    // singer
            "Q753110",    // songwriter
            "Q2252262",   // rapper
            "Q183945",    // record producer
            "Q36834",     // composer
            "Q15117302",  // disc jockey
            "Q488205"     // conductor
    );

    private static final Set<String> WIKIDATA_MUSIC_INSTANCES = Set.of(
            "Q215380",    // musical group
            "Q2088357",   // musical ensemble
            "Q5741069",   // rock band
            "Q10648343"   // musical duo
    );

    private static final Map<String, Integer> WIKIDATA_SCORE_CACHE = new ConcurrentHashMap<>();

    private final WikipediaApiClient client;

    WikipediaSearchSupport(WikipediaApiClient client) {
        this.client = client;
    }

    boolean isQualifiedMusicPage(String artistName, WikipediaPageCandidate page, int candidateScore) {
        if (page == null || !hasText(artistName) || !hasText(page.extract())) return false;
        int score = scoreResolvedPage(
                new WikipediaSearchCandidate(page.title(), "validated-page", "", candidateScore),
                page,
                artistName,
                !hasClearTextualMusicSignal(page)
        );
        return score >= MIN_ACCEPT_SCORE;
    }

    WikipediaPageCandidate resolvePageFromWikidata(String artistName) {
        List<WikipediaWikidataCandidate> candidates = findWikidataCandidates(artistName);
        if (candidates.isEmpty()) return null;

        WikipediaPageCandidate bestPage = null;
        int bestScore = Integer.MIN_VALUE;

        for (WikipediaWikidataCandidate candidate : candidates.stream()
                .limit(MAX_VALIDATION_CANDIDATES).toList()) {
            if (!hasText(candidate.wikipediaTitle())) continue;
            WikipediaPageCandidate page = client.fetchPageCandidate(candidate.wikipediaTitle());
            if (page == null || !hasText(page.extract())) continue;

            int score = scoreResolvedPage(
                    new WikipediaSearchCandidate(
                            page.title(),
                            "wikidata:" + candidate.wikidataId(),
                            candidate.description(),
                            170 + candidate.score()
                    ),
                    page,
                    artistName
            );
            if (score > bestScore) {
                bestScore = score;
                bestPage = page;
            }
        }

        return bestScore >= MIN_ACCEPT_SCORE ? bestPage : null;
    }

    private List<WikipediaWikidataCandidate> findWikidataCandidates(String artistName) {
        Map<String, WikipediaWikidataCandidate> candidates = new LinkedHashMap<>();
        int queryScore = 150;

        for (String query : WikipediaSearchQueries.buildWikidataSearchQueries(artistName)) {
            JsonArray results = client.searchWikidata(query);
            if (results == null || results.isEmpty()) {
                queryScore = Math.max(90, queryScore - 6);
                continue;
            }

            for (int i = 0; i < results.size(); i++) {
                JsonElement element = results.get(i);
                if (element == null || !element.isJsonObject()) continue;
                JsonObject result = element.getAsJsonObject();

                String id = stringField(result, "id");
                String label = stringField(result, "label");
                String description = stringField(result, "description");
                if (!hasText(id) || !hasText(label)) continue;

                String lowerDescription = description.toLowerCase(Locale.ROOT);
                int musicScore = wikidataMusicScore(id);
                boolean descriptionLooksMusical = containsMusicTerm("", lowerDescription)
                        || containsAny("", lowerDescription,
                        "rock group", "pop group", "latin group", "musical act");
                if (musicScore <= 0 && !descriptionLooksMusical) continue;

                String wikipediaTitle = client.fetchEnglishWikipediaTitle(id);
                if (!hasText(wikipediaTitle)) continue;

                int score = Math.max(0, queryScore - (i * 8));
                score += titleMatchScore(label, artistName);
                score += Math.min(160, musicScore);
                if (descriptionLooksMusical) score += 60;

                WikipediaWikidataCandidate candidate = new WikipediaWikidataCandidate(
                        id, label, description, wikipediaTitle, score
                );
                WikipediaWikidataCandidate previous = candidates.get(id);
                if (previous == null || candidate.score() > previous.score()) {
                    candidates.put(id, candidate);
                }
            }
            queryScore = Math.max(90, queryScore - 6);
        }

        return candidates.values().stream()
                .sorted(Comparator.comparingInt(WikipediaWikidataCandidate::score).reversed())
                .toList();
    }

    WikipediaPageCandidate resolvePageFromGeneratorSearch(String artistName) {
        WikipediaPageCandidate bestPage = null;
        int bestScore = Integer.MIN_VALUE;
        int queryScore = 145;

        for (String query : WikipediaSearchQueries.buildGeneratorSearchQueries(artistName)) {
            List<WikipediaPageCandidate> pages = client.generatorSearchPages(query);
            for (int i = 0; i < pages.size(); i++) {
                WikipediaPageCandidate page = pages.get(i);
                int score = scoreResolvedPage(
                        new WikipediaSearchCandidate(
                                page.title(),
                                "generator:" + query,
                                "",
                                Math.max(0, queryScore - (i * 8))
                        ),
                        page,
                        artistName
                );
                if (score > bestScore) {
                    bestScore = score;
                    bestPage = page;
                }
            }
            queryScore = Math.max(92, queryScore - 5);
            if (bestScore >= MIN_ACCEPT_SCORE + 120) break;
        }

        return bestScore >= MIN_ACCEPT_SCORE ? bestPage : null;
    }

    List<WikipediaSearchCandidate> findCandidateTitles(String artistName) {
        Map<String, WikipediaSearchCandidate> candidates = new LinkedHashMap<>();

        WikipediaSearchQueries.addDirectArtistTitleCandidates(candidates, artistName);
        int baseScore = 132;
        List<String> openSearchQueries = WikipediaSearchQueries.buildOpenSearchQueries(artistName);
        int coreOpenSearchCount = Math.min(8, openSearchQueries.size());
        for (String query : openSearchQueries.subList(0, coreOpenSearchCount)) {
            addOpenSearchCandidates(candidates, query, baseScore);
            baseScore = Math.max(96, baseScore - 4);
        }
        if (candidates.size() < 5 && coreOpenSearchCount < openSearchQueries.size()) {
            for (String query : openSearchQueries.subList(coreOpenSearchCount, openSearchQueries.size())) {
                addOpenSearchCandidates(candidates, query, baseScore);
                baseScore = Math.max(88, baseScore - 3);
            }
        }

        List<String> searchQueries = WikipediaSearchQueries.buildSearchQueries(artistName);
        int coreSearchCount = Math.min(6, searchQueries.size());
        for (String query : searchQueries.subList(0, coreSearchCount)) {
            JsonArray results = client.searchWikipedia(query);
            if (results == null || results.isEmpty()) continue;
            addSearchResults(candidates, artistName, query, results, 95);
        }
        if (candidates.size() < 5 && coreSearchCount < searchQueries.size()) {
            for (String query : searchQueries.subList(coreSearchCount, searchQueries.size())) {
                JsonArray results = client.searchWikipedia(query);
                if (results == null || results.isEmpty()) continue;
                addSearchResults(candidates, artistName, query, results, 82);
            }
        }

        return candidates.values().stream()
                .sorted(Comparator.comparingInt(WikipediaSearchCandidate::score).reversed())
                .toList();
    }

    private void addSearchResults(
            Map<String, WikipediaSearchCandidate> candidates,
            String artistName,
            String query,
            JsonArray results,
            int baseScore
    ) {
        for (int i = 0; i < results.size(); i++) {
            JsonElement element = results.get(i);
            if (element == null || !element.isJsonObject()) continue;

            JsonObject result = element.getAsJsonObject();
            String title = stringField(result, "title");
            if (!hasText(title)) continue;

            String snippet = stripHtml(stringField(result, "snippet"));
            String lowerTitle = title.toLowerCase(Locale.ROOT);
            String lowerSnippet = snippet.toLowerCase(Locale.ROOT);
            if (isDisambiguationOrList(lowerTitle, lowerSnippet)) continue;

            int score = Math.max(0, baseScore - (i * 5));
            score += titleMatchScore(title, artistName);
            if (containsMusicTerm(lowerTitle, lowerSnippet)) score += 55;
            if (isLikelyMediaObject(lowerTitle, lowerSnippet)) score -= 90;

            WikipediaSearchQueries.addCandidate(
                    candidates,
                    new WikipediaSearchCandidate(title, query, snippet, score)
            );
        }
    }

    private void addOpenSearchCandidates(Map<String, WikipediaSearchCandidate> candidates,
                                         String query,
                                         int baseScore) {
        JsonArray openSearch = client.openSearchWikipedia(query);
        if (openSearch == null || openSearch.size() < 2 || !openSearch.get(1).isJsonArray()) return;

        JsonArray titles = openSearch.get(1).getAsJsonArray();
        JsonArray descriptions = openSearch.size() > 2 && openSearch.get(2).isJsonArray()
                ? openSearch.get(2).getAsJsonArray()
                : new JsonArray();

        for (int i = 0; i < titles.size(); i++) {
            String title = elementString(titles.get(i));
            if (!hasText(title)) continue;

            String description = i < descriptions.size() ? elementString(descriptions.get(i)) : "";
            String lowerTitle = title.toLowerCase(Locale.ROOT);
            String lowerDescription = description.toLowerCase(Locale.ROOT);
            if (isDisambiguationOrList(lowerTitle, lowerDescription)) continue;

            int score = Math.max(0, baseScore - (i * 7));
            score += titleMatchScore(title, query);
            score += titleMatchScore(title, normalizeArtistName(query));
            if (containsMusicTerm(lowerTitle, lowerDescription)) score += 50;
            if (isLikelyMediaObject(lowerTitle, lowerDescription)) score -= 90;
            WikipediaSearchQueries.addCandidate(
                    candidates,
                    new WikipediaSearchCandidate(title, "opensearch:" + query, description, score)
            );
        }
    }

    int scoreResolvedPage(WikipediaSearchCandidate candidate,
                          WikipediaPageCandidate page,
                          String artistName) {
        return scoreResolvedPage(candidate, page, artistName, true);
    }

    private int scoreResolvedPage(WikipediaSearchCandidate candidate,
                                  WikipediaPageCandidate page,
                                  String artistName,
                                  boolean includeWikidataScore) {
        String lowerTitle = page.title().toLowerCase(Locale.ROOT);
        String lowerExtract = page.extract().toLowerCase(Locale.ROOT);
        String firstSentence = firstSentence(page.extract());
        String lowerFirstSentence = firstSentence.toLowerCase(Locale.ROOT);
        String lowerCategories = String.join(" ", page.categories()).toLowerCase(Locale.ROOT);

        if (page.disambiguation()
                || isDisambiguationOrList(lowerTitle, lowerFirstSentence)
                || isLikelyMediaObject(lowerTitle, lowerFirstSentence)) {
            return Integer.MIN_VALUE;
        }

        int titleScore = titleMatchScore(page.title(), artistName);
        boolean titleMatches = titleScore >= 60;
        boolean aliasAppears = normalizedContains(lowerExtract, artistName);
        boolean musicSignal = containsMusicTerm(lowerTitle, lowerFirstSentence)
                || containsMusicTerm(lowerTitle, lowerExtract)
                || containsMusicTerm(lowerCategories, lowerCategories);
        boolean categoryMusicSignal = containsMusicCategory(lowerCategories);
        int wikidataMusicScore = includeWikidataScore ? wikidataMusicScore(page.wikidataItem()) : 0;

        if (!titleMatches && !aliasAppears) return Integer.MIN_VALUE;
        if (!musicSignal && !categoryMusicSignal && wikidataMusicScore <= 0) return Integer.MIN_VALUE;

        int score = candidate.score();
        score += titleScore;
        if (aliasAppears) score += 35;
        if (musicSignal) score += 70;
        if (categoryMusicSignal) score += 80;
        score += wikidataMusicScore;
        score += firstSentenceSubjectScore(page.extract(), artistName);

        if (containsPublicFigureTerm(lowerTitle, lowerFirstSentence)
                || containsPublicFigureTerm(lowerCategories, lowerExtract)) {
            score += 45;
        }

        if (containsAny(lowerTitle, lowerFirstSentence, "footballer", "politician", "basketball", "baseball")) {
            score -= 90;
        }
        if (containsAny(lowerTitle, lowerFirstSentence, "actor", "actress", "film director")
                && wikidataMusicScore <= 0) {
            score -= 55;
        }

        return score;
    }

    private boolean hasClearTextualMusicSignal(WikipediaPageCandidate page) {
        if (page == null) return false;
        String lowerTitle = page.title().toLowerCase(Locale.ROOT);
        String lowerExtract = page.extract().toLowerCase(Locale.ROOT);
        String lowerCategories = String.join(" ", page.categories()).toLowerCase(Locale.ROOT);
        return containsMusicTerm(lowerTitle, lowerExtract)
                || containsMusicCategory(lowerCategories);
    }

    int wikidataMusicScore(String wikidataItem) {
        if (!hasText(wikidataItem)) return 0;
        return WIKIDATA_SCORE_CACHE.computeIfAbsent(wikidataItem, this::fetchWikidataMusicScore);
    }

    boolean isPublicFigureMusicPage(WikipediaPageCandidate page) {
        if (page == null) return false;

        String lowerTitle = page.title().toLowerCase(Locale.ROOT);
        String lowerExtract = page.extract().toLowerCase(Locale.ROOT);
        String lowerCategories = String.join(" ", page.categories()).toLowerCase(Locale.ROOT);
        boolean publicFigure = containsPublicFigureTerm(lowerTitle, lowerExtract)
                || containsPublicFigureTerm(lowerCategories, lowerExtract);
        if (!publicFigure) return false;

        return containsMusicTerm(lowerTitle, lowerExtract)
                || containsMusicCategory(lowerCategories)
                || wikidataMusicScore(page.wikidataItem()) > 0;
    }

    String normalizeArtistName(String artistName) {
        if (artistName == null) return "";
        return artistName
                .replaceAll("(?i)\\s+(feat\\.|ft\\.|featuring)\\s+.*$", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private int fetchWikidataMusicScore(String wikidataItem) {
        JsonObject entity = client.fetchWikidataEntity(wikidataItem);
        if (entity == null) return 0;

        JsonObject claims = entity.has("claims") && entity.get("claims").isJsonObject()
                ? entity.getAsJsonObject("claims")
                : null;
        if (claims == null) return 0;

        int score = 0;
        score += scoreWikidataClaims(claims, "P106", WIKIDATA_MUSIC_OCCUPATIONS, 110);
        score += scoreWikidataClaims(claims, "P31", WIKIDATA_MUSIC_INSTANCES, 115);
        return Math.min(score, 160);
    }

    private int scoreWikidataClaims(JsonObject claims,
                                    String property,
                                    Set<String> expectedIds,
                                    int score) {
        if (claims == null || !claims.has(property) || !claims.get(property).isJsonArray()) return 0;
        for (JsonElement claimElement : claims.getAsJsonArray(property)) {
            String valueId = wikidataClaimValueId(claimElement);
            if (expectedIds.contains(valueId)) return score;
        }
        return 0;
    }

    private String wikidataClaimValueId(JsonElement claimElement) {
        if (claimElement == null || !claimElement.isJsonObject()) return "";
        JsonObject claim = claimElement.getAsJsonObject();
        JsonObject mainsnak = claim.has("mainsnak") && claim.get("mainsnak").isJsonObject()
                ? claim.getAsJsonObject("mainsnak")
                : null;
        JsonObject datavalue = mainsnak != null && mainsnak.has("datavalue")
                && mainsnak.get("datavalue").isJsonObject()
                ? mainsnak.getAsJsonObject("datavalue")
                : null;
        JsonObject value = datavalue != null && datavalue.has("value")
                && datavalue.get("value").isJsonObject()
                ? datavalue.getAsJsonObject("value")
                : null;
        return stringField(value, "id");
    }

    private int titleMatchScore(String title, String artistName) {
        String normalizedTitle = cacheKey(title);
        String normalizedArtist = cacheKey(artistName);
        String compactTitle = compactKey(title);
        String compactArtist = compactKey(artistName);
        String stylizedArtist = stylizedCompactKey(artistName);
        if (normalizedTitle.isBlank() || normalizedArtist.isBlank()) return 0;

        if (normalizedTitle.equals(normalizedArtist)
                || compactTitle.equals(compactArtist)
                || compactTitle.equals(stylizedArtist)) {
            return 140;
        }
        if (normalizedTitle.startsWith(normalizedArtist + " ")
                || (!compactArtist.isBlank() && compactTitle.startsWith(compactArtist))
                || (!stylizedArtist.isBlank() && compactTitle.startsWith(stylizedArtist))) {
            if (normalizedTitle.matches(".*\\b(musician|singer|rapper|band|dj|producer|songwriter|composer|artist)\\b.*")) {
                return 125;
            }
            return 85;
        }
        if (normalizedTitle.contains(normalizedArtist)) return 65;
        return 0;
    }

    private int firstSentenceSubjectScore(String extract, String artistName) {
        if (!hasText(extract) || !hasText(artistName)) return 0;

        String normalizedSentence = cacheKey(firstSentence(extract));
        String normalizedArtist = cacheKey(artistName);
        String compactSentence = compactKey(firstSentence(extract));
        String stylizedArtist = stylizedCompactKey(artistName);
        if (normalizedSentence.startsWith(normalizedArtist + " ")) return 55;
        if (normalizedSentence.contains("known professionally as " + normalizedArtist)) return 65;
        if (normalizedSentence.contains("professionally known as " + normalizedArtist)) return 65;
        if (!stylizedArtist.isBlank() && compactSentence.contains(stylizedArtist)) return 35;
        if (normalizedSentence.contains(normalizedArtist)) return 25;
        return 0;
    }

    private boolean normalizedContains(String text, String value) {
        String normalizedText = cacheKey(text);
        String normalizedValue = cacheKey(value);
        String compactText = compactKey(text);
        String compactValue = compactKey(value);
        String stylizedValue = stylizedCompactKey(value);
        return (!normalizedText.isBlank()
                && !normalizedValue.isBlank()
                && (" " + normalizedText + " ").contains(" " + normalizedValue + " "))
                || (!compactText.isBlank() && compactValue.length() >= 4 && compactText.contains(compactValue))
                || (!compactText.isBlank() && stylizedValue.length() >= 4 && compactText.contains(stylizedValue));
    }

    private boolean containsMusicTerm(String lowerTitle, String lowerText) {
        return containsAny(lowerTitle, lowerText,
                "musician", "singer", "songwriter", "rapper", "band", "vocalist",
                "record producer", "recording artist", "musical artist", "musical group",
                "singer songwriter", "singer-songwriter", "musical duo", "musical collective",
                "musical act", "vocal group", "dj", "composer", "music artist",
                "music producer", "musical performer",
                "reggaeton", "pop artist", "rock band", "rock musician", "hip hop artist",
                "hip hop", "latin trap", "latin pop", "disc jockey");
    }

    private boolean containsPublicFigureTerm(String lowerTitle, String lowerText) {
        return containsAny(lowerTitle, lowerText,
                "youtuber", "youtube personality", "internet personality",
                "internet celebrity", "social media influencer", "social media personality",
                "influencer", "content creator", "media personality",
                "television personality", "public figure", "celebrity");
    }

    private boolean containsMusicCategory(String lowerCategories) {
        return containsAny(lowerCategories, lowerCategories,
                "singers", "musicians", "rappers", "songwriters", "bands", "djs",
                "record producers", "musical groups", "vocalists", "composers",
                "reggaeton", "hip hop", "rock music", "pop music", "latin music",
                "singer-songwriters", "recording artists", "musical duos", "musical trios");
    }

    private boolean isLikelyMediaObject(String lowerTitle, String lowerText) {
        if (containsAny(lowerTitle, lowerText,
                " is a song", " is an album", " is a single", " is an ep",
                " was a song", " was an album", " was a single", " was an ep")) return true;
        String title = lowerTitle == null ? "" : lowerTitle;
        if (title.matches(".*\\((song|album|ep|single)\\)\\s*$")) return true;
        return containsAny(title, title, "discography", "songs recorded by");
    }

    private boolean isDisambiguationOrList(String lowerTitle, String lowerText) {
        return lowerTitle.contains("disambiguation")
                || lowerText.contains("may refer")
                || lowerText.contains("may also refer")
                || lowerTitle.startsWith("list of ");
    }

    private boolean containsAny(String lowerTitle, String lowerDescription, String... terms) {
        if (terms == null) return false;
        String haystack = normalizeSearchText(
                (lowerTitle == null ? "" : lowerTitle) + " "
                        + (lowerDescription == null ? "" : lowerDescription)
        );
        if (haystack.isBlank()) return false;

        for (String term : terms) {
            if (term == null || term.isBlank()) continue;
            String normalizedTerm = normalizeSearchText(term);
            if (!normalizedTerm.isBlank()
                    && (" " + haystack + " ").contains(" " + normalizedTerm + " ")) {
                return true;
            }
        }
        return false;
    }

    private String normalizeSearchText(String value) {
        if (value == null || value.isBlank()) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
