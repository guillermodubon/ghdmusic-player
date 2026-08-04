package io.github.guillermodubon.musicplayer.controllers.ui.screens.discoverPage.providers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.MusicCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.factory.CardFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.discoverPage.context.DiscoverPageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.discoverPage.providers.base.BaseDiscoverPagePageSectionProvider;
import io.github.guillermodubon.musicplayer.utils.MusicCardHelper;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;
import io.github.guillermodubon.musicplayer.utils.DiscoverUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** The Home top-chart logic now lives on Discover, where global trends belong. */
public class TrendSongsSectionProvider extends BaseDiscoverPagePageSectionProvider {

    public TrendSongsSectionProvider(DiscoverPageContext context) {
        super(context);
    }

    @Override
    public void render(VBox container) {
        VBox section = sectionBlock("Trending Songs");
        container.getChildren().add(section);
        int generation = captureRenderGeneration(container);

        supplyAsync(this::loadTopTracks)
                .whenComplete((tracks, error) -> Platform.runLater(() -> {
                    if (!isRenderCurrent(container, generation)) return;
                    if (tracks == null || tracks.isEmpty()) {
                        section.getChildren().setAll(sectionTitle("Trending Songs"), emptyState("No trending songs available"));
                        return;
                    }

                    FlowPane content = createContentFlow(container);
                    section.getChildren().setAll(sectionTitle("Trending Songs"), content);
                    appendNodesInBatches(container, generation, content, tracks, this::createTrackCard, rendered -> {
                        if (rendered == 0) {
                            section.getChildren().setAll(sectionTitle("Trending Songs"), emptyState("No trending songs available"));
                        }
                    });
                }));
    }

    private List<TrackCandidate> loadTopTracks() {
        if (context.endpoints() == null || shouldAbort()) return List.of();
        JsonObject root = getJson(context.endpoints().chartTracks());
        if (root == null || !root.has("data") || !root.get("data").isJsonArray()) return List.of();

        List<TrackCandidate> tracks = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("data")) {
            if (shouldAbort()) return List.of();
            if (!element.isJsonObject()) continue;
            JsonObject track = element.getAsJsonObject();
            long id = DeezerApiService.safeGetLong(track, "id", -1L);
            if (id <= 0) continue;

            List<String> artistNames = resolveTrackArtistNames(id, track);
            String coverUrl = resolveTrackCoverUrl(id, track);
            tracks.add(new TrackCandidate(
                    id,
                    DeezerApiService.extractTitle(track),
                    artistNames,
                    resolveTrackArtistIds(id, track),
                    coverUrl
            ));
            if (tracks.size() >= GLOBAL_MAX) break;
        }
        return tracks;
    }

    private StackPane createTrackCard(TrackCandidate track) {
        if (track == null) return null;
        try {
            Image cover = track.coverUrl() == null || track.coverUrl().isBlank()
                    ? defaultCover()
                    : DiscoverUtils.remoteImage(track.coverUrl());
            StackPane card = (StackPane) CardFactory.createMusicCard(new MusicCardData(
                    String.valueOf(track.id()),
                    MusicCardHelper.coverOrDefault(cover, defaultCover()),
                    track.title() == null || track.title().isBlank() ? "Unknown" : track.title(),
                    track.artistNames(),
                    context.musicActions().songClick(null),
                    context.musicActions().artistNameClick(null)
            ));
            card.getProperties().put("trackId", track.id());
            card.getProperties().put("artistIds", new ArrayList<>(track.artistIds()));
            card.getProperties().put("artistNames", track.artistNames());
            if (track.coverUrl() != null && !track.coverUrl().isBlank()) {
                card.getProperties().put("coverUrl", track.coverUrl());
            }
            return card;
        } catch (IOException ignored) {
            return null;
        }
    }

    private record TrackCandidate(long id,
                                  String title,
                                  List<String> artistNames,
                                  List<Long> artistIds,
                                  String coverUrl) {
        private TrackCandidate {
            artistNames = artistNames == null || artistNames.isEmpty() ? List.of("Unknown") : List.copyOf(artistNames);
            artistIds = artistIds == null ? List.of() : List.copyOf(artistIds);
        }
    }
}
