package io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.providers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.MusicCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.factory.CardFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.context.HomePageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.providers.base.BaseHomePageSectionProvider;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Song;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;


public class RecommendationsSectionProvider extends BaseHomePageSectionProvider {

    private static final int MAX_RECOMMENDATION_CARDS = MAX_CARDS_PER_SECTION + 2;

    public RecommendationsSectionProvider(HomePageContext context) {
        super(context);
    }

    @Override
    public CompletableFuture<Void> render(VBox container, String filter, long renderId) {
        VBox section = sectionBlock(container, "Recommendations for you");
        section.getStyleClass().add("recommendations-section");
        setSectionContent(section, emptyState("Loading recommendations..."));

        if (context.svc() == null || context.memory() == null) {
            removeSection(section);
            return CompletableFuture.completedFuture(null);
        }

        List<Artist> artists = new ArrayList<>(context.memory().artists());
        if (artists.isEmpty()) {
            removeSection(section);
            return CompletableFuture.completedFuture(null);
        }

        List<Long> artistIds = artists.stream()
                .filter(Objects::nonNull)
                .map(Artist::getArtistID)
                .filter(id -> id > 0)
                .distinct()
                .collect(Collectors.toCollection(ArrayList::new));

        Collections.shuffle(artistIds);
        if (artistIds.size() > MAX_ARTISTS_TO_QUERY) {
            artistIds = artistIds.subList(0, MAX_ARTISTS_TO_QUERY);
        }

        if (artistIds.isEmpty()) {
            removeSection(section);
            return CompletableFuture.completedFuture(null);
        }

        // Capture the library once per render. Every worker reuses this
        // immutable set, avoiding one DB/cache lookup for each recommendation.
        Set<Long> knownLibrarySongIds = snapshotKnownLibrarySongIds();

        List<CompletableFuture<List<Parent>>> futures = new ArrayList<>();
        for (Long aid : artistIds) {
            futures.add(supplyAsync(() -> fetchArtistTopCards(
                            aid,
                            MAX_TOP_PER_ARTIST,
                            filter,
                            renderId,
                            knownLibrarySongIds
                    ))
                    .exceptionally(e -> List.of()));
        }

        CompletableFuture<Void> completion = new CompletableFuture<>();

        CompletableFuture
                .allOf(futures.toArray(new CompletableFuture[0]))
                .handle((v, th) -> {
                    List<Parent> cards = new ArrayList<>();
                    for (CompletableFuture<List<Parent>> f : futures) {
                        try {
                            List<Parent> part = f.getNow(List.of());
                            if (part != null) cards.addAll(part);
                        } catch (Throwable ignored) {
                        }
                        if (cards.size() >= MAX_RECOMMENDATION_CARDS) break;
                    }

                    Set<Long> seenRecommendationIds = new LinkedHashSet<>();
                    List<Parent> finalCards = cards.stream()
                            .filter(card -> isNewRecommendation(card, seenRecommendationIds))
                            .limit(MAX_RECOMMENDATION_CARDS)
                            .collect(Collectors.toList());

                    Platform.runLater(() -> {
                        try {
                            if (isRenderActive(renderId)) {
                                if (finalCards.isEmpty()) {
                                    removeSection(section);
                                } else {
                                    setSectionContent(section, createFeaturedCarousel(finalCards));
                                }
                            }
                        } finally {
                            completion.complete(null);
                        }
                    });
                    return null;
                });

        return completion;
    }

    private List<Parent> fetchArtistTopCards(long artistId,
                                              int limit,
                                              String filter,
                                              long renderId,
                                              Set<Long> knownLibrarySongIds) {
        List<Parent> out = new ArrayList<>();
        if (artistId <= 0 || context.endpoints() == null) return out;

        try {
            // Ask for a few more candidates because library tracks are removed
            // before cards are created.
            JsonObject root = getJson(context.endpoints().artistTopTracks(artistId, Math.max(12, limit * 4)));
            if (root == null || !root.has("data") || !root.get("data").isJsonArray()) return out;

            int taken = 0;
            String f = norm(filter);

            for (JsonElement el : root.getAsJsonArray("data")) {
                if (!isRenderActive(renderId)) return List.of();
                if (!el.isJsonObject()) continue;

                JsonObject t = el.getAsJsonObject();
                long id = DeezerApiService.safeGetLong(t, "id", -1L);
                if (id <= 0) continue;
                if (knownLibrarySongIds.contains(id)) continue;

                String title = DeezerApiService.extractTitle(t);
                List<String> artistNames = resolveTrackArtistNames(id, t);
                if (!matchesFilter(title, artistNames, f)) continue;

                String coverUrl = null;
                try {
                    if (t.has("album") && t.get("album").isJsonObject()) {
                        coverUrl = DeezerApiService.extractHighResolutionCoverUrl(t.getAsJsonObject("album"));
                    }
                } catch (Exception ignored) {}

                Image cover = null;
                if (coverUrl != null && !coverUrl.isBlank()) {
                    try {
                        cover = MediaImageResolver.remoteImage(coverUrl, 500, 500);
                    } catch (Exception ignored) {
                        cover = null;
                    }
                }

                try {
                    MusicCardData data = new MusicCardData(
                            String.valueOf(id),
                            cover,
                            title == null ? "Unknown" : title,
                            artistNames == null || artistNames.isEmpty() ? List.of("Unknown") : artistNames,
                            context.musicActions().songClick(null),
                            context.musicActions().artistNameClick(null)
                    );

                    Parent card = CardFactory.createBigFeaturedMusicCard(data);
                    card.getProperties().put("recommendationTrackId", id);
                    styleBigFeaturedCard(card);
                    out.add(card);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }

                if (++taken >= limit || out.size() >= MAX_RECOMMENDATION_CARDS) break;
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        return out;
    }

    private Set<Long> snapshotKnownLibrarySongIds() {
        Set<Long> ids = new HashSet<>();

        try {
            if (context.svc() != null && context.svc().getSongs() != null) {
                context.svc().getSongs().stream()
                        .filter(Objects::nonNull)
                        .map(Song::getSongID)
                        .filter(id -> id > 0)
                        .forEach(ids::add);
            }
        } catch (Exception ignored) {
        }

        try {
            if (context.memory() != null) {
                // MainMenuMemoryRepository verifies these songs against the
                // manifest, covering audio that is available locally now.
                context.memory().songs().stream()
                        .filter(Objects::nonNull)
                        .map(Song::getSongID)
                        .filter(id -> id > 0)
                        .forEach(ids::add);
            }
        } catch (Exception ignored) {
        }

        return Set.copyOf(ids);
    }

    private boolean isNewRecommendation(Parent card, Set<Long> seenRecommendationIds) {
        if (card == null) return false;
        Object value = card.getProperties().get("recommendationTrackId");
        if (!(value instanceof Number number)) return true;
        long trackId = number.longValue();
        return trackId <= 0 || seenRecommendationIds.add(trackId);
    }

    private void styleBigFeaturedCard(Node card) {
        styleBigFeaturedCard(card, 320);
    }

    private void styleBigFeaturedCard(Node card, double width) {
        if (card instanceof Region region) {
            double safeWidth = clamp(width, 220, 360);
            double safeHeight = clamp(safeWidth * 1.25, 320, 480);
            region.setPrefWidth(safeWidth);
            region.setMinWidth(0);
            region.setMaxWidth(safeWidth);
            region.setPrefHeight(safeHeight);
            region.setMinHeight(0);
            region.setMaxHeight(safeHeight);
            HBox.setHgrow(region, Priority.NEVER);
        }
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
