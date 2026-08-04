package io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.context;

import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.repositories.SearchResultsPageDeezerRepository;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.repositories.SearchResultsPageMemoryRepository;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.common.ScreenRequestScope;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.ArtistCardActionManager;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.utils.DeezerEndpoints;

public record SearchResultsPageContext(
        StartUpService svc,
        SearchResultsPageMemoryRepository memory,
        SearchResultsPageDeezerRepository deezer,
        DeezerEndpoints.SearchResultsEndpoints endpoints,
        MusicCardActionManager musicActions,
        ArtistCardActionManager artistActions,
        ScreenRequestScope requestScope
) {}
