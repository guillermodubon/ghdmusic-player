package io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.context;

import io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.common.GenrePageSharedState;

import java.util.function.Supplier;

public record GenrePageRenderContext(
        int genreId,
        String genreName,
        GenrePageContext context,
        GenrePageSharedState shared,
        int generation,
        Supplier<Boolean> alive,
        Runnable onNonLibrarySectionLoaded
) {
    public boolean isAlive() {
        return alive != null && alive.get();
    }

    public void notifyNonLibrarySectionLoaded() {
        if (isAlive() && onNonLibrarySectionLoaded != null) {
            onNonLibrarySectionLoaded.run();
        }
    }
}
