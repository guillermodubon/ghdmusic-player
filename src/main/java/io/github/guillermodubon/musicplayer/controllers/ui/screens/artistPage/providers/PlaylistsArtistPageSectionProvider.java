package io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.providers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.MusicCardData;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.ArtistPageController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.context.ArtistPageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.context.ArtistPageRenderContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.providers.base.BaseArtistPageSectionProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PlaylistsArtistPageSectionProvider extends BaseArtistPageSectionProvider {

    private final ArtistPageController.UiBindings ui;

    public PlaylistsArtistPageSectionProvider(ArtistPageContext context, ArtistPageController.UiBindings ui) {
        super(context);
        this.ui = ui;
    }

    @Override
    public void render(ArtistPageRenderContext rc) {
        if (!isCurrent(rc) || ui == null || rc.artist() == null) return;

        clearFlow(ui.playlistsFlow());

        supplyAsync(() -> buildPlaylistCards(rc))
                .whenComplete((cards, th) -> Platform.runLater(() -> {
                    if (!isCurrent(rc)) return;
                    renderFlowCards(rc, ui.playlistsTitle(), ui.playlistsFlow(), cards);
                }));
    }

    private List<CardRequest> buildPlaylistCards(ArtistPageRenderContext rc) {
        List<CardRequest> cards = new ArrayList<>();
        String artistName = rc.artist().getName();
        if (artistName == null || artistName.isBlank()) return cards;

        try {
            JsonObject res = service.searchPlaylistsJson(artistName);
            if (res == null || !res.has("data") || !res.get("data").isJsonArray()) {
                res = service.searchPlaylistsJson("\"" + artistName + "\"");
            }
            if (res == null || !res.has("data") || !res.get("data").isJsonArray()) return cards;

            String nameLower = artistName.trim().toLowerCase(Locale.ROOT);
            int limit = 6;

            for (JsonElement e : res.getAsJsonArray("data")) {
                if (!isCurrent(rc)) return List.of();
                if (cards.size() >= limit) break;
                if (!e.isJsonObject()) continue;

                JsonObject p = e.getAsJsonObject();
                long pid = DeezerApiService.safeGetLong(p, "id", -1L);
                if (pid <= 0) continue;

                String title = DeezerApiService.extractTitle(p);
                String cover = DeezerApiService.extractHighResolutionPictureUrl(p);

                boolean keep = title != null && title.toLowerCase(Locale.ROOT).contains(nameLower);
                if (!keep && p.has("description") && !p.get("description").isJsonNull()) {
                    String desc = p.get("description").getAsString();
                    keep = desc != null && desc.toLowerCase(Locale.ROOT).contains(nameLower);
                }
                if (!keep) continue;

                List<String> creators = List.of(MusicCardData.REMOTE_PLAYLIST_CREATOR_LABEL);

                cards.add(new CardRequest(
                        MusicCardData.playlist(
                            String.valueOf(pid),
                            defaultCover(),
                            title == null ? "Playlist" : title,
                            creators,
                            id -> context.musicActions().playlistClick(null).accept(id),
                            name -> { }
                        ),
                        cover
                ));
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        return cards;
    }
}
