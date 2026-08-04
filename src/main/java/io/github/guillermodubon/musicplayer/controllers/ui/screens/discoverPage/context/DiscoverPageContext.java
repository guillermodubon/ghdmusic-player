package io.github.guillermodubon.musicplayer.controllers.ui.screens.discoverPage.context;

import io.github.guillermodubon.musicplayer.controllers.ui.screens.discoverPage.repositories.DiscoverPageDeezerRepository;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.discoverPage.repositories.DiscoverPageMemoryRepository;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.common.ScreenRequestScope;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.ArtistCardActionManager;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.GenreCardActionManager;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.utils.DeezerEndpoints;

public record DiscoverPageContext(
        StartUpService svc,
        DiscoverPageMemoryRepository memory,
        DiscoverPageDeezerRepository deezer,
        DeezerEndpoints.DiscoverEndpoints endpoints,
        MusicCardActionManager musicActions,
        ArtistCardActionManager artistActions,
        GenreCardActionManager genreActions,
        ScreenRequestScope requestScope
) {}
