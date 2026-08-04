package io.github.guillermodubon.musicplayer.controllers.ui.screens.discoverPage.providers.common;

import javafx.scene.layout.VBox;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.discoverPage.context.DiscoverPageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.discoverPage.providers.GenresSectionProvider;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.discoverPage.providers.LatestReleasesSectionProvider;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.discoverPage.providers.TrendSongsSectionProvider;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.discoverPage.providers.base.DiscoverPageSectionProvider;

import java.util.List;

public class DiscoverPageSectionRegistry {

    private final List<DiscoverPageSectionProvider> providers;

    public DiscoverPageSectionRegistry(DiscoverPageContext context) {
        this.providers = List.of(
                new GenresSectionProvider(context),
                new TrendSongsSectionProvider(context),
                new LatestReleasesSectionProvider(context)
        );
    }

    public void renderAll(VBox container) {
        if (container == null) return;
        container.getChildren().clear();
        for (DiscoverPageSectionProvider provider : providers) {
            provider.render(container);
        }
    }

    public void dispose() {
        for (DiscoverPageSectionProvider p : providers) {
            try { p.dispose(); } catch (Exception ignored) {}
        }
    }
}
