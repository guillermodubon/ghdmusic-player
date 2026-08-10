package io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.providers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.ArtistCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.factory.CardFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.context.HomePageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.providers.base.BaseHomePageSectionProvider;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Genre;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.repository.dao.artist.ArtistDao;
import io.github.guillermodubon.musicplayer.repository.dao.artist.ArtistDaoImpl;
import io.github.guillermodubon.musicplayer.services.api.DeezerArtistMetadataResolver;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Displays remote artist recommendations after every other Home section. */
public class ArtistsYouMightLikeSectionProvider extends BaseHomePageSectionProvider {

    private static final int RECOMMENDATION_MAX = MAX_CARDS_PER_SECTION;
    private static final int ARTIST_SEED_LIMIT = 8;
    private static final int RELATED_PER_SEED_LIMIT = 12;
    private static final int GENRE_ARTISTS_PER_LOOKUP = 12;
    private static final Duration EMPTY_RESULT_RETRY_DELAY = Duration.millis(420);
    private static final AtomicInteger RENDER_THREAD_ID = new AtomicInteger();
    private static final ExecutorService RENDER_POOL = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "home-artist-section-" + RENDER_THREAD_ID.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });
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
        loadAndRender(section, filter, renderId, 0, completion);
        return completion;
    }

    /**
     * The startup cache can still be catching up with SQLite during the first
     * Home render. Keep the slot pending for one short retry rather than
     * permanently removing a valid personalized section because of that
     * transient state or a one-off Deezer response failure.
     */
    private void loadAndRender(VBox section,
                               String filter,
                               long renderId,
                               int attempt,
                               CompletableFuture<Void> completion) {
        supplyAsync(() -> loadArtistCandidates(renderId), RENDER_POOL)
                .exceptionally(ignored -> List.of())
                .whenComplete((candidates, error) -> Platform.runLater(() -> {
                    if (!isRenderActive(renderId)) {
                        completion.complete(null);
                        return;
                    }

                    List<Parent> cards = createArtistCards(candidates, filter);
                    if (!cards.isEmpty()) {
                        setSectionContent(section, createMusicCarousel(cards));
                        completion.complete(null);
                        return;
                    }

                    if (attempt == 0 && hasPotentialArtistSeeds()) {
                        PauseTransition retry = new PauseTransition(EMPTY_RESULT_RETRY_DELAY);
                        retry.setOnFinished(event -> loadAndRender(
                                section,
                                filter,
                                renderId,
                                attempt + 1,
                                completion
                        ));
                        retry.playFromStart();
                        return;
                    }

                    removeSection(section);
                    completion.complete(null);
                }));
    }

    private List<Parent> createArtistCards(List<ArtistCandidate> candidates, String filter) {
        List<Parent> cards = new ArrayList<>();
        String normalizedFilter = norm(filter);
        for (ArtistCandidate candidate : candidates == null ? List.<ArtistCandidate>of() : candidates) {
            if (candidate == null || !matchesFilter(candidate.name(), List.of(), normalizedFilter)) continue;
            Parent card = createArtistCard(candidate);
            if (card != null) cards.add(card);
            if (cards.size() >= RECOMMENDATION_MAX) break;
        }
        return cards;
    }

    private List<ArtistCandidate> loadArtistCandidates(long renderId) {
        if (!isRenderActive(renderId) || context.endpoints() == null) return List.of();
        LibraryArtistState libraryArtists = libraryArtistState();
        if (libraryArtists.isEmpty()) return List.of();

        Set<Long> existingArtistIds = libraryArtists.ids();
        Set<String> existingArtistNames = libraryArtists.names();
        LinkedHashMap<Long, ArtistCandidate> candidates = new LinkedHashMap<>();
        mergeCandidates(
                candidates,
                loadRelatedCandidates(libraryArtists.seedIds(), existingArtistIds, existingArtistNames, renderId),
                existingArtistIds,
                existingArtistNames
        );

        if (candidates.size() < RECOMMENDATION_MAX && isRenderActive(renderId)) {
            Set<Long> blocked = new HashSet<>(existingArtistIds);
            blocked.addAll(candidates.keySet());
            Set<String> blockedNames = new HashSet<>(existingArtistNames);
            blockedNames.addAll(candidateNames(candidates));
            mergeCandidates(
                    candidates,
                    loadGenreFallbackCandidates(blocked, blockedNames, renderId),
                    existingArtistIds,
                    existingArtistNames
            );
        }

        /*
         * A fresh or small library may not have local genres or enough artist
         * seeds for the related-artist endpoints. In that case the section was
         * removed even though Deezer still had valid artist recommendations.
         * The general chart is a single, bounded fallback request and only runs
         * when the existing sources did not provide enough candidates.
         */
        if (candidates.size() < RECOMMENDATION_MAX
                && !libraryArtists.isEmpty()
                && isRenderActive(renderId)) {
            Set<Long> blocked = new HashSet<>(existingArtistIds);
            blocked.addAll(candidates.keySet());
            Set<String> blockedNames = new HashSet<>(existingArtistNames);
            blockedNames.addAll(candidateNames(candidates));
            mergeCandidates(
                    candidates,
                    loadChartFallbackCandidates(blocked, blockedNames, renderId),
                    existingArtistIds,
                    existingArtistNames
            );
        }

        return candidates.values().stream().limit(RECOMMENDATION_MAX).toList();
    }

    private boolean hasPotentialArtistSeeds() {
        return !libraryArtistState().isEmpty() && context.endpoints() != null;
    }

    /**
     * Memory is the normal fast path. If its artist cache is briefly empty,
     * read the persisted artist rows once; if that is also still unavailable,
     * use the already loaded songs as a final bounded in-memory fallback.
     */
    private LibraryArtistState libraryArtistState() {
        LinkedHashMap<String, Artist> artistsByName = new LinkedHashMap<>();
        try {
            if (context.memory() != null && context.memory().artists() != null) {
                for (Artist artist : context.memory().artists()) {
                    addLibraryArtist(artistsByName, artist);
                }
            }
        } catch (Exception ignored) {
        }

        if (artistsByName.isEmpty()) {
            try {
                ArtistDao dao = new ArtistDaoImpl(null);
                for (Artist artist : dao.findAll()) {
                    addLibraryArtist(artistsByName, artist);
                }
            } catch (Exception ignored) {
            }
        }

        if (artistsByName.isEmpty()) {
            collectArtistsFromLoadedSongs(artistsByName);
        }

        Set<Long> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        List<Long> seedIds = new ArrayList<>();
        for (Artist artist : artistsByName.values()) {
            if (artist == null) continue;
            String normalizedName = normalizeArtistName(artist.getName());
            if (normalizedName.isBlank()) continue;
            names.add(normalizedName);
            if (artist.getArtistID() > 0 && ids.add(artist.getArtistID())
                    && seedIds.size() < ARTIST_SEED_LIMIT) {
                seedIds.add(artist.getArtistID());
            }
        }
        return new LibraryArtistState(Set.copyOf(ids), Set.copyOf(names), List.copyOf(seedIds));
    }

    private void collectArtistsFromLoadedSongs(LinkedHashMap<String, Artist> artistsByName) {
        try {
            if (context.svc() == null || context.svc().getSongs() == null) return;
            for (Song song : context.svc().getSongs()) {
                if (song == null) continue;
                if (song.getArtist() != null) {
                    for (Artist artist : song.getArtist()) addLibraryArtist(artistsByName, artist);
                }
                if (song.getAlbum() != null && song.getAlbum().getArtist() != null) {
                    for (Artist artist : song.getAlbum().getArtist()) addLibraryArtist(artistsByName, artist);
                }
                if (artistsByName.size() >= ARTIST_SEED_LIMIT) return;
            }
        } catch (Exception ignored) {
        }
    }

    private void addLibraryArtist(LinkedHashMap<String, Artist> target, Artist artist) {
        if (target == null || artist == null) return;
        String name = normalizeArtistName(artist.getName());
        if (name.isBlank() || name.equals("unknown") || name.equals("unknown artist")) return;
        target.merge(name, artist, (current, incoming) ->
                current.getArtistID() > 0 || incoming.getArtistID() <= 0 ? current : incoming);
    }

    private List<ArtistCandidate> loadRelatedCandidates(List<Long> seedIds,
                                                          Set<Long> existingArtistIds,
                                                          Set<String> existingArtistNames,
                                                          long renderId) {
        if (seedIds == null || seedIds.isEmpty()) return List.of();
        List<Callable<List<ArtistCandidate>>> tasks = seedIds.stream()
                .<Callable<List<ArtistCandidate>>>map(seedId -> () -> loadRelatedArtists(
                        seedId,
                        existingArtistIds,
                        existingArtistNames,
                        renderId
                ))
                .toList();
        return flatten(loadConcurrently(tasks));
    }

    private List<ArtistCandidate> loadRelatedArtists(long seedId,
                                                       Set<Long> existingArtistIds,
                                                       Set<String> existingArtistNames,
                                                       long renderId) {
        if (seedId <= 0 || !isRenderActive(renderId)) return List.of();
        JsonObject root = getJson(context.endpoints().artistRelated(seedId, RELATED_PER_SEED_LIMIT));
        if (root == null || !root.has("data") || !root.get("data").isJsonArray()) return List.of();

        List<ArtistCandidate> candidates = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("data")) {
            if (!isRenderActive(renderId)) return List.of();
            ArtistCandidate candidate = candidateFromJson(element);
            if (candidate == null
                    || candidate.id() == seedId
                    || existingArtistIds.contains(candidate.id())
                    || existingArtistNames.contains(normalizeArtistName(candidate.name()))) continue;
            candidates.add(candidate);
            if (candidates.size() >= RELATED_PER_SEED_LIMIT) break;
        }
        return candidates;
    }

    private List<ArtistCandidate> loadGenreFallbackCandidates(Set<Long> blockedIds,
                                                                Set<String> blockedNames,
                                                                long renderId) {
        List<Callable<List<ArtistCandidate>>> tasks = new ArrayList<>();
        try {
            for (Genre genre : context.memory().genres()) {
                if (genre == null || genre.getGenreID() <= 0) continue;
                int genreId = genre.getGenreID();
                tasks.add(() -> loadArtistsForGenre(genreId, blockedIds, blockedNames, renderId));
                if (tasks.size() >= MAX_GENRES_TO_QUERY) break;
            }
        } catch (Exception ignored) {
        }
        return flatten(loadConcurrently(tasks));
    }

    private List<ArtistCandidate> loadArtistsForGenre(int genreId,
                                                        Set<Long> blockedIds,
                                                        Set<String> blockedNames,
                                                        long renderId) {
        if (genreId <= 0 || !isRenderActive(renderId)) return List.of();
        JsonObject root = getJson(context.endpoints().genreArtists(genreId));
        if (root == null || !root.has("data") || !root.get("data").isJsonArray()) return List.of();

        List<ArtistCandidate> candidates = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("data")) {
            if (!isRenderActive(renderId)) return List.of();
            ArtistCandidate candidate = candidateFromJson(element);
            if (candidate == null
                    || blockedIds.contains(candidate.id())
                    || blockedNames.contains(normalizeArtistName(candidate.name()))) continue;
            candidates.add(candidate);
            if (candidates.size() >= GENRE_ARTISTS_PER_LOOKUP) break;
        }
        return candidates;
    }

    private List<ArtistCandidate> loadChartFallbackCandidates(
            Set<Long> blockedIds,
            Set<String> blockedNames,
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
            if (candidate == null
                    || blockedIds.contains(candidate.id())
                    || blockedNames.contains(normalizeArtistName(candidate.name()))) continue;
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
            futures.add(supplyAsync(task, LOOKUP_POOL)
                    .exceptionally(ignored -> List.of()));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return futures.stream().map(future -> future.getNow(List.of())).toList();
    }

    private void mergeCandidates(LinkedHashMap<Long, ArtistCandidate> target,
                                 Collection<ArtistCandidate> incoming,
                                 Set<Long> existingArtistIds,
                                 Set<String> existingArtistNames) {
        if (incoming == null) return;
        for (ArtistCandidate candidate : incoming) {
            if (candidate == null || candidate.id() <= 0 || existingArtistIds.contains(candidate.id())) continue;
            String name = normalizeArtistName(candidate.name());
            if (name.isBlank() || existingArtistNames.contains(name) || candidateNames(target).contains(name)) continue;
            target.putIfAbsent(candidate.id(), candidate);
            if (target.size() >= RECOMMENDATION_MAX) return;
        }
    }

    private Set<String> candidateNames(LinkedHashMap<Long, ArtistCandidate> candidates) {
        Set<String> names = new HashSet<>();
        if (candidates == null) return names;
        for (ArtistCandidate candidate : candidates.values()) {
            if (candidate != null) {
                String name = normalizeArtistName(candidate.name());
                if (!name.isBlank()) names.add(name);
            }
        }
        return names;
    }

    private String normalizeArtistName(String name) {
        return name == null
                ? ""
                : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
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
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private record LibraryArtistState(Set<Long> ids, Set<String> names, List<Long> seedIds) {
        private boolean isEmpty() {
            return names == null || names.isEmpty();
        }
    }

    private record ArtistCandidate(long id, String name, String pictureUrl) {
    }
}
