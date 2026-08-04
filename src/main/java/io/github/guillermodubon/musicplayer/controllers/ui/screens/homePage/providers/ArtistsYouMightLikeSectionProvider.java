package io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.providers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.layout.VBox;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.ArtistCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.factory.CardFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.context.HomePageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.providers.base.BaseHomePageSectionProvider;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Genre;
import io.github.guillermodubon.musicplayer.services.api.DeezerArtistMetadataResolver;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Displays remote artist recommendations after every other Home section. */
public class ArtistsYouMightLikeSectionProvider extends BaseHomePageSectionProvider {

    private static final int RECOMMENDATION_MAX = MAX_CARDS_PER_SECTION;
    private static final int ARTIST_SEED_LIMIT = 6;
    private static final int RELATED_PER_SEED_LIMIT = 4;
    private static final int GENRE_ARTISTS_PER_LOOKUP = 5;
    private static final AtomicInteger LOOKUP_THREAD_ID = new AtomicInteger();
    private static final ExecutorService LOOKUP_POOL = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "home-artist-recommendations-" + LOOKUP_THREAD_ID.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    public ArtistsYouMightLikeSectionProvider(HomePageContext context) {
        super(context);
    }

    @Override
    public CompletableFuture<Void> render(VBox container, String filter, long renderId) {
        VBox section = sectionBlock(container, "Artists you might like");
        setSectionContent(section, emptyState("Finding artists you might like..."));

        CompletableFuture<Void> completion = new CompletableFuture<>();
        supplyAsync(() -> loadArtistCandidates(renderId))
                .whenComplete((candidates, error) -> Platform.runLater(() -> {
                    try {
                        if (!isRenderActive(renderId)) return;
                        List<Parent> cards = new ArrayList<>();
                        String normalizedFilter = norm(filter);
                        for (ArtistCandidate candidate : candidates == null ? List.<ArtistCandidate>of() : candidates) {
                            if (candidate == null || !matchesFilter(candidate.name(), List.of(), normalizedFilter)) continue;
                            Parent card = createArtistCard(candidate);
                            if (card != null) cards.add(card);
                            if (cards.size() >= RECOMMENDATION_MAX) break;
                        }

                        if (cards.isEmpty()) {
                            removeSection(section);
                        } else {
                            setSectionContent(section, createMusicCarousel(cards));
                        }
                    } finally {
                        completion.complete(null);
                    }
                }));
        return completion;
    }

    private List<ArtistCandidate> loadArtistCandidates(long renderId) {
        if (!isRenderActive(renderId) || context.endpoints() == null) return List.of();
        if (!hasLibraryArtists()) return List.of();

        Set<Long> existingArtistIds = existingArtistIds();
        LinkedHashMap<Long, ArtistCandidate> candidates = new LinkedHashMap<>();
        mergeCandidates(candidates, loadRelatedCandidates(seedArtistIds(), existingArtistIds, renderId), existingArtistIds);

        if (candidates.size() < RECOMMENDATION_MAX && isRenderActive(renderId)) {
            Set<Long> blocked = new HashSet<>(existingArtistIds);
            blocked.addAll(candidates.keySet());
            mergeCandidates(candidates, loadGenreFallbackCandidates(blocked, renderId), existingArtistIds);
        }

        /*
         * A fresh or small library may not have local genres or enough artist
         * seeds for the related-artist endpoints. In that case the section was
         * removed even though Deezer still had valid artist recommendations.
         * The general chart is a single, bounded fallback request and only runs
         * when the existing sources did not provide enough candidates.
         */
        if (candidates.size() < RECOMMENDATION_MAX
                && hasLibraryArtists()
                && isRenderActive(renderId)) {
            Set<Long> blocked = new HashSet<>(existingArtistIds);
            blocked.addAll(candidates.keySet());
            mergeCandidates(candidates, loadChartFallbackCandidates(blocked, renderId), existingArtistIds);
        }

        return candidates.values().stream().limit(RECOMMENDATION_MAX).toList();
    }

    private boolean hasLibraryArtists() {
        try {
            return context.memory() != null
                    && context.memory().artists() != null
                    && !context.memory().artists().isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private Set<Long> existingArtistIds() {
        Set<Long> ids = new HashSet<>();
        try {
            for (Artist artist : context.memory().artists()) {
                if (artist != null && artist.getArtistID() > 0) ids.add(artist.getArtistID());
            }
        } catch (Exception ignored) {
        }
        return ids;
    }

    private List<Long> seedArtistIds() {
        List<Long> ids = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        try {
            for (Artist artist : context.memory().artists()) {
                if (artist == null || artist.getArtistID() <= 0 || !seen.add(artist.getArtistID())) continue;
                ids.add(artist.getArtistID());
                if (ids.size() >= ARTIST_SEED_LIMIT) break;
            }
        } catch (Exception ignored) {
        }
        return ids;
    }

    private List<ArtistCandidate> loadRelatedCandidates(List<Long> seedIds,
                                                          Set<Long> existingArtistIds,
                                                          long renderId) {
        if (seedIds == null || seedIds.isEmpty()) return List.of();
        List<Callable<List<ArtistCandidate>>> tasks = seedIds.stream()
                .<Callable<List<ArtistCandidate>>>map(seedId -> () -> loadRelatedArtists(seedId, existingArtistIds, renderId))
                .toList();
        return flatten(loadConcurrently(tasks));
    }

    private List<ArtistCandidate> loadRelatedArtists(long seedId,
                                                       Set<Long> existingArtistIds,
                                                       long renderId) {
        if (seedId <= 0 || !isRenderActive(renderId)) return List.of();
        JsonObject root = getJson(context.endpoints().artistRelated(seedId, RELATED_PER_SEED_LIMIT));
        if (root == null || !root.has("data") || !root.get("data").isJsonArray()) return List.of();

        List<ArtistCandidate> candidates = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("data")) {
            if (!isRenderActive(renderId)) return List.of();
            ArtistCandidate candidate = candidateFromJson(element);
            if (candidate == null || candidate.id() == seedId || existingArtistIds.contains(candidate.id())) continue;
            candidates.add(candidate);
            if (candidates.size() >= RELATED_PER_SEED_LIMIT) break;
        }
        return candidates;
    }

    private List<ArtistCandidate> loadGenreFallbackCandidates(Set<Long> blockedIds, long renderId) {
        List<Callable<List<ArtistCandidate>>> tasks = new ArrayList<>();
        try {
            for (Genre genre : context.memory().genres()) {
                if (genre == null || genre.getGenreID() <= 0) continue;
                int genreId = genre.getGenreID();
                tasks.add(() -> loadArtistsForGenre(genreId, blockedIds, renderId));
                if (tasks.size() >= MAX_GENRES_TO_QUERY) break;
            }
        } catch (Exception ignored) {
        }
        return flatten(loadConcurrently(tasks));
    }

    private List<ArtistCandidate> loadArtistsForGenre(int genreId,
                                                        Set<Long> blockedIds,
                                                        long renderId) {
        if (genreId <= 0 || !isRenderActive(renderId)) return List.of();
        JsonObject root = getJson(context.endpoints().genreArtists(genreId));
        if (root == null || !root.has("data") || !root.get("data").isJsonArray()) return List.of();

        List<ArtistCandidate> candidates = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("data")) {
            if (!isRenderActive(renderId)) return List.of();
            ArtistCandidate candidate = candidateFromJson(element);
            if (candidate == null || blockedIds.contains(candidate.id())) continue;
            candidates.add(candidate);
            if (candidates.size() >= GENRE_ARTISTS_PER_LOOKUP) break;
        }
        return candidates;
    }

    private List<ArtistCandidate> loadChartFallbackCandidates(
            Set<Long> blockedIds,
            long renderId
    ) {
        if (!isRenderActive(renderId) || context.endpoints() == null) return List.of();

        // genreArtists(0) resolves to Deezer's global artist chart endpoint.
        JsonObject root = getJson(context.endpoints().genreArtists(0));
        if (root == null || !root.has("data") || !root.get("data").isJsonArray()) {
            return List.of();
        }

        List<ArtistCandidate> candidates = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("data")) {
            if (!isRenderActive(renderId)) return List.of();
            ArtistCandidate candidate = candidateFromJson(element);
            if (candidate == null || blockedIds.contains(candidate.id())) continue;
            candidates.add(candidate);
            if (candidates.size() >= RECOMMENDATION_MAX) break;
        }
        return candidates;
    }

    private List<ArtistCandidate> flatten(List<List<ArtistCandidate>> groups) {
        List<ArtistCandidate> all = new ArrayList<>();
        if (groups == null) return all;
        for (List<ArtistCandidate> group : groups) {
            if (group != null) all.addAll(group);
        }
        return all;
    }

    private List<List<ArtistCandidate>> loadConcurrently(Collection<? extends Callable<List<ArtistCandidate>>> tasks) {
        if (tasks == null || tasks.isEmpty()) return List.of();
        List<CompletableFuture<List<ArtistCandidate>>> futures = new ArrayList<>();
        for (Callable<List<ArtistCandidate>> task : tasks) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return task.call();
                } catch (Exception ignored) {
                    return List.of();
                }
            }, LOOKUP_POOL));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return futures.stream().map(future -> future.getNow(List.of())).toList();
    }

    private void mergeCandidates(LinkedHashMap<Long, ArtistCandidate> target,
                                 Collection<ArtistCandidate> incoming,
                                 Set<Long> existingArtistIds) {
        if (incoming == null) return;
        for (ArtistCandidate candidate : incoming) {
            if (candidate == null || candidate.id() <= 0 || existingArtistIds.contains(candidate.id())) continue;
            target.putIfAbsent(candidate.id(), candidate);
            if (target.size() >= RECOMMENDATION_MAX) return;
        }
    }

    private ArtistCandidate candidateFromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject artist = element.getAsJsonObject();
        long id = DeezerApiService.safeGetLong(artist, "id", -1L);
        String name = DeezerApiService.extractTitle(artist);
        if (id <= 0 || name == null || name.isBlank()) return null;
        return new ArtistCandidate(id, name, pictureUrl(artist));
    }

    private String pictureUrl(JsonObject artist) {
        return DeezerArtistMetadataResolver.pictureUrl(artist);
    }

    private Parent createArtistCard(ArtistCandidate candidate) {
        try {
            Artist artist = new Artist(candidate.id(), candidate.name(), null, List.of());
            artist.setPortraitUrl(candidate.pictureUrl());
            Parent card = CardFactory.createArtistCard(
                    new ArtistCardData(artist, context.artistActions().artistClick(null))
            );
            card.getProperties().put("artistId", candidate.id());
            styleMusicCard(card);
            return card;
        } catch (IOException ignored) {
            return null;
        }
    }

    private record ArtistCandidate(long id, String name, String pictureUrl) {
    }
}
