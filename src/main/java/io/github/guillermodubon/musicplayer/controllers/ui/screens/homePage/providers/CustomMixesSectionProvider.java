package io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.image.Image;
import javafx.scene.layout.VBox;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.MusicCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.factory.CardFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.context.HomePageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.providers.base.BaseHomePageSectionProvider;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;
import io.github.guillermodubon.musicplayer.utils.DeezerEndpoints;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public class CustomMixesSectionProvider extends BaseHomePageSectionProvider {

    private static final int MAX_ARTIST_PLAYLIST_SEARCHES = 8;
    private static final int MIN_PLAYLISTS_PER_ARTIST = 4;

    public CustomMixesSectionProvider(HomePageContext context) {
        super(context);
    }

    @Override
    public CompletableFuture<Void> render(VBox container, String filter, long renderId) {
        VBox section = sectionBlock(container, "Custom mixes");
        setSectionContent(section, emptyState("Loading custom mixes..."));

        if (context.endpoints() == null) {
            removeSection(section);
            return CompletableFuture.completedFuture(null);
        }

        List<ArtistReference> artistReferences = randomArtistReferences();
        if (artistReferences.isEmpty()) {
            removeSection(section);
            return CompletableFuture.completedFuture(null);
        }

        int perArtistLimit = playlistsPerArtist(artistReferences.size());
        List<CompletableFuture<List<PlaylistCardResult>>> futures = new ArrayList<>();
        for (ArtistReference artist : artistReferences) {
            futures.add(supplyAsync(() -> fetchArtistPlaylistCards(
                            artist,
                            perArtistLimit,
                            filter,
                            renderId
                    ))
                    .exceptionally(error -> List.of()));
        }

        CompletableFuture<Void> completion = new CompletableFuture<>();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((v, th) -> Platform.runLater(() -> {
                    try {
                        if (!isRenderActive(renderId)) return;

                        List<List<PlaylistCardResult>> cardsByArtist = new ArrayList<>();
                        for (CompletableFuture<List<PlaylistCardResult>> future : futures) {
                            cardsByArtist.add(future.getNow(List.of()));
                        }

                        // Interleave artists so one prolific search cannot fill
                        // the whole section before the other artists contribute.
                        LinkedHashMap<Long, Parent> cardsById = new LinkedHashMap<>();
                        for (int position = 0; cardsById.size() < MAX_CARDS_PER_SECTION; position++) {
                            boolean addedCandidate = false;
                            for (List<PlaylistCardResult> artistCards : cardsByArtist) {
                                if (artistCards == null || position >= artistCards.size()) continue;

                                PlaylistCardResult result = artistCards.get(position);
                                if (result == null || result.playlistId() <= 0 || result.card() == null) continue;
                                cardsById.putIfAbsent(result.playlistId(), result.card());
                                addedCandidate = true;
                                if (cardsById.size() >= MAX_CARDS_PER_SECTION) break;
                            }
                            if (!addedCandidate) break;
                        }

                        if (cardsById.isEmpty()) {
                            removeSection(section);
                        } else {
                            setSectionContent(section, createMusicCarousel(
                                    cardsById.values().stream().limit(MAX_CARDS_PER_SECTION).toList()
                            ));
                        }
                    } finally {
                        completion.complete(null);
                    }
                }));
        return completion;
    }

    private List<ArtistReference> randomArtistReferences() {
        LinkedHashMap<String, ArtistReference> unique = new LinkedHashMap<>();
        try {
            for (Artist artist : context.memory().artists()) {
                if (artist == null) continue;

                long artistId = artist.getArtistID();
                String artistName = normalizeArtistName(artist.getName());
                if (artistName == null && artistId <= 0) continue;

                String key = artistId > 0
                        ? "id:" + artistId
                        : "name:" + artistName.toLowerCase(Locale.ROOT);
                unique.putIfAbsent(key, new ArtistReference(artistId, artistName));
            }
        } catch (Exception ignored) {
        }

        List<ArtistReference> references = new ArrayList<>(unique.values());
        Collections.shuffle(references);
        return references.stream().limit(MAX_ARTIST_PLAYLIST_SEARCHES).toList();
    }

    private String normalizeArtistName(String name) {
        if (name == null) return null;
        String normalized = name.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private String resolveArtistName(long artistId) {
        if (artistId <= 0 || context.deezer() == null) return null;
        try {
            JsonObject artist = getJson(DeezerEndpoints.artistById(artistId));
            return normalizeArtistName(DeezerApiService.extractTitle(artist));
        } catch (Exception ignored) {
            return null;
        }
    }

    private int playlistsPerArtist(int artistCount) {
        if (artistCount <= 0) return MIN_PLAYLISTS_PER_ARTIST;
        return Math.max(
                MIN_PLAYLISTS_PER_ARTIST,
                (int) Math.ceil(MAX_CARDS_PER_SECTION / (double) artistCount)
        );
    }

    private List<PlaylistCardResult> fetchArtistPlaylistCards(ArtistReference artist,
                                                               int limit,
                                                               String filter,
                                                               long renderId) {
        List<PlaylistCardResult> out = new ArrayList<>();
        if (artist == null) return out;

        // Missing names are resolved inside the IO worker so the first paint
        // of the Home screen never waits for an artist metadata request.
        String artistName = normalizeArtistName(artist.name());
        if (artistName == null) artistName = resolveArtistName(artist.id());
        if (artistName == null) return out;

        JsonArray data = searchPlaylistData(artistName);
        if (data == null || data.size() == 0) {
            data = searchPlaylistData("\"" + artistName + "\"");
        }
        if (data == null || data.size() == 0) return out;

        String f = norm(filter);
        for (JsonElement element : data) {
            if (!isRenderActive(renderId)) return List.of();
            if (!element.isJsonObject()) continue;

            JsonObject playlist = element.getAsJsonObject();
            long playlistId = DeezerApiService.safeGetLong(playlist, "id", -1L);
            if (playlistId <= 0) continue;

            String title = DeezerApiService.extractTitle(playlist);
            String creator = MusicCardData.REMOTE_PLAYLIST_CREATOR_LABEL;
            if (!matchesFilter(title, List.of(creator, artistName), f)) continue;

            try {
                Parent card = createPlaylistCard(playlistId, title, creator, playlist);
                if (card != null) out.add(new PlaylistCardResult(playlistId, card));
            } catch (Exception ignored) {
            }

            if (out.size() >= limit) break;
        }
        return out;
    }

    private JsonArray searchPlaylistData(String query) {
        if (query == null || query.isBlank()) return null;
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        JsonObject root = getJson(context.endpoints().searchPlaylists(encodedQuery));
        return root == null || !root.has("data") || !root.get("data").isJsonArray()
                ? null
                : root.getAsJsonArray("data");
    }

    private Parent createPlaylistCard(long playlistId, String title, String creator, JsonObject playlist) throws Exception {
        Image cover = null;
        String coverUrl = DeezerApiService.extractHighResolutionPictureUrl(playlist);
        if (coverUrl != null && !coverUrl.isBlank()) {
            cover = MediaImageResolver.remoteCardImage(coverUrl);
        }

        MusicCardData data = MusicCardData.playlist(
                String.valueOf(playlistId),
                cover,
                title == null || title.isBlank() ? "Playlist" : title,
                List.of(creator),
                context.musicActions().playlistClick(null),
                context.musicActions().artistNameClick(null)
        );
        Parent card = CardFactory.createMusicCard(data);
        styleMusicCard(card);
        return card;
    }

    private record PlaylistCardResult(long playlistId, Parent card) {
    }

    private record ArtistReference(long id, String name) {
    }
}
