package io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.MusicCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.factory.CardFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.context.HomePageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.providers.base.BaseHomePageSectionProvider;
import io.github.guillermodubon.musicplayer.models.Genre;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.image.Image;
import javafx.scene.layout.VBox;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class FeaturedPlaylistsSectionProvider extends BaseHomePageSectionProvider {

    private static final String SECTION_TITLE = "Playlists based on your favorite genres";
    private static final int PLAYLIST_FETCH_LIMIT = 100;
    private static final int SOURCE_GENRE_CHART = 0;
    private static final int SOURCE_NAME_SEARCH = 1;
    private static final int SOURCE_GLOBAL_CHART = 2;

    public FeaturedPlaylistsSectionProvider(HomePageContext context) {
        super(context);
    }

    @Override
    public CompletableFuture<Void> render(VBox container, String filter, long renderId) {
        if (!isRenderActive(renderId)) return CompletableFuture.completedFuture(null);

        VBox section = sectionBlock(container, SECTION_TITLE);
        setSectionContent(section, emptyState("Loading playlists based on your favorite genres..."));

        List<GenreSeed> genreSeeds = genreSeeds();
        if (genreSeeds.isEmpty()) {
            removeSection(section);
            return CompletableFuture.completedFuture(null);
        }

        List<CompletableFuture<List<PlaylistCandidate>>> futures = new ArrayList<>();
        for (GenreSeed genre : genreSeeds) {
            futures.add(supplyAsync(() -> fetchPlaylistsForGenre(genre, filter, renderId))
                    .exceptionally(ignored -> List.of()));
        }

        CompletableFuture<Void> completion = new CompletableFuture<>();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((unused, throwable) -> Platform.runLater(() -> {
                    try {
                        if (!isRenderActive(renderId)) return;

                        List<List<PlaylistCandidate>> groups = new ArrayList<>();
                        for (CompletableFuture<List<PlaylistCandidate>> future : futures) {
                            List<PlaylistCandidate> group = future.getNow(List.of());
                            groups.add(group == null ? List.of() : group);
                        }

                        List<Parent> cards = createPlaylistCards(selectFeaturedPlaylists(groups), renderId);
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

    private List<GenreSeed> genreSeeds() {
        LinkedHashMap<String, GenreSeed> unique = new LinkedHashMap<>();
        try {
            if (context.memory() == null || context.memory().genres() == null) {
                return List.of(new GenreSeed(0, ""));
            }

            for (Genre genre : context.memory().genres()) {
                if (genre == null) continue;

                String name = cleanGenreName(genre.getName());
                boolean hasUsableName = isUsableGenreName(name);
                // "Unknown" is persisted locally with an internal ID. That
                // number is not a Deezer genre ID and must not be queried as one.
                int genreId = hasUsableName ? genre.getGenreID() : 0;
                if (genreId <= 0 && !hasUsableName) continue;

                String key = genreId > 0 ? "id:" + genreId : "name:" + name.toLowerCase(Locale.ROOT);
                unique.putIfAbsent(key, new GenreSeed(genreId, hasUsableName ? name : ""));
                if (unique.size() >= MAX_GENRES_TO_QUERY) break;
            }
        } catch (Exception ignored) {
        }
        if (!unique.isEmpty()) return new ArrayList<>(unique.values());

        // No user genre can be queried safely. Keep the section useful with
        // Deezer's chart instead of issuing a meaningless "Unknown" search.
        return List.of(new GenreSeed(0, ""));
    }

    private List<PlaylistCandidate> fetchPlaylistsForGenre(GenreSeed genre, String filter, long renderId) {
        if (context.endpoints() == null || genre == null || !isRenderActive(renderId)) return List.of();

        List<PlaylistCandidate> candidates = new ArrayList<>();
        Set<Long> seenPlaylistIds = new LinkedHashSet<>();
        String normalizedFilter = norm(filter);

        try {
            // Deezer's genre chart is the strongest quality signal available
            // without relying on a free-text result alone.
            if (genre.id() > 0) {
                JsonObject chartRoot = getJson(withLimit(
                        context.endpoints().genrePlaylists(genre.id()),
                        PLAYLIST_FETCH_LIMIT
                ));
                appendCandidates(candidates, seenPlaylistIds, playlistArray(chartRoot), normalizedFilter,
                        SOURCE_GENRE_CHART, renderId);
            }

            // Keep the text lookup as a complementary source: it covers genres
            // whose chart is sparse and supplies more than the chart page alone.
            if (!genre.name().isBlank()) {
                String encodedGenre = URLEncoder.encode(genre.name(), StandardCharsets.UTF_8);
                JsonObject searchRoot = getJson(withLimit(
                        context.endpoints().searchPlaylists(encodedGenre),
                        PLAYLIST_FETCH_LIMIT
                ));
                appendCandidates(candidates, seenPlaylistIds, playlistArray(searchRoot), normalizedFilter,
                        SOURCE_NAME_SEARCH, renderId);
            }

            // This does not replace the genre-based recommendations. It only
            // completes a sparse genre response with Deezer's own chart so the
            // carousel can still present a full, high-quality set of cards.
            if (candidates.size() < MAX_CARDS_PER_SECTION && isRenderActive(renderId)) {
                JsonObject globalChartRoot = getJson(withLimit(
                        context.endpoints().chartPlaylists(),
                        PLAYLIST_FETCH_LIMIT
                ));
                appendCandidates(candidates, seenPlaylistIds, playlistArray(globalChartRoot), normalizedFilter,
                        SOURCE_GLOBAL_CHART, renderId);
            }
        } catch (Exception ignored) {
        }

        sortCandidates(candidates);
        return candidates;
    }

    private String withLimit(String endpoint, int limit) {
        if (endpoint == null || endpoint.isBlank()) return endpoint;
        int safeLimit = Math.max(MAX_CARDS_PER_SECTION, limit);
        return endpoint + (endpoint.contains("?") ? "&" : "?") + "limit=" + safeLimit;
    }

    private void appendCandidates(List<PlaylistCandidate> target,
                                  Set<Long> seenPlaylistIds,
                                  JsonArray source,
                                  String filter,
                                  int sourcePriority,
                                  long renderId) {
        if (target == null || seenPlaylistIds == null || source == null) return;

        int sourcePosition = 0;
        for (JsonElement element : source) {
            int position = sourcePosition++;
            if (!isRenderActive(renderId)) return;
            if (!element.isJsonObject()) continue;

            JsonObject playlist = element.getAsJsonObject();
            long id = DeezerApiService.safeGetLong(playlist, "id", -1L);
            if (id <= 0 || !seenPlaylistIds.add(id)) continue;

            String title = DeezerApiService.extractTitle(playlist);
            if (title == null || title.isBlank()) continue;

            String creator = extractPlaylistCreator(playlist);
            if (!matchesFilter(title, List.of(creator, MusicCardData.REMOTE_PLAYLIST_CREATOR_LABEL), filter)) {
                continue;
            }

            target.add(new PlaylistCandidate(
                    playlist.deepCopy(),
                    id,
                    title,
                    creator,
                    isOfficialPlaylist(creator),
                    DeezerApiService.safeGetLong(playlist, "fans", 0L),
                    DeezerApiService.safeGetLong(playlist, "nb_tracks", 0L),
                    sourcePriority,
                    position
            ));
        }
    }

    private List<PlaylistCandidate> selectFeaturedPlaylists(List<List<PlaylistCandidate>> groups) {
        LinkedHashMap<Long, PlaylistCandidate> selected = new LinkedHashMap<>();
        if (groups == null || groups.isEmpty()) return List.of();

        List<List<PlaylistCandidate>> activeGroups = groups.stream()
                .filter(group -> group != null && !group.isEmpty())
                .toList();
        if (activeGroups.isEmpty()) return List.of();

        List<Integer> nextIndexes = new ArrayList<>();
        for (int index = 0; index < activeGroups.size(); index++) nextIndexes.add(0);

        int baseQuota = MAX_CARDS_PER_SECTION / activeGroups.size();
        int remainingQuota = MAX_CARDS_PER_SECTION % activeGroups.size();
        for (int groupIndex = 0; groupIndex < activeGroups.size(); groupIndex++) {
            int quota = baseQuota + (groupIndex < remainingQuota ? 1 : 0);
            takeFromGroup(activeGroups.get(groupIndex), nextIndexes, groupIndex, quota, selected);
        }

        // If a genre has fewer playlists than its share, complete the 16-card
        // target from the remaining genre results without duplicating a card.
        boolean added;
        do {
            added = false;
            for (int groupIndex = 0; groupIndex < activeGroups.size(); groupIndex++) {
                if (selected.size() >= MAX_CARDS_PER_SECTION) break;
                int sizeBefore = selected.size();
                takeFromGroup(activeGroups.get(groupIndex), nextIndexes, groupIndex, 1, selected);
                added |= selected.size() > sizeBefore;
            }
        } while (added && selected.size() < MAX_CARDS_PER_SECTION);

        return new ArrayList<>(selected.values());
    }

    private void takeFromGroup(List<PlaylistCandidate> group,
                               List<Integer> nextIndexes,
                               int groupIndex,
                               int amount,
                               LinkedHashMap<Long, PlaylistCandidate> selected) {
        if (group == null || nextIndexes == null || selected == null || amount <= 0) return;

        int nextIndex = nextIndexes.get(groupIndex);
        int added = 0;
        while (nextIndex < group.size()
                && added < amount
                && selected.size() < MAX_CARDS_PER_SECTION) {
            PlaylistCandidate candidate = group.get(nextIndex++);
            if (candidate == null || candidate.id() <= 0 || selected.containsKey(candidate.id())) continue;
            selected.put(candidate.id(), candidate);
            added++;
        }
        nextIndexes.set(groupIndex, nextIndex);
    }

    private List<Parent> createPlaylistCards(List<PlaylistCandidate> candidates, long renderId) {
        List<Parent> cards = new ArrayList<>();
        if (candidates == null) return cards;

        for (PlaylistCandidate candidate : candidates) {
            if (!isRenderActive(renderId) || cards.size() >= MAX_CARDS_PER_SECTION) break;

            try {
                Image cover = resolveCover(candidate.playlist());
                MusicCardData data = MusicCardData.playlist(
                        String.valueOf(candidate.id()),
                        cover,
                        candidate.title(),
                        List.of(MusicCardData.REMOTE_PLAYLIST_CREATOR_LABEL),
                        context.musicActions().playlistClick(null),
                        context.musicActions().artistNameClick(null)
                );

                Parent card = CardFactory.createMusicCard(data);
                card.getProperties().put("playlistId", candidate.id());
                styleMusicCard(card);
                cards.add(card);
            } catch (Exception ignored) {
            }
        }
        return cards;
    }

    private Image resolveCover(JsonObject playlist) {
        try {
            String coverUrl = DeezerApiService.extractHighResolutionPictureUrl(playlist);
            if (coverUrl != null && !coverUrl.isBlank()) {
                Image cover = MediaImageResolver.remoteCardImage(coverUrl);
                if (cover != null) return cover;
            }
        } catch (Exception ignored) {
        }
        return defaultCover();
    }

    private void sortCandidates(List<PlaylistCandidate> candidates) {
        if (candidates == null) return;
        candidates.sort(Comparator
                .comparingInt((PlaylistCandidate candidate) -> candidate.official() ? 0 : 1)
                .thenComparingInt(PlaylistCandidate::sourcePriority)
                .thenComparing(Comparator.comparingLong(PlaylistCandidate::fans).reversed())
                .thenComparing(Comparator.comparingLong(PlaylistCandidate::trackCount).reversed())
                .thenComparingInt(PlaylistCandidate::sourcePosition));
    }

    private String cleanGenreName(String rawName) {
        if (rawName == null) return "";
        return rawName
                .replaceAll("[\\\\/_-]+", " ")
                .replaceAll("[^\\p{L}\\p{N}\\s&]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean isUsableGenreName(String name) {
        if (name == null || name.isBlank()) return false;

        String normalized = name.trim().toLowerCase(Locale.ROOT);
        return !normalized.contains("unknown")
                && !normalized.contains("unknow")
                && !normalized.equals("null")
                && !normalized.equals("undefined")
                && !normalized.equals("n/a")
                && !normalized.equals("na")
                && !normalized.equals("various")
                && normalized.codePoints().anyMatch(Character::isLetter);
    }

    private JsonArray playlistArray(JsonObject root) {
        if (root == null) return null;
        try {
            if (root.has("playlists") && root.get("playlists").isJsonObject()) {
                JsonObject playlists = root.getAsJsonObject("playlists");
                if (playlists.has("data") && playlists.get("data").isJsonArray()) {
                    return playlists.getAsJsonArray("data");
                }
            }
            if (root.has("data") && root.get("data").isJsonArray()) {
                return root.getAsJsonArray("data");
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String extractPlaylistCreator(JsonObject playlist) {
        if (playlist == null) return "";
        for (String key : List.of("creator", "user", "owner")) {
            if (!playlist.has(key) || playlist.get(key).isJsonNull()) continue;
            JsonElement value = playlist.get(key);
            try {
                if (value.isJsonPrimitive()) return value.getAsString().trim();
                if (!value.isJsonObject()) continue;

                JsonObject object = value.getAsJsonObject();
                for (String nameKey : List.of("name", "username", "title")) {
                    if (object.has(nameKey) && !object.get(nameKey).isJsonNull()) {
                        String name = object.get(nameKey).getAsString().trim();
                        if (!name.isBlank()) return name;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private boolean isOfficialPlaylist(String creator) {
        if (creator == null || creator.isBlank()) return false;
        String normalized = creator.toLowerCase(Locale.ROOT);
        return normalized.contains("deezer") || normalized.contains("editorial");
    }

    private record GenreSeed(int id, String name) {
    }

    private record PlaylistCandidate(
            JsonObject playlist,
            long id,
            String title,
            String creator,
            boolean official,
            long fans,
            long trackCount,
            int sourcePriority,
            int sourcePosition
    ) {
    }
}
