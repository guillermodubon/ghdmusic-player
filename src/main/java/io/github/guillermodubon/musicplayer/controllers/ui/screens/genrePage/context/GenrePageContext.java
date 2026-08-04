package io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.context;


import io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.repositories.GenrePageDeezerRepository;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.repositories.GenrePageMemoryRepository;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.common.ScreenRequestScope;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.ArtistCardActionManager;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.utils.DeezerEndpoints;

public record GenrePageContext(
        StartUpService svc,
        GenrePageMemoryRepository memory,
        GenrePageDeezerRepository deezer,
        DeezerEndpoints.GenreDetailsControllerEndpoints endpoints,
        MusicCardActionManager musicActions,
        ArtistCardActionManager artistActions,
        ScreenRequestScope requestScope
) {}
