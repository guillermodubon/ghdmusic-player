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
import java.util.*;
import java.util.concurrent.CompletableFuture;


public final class GenreTopTracksSectionProvider extends BaseGenrePageSectionProvider {

    public GenreTopTracksSectionProvider(GenrePageContext context) {
        super(context);
    }

    @Override
    public CompletableFuture<Void> render(VBox container, GenrePageRenderContext renderContext) {
        if (container == null || renderContext == null) return CompletableFuture.completedFuture(null);

        VBox section = sectionBlock();
        container.getChildren().add(section);

        CompletableFuture<Void> completion = new CompletableFuture<>();

        supplyAsync(() -> loadTopTracksCards(renderContext))
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
                            sectionTitle("Top " + renderContext.genreName() + " songs"),
                            fp
                    );
                    renderContext.notifyNonLibrarySectionLoaded();
                    completion.complete(null);
                }));
        return completion;
    }

    private List<Parent> loadTopTracksCards(GenrePageRenderContext rc) {
        List<Parent> out = new ArrayList<>();
        if (context.endpoints() == null) return out;

        Set<Long> seen = new LinkedHashSet<>();
        LinkedHashMap<Long, String> chartArtists = new LinkedHashMap<>();

        JsonArray chartTracks = getArray(context.endpoints().genreTracks(rc.genreId(), MAX_DISPLAY));
        Map<Long, JsonObject> trackDetails = loadArtistDetails(
                chartTracks,
                trackId -> context.endpoints().trackById(trackId)
        );
        appendTrackCards(rc, out, seen, chartTracks, chartArtists, trackDetails);
        rc.shared().setTopArtistCandidates(chartArtists);

        if (out.size() < MAX_DISPLAY) {
            LinkedHashMap<Long, String> fallbackArtists = libraryArtistCandidates(rc);
            if (fallbackArtists.isEmpty()) fallbackArtists.putAll(chartArtists);
            completeFromArtists(rc, out, seen, fallbackArtists);
        }

        return out.size() <= MAX_DISPLAY ? out : out.subList(0, MAX_DISPLAY);
    }

    private void appendTrackCards(GenrePageRenderContext rc,
                                  List<Parent> out,
                                  Set<Long> seen,
                                  JsonArray arr,
                                  LinkedHashMap<Long, String> artistCandidates,
                                  Map<Long, JsonObject> artistDetails) {
        if (arr == null) return;

        for (JsonElement el : arr) {
            if (!rc.isAlive() || out.size() >= MAX_DISPLAY) break;
            if (!el.isJsonObject()) continue;

            JsonObject obj = el.getAsJsonObject();
            addArtistCandidates(artistCandidates, obj);
            long id = GenreDetailsControllerUtils.safeGetLong(obj, "id", -1L);
            if (id <= 0 || !seen.add(id)) continue;

            String title = GenreDetailsControllerUtils.safeGetString(obj, "title", "Unknown");
            List<String> artistNames = artistDetails == null
                    ? resolveTrackArtistNames(id, obj)
                    : resolveTrackArtistNames(obj, artistDetails.get(id));

            String coverUrl = extractTrackCoverUrl(obj);

            try {
                Parent card = CardFactory.createMusicCard(new MusicCardData(
                        String.valueOf(id),
                        coverUrl == null || coverUrl.isBlank() ? defaultCover() : MediaImageResolver.remoteCardImage(coverUrl),
                        title,
                        artistNames,
                        context.musicActions().songClick(null),
                        context.musicActions().artistNameClick(null)
                ));

                card.getProperties().put("id", id);
                card.getProperties().put("trackId", id);
                card.getProperties().put("type", "single");

                List<Long> artistIds = GenreDetailsControllerUtils.extractArtistIdsFromResource(obj);
                if (!artistIds.isEmpty()) {
                    card.getProperties().put("artistIds", artistIds);
                    rc.shared().topArtistIds().addAll(artistIds);
                }

                if (coverUrl != null && !coverUrl.isBlank()) {
                    card.getProperties().put("coverUrl", coverUrl);
                }

                out.add(card);
            } catch (Exception ignored) {
            }
        }
    }

    private void completeFromArtists(GenrePageRenderContext rc,
                                     List<Parent> out,
                                     Set<Long> seen,
                                     LinkedHashMap<Long, String> artistCandidates) {
        if (artistCandidates == null || artistCandidates.isEmpty()) return;

        int missing = MAX_DISPLAY - out.size();
        if (missing <= 0) return;

        List<Map.Entry<Long, String>> artists = artistCandidates.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getKey() > 0)
                .limit(Math.max(1, missing))
                .toList();
        if (artists.isEmpty()) return;

        int fetchLimit = Math.min(MAX_DISPLAY, Math.max(1, missing + 2));
        List<CompletableFuture<ArtistTrackBatch>> futures = artists.stream()
                .map(entry -> supplyHttpAsync(() -> new ArtistTrackBatch(
                        entry.getKey(),
                        entry.getValue(),
                        getArray(context.endpoints().artistTopTracks(entry.getKey(), fetchLimit))
                )).exceptionally(ex -> new ArtistTrackBatch(entry.getKey(), entry.getValue(), new JsonArray())))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        List<ArtistTrackBatch> batches = futures.stream()
                .map(future -> future.getNow(null))
                .filter(Objects::nonNull)
                .toList();

        boolean added;
        do {
            added = false;
            for (ArtistTrackBatch batch : batches) {
                if (!rc.isAlive() || out.size() >= MAX_DISPLAY) return;
                added |= appendNextArtistTrack(rc, out, seen, batch);
            }
        } while (added && out.size() < MAX_DISPLAY);
    }

    private boolean appendNextArtistTrack(GenrePageRenderContext rc,
                                          List<Parent> out,
                                          Set<Long> seen,
                                          ArtistTrackBatch batch) {
        if (batch == null || batch.tracks == null) return false;

        while (batch.cursor < batch.tracks.size()) {
            JsonElement element = batch.tracks.get(batch.cursor++);
            if (!rc.isAlive()) return false;
            if (element == null || !element.isJsonObject()) continue;

            JsonObject track = element.getAsJsonObject();
            ensureArtistObject(track, batch.artistId, batch.artistName);

            int before = out.size();
            appendTrackCards(rc, out, seen, singletonArray(track), new LinkedHashMap<>(), null);
            return out.size() > before;
        }
        return false;
    }

    private JsonArray singletonArray(JsonObject object) {
        JsonArray array = new JsonArray();
        if (object != null) array.add(object);
        return array;
    }

    private String extractTrackCoverUrl(JsonObject obj) {
        try {
            if (obj != null && obj.has("album") && obj.get("album").isJsonObject()) {
                JsonObject album = obj.getAsJsonObject("album");
                String cover = DeezerApiService.extractHighResolutionCoverUrl(album);
                if (cover != null && !cover.isBlank()) return cover;
            }
            return DeezerApiService.extractHighResolutionCoverUrl(obj);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static final class ArtistTrackBatch {
        private final long artistId;
        private final String artistName;
        private final JsonArray tracks;
        private int cursor;

        private ArtistTrackBatch(long artistId, String artistName, JsonArray tracks) {
            this.artistId = artistId;
            this.artistName = artistName;
            this.tracks = tracks == null ? new JsonArray() : tracks;
        }
    }
}
