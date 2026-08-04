package io.github.guillermodubon.musicplayer.services.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static io.github.guillermodubon.musicplayer.services.api.WikipediaTextNormalizer.encodePath;
import static io.github.guillermodubon.musicplayer.services.api.WikipediaTextNormalizer.encodeQuery;
import static io.github.guillermodubon.musicplayer.services.api.WikipediaTextNormalizer.hasText;
import static io.github.guillermodubon.musicplayer.services.api.WikipediaTextNormalizer.stringField;

/** Owns Wikipedia/Wikidata HTTP calls and response parsing. */
final class WikipediaApiClient {

    private static final int SEARCH_LIMIT = 8;
    private static final String ACTION_API_URL = "https://en.wikipedia.org/w/api.php";
    private static final String SEARCH_URL = ACTION_API_URL + "?action=query&list=search&srsearch=";
    private static final String OPENSEARCH_URL = ACTION_API_URL
            + "?action=opensearch&namespace=0&format=json&limit=" + SEARCH_LIMIT + "&search=";
    private static final String GENERATOR_SEARCH_URL = ACTION_API_URL
            + "?action=query&generator=search&gsrnamespace=0&gsrlimit=5&prop=extracts%7Ccategories%7Cpageprops"
            + "&exintro=1&explaintext=1&cllimit=50&ppprop=disambiguation%7Cwikibase_item&redirects=1"
            + "&format=json&origin=*&gsrsearch=";
    private static final String PAGE_DETAILS_URL = ACTION_API_URL
            + "?action=query&prop=extracts%7Ccategories%7Cpageprops&exintro&explaintext&cllimit=50"
            + "&ppprop=disambiguation%7Cwikibase_item&titles=";
    private static final String WIKIDATA_SEARCH_URL =
            "https://www.wikidata.org/w/api.php?action=wbsearchentities&language=en&format=json"
                    + "&type=item&limit=8&search=";
    private static final String WIKIDATA_ENTITY_URL = "https://www.wikidata.org/wiki/Special:EntityData/";

    private static final ExecutorService HTTP_EXECUTOR =
            Executors.newFixedThreadPool(6, runnable -> {
                Thread thread = new Thread(runnable, "wikipedia-api-service");
                thread.setDaemon(true);
                return thread;
            });

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .dispatcher(new Dispatcher(HTTP_EXECUTOR))
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build();

    private static final Map<String, JsonObject> WIKIDATA_ENTITY_CACHE = new ConcurrentHashMap<>();

    ExecutorService executor() {
        return HTTP_EXECUTOR;
    }

    JsonArray searchWikidata(String query) {
        if (!hasText(query)) return null;
        String url = WIKIDATA_SEARCH_URL + encodeQuery(query);
        Request request = request(url);
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            JsonObject json = JsonParser.parseString(response.body().string()).getAsJsonObject();
            return json.has("search") && json.get("search").isJsonArray()
                    ? json.getAsJsonArray("search")
                    : null;
        } catch (Exception ex) {
            System.err.println("Wikidata search failed for query '" + query + "': " + ex.getMessage());
            return null;
        }
    }

    String fetchEnglishWikipediaTitle(String wikidataItem) {
        JsonObject entity = fetchWikidataEntity(wikidataItem);
        if (entity == null) return "";

        JsonObject sitelinks = entity.has("sitelinks") && entity.get("sitelinks").isJsonObject()
                ? entity.getAsJsonObject("sitelinks")
                : null;
        JsonObject enwiki = sitelinks != null && sitelinks.has("enwiki") && sitelinks.get("enwiki").isJsonObject()
                ? sitelinks.getAsJsonObject("enwiki")
                : null;
        return stringField(enwiki, "title");
    }

    List<WikipediaPageCandidate> generatorSearchPages(String query) {
        if (!hasText(query)) return List.of();
        String url = GENERATOR_SEARCH_URL + encodeQuery(query);
        Request request = request(url);
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return List.of();
            JsonObject root = JsonParser.parseString(response.body().string()).getAsJsonObject();
            JsonObject queryObject = root.has("query") && root.get("query").isJsonObject()
                    ? root.getAsJsonObject("query")
                    : null;
            JsonObject pages = queryObject != null && queryObject.has("pages") && queryObject.get("pages").isJsonObject()
                    ? queryObject.getAsJsonObject("pages")
                    : null;
            if (pages == null || pages.entrySet().isEmpty()) return List.of();

            List<WikipediaPageCandidate> out = new ArrayList<>();
            for (Map.Entry<String, JsonElement> entry : pages.entrySet()) {
                if (entry == null || entry.getValue() == null || !entry.getValue().isJsonObject()) continue;
                WikipediaPageCandidate page = pageCandidateFromJson(entry.getValue().getAsJsonObject());
                if (page != null && hasText(page.extract())) out.add(page);
            }
            return out;
        } catch (Exception ex) {
            System.err.println("Wikipedia generator search failed for query '" + query + "': " + ex.getMessage());
            return List.of();
        }
    }

    JsonArray openSearchWikipedia(String query) {
        if (!hasText(query)) return null;
        String url = OPENSEARCH_URL + encodeQuery(query);
        Request request = request(url);
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            return JsonParser.parseString(response.body().string()).getAsJsonArray();
        } catch (Exception ex) {
            System.err.println("Wikipedia opensearch failed for query '" + query + "': " + ex.getMessage());
            return null;
        }
    }

    JsonArray searchWikipedia(String query) {
        if (!hasText(query)) return null;
        String searchUrl = SEARCH_URL
                + encodeQuery(query)
                + "&srlimit=" + SEARCH_LIMIT
                + "&srprop=snippet"
                + "&format=json";

        Request request = request(searchUrl);
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            JsonObject json = JsonParser.parseString(response.body().string()).getAsJsonObject();
            JsonObject queryObject = json.has("query") && json.get("query").isJsonObject()
                    ? json.getAsJsonObject("query")
                    : null;
            return queryObject == null ? null : queryObject.getAsJsonArray("search");
        } catch (Exception ex) {
            System.err.println("Wikipedia search failed for query '" + query + "': " + ex.getMessage());
            return null;
        }
    }

    WikipediaPageCandidate fetchPageCandidate(String title) {
        if (!hasText(title)) return null;

        String detailsUrl = PAGE_DETAILS_URL
                + encodeQuery(title)
                + "&redirects=1&format=json";

        Request request = request(detailsUrl);
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;

            JsonObject root = JsonParser.parseString(response.body().string()).getAsJsonObject();
            JsonObject query = root.has("query") && root.get("query").isJsonObject()
                    ? root.getAsJsonObject("query")
                    : null;
            JsonObject pages = query != null && query.has("pages") && query.get("pages").isJsonObject()
                    ? query.getAsJsonObject("pages")
                    : null;
            if (pages == null || pages.entrySet().isEmpty()) return null;

            JsonObject page = pages.entrySet().iterator().next().getValue().getAsJsonObject();
            return pageCandidateFromJson(page);
        } catch (Exception ex) {
            System.err.println("Wikipedia page fetch failed for title '" + title + "': " + ex.getMessage());
            return null;
        }
    }

    JsonObject fetchWikidataEntity(String wikidataItem) {
        if (!hasText(wikidataItem)) return null;
        JsonObject cached = WIKIDATA_ENTITY_CACHE.get(wikidataItem);
        if (cached != null) return cached;

        String url = WIKIDATA_ENTITY_URL + encodePath(wikidataItem) + ".json";
        Request request = request(url);
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            JsonObject root = JsonParser.parseString(response.body().string()).getAsJsonObject();
            JsonObject entities = root.has("entities") && root.get("entities").isJsonObject()
                    ? root.getAsJsonObject("entities")
                    : null;
            JsonObject entity = entities != null && entities.has(wikidataItem)
                    && entities.get(wikidataItem).isJsonObject()
                    ? entities.getAsJsonObject(wikidataItem)
                    : null;
            if (entity != null) WIKIDATA_ENTITY_CACHE.put(wikidataItem, entity);
            return entity;
        } catch (Exception ex) {
            System.err.println("Wikidata validation failed for item '" + wikidataItem + "': " + ex.getMessage());
            return null;
        }
    }

    private WikipediaPageCandidate pageCandidateFromJson(JsonObject page) {
        if (page == null || page.has("missing")) return null;

        String resolvedTitle = stringField(page, "title");
        String extract = stringField(page, "extract");
        if (!hasText(resolvedTitle) || !hasText(extract)) return null;

        boolean disambiguation = false;
        String wikidataItem = "";
        if (page.has("pageprops") && page.get("pageprops").isJsonObject()) {
            JsonObject props = page.getAsJsonObject("pageprops");
            disambiguation = props.has("disambiguation");
            wikidataItem = stringField(props, "wikibase_item");
        }

        List<String> categories = new ArrayList<>();
        if (page.has("categories") && page.get("categories").isJsonArray()) {
            for (JsonElement element : page.getAsJsonArray("categories")) {
                if (element == null || !element.isJsonObject()) continue;
                String category = stringField(element.getAsJsonObject(), "title");
                if (hasText(category)) categories.add(category);
            }
        }

        return new WikipediaPageCandidate(resolvedTitle, extract, categories, disambiguation, wikidataItem);
    }

    private Request request(String url) {
        return new Request.Builder()
                .url(url)
                .header("User-Agent", "GHDMusic/1.0 artist-biography")
                .header("Api-User-Agent", "GHDMusic/1.0 artist-biography")
                .header("Accept", "application/json")
                .build();
    }

}
