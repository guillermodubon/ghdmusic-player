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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class GenrePlaylistsSectionProvider extends BaseGenrePageSectionProvider {

    public GenrePlaylistsSectionProvider(GenrePageContext context) {
        super(context);
    }

    @Override
    public CompletableFuture<Void> render(VBox container, GenrePageRenderContext renderContext) {
        if (container == null || renderContext == null) return CompletableFuture.completedFuture(null);

        VBox section = sectionBlock();
        container.getChildren().add(section);

        CompletableFuture<Void> completion = new CompletableFuture<>();

        supplyAsync(() -> loadPlaylistsCards(renderContext))
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
                            sectionTitle(renderContext.genreName() + " Playlists"),
                            fp
                    );
                    renderContext.notifyNonLibrarySectionLoaded();
                    completion.complete(null);
                }));
        return completion;
    }

    private List<Parent> loadPlaylistsCards(GenrePageRenderContext rc) {
        List<Parent> out = new ArrayList<>();
        if (context.endpoints() == null) return out;

        String query = encodeQuery((rc.genreName() == null ? "" : rc.genreName()) + " songs");
        JsonArray arr = getArray(context.endpoints().searchPlaylists(query));

        Set<Long> seen = new LinkedHashSet<>();

        for (JsonElement el : arr) {
            if (!rc.isAlive() || out.size() >= MAX_DISPLAY) break;
            if (!el.isJsonObject()) continue;

            JsonObject obj = el.getAsJsonObject();
            long id = GenreDetailsControllerUtils.safeGetLong(obj, "id", -1L);
            if (id <= 0 || !seen.add(id)) continue;

            String title = GenreDetailsControllerUtils.safeGetString(obj, "title", "Unknown");
            List<String> artistNames = List.of(MusicCardData.REMOTE_PLAYLIST_CREATOR_LABEL);

            String coverUrl = DeezerApiService.extractHighResolutionPictureUrl(obj);

            try {
                Parent card = CardFactory.createMusicCard(MusicCardData.playlist(
                        String.valueOf(id),
                        coverUrl == null || coverUrl.isBlank() ? defaultCover() : MediaImageResolver.remoteCardImage(coverUrl),
                        title,
                        artistNames,
                        context.musicActions().playlistClick(null),
                        context.musicActions().artistNameClick(null)
                ));

                card.getProperties().put("id", id);
                card.getProperties().put("type", "playlist");
                if (coverUrl != null && !coverUrl.isBlank()) card.getProperties().put("coverUrl", coverUrl);

                out.add(card);
            } catch (Exception ignored) {}
        }

        return out;
    }
}
