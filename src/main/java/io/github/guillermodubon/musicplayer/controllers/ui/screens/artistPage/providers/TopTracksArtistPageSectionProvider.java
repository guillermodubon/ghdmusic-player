package io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.providers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.CardArtistNameResolver;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.MusicCardData;
import io.github.guillermodubon.musicplayer.utils.MusicCardHelper;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.ArtistPageController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.context.ArtistPageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.context.ArtistPageRenderContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.providers.base.BaseArtistPageSectionProvider;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class TopTracksArtistPageSectionProvider extends BaseArtistPageSectionProvider {

    private final ArtistPageController.UiBindings ui;

    public TopTracksArtistPageSectionProvider(ArtistPageContext context, ArtistPageController.UiBindings ui) {
        super(context);
        this.ui = ui;
    }

    @Override
    public void render(ArtistPageRenderContext rc) {
        if (!isCurrent(rc) || ui == null || rc.artist() == null) return;

        clearFlow(ui.topTracksFlow());

        supplyAsync(() -> buildTopTrackCards(rc))
                .whenComplete((cards, th) -> Platform.runLater(() -> {
                    if (!isCurrent(rc)) return;
                    renderFlowCards(rc, ui.topTracksTitle(), ui.topTracksFlow(), cards);
                }));
    }

    private List<CardRequest> buildTopTrackCards(ArtistPageRenderContext rc) {
        List<CardRequest> cards = new ArrayList<>();

        try {
            long artistId = service.resolveArtistId(rc.artist());
            if (artistId <= 0) return cards;

            JsonObject topObj = service.topTracksJson(artistId);
            if (topObj == null || !topObj.has("data") || !topObj.get("data").isJsonArray()
                    || topObj.getAsJsonArray("data").isEmpty()) {
                if (rc.artist().getArtistID() > 0) return cards;
                topObj = service.searchTracksJson(rc.artist().getName());
            }
            if (topObj == null || !topObj.has("data") || !topObj.get("data").isJsonArray()) return cards;

            Map<Long, Song> localSongById = service.snapshotSongs().stream()
                    .filter(s -> s != null && s.getSongID() > 0)
                    .collect(Collectors.toMap(Song::getSongID, s -> s, (a, b) -> a));

            for (JsonElement e : topObj.getAsJsonArray("data")) {
                if (!isCurrent(rc)) return List.of();
                if (cards.size() >= 10) break;
                if (!e.isJsonObject()) continue;

                JsonObject t = e.getAsJsonObject();
                long tid = DeezerApiService.safeGetLong(t, "id", -1L);
                if (tid <= 0) continue;

                String cover = t.has("album") && t.get("album").isJsonObject()
                        ? DeezerApiService.extractHighResolutionCoverUrl(t.getAsJsonObject("album"))
                        : null;

                if (localSongById.containsKey(tid)) {
                    Song local = localSongById.get(tid);
                    var localCover = MediaImageResolver.musicCardSongCover(local);
                    cards.add(new CardRequest(new MusicCardData(
                                String.valueOf(local.getSongID()),
                                localCover == null ? defaultCover() : localCover,
                                local.getTitle(),
                                CardArtistNameResolver.fromSong(local),
                                context.musicActions().songClick(null),
                                context.musicActions().artistNameClick(null),
                                true
                        ), localCover == null ? cover : null));
                    continue;
                }

                String title = DeezerApiService.extractTitle(t);

                List<String> creators = resolveTrackArtistNames(tid, t, rc.artist().getName());

                cards.add(new CardRequest(
                        new MusicCardData(
                            String.valueOf(tid),
                            defaultCover(),
                            title,
                            creators,
                            id -> context.musicActions().songClick(null).accept(id),
                            name -> context.musicActions().artistNameClick(null).accept(name),
                            true
                        ),
                        cover
                ));
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        return cards;
    }

    private List<String> resolveTrackArtistNames(long trackId, JsonObject trackJson, String fallbackArtistName) {
        LinkedHashSet<String> names = new LinkedHashSet<>(MusicCardHelper.extractArtistNamesFromTrackJson(trackJson));
        if (names.size() <= 1 && trackId > 0) {
            try {
                JsonObject detail = service.trackByIdJson(trackId);
                if (detail != null) names.addAll(MusicCardHelper.extractArtistNamesFromTrackJson(detail));
            } catch (Exception ignored) {
            }
        }
        if (names.isEmpty() && fallbackArtistName != null && !fallbackArtistName.isBlank()) {
            names.add(fallbackArtistName.trim());
        }
        return names.isEmpty() ? List.of("Unknown") : List.copyOf(names);
    }
}
