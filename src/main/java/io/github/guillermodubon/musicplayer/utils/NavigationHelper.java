package io.github.guillermodubon.musicplayer.utils;

import javafx.scene.layout.BorderPane;
import io.github.guillermodubon.musicplayer.services.navigation.PlayerMenuNavigator;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.ArtistOpenCoordinator;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

public final class NavigationHelper {
    public static void showArtistScreen(Artist artist, StartUpService svc, BorderPane root) {
        if (artist == null || root == null || svc == null) return;

        PlayerMenuNavigator navigator = new PlayerMenuNavigator(svc);
        ArtistOpenCoordinator artistCoordinator = new ArtistOpenCoordinator(svc, navigator);
        artistCoordinator.handle(artist, root);
    }
}
