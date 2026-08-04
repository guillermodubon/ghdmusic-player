package io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.providers.common;

import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.ArtistPageController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.context.ArtistPageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.context.ArtistPageRenderContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.providers.AlbumsArtistPageSectionProvider;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.providers.LocalArtistPageSectionProvider;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.providers.PlaylistsArtistPageSectionProvider;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.providers.SinglesArtistPageSectionProvider;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.providers.TopTracksArtistPageSectionProvider;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.providers.base.ArtistPageSectionProvider;

import java.io.IOException;
import java.util.List;

public class ArtistPageSectionRegistry {

    private final List<ArtistPageSectionProvider> providers;
    private final ArtistPageController.UiBindings ui;

    public ArtistPageSectionRegistry(ArtistPageContext context, ArtistPageController.UiBindings ui) {
        this.ui = ui;
        this.providers = List.of(
                new LocalArtistPageSectionProvider(context, ui),
                new TopTracksArtistPageSectionProvider(context, ui),
                new AlbumsArtistPageSectionProvider(context, ui),
                new SinglesArtistPageSectionProvider(context, ui),
                new PlaylistsArtistPageSectionProvider(context, ui)
        );
    }

    public void renderAll(ArtistPageRenderContext renderContext) throws IOException {
        for (ArtistPageSectionProvider provider : providers) {
            try {
                provider.render(renderContext);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    public void dispose() {
        for (ArtistPageSectionProvider provider : providers) {
            try { provider.dispose(); } catch (Exception ignored) {}
        }
    }
}
