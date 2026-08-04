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
import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;
import io.github.guillermodubon.musicplayer.utils.AlbumArtistResolver;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Suggests non-library albums from the artists already present in the listener's library.
 * The remote lookups are limited and concurrent so Home stays responsive on large libraries.
 */
public class PopularAlbumsSectionProvider extends BaseHomePageSectionProvider {

    private static final int MAX_ARTIST_LOOKUPS = 6;
    private static final int ALBUMS_PER_ARTIST = 4;
    private static final int ARTIST_ALBUM_PAGE_SIZE = 100;

    public PopularAlbumsSectionProvider(HomePageContext context) {
        super(context);
    }

    @Override
    public CompletableFuture<Void> render(VBox container, String filter, long renderId) {
        VBox section = sectionBlock(container, "Suggested Albums for you");
        setSectionContent(section, emptyState("Loading suggested albums..."));

        if (context.endpoints() == null) {
            removeSection(section);
            return CompletableFuture.completedFuture(null);
        }

        List<Artist> artists = seedArtists();
        if (artists.isEmpty()) {
            removeSection(section);
            return CompletableFuture.completedFuture(null);
        }

        Set<Long> localAlbumIds = existingAlbumIds();
        List<CompletableFuture<List<AlbumCardResult>>> futures = new ArrayList<>();
        for (Artist artist : artists) {
            futures.add(supplyAsync(() -> fetchArtistAlbums(artist, localAlbumIds, filter, renderId))
                    .exceptionally(error -> List.of()));
        }

        CompletableFuture<Void> completion = new CompletableFuture<>();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((ignored, error) -> Platform.runLater(() -> {
                    try {
                        if (!isRenderActive(renderId)) return;

                        LinkedHashMap<Long, Parent> cardsByAlbumId = new LinkedHashMap<>();
                        for (CompletableFuture<List<AlbumCardResult>> future : futures) {
                            for (AlbumCardResult result : future.getNow(List.of())) {
                                if (result == null || result.card() == null || result.id() <= 0) continue;
                                cardsByAlbumId.putIfAbsent(result.id(), result.card());
                                if (cardsByAlbumId.size() >= MAX_CARDS_PER_SECTION) break;
                            }
                            if (cardsByAlbumId.size() >= MAX_CARDS_PER_SECTION) break;
                        }

                        if (cardsByAlbumId.isEmpty()) {
                            removeSection(section);
                        } else {
                            setSectionContent(section, createMusicCarousel(
                                    cardsByAlbumId.values().stream().limit(MAX_CARDS_PER_SECTION).toList()
                            ));
                        }
                    } finally {
                        completion.complete(null);
                    }
                }));
        return completion;
    }

    private List<Artist> seedArtists() {
        LinkedHashMap<String, Artist> unique = new LinkedHashMap<>();
        try {
            for (Artist artist : context.memory().artists()) {
                if (artist == null || artist.getName() == null || artist.getName().isBlank()) continue;
                long id = artist.getArtistID();
                String key = id > 0 ? "id:" + id : "name:" + artist.getName().trim().toLowerCase();
                unique.putIfAbsent(key, artist);
            }
        } catch (Exception ignored) {
        }

        List<Artist> artists = new ArrayList<>(unique.values());
        Collections.shuffle(artists);
        return artists.stream().limit(MAX_ARTIST_LOOKUPS).toList();
    }

    private Set<Long> existingAlbumIds() {
        Set<Long> ids = new HashSet<>();
        try {
            if (context.svc() != null && context.svc().getAlbums() != null) {
                for (Album album : context.svc().getAlbums()) {
                    if (album != null && album.getAlbumID() > 0) ids.add(album.getAlbumID());
                }
            }
        } catch (Exception ignored) {
        }
        try {
            for (Album album : context.memory().albums()) {
                if (album != null && album.getAlbumID() > 0) ids.add(album.getAlbumID());
            }
        } catch (Exception ignored) {
        }
        return ids;
    }

    private List<AlbumCardResult> fetchArtistAlbums(Artist artist,
                                                     Set<Long> localAlbumIds,
                                                     String filter,
                                                     long renderId) {
        if (artist == null || !isRenderActive(renderId)) return List.of();

        long artistId = resolveArtistId(artist);
        if (artistId <= 0 || !isRenderActive(renderId)) return List.of();

        JsonObject root = getJson(context.endpoints().artistAlbums(artistId, ARTIST_ALBUM_PAGE_SIZE, 0));
        if (root == null || !root.has("data") || !root.get("data").isJsonArray()) return List.of();

        String normalizedFilter = norm(filter);
        List<AlbumCardResult> result = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("data")) {
            if (!isRenderActive(renderId)) return List.of();
            if (!element.isJsonObject()) continue;

            JsonObject album = element.getAsJsonObject();
            long albumId = DeezerApiService.safeGetLong(album, "id", -1L);
            if (albumId <= 0 || localAlbumIds.contains(albumId)) continue;

            String title = DeezerApiService.extractTitle(album);
            List<String> names = normalizeArtistNames(extractAlbumArtistNamesFromResource(album));
            if (!AlbumArtistResolver.hasExplicitOwnerCollection(album) && names.size() <= 1) {
                try {
                    JsonObject detail = getJson(context.endpoints().albumById(albumId));
                    List<String> detailedNames = normalizeArtistNames(
                            extractAlbumArtistNamesFromResource(detail)
                    );
                    if (!detailedNames.isEmpty()) names = detailedNames;
                } catch (Exception ignored) {
                }
            }
            if (names.isEmpty()) names = List.of(artist.getName());
            if (!matchesFilter(title, names, normalizedFilter)) continue;

            Parent card = createAlbumCard(albumId, title, names, album);
            if (card != null) result.add(new AlbumCardResult(albumId, card));
            if (result.size() >= ALBUMS_PER_ARTIST) break;
        }
        return result;
    }

    private long resolveArtistId(Artist artist) {
        if (artist.getArtistID() > 0) return artist.getArtistID();
        if (artist.getName() == null || artist.getName().isBlank()) return -1L;

        String encoded = URLEncoder.encode(artist.getName(), StandardCharsets.UTF_8);
        JsonObject root = getJson(context.endpoints().searchArtists(encoded));
        if (root == null || !root.has("data") || !root.get("data").isJsonArray()) return -1L;

        JsonArray artists = root.getAsJsonArray("data");
        long fallback = -1L;
        for (JsonElement element : artists) {
            if (!element.isJsonObject()) continue;
            JsonObject candidate = element.getAsJsonObject();
            long id = DeezerApiService.safeGetLong(candidate, "id", -1L);
            if (id <= 0) continue;
            if (fallback <= 0) fallback = id;
            String name = DeezerApiService.extractTitle(candidate);
            if (name != null && name.equalsIgnoreCase(artist.getName().trim())) return id;
        }
        return fallback;
    }

    private Parent createAlbumCard(long albumId,
                                   String title,
                                   List<String> artistNames,
                                   JsonObject album) {
        try {
            String coverUrl = DeezerApiService.extractHighResolutionCoverUrl(album);
            Image cover = coverUrl == null || coverUrl.isBlank() ? null : MediaImageResolver.remoteCardImage(coverUrl);
            MusicCardData data = new MusicCardData(
                    String.valueOf(albumId),
                    cover,
                    title == null || title.isBlank() ? "Album" : title,
                    artistNames,
                    context.musicActions().albumClick(null),
                    context.musicActions().artistNameClick(null)
            );
            Parent card = CardFactory.createMusicCard(data);
            styleMusicCard(card);
            return card;
        } catch (Exception ignored) {
            return null;
        }
    }

    private record AlbumCardResult(long id, Parent card) {
    }
}
