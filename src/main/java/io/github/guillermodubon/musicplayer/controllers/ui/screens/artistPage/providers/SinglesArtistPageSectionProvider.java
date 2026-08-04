package io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.providers;

import javafx.application.Platform;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.ArtistPageController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.context.ArtistPageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.context.ArtistPageRenderContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.providers.base.BaseArtistPageSectionProvider;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.providers.common.ArtistReleaseCardFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.providers.common.ArtistReleaseCatalog;
import io.github.guillermodubon.musicplayer.models.Album;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Loads artist singles independently and uses a targeted fallback if Deezer's album feed omits them. */
public final class SinglesArtistPageSectionProvider extends BaseArtistPageSectionProvider {
    private static final int MAX_CARDS = 10;

    private final ArtistPageController.UiBindings ui;

    public SinglesArtistPageSectionProvider(ArtistPageContext context, ArtistPageController.UiBindings ui) {
        super(context);
        this.ui = ui;
    }

    @Override
    public void render(ArtistPageRenderContext renderContext) {
        if (!isCurrent(renderContext) || ui == null || renderContext.artist() == null) return;

        clearFlow(ui.singlesFlow());
        supplyAsync(() -> buildCards(renderContext)).whenComplete((cards, error) ->
                Platform.runLater(() -> {
                    if (!isCurrent(renderContext)) return;
                    renderFlowCards(renderContext, ui.singlesTitle(), ui.singlesFlow(), cards);
                })
        );
    }

    private List<CardRequest> buildCards(ArtistPageRenderContext renderContext) {
        List<ArtistReleaseCatalog.Release> releases = ArtistReleaseCatalog.singles(service, renderContext);
        Map<Long, Album> localById = ArtistReleaseCardFactory.localAlbumsById(service.snapshotAlbums());
        List<CardRequest> cards = new ArrayList<>(MAX_CARDS);

        for (ArtistReleaseCatalog.Release release : releases) {
            if (!isCurrent(renderContext)) return List.of();
            if (!release.single()) continue;

            CardRequest card = ArtistReleaseCardFactory.singleCard(
                    release,
                    localById.get(release.id()),
                    context,
                    defaultCover()
            );
            if (card != null) cards.add(card);
            if (cards.size() >= MAX_CARDS) break;
        }
        return cards;
    }
}
