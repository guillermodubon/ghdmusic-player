package io.github.guillermodubon.musicplayer.controllers.ui.screens.discoverPage.providers.base;

import javafx.scene.layout.VBox;

public interface DiscoverPageSectionProvider {
    void render(VBox container);
    default void dispose(){}
}
