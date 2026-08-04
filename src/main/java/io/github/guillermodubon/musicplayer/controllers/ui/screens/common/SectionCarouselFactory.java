package io.github.guillermodubon.musicplayer.controllers.ui.screens.common;

import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import java.util.List;
import io.github.guillermodubon.musicplayer.controllers.ui.components.sections.ResponsiveCardCarousel;

/**
 * Compatibility facade for screens that still use the old factory name.
 * The carousel implementation is owned by the shared UI component.
 */
public final class SectionCarouselFactory {
    private SectionCarouselFactory() {
    }

    public static StackPane createMusicCarousel(List<? extends Node> cards) {
        return ResponsiveCardCarousel.createMusicCarousel(cards);
    }

    public static StackPane createFeaturedCarousel(List<? extends Node> cards) {
        return ResponsiveCardCarousel.createFeaturedCarousel(cards);
    }

}
