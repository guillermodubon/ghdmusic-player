package io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.context;

import io.github.guillermodubon.musicplayer.models.Artist;

import java.util.function.Supplier;

public record ArtistPageRenderContext(
        Artist artist,
        ArtistPageContext context,
        ArtistPageSharedState shared,
        int generation,
        Supplier<Boolean> alive
) {
    public boolean isAlive() {
        return alive != null && alive.get();
    }
}