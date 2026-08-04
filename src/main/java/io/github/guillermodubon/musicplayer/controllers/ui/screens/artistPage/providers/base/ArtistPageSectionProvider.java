package io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.providers.base;

import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.context.ArtistPageRenderContext;

import java.io.IOException;

public interface ArtistPageSectionProvider {
    void render(ArtistPageRenderContext renderContext) throws IOException;
    default void dispose() {}
}
