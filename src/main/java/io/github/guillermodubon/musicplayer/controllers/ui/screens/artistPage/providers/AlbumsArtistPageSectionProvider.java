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

/** Loads albums independently from singles so a slow release type cannot block the other section. */
public final class AlbumsArtistPageSectionProvider extends BaseArtistPageSectionProvider {
    private static final int MAX_CARDS = 10;

    private final ArtistPageController.UiBindings ui;

    public AlbumsArtistPageSectionProvider(ArtistPageContext context, ArtistPageController.UiBindings ui) {
        super(context);
        this.ui = ui;
    }

    @Override
    public void render(ArtistPageRenderContext renderContext) {
        if (!isCurrent(renderContext) || ui == null || renderContext.artist() == null) return;

        clearFlow(ui.albumsFlow());
        supplyAsync(() -> buildCards(renderContext)).whenComplete((cards, error) ->
                Platform.runLater(() -> {
                    if (!isCurrent(renderContext)) return;
                    renderFlowCards(renderContext, ui.albumsTitle(), ui.albumsFlow(), cards);
                })
        );
    }

    private List<CardRequest> buildCards(ArtistPageRenderContext renderContext) {
        List<ArtistReleaseCatalog.Release> releases = ArtistReleaseCatalog.releases(service, renderContext);
        Map<Long, Album> localById = ArtistReleaseCardFactory.localAlbumsById(service.snapshotAlbums());
        List<CardRequest> cards = new ArrayList<>(MAX_CARDS);

        for (ArtistReleaseCatalog.Release release : releases) {
            if (!isCurrent(renderContext)) return List.of();
            if (release.single()) continue;

            CardRequest card = ArtistReleaseCardFactory.albumCard(
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
