package io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.providers.base;

import javafx.scene.layout.VBox;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.context.GenrePageRenderContext;

import java.util.concurrent.CompletableFuture;

public interface GenrePageSectionProvider {
    /**
     * Completes when the section has rendered or has determined that it has no content.
     * The registry starts independent sections concurrently under the current screen scope.
     */
    CompletableFuture<Void> render(VBox container, GenrePageRenderContext context);
    default void dispose() {}
}
