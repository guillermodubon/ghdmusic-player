package io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.context;

import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.repositories.MainMenuDeezerRepository;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.repositories.MainMenuMemoryRepository;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.libraryCatalogListScreens.common.CatalogType;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.common.ScreenRequestScope;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.ArtistCardActionManager;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.utils.DeezerEndpoints;

import java.util.function.Consumer;

// HomePageContext
public record HomePageContext(
        StartUpService svc,
        MainMenuMemoryRepository memory,
        MainMenuDeezerRepository deezer,
        DeezerEndpoints.MainMenuEndpoints endpoints,
        MusicCardActionManager musicActions,
        ArtistCardActionManager artistActions,
        java.util.concurrent.atomic.AtomicLong renderVersion,
        ScreenRequestScope requestScope,
        Consumer<CatalogType> catalogNavigator
) {}
