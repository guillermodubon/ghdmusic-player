package io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.MusicCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.factory.CardFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.context.HomePageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.providers.base.BaseHomePageSectionProvider;
import io.github.guillermodubon.musicplayer.models.Genre;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class FeaturedPlaylistsSectionProvider extends BaseHomePageSectionProvider {

    private static final String SECTION_TITLE = "Playlists based on your favorite genres";

    public FeaturedPlaylistsSectionProvider(HomePageContext context) {
        super(context);
    }

    @Override
    public CompletableFuture<Void> render(VBox container, String filter, long renderId) {
        if (!isRenderActive(renderId)) return CompletableFuture.completedFuture(null);

        VBox section = sectionBlock(container, SECTION_TITLE);
        setSectionContent(section, emptyState("Loading playlists based on your favorite genres..."));

        List<Genre> genres = new ArrayList<>(context.memory().genres());
        List<Integer> genreIds = genres.stream()
                .filter(g -> g != null && g.getGenreID() > 0)
                .map(Genre::getGenreID)
                .distinct()
                .limit(MAX_GENRES_TO_QUERY)
                .collect(Collectors.toList());

        // Featured playlists are intentionally based only on the listener's genres.
        // Without genre affinity there is no relevant section to display.
        if (genreIds.isEmpty()) {
            removeSection(section);
            return CompletableFuture.completedFuture(null);
        }

        List<CompletableFuture<List<Parent>>> futures = new ArrayList<>();
        for (Integer genreId : genreIds) {
            futures.add(supplyAsync(() -> fetchPlaylistsForGenreCards(genreId, MAX_GENRE_PLAYLISTS_PER_GENRE, filter, renderId))
                    .exceptionally(e -> {
                        e.printStackTrace();
                        return List.of();
                    }));
        }

        CompletableFuture<Void> completion = new CompletableFuture<>();
        CompletableFuture
                .allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((v, th) -> Platform.runLater(() -> {
                    try {
                        if (!isRenderActive(renderId)) return;

                        LinkedHashMap<Long, Parent> cardsByPlaylistId = new LinkedHashMap<>();
                        for (CompletableFuture<List<Parent>> future : futures) {
                            try {
                                List<Parent> part = future.getNow(List.of());
                                if (part != null) {
                                    for (Parent card : part) {
                                        long playlistId = extractPlaylistId(card);
                                        if (playlistId <= 0 || cardsByPlaylistId.containsKey(playlistId)) continue;
                                        cardsByPlaylistId.put(playlistId, card);
                                        if (cardsByPlaylistId.size() >= MAX_CARDS_PER_SECTION) break;
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                            if (cardsByPlaylistId.size() >= MAX_CARDS_PER_SECTION) break;
                        }

                        List<Parent> finalCards = cardsByPlaylistId.values().stream()
                                .limit(MAX_CARDS_PER_SECTION)
                                .collect(Collectors.toList());

                        if (finalCards.isEmpty()) {
                            removeSection(section);
                        } else {
                            setSectionContent(section, createMusicCarousel(finalCards));
                        }
                    } finally {
                        completion.complete(null);
                    }
                }));
        return completion;
    }

    private List<Parent> fetchPlaylistsForGenreCards(int genreId, int perGenreLimit, String filter, long renderId) {
        List<Parent> out = new ArrayList<>();
        String f = norm(filter);

        try {
            JsonObject root = context.endpoints() == null
                    ? null
                    : getJson(context.endpoints().genrePlaylists(genreId));

            if (root == null) return out;

            JsonArray playlistsArray = null;

            if (root.has("playlists") && root.get("playlists").isJsonObject()) {
                JsonObject playlistsObj = root.getAsJsonObject("playlists");
                if (playlistsObj.has("data") && playlistsObj.get("data").isJsonArray()) {
                    playlistsArray = playlistsObj.getAsJsonArray("data");
                }
            } else if (root.has("data") && root.get("data").isJsonArray()) {
                playlistsArray = root.getAsJsonArray("data");
            }

            if (playlistsArray == null || playlistsArray.size() == 0) return out;

            int taken = 0;

            for (JsonElement el : playlistsArray) {
                if (!isRenderActive(renderId)) return List.of();
                if (!el.isJsonObject()) continue;

                JsonObject p = el.getAsJsonObject();
                long id = DeezerApiService.safeGetLong(p, "id", -1L);
                if (id <= 0) continue;

                String title = DeezerApiService.extractTitle(p);

                String creator = MusicCardData.REMOTE_PLAYLIST_CREATOR_LABEL;

                if (!matchesFilter(title, List.of(creator), f)) continue;

                String coverUrl = null;
                try {
                    coverUrl = DeezerApiService.extractHighResolutionPictureUrl(p);
                } catch (Exception ignored) {
                }

                Image cover = null;
                if (coverUrl != null && !coverUrl.isBlank()) {
                    try {
                        cover = MediaImageResolver.remoteCardImage(coverUrl);
                    } catch (Exception ignored) {
                        cover = null;
                    }
                }

                try {
                    MusicCardData data = MusicCardData.playlist(
                            String.valueOf(id),
                            cover,
                            title == null || title.isBlank() ? "Playlist" : title,
                            List.of(creator),
                            context.musicActions().playlistClick(null),
                            context.musicActions().artistNameClick(null)
                    );

                    Parent card = CardFactory.createMusicCard(data);
                    card.getProperties().put("playlistId", id);
                    styleMusicCard(card);
                    out.add(card);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }

                taken++;
                if (taken >= perGenreLimit || out.size() >= MAX_CARDS_PER_SECTION) break;
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        return out;
    }

    private long extractPlaylistId(Parent card) {
        if (card == null) return -1L;
        Object raw = card.getProperties().get("playlistId");
        if (raw instanceof Number n) return n.longValue();
        try {
            return raw == null ? -1L : Long.parseLong(String.valueOf(raw));
        } catch (Exception ignored) {
            return -1L;
        }
    }
}
