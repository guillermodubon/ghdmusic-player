package io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.image.Image;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.MusicCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.factory.CardFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.context.GenrePageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.context.GenrePageRenderContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.providers.base.BaseGenrePageSectionProvider;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;
import io.github.guillermodubon.musicplayer.utils.GenreDetailsControllerUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class GenreAlbumsSectionProvider extends BaseGenrePageSectionProvider {

    public GenreAlbumsSectionProvider(GenrePageContext context) {
        super(context);
    }

    @Override
    public CompletableFuture<Void> render(VBox container, GenrePageRenderContext renderContext) {
        if (container == null || renderContext == null) return CompletableFuture.completedFuture(null);

        VBox section = sectionBlock();
        container.getChildren().add(section);

        CompletableFuture<Void> completion = new CompletableFuture<>();

        supplyAsync(() -> loadAlbumsCards(renderContext))
                .whenComplete((cards, th) -> Platform.runLater(() -> {
                    if (!renderContext.isAlive()) {
                        completion.complete(null);
                        return;
                    }

                    if (cards == null || cards.isEmpty()) {
                        hideSectionIfEmpty(container, section);
                        completion.complete(null);
                        return;
                    }

                    FlowPane fp = flowSection(container);
                    fp.getChildren().addAll(cards);

                    section.getChildren().setAll(
                            sectionTitle(renderContext.genreName() + " Albums"),
                            fp
                    );
                    renderContext.notifyNonLibrarySectionLoaded();
                    completion.complete(null);
                }));
        return completion;
    }

    private List<Parent> loadAlbumsCards(GenrePageRenderContext rc) {
        List<Parent> out = new ArrayList<>();
        if (context.endpoints() == null) return out;

        Set<Long> seen = new LinkedHashSet<>();
        LinkedHashMap<Long, String> chartArtists = new LinkedHashMap<>();

        JsonArray chartAlbums = getArray(context.endpoints().genreAlbums(rc.genreId(), MAX_DISPLAY));
        Map<Long, JsonObject> albumDetails = loadArtistDetails(
                chartAlbums,
                albumId -> context.endpoints().albumById(albumId)
        );
        appendAlbumCards(rc, out, seen, chartAlbums, chartArtists, albumDetails);

        if (out.size() < MAX_DISPLAY) {
            completeFromArtists(rc, out, seen, libraryArtistCandidates(rc));
        }

        if (out.size() < MAX_DISPLAY) {
            completeFromArtists(rc, out, seen, chartArtists);
        }

        if (out.size() < MAX_DISPLAY) {
            completeFromArtists(rc, out, seen, songSectionArtistCandidates(rc));
        }

        return out.size() <= MAX_DISPLAY ? out : out.subList(0, MAX_DISPLAY);
    }

    private void appendAlbumCards(GenrePageRenderContext rc,
                                  List<Parent> out,
                                  Set<Long> seen,
                                  JsonArray arr,
                                  LinkedHashMap<Long, String> artistCandidates,
                                  Map<Long, JsonObject> artistDetails) {
        if (arr == null) return;

        for (JsonElement el : arr) {
            if (!rc.isAlive() || out.size() >= MAX_DISPLAY) break;
            if (!el.isJsonObject()) continue;

            JsonObject album = el.getAsJsonObject();
            addArtistCandidates(artistCandidates, album);
            appendAlbumCard(rc, out, seen, album, artistDetails == null ? null : artistDetails.get(
                    GenreDetailsControllerUtils.safeGetLong(album, "id", -1L)
            ), artistDetails != null);
        }
    }

    private void appendAlbumCard(GenrePageRenderContext rc,
                                 List<Parent> out,
                                 Set<Long> seen,
                                 JsonObject obj,
                                 JsonObject artistDetail,
                                 boolean detailsPreloaded) {
        long id = GenreDetailsControllerUtils.safeGetLong(obj, "id", -1L);
        if (id <= 0 || !seen.add(id)) return;

        String title = GenreDetailsControllerUtils.safeGetString(obj, "title", "Unknown");
        List<String> artistNames = detailsPreloaded
                ? resolveAlbumArtistNames(obj, artistDetail)
                : resolveAlbumArtistNames(id, obj);

        String coverUrl = extractAlbumCoverUrl(obj);

        try {
            Parent card = CardFactory.createMusicCard(new MusicCardData(
                    String.valueOf(id),
                    coverUrl == null || coverUrl.isBlank() ? defaultCover() : MediaImageResolver.remoteCardImage(coverUrl),
                    title,
                    artistNames,
                    context.musicActions().albumClick(null),
                    context.musicActions().artistNameClick(null)
            ));

            card.getProperties().put("id", id);
            card.getProperties().put("albumId", id);
            card.getProperties().put("type", "album");
            List<Long> artistIds = GenreDetailsControllerUtils.extractAlbumArtistIdsFromResource(
                    artistDetail == null ? obj : artistDetail
            );
            if (!artistIds.isEmpty()) card.getProperties().put("artistIds", artistIds);
            if (coverUrl != null && !coverUrl.isBlank()) card.getProperties().put("coverUrl", coverUrl);

            out.add(card);
        } catch (Exception ignored) {
        }
    }

    private void completeFromArtists(GenrePageRenderContext rc,
                                     List<Parent> out,
                                     Set<Long> seen,
                                     LinkedHashMap<Long, String> artistCandidates) {
        if (artistCandidates == null || artistCandidates.isEmpty()) return;

        List<Map.Entry<Long, String>> artists = artistCandidates.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getKey() > 0)
                .toList();
        if (artists.isEmpty()) return;

        int cursor = 0;
        while (rc.isAlive() && out.size() < MAX_DISPLAY && cursor < artists.size()) {
            int missing = MAX_DISPLAY - out.size();
            int available = artists.size() - cursor;
            int waveSize = artists.size() == 1 ? 1 : Math.max(1, Math.min(missing, available));
            List<Map.Entry<Long, String>> wave = artists.subList(cursor, cursor + waveSize);
            int beforeWave = out.size();
            appendFromArtistWave(rc, out, seen, wave);
            cursor += waveSize;

            if (out.size() == beforeWave && cursor >= artists.size()) {
                break;
            }
            if (artists.size() == 1) {
                break;
            }
        }
    }

    private LinkedHashMap<Long, String> songSectionArtistCandidates(GenrePageRenderContext rc) {
        LinkedHashMap<Long, String> candidates = new LinkedHashMap<>();
        if (rc == null) return candidates;

        candidates.putAll(rc.shared().topArtistCandidates());

        for (Long artistId : rc.shared().topArtistIds()) {
            if (artistId == null || artistId <= 0 || candidates.containsKey(artistId)) continue;
            candidates.put(artistId, artistNameById(artistId));
        }

        return candidates;
    }

    private void appendFromArtistWave(GenrePageRenderContext rc,
                                      List<Parent> out,
                                      Set<Long> seen,
                                      List<Map.Entry<Long, String>> artists) {
        if (artists == null || artists.isEmpty()) return;

        List<CompletableFuture<ArtistAlbumBatch>> futures = artists.stream()
                .map(entry -> supplyHttpAsync(() -> new ArtistAlbumBatch(
                        entry.getKey(),
                        entry.getValue(),
                        getArray(context.endpoints().artistAlbums(entry.getKey()))
                )).exceptionally(ex -> new ArtistAlbumBatch(entry.getKey(), entry.getValue(), new JsonArray())))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        List<ArtistAlbumBatch> batches = futures.stream()
                .map(future -> future.getNow(null))
                .filter(Objects::nonNull)
                .toList();

        boolean added;
        do {
            added = false;
            for (ArtistAlbumBatch batch : batches) {
                if (!rc.isAlive() || out.size() >= MAX_DISPLAY) return;
                added |= appendNextArtistAlbum(rc, out, seen, batch);
            }
        } while (added && out.size() < MAX_DISPLAY);
    }

    private boolean appendNextArtistAlbum(GenrePageRenderContext rc,
                                          List<Parent> out,
                                          Set<Long> seen,
                                          ArtistAlbumBatch batch) {
        if (batch == null || batch.albums == null) return false;

        while (batch.cursor < batch.albums.size()) {
            JsonElement element = batch.albums.get(batch.cursor++);
            if (!rc.isAlive()) return false;
            if (element == null || !element.isJsonObject()) continue;

            JsonObject album = element.getAsJsonObject();
            ensureArtistObject(album, batch.artistId, batch.artistName);

            int before = out.size();
            appendAlbumCards(rc, out, seen, singletonArray(album), new LinkedHashMap<>(), null);
            return out.size() > before;
        }
        return false;
    }

    private JsonArray singletonArray(JsonObject object) {
        JsonArray array = new JsonArray();
        if (object != null) array.add(object);
        return array;
    }

    private String extractAlbumCoverUrl(JsonObject obj) {
        try {
            return DeezerApiService.extractHighResolutionCoverUrl(obj);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static final class ArtistAlbumBatch {
        private final long artistId;
        private final String artistName;
        private final JsonArray albums;
        private int cursor;

        private ArtistAlbumBatch(long artistId, String artistName, JsonArray albums) {
            this.artistId = artistId;
            this.artistName = artistName;
            this.albums = albums == null ? new JsonArray() : albums;
        }
    }
}
