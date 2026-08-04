package io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.providers.common;

import javafx.application.Platform;
import javafx.scene.layout.VBox;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.context.GenrePageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.context.GenrePageRenderContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.providers.GenreAlbumsSectionProvider;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.providers.GenreLibrarySectionProvider;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.providers.GenrePlaylistsSectionProvider;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.providers.GenreTopTracksSectionProvider;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.providers.base.GenrePageSectionProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class GenrePageSectionRegistry {

    private final List<GenrePageSectionProvider> providers;

    public GenrePageSectionRegistry(GenrePageContext context) {
        this.providers = List.of(
                new GenreLibrarySectionProvider(context),
                new GenreTopTracksSectionProvider(context),
                new GenreAlbumsSectionProvider(context),
                new GenrePlaylistsSectionProvider(context)
        );
    }

    public void renderAll(VBox container,
                          GenrePageRenderContext renderContext,
                          Consumer<Boolean> completionHandler) {
        if (container == null || renderContext == null || !renderContext.isAlive()) return;
        container.getChildren().clear();

        List<CompletableFuture<Void>> completions = new ArrayList<>();
        for (GenrePageSectionProvider provider : providers) {
            if (!renderContext.isAlive()) return;
            try {
                // Providers are independent and share one cancellable request scope.
                // Starting them together makes each section appear as soon as its data arrives.
                CompletableFuture<Void> completion = provider.render(container, renderContext);
                completions.add(completion == null ? CompletableFuture.completedFuture(null) : completion);
            } catch (Throwable ignored) {
                // A failed endpoint must not prevent the remaining sections from rendering.
                completions.add(CompletableFuture.completedFuture(null));
            }
        }

        CompletableFuture.allOf(completions.toArray(new CompletableFuture[0]))
                .whenComplete((ignored, error) -> {
                    Platform.runLater(() -> {
                        if (completionHandler != null && renderContext.isAlive()) {
                            completionHandler.accept(!container.getChildren().isEmpty());
                        }
                    });
                });
    }

    public void dispose() {
        for (GenrePageSectionProvider provider : providers) {
            try { provider.dispose(); } catch (Exception ignored) {}
        }
    }
}
