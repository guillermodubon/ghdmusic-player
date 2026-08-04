package io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers;

import javafx.scene.Node;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.util.function.Consumer;

public class GenreCardActionManager {

    private final GenrePlaybackCoordinator genreCoordinator;

    public GenreCardActionManager(StartUpService svc) {
        this.genreCoordinator = new GenrePlaybackCoordinator(svc);
    }

    public Consumer<Integer> genreClick(String genreName, Node probe) {
        return id -> genreCoordinator.handle(id, genreName, probe);
    }
}
