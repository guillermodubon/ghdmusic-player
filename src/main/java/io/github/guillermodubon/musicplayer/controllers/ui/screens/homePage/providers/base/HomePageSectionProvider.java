package io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.providers.base;

import javafx.scene.layout.VBox;

import java.util.concurrent.CompletableFuture;

public interface HomePageSectionProvider {
    /**
     * Completes after this section has either rendered or decided it has no content.
     * The registry starts providers concurrently and uses this boundary to reveal
     * or hide the provider slot without leaving blank space.
     */
    CompletableFuture<Void> render(VBox container, String filter, long renderId);
}
