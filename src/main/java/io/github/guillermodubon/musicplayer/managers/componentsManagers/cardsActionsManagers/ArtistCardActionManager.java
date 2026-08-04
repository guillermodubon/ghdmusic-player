package io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers;

import javafx.scene.Node;
import io.github.guillermodubon.musicplayer.services.navigation.PlayerMenuNavigator;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.util.function.Consumer;

public class ArtistCardActionManager {

    private final ArtistOpenCoordinator artistCoordinator;

    public ArtistCardActionManager(StartUpService svc, PlayerMenuNavigator navigator) {
        this.artistCoordinator = new ArtistOpenCoordinator(svc, navigator);
    }

    public Consumer<Artist> artistClick(Node probe) {
        return artist -> artistCoordinator.handle(artist, probe);
    }
}
