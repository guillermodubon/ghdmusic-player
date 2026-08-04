package io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.context;

import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.repositories.ArtistPageDeezerRepository;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.repositories.ArtistPageMemoryRepository;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.common.ScreenRequestScope;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.utils.DeezerEndpoints;

public record ArtistPageContext(
        StartUpService svc,
        ArtistPageMemoryRepository memory,
        ArtistPageDeezerRepository deezer,
        DeezerEndpoints.ArtistPageEndpoints endpoints,
        MusicCardActionManager musicActions,
        ScreenRequestScope requestScope
) {}
