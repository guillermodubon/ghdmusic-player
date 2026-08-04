package io.github.guillermodubon.musicplayer.services.api;

import com.google.gson.JsonSyntaxException;
import io.github.guillermodubon.musicplayer.models.WikipediaApiMetadata;
import io.github.guillermodubon.musicplayer.utils.TextHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static io.github.guillermodubon.musicplayer.services.api.WikipediaTextNormalizer.cacheKey;
import static io.github.guillermodubon.musicplayer.services.api.WikipediaTextNormalizer.hasText;

/**
 * Public facade for artist biographies.
 *
 * The service coordinates caching and the ordered resolution flow. HTTP,
 * query construction, candidate scoring, and response parsing live in the
 * package-private collaborators next to this class.
 */
public class WikipediaApiService implements ApiService<WikipediaApiMetadata> {

    private static final int SUMMARY_SENTENCES = 4;
    private static final TextHelper TEXT_HELPER = new TextHelper();
    private static final Map<String, WikipediaApiMetadata> MEMORY_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<WikipediaApiMetadata>> IN_FLIGHT = new ConcurrentHashMap<>();

    private final WikipediaApiClient client = new WikipediaApiClient();
    private final WikipediaSearchSupport searchSupport = new WikipediaSearchSupport(client);

    @Override
    public List<WikipediaApiMetadata> getApiObjectsList(List<String> artistNames) {
        if (artistNames == null || artistNames.isEmpty()) return List.of();

        Map<String, String> dedupedNames = artistNames.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::strip)
                .collect(Collectors.toMap(
                        WikipediaTextNormalizer::cacheKey,
                        name -> name,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));

        List<CompletableFuture<WikipediaApiMetadata>> futures = dedupedNames.values().stream()
                .map(name -> CompletableFuture.supplyAsync(
                        () -> getFetchedApiMetadataObject(name),
                        client.executor()
                ))
                .toList();

        List<WikipediaApiMetadata> out = new ArrayList<>();
        for (CompletableFuture<WikipediaApiMetadata> future : futures) {
            try {
                WikipediaApiMetadata metadata = future.get(18, java.util.concurrent.TimeUnit.SECONDS);
                if (metadata != null
                        && hasText(metadata.getArtistName())
                        && hasText(metadata.getArtistBiography())) {
                    out.add(metadata);
                }
            } catch (Exception ignored) {
                future.cancel(true);
            }
        }
        return out.stream().filter(Objects::nonNull).collect(Collectors.toList());
    }

    public WikipediaApiMetadata getFetchedApiMetadataObject(String artistName) {
        String cleanArtistName = searchSupport.normalizeArtistName(artistName);
        if (!hasText(cleanArtistName)) return null;

        String key = cacheKey(cleanArtistName);
        WikipediaApiMetadata cached = MEMORY_CACHE.get(key);
        if (cached != null) return cached;

        CompletableFuture<WikipediaApiMetadata> request = new CompletableFuture<>();
        CompletableFuture<WikipediaApiMetadata> existing = IN_FLIGHT.putIfAbsent(key, request);
        if (existing != null) {
            try {
                return existing.join();
            } catch (Exception ignored) {
                return null;
            }
        }

        try {
            WikipediaApiMetadata fetched = fetchFromWikipedia(cleanArtistName);
            if (fetched != null && hasText(fetched.getArtistBiography())) {
                MEMORY_CACHE.put(key, fetched);
            }
            request.complete(fetched);
            return fetched;
        } catch (JsonSyntaxException e) {
            System.err.println("Error parsing Wikipedia response for '" + cleanArtistName + "': " + e.getMessage());
            request.complete(null);
            return null;
        } catch (Exception e) {
            request.complete(null);
            return null;
        } finally {
            IN_FLIGHT.remove(key, request);
        }
    }

    public String getBiographyForArtist(String artistName) {
        WikipediaApiMetadata metadata = getFetchedApiMetadataObject(artistName);
        return metadata == null ? null : metadata.getArtistBiography();
    }

    private WikipediaApiMetadata fetchFromWikipedia(String artistName) {
        try {
            // Keep the same resolution order: direct title, Wikidata,
            // generator search, then the broader Wikipedia candidate search.
            for (String title : WikipediaSearchQueries.buildDirectSummaryTitles(artistName)) {
                WikipediaPageCandidate directPage = client.fetchPageCandidate(title);
                if (!searchSupport.isQualifiedMusicPage(artistName, directPage, 125)) continue;

                WikipediaApiMetadata direct = metadataFromPage(artistName, directPage);
                if (direct != null) return direct;
            }

            WikipediaPageCandidate wikidataPage = searchSupport.resolvePageFromWikidata(artistName);
            if (wikidataPage != null) {
                WikipediaApiMetadata metadata = metadataFromPage(artistName, wikidataPage);
                if (metadata != null) return metadata;
            }

            WikipediaPageCandidate generatorPage = searchSupport.resolvePageFromGeneratorSearch(artistName);
            if (generatorPage != null) {
                WikipediaApiMetadata metadata = metadataFromPage(artistName, generatorPage);
                if (metadata != null) return metadata;
            }

            List<WikipediaSearchCandidate> candidates = searchSupport.findCandidateTitles(artistName);
            if (candidates.isEmpty()) return null;

            WikipediaPageCandidate bestPage = null;
            int bestScore = Integer.MIN_VALUE;
            for (WikipediaSearchCandidate candidate : candidates.stream()
                    .limit(WikipediaSearchSupport.MAX_VALIDATION_CANDIDATES).toList()) {
                WikipediaPageCandidate page = client.fetchPageCandidate(candidate.title());
                if (page == null || !hasText(page.extract())) continue;

                int score = searchSupport.scoreResolvedPage(candidate, page, artistName);
                if (score > bestScore) {
                    bestScore = score;
                    bestPage = page;
                }
            }

            if (bestPage == null || bestScore < WikipediaSearchSupport.MIN_ACCEPT_SCORE) return null;
            return metadataFromPage(artistName, bestPage);
        } catch (Exception e) {
            System.err.println("Wikipedia biography lookup failed for '" + artistName + "': " + e.getMessage());
            return null;
        }
    }

    private WikipediaApiMetadata metadataFromPage(String artistName, WikipediaPageCandidate page) {
        if (page == null || !hasText(page.title())) return null;
        if (!searchSupport.isQualifiedMusicPage(artistName, page, 0)) return null;

        // fetchPageCandidate already requests the English introductory
        // extract. Reusing it avoids a second REST call on the cold path;
        // the text helper still produces the same concise description.
        String source = page.extract();
        String description = TEXT_HELPER.summarizeAndCleanTextByPeriods(source, SUMMARY_SENTENCES);
        return hasText(description)
                ? new WikipediaApiMetadata(
                        artistName,
                        description,
                        searchSupport.isPublicFigureMusicPage(page)
                )
                : null;
    }
}
