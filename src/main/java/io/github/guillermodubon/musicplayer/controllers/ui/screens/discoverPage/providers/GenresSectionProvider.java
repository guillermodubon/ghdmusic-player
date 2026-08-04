package io.github.guillermodubon.musicplayer.controllers.ui.screens.discoverPage.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.GenreCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.factory.CardFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.discoverPage.context.DiscoverPageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.discoverPage.providers.base.BaseDiscoverPagePageSectionProvider;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class GenresSectionProvider extends BaseDiscoverPagePageSectionProvider {
    private static final String GENRE_CARD_IMAGE_BASE = "/io/github/guillermodubon/musicplayer/assets/images/genreCardImages/";
    private static final Set<Integer> EXCLUDED_GENRE_IDS = Set.of(186, 2, 81, 95);
    private static final List<Integer> LOCAL_GENRE_IDS = List.of(
            16, 75, 85, 98, 106, 113, 116, 129, 132, 144,
            152, 153, 165, 169, 173, 464, 466, 65, 122
    );
    private static final ConcurrentMap<String, Image> GENRE_COVER_CACHE = new ConcurrentHashMap<>();
    private static final Map<Integer, String> ENGLISH_GENRE_NAMES = Map.ofEntries(
            Map.entry(12, "Arabic Music"),
            Map.entry(16, "Asian Music"),
            Map.entry(75, "Brazilian Music"),
            Map.entry(85, "Alternative"),
            Map.entry(98, "Classical"),
            Map.entry(106, "Electro"),
            Map.entry(113, "Dance"),
            Map.entry(116, "Rap/Hip Hop"),
            Map.entry(129, "Jazz"),
            Map.entry(132, "Pop"),
            Map.entry(144, "Reggae"),
            Map.entry(152, "Rock"),
            Map.entry(153, "Blues"),
            Map.entry(165, "R&B"),
            Map.entry(169, "Soul & Funk"),
            Map.entry(173, "Films/Games"),
            Map.entry(464, "Metal"),
            Map.entry(466, "Folk"),
            Map.entry(65,"Mexican Music"),
            Map.entry(122, "Reggaeton")
    );
    private static final Map<Integer, String> GENRE_IMAGE_BY_ID = Map.ofEntries(
            Map.entry(16, "AsianMusic.png"),
            Map.entry(75, "BrazilianMusic.png"),
            Map.entry(85, "Alternative.png"),
            Map.entry(98, "clasical.png"),
            Map.entry(106, "Electro.png"),
            Map.entry(113, "dance.png"),
            Map.entry(116, "rapHipHop.png"),
            Map.entry(129, "Jazz.png"),
            Map.entry(132, "Pop.png"),
            Map.entry(144, "Reggae.png"),
            Map.entry(152, "Rock.png"),
            Map.entry(153, "Blues.png"),
            Map.entry(165, "R&B.png"),
            Map.entry(169, "soul & funk.png"),
            Map.entry(173, "FilmsGames.png"),
            Map.entry(464, "Metal.png"),
            Map.entry(466, "Folk.png"),
            Map.entry(65,"Mexican Music.png"),
            Map.entry(122, "Reggaeton.png")
    );
    private static final Map<String, String> GENRE_IMAGE_BY_NAME = Map.ofEntries(
            Map.entry(normalizeKey("Alternative"), "Alternative.png"),
            Map.entry(normalizeKey("Asian Music"), "AsianMusic.png"),
            Map.entry(normalizeKey("Blues"), "Blues.png"),
            Map.entry(normalizeKey("Brazilian Music"), "BrazilianMusic.png"),
            Map.entry(normalizeKey("Classical"), "clasical.png"),
            Map.entry(normalizeKey("Cumbia"), "Cumbia.png"),
            Map.entry(normalizeKey("Dance"), "dance.png"),
            Map.entry(normalizeKey("Electro"), "Electro.png"),
            Map.entry(normalizeKey("Films/Games"), "FilmsGames.png"),
            Map.entry(normalizeKey("Folk"), "Folk.png"),
            Map.entry(normalizeKey("Jazz"), "Jazz.png"),
            Map.entry(normalizeKey("Pop"), "Pop.png"),
            Map.entry(normalizeKey("Latino"), "latino.png"),
            Map.entry(normalizeKey("Latin Music"), "latino.png"),
            Map.entry(normalizeKey("Metal"), "Metal.png"),
            Map.entry(normalizeKey("R&B"), "R&B.png"),
            Map.entry(normalizeKey("Rap/Hip Hop"), "rapHipHop.png"),
            Map.entry(normalizeKey("Hip Hop"), "rapHipHop.png"),
            Map.entry(normalizeKey("Reggae"), "Reggae.png"),
            Map.entry(normalizeKey("Reggaeton"), "Reggaeton.png"),
            Map.entry(normalizeKey("Rock"), "Rock.png"),
            Map.entry(normalizeKey("Salsa"), "Salsa.png"),
            Map.entry(normalizeKey("Soul & Funk"), "soul & funk.png"),
            Map.entry(normalizeKey("Mexican Music"),"Mexican Music.png"),
            Map.entry(normalizeKey("Reggaetón"), "Reggaeton.png")
    );

    public GenresSectionProvider(DiscoverPageContext context) {
        super(context);
    }

    private record GenreCandidate(int id, String name) {}

    @Override
    public void render(VBox container) {
        VBox section = sectionBlock("Loading genres...");
        container.getChildren().add(section);
        int renderGeneration = captureRenderGeneration(container);

        supplyAsync(this::loadGenreCandidates)
                .whenComplete((candidates, th) -> Platform.runLater(() -> {
                    if (!isRenderCurrent(container, renderGeneration)) return;
                    if (candidates == null || candidates.isEmpty()) {
                        section.getChildren().setAll(
                                sectionTitle("Discover new music by genre"),
                                emptyState("No genres available")
                        );
                        return;
                    }

                    FlowPane fp = createGenreGrid(container);
                    section.getChildren().setAll(sectionTitle("Discover new music by genre"), fp);
                    appendNodesInBatches(container, renderGeneration, fp, candidates, this::createGenreCard, rendered -> {
                        if (rendered == 0) {
                            section.getChildren().setAll(
                                    sectionTitle("Discover new music by genre"),
                                    emptyState("No genres available")
                            );
                        }
                    });
                }));
    }

    private List<GenreCandidate> loadGenreCandidates() {
        if (shouldAbort()) return List.of();

        LinkedHashMap<Integer, GenreCandidate> candidates = new LinkedHashMap<>();
        for (GenreCandidate fallback : fallbackGenreCandidates()) {
            candidates.putIfAbsent(fallback.id(), fallback);
        }

        try {
            JsonObject root = getJson(context.endpoints().genreRoot());
            if (root == null) return new ArrayList<>(candidates.values());

            if (!root.has("data") || !root.get("data").isJsonArray()) {
                return new ArrayList<>(candidates.values());
            }

            JsonArray arr = root.getAsJsonArray("data");

            for (JsonElement el : arr) {
                if (shouldAbort()) return List.of();
                if (!el.isJsonObject()) continue;

                JsonObject g = el.getAsJsonObject();

                int gid = g.has("id") && !g.get("id").isJsonNull()
                        ? g.get("id").getAsInt()
                        : -1;

                if (gid <= 0) continue;
                if (EXCLUDED_GENRE_IDS.contains(gid)) continue;

                String apiName = g.has("name") && !g.get("name").isJsonNull()
                        ? g.get("name").getAsString()
                        : "Unknown";
                String name = englishGenreName(gid, apiName);
                candidates.putIfAbsent(gid, new GenreCandidate(gid, name));
            }
        } catch (Exception ignored) {
        }

        return new ArrayList<>(candidates.values());
    }

    /**
     * The Discover genre art ships with the application, so it is a reliable and instant
     * fallback when Deezer's genre catalogue is unavailable or temporarily rate limited.
     */
    private List<GenreCandidate> fallbackGenreCandidates() {
        if (shouldAbort()) return List.of();

        List<GenreCandidate> fallback = new ArrayList<>(LOCAL_GENRE_IDS.size());
        for (int genreId : LOCAL_GENRE_IDS) {
            String name = englishGenreName(genreId, "Genre");
            fallback.add(new GenreCandidate(genreId, name));
        }
        return fallback;
    }

    private StackPane createGenreCard(GenreCandidate candidate) {
        if (candidate == null) return null;
        GenreCardData data = new GenreCardData(
                candidate.id(),
                candidate.name(),
                null,
                loadLocalGenreCover(candidate.id(), candidate.name()),
                context.genreActions().genreClick(candidate.name(), null)
        );
        try {
            return (StackPane) CardFactory.createGenreCard(data);
        } catch (IOException ignored) {
            return null;
        }
    }

    private String englishGenreName(int genreId, String apiName) {
        String mapped = ENGLISH_GENRE_NAMES.get(genreId);
        if (mapped != null && !mapped.isBlank()) {
            return mapped;
        }

        if (apiName == null || apiName.isBlank()) {
            return "Unknown";
        }

        return apiName.trim();
    }

    private Image loadLocalGenreCover(int genreId, String name) {
        List<String> candidates = new ArrayList<>();
        addCandidate(candidates, GENRE_IMAGE_BY_ID.get(genreId));
        addCandidate(candidates, GENRE_IMAGE_BY_NAME.get(normalizeKey(name)));
        addCandidate(candidates, name == null ? null : name.trim() + ".png");
        addCandidate(candidates, compactName(name) + ".png");

        for (String path : candidates) {
            Image cached = GENRE_COVER_CACHE.get(path);
            if (cached != null) return cached;

            URL resource = getClass().getResource(path);
            if (resource == null) continue;
            try {
                Image loaded = new Image(resource.toExternalForm(), 240, 240, false, true, true);
                Image previous = GENRE_COVER_CACHE.putIfAbsent(path, loaded);
                return previous == null ? loaded : previous;
            } catch (Exception ignored) { }
        }

        return null;
    }

    private void addCandidate(List<String> candidates, String fileName) {
        if (candidates == null || fileName == null || fileName.isBlank()) return;
        String path = GENRE_CARD_IMAGE_BASE + fileName.trim();
        if (!candidates.contains(path)) {
            candidates.add(path);
        }
    }

    private static String normalizeKey(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private String compactName(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("[^A-Za-z0-9]+", "");
    }
}
