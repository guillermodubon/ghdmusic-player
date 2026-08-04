package io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.searchDropdown.context;

import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.searchDropdown.repositories.SearchDropdownDeezerRepository;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.ArtistCardActionManager;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.utils.DeezerEndpoints;

public record SearchDropdownContext(
        StartUpService svc,
        SearchDropdownDeezerRepository deezer,
        DeezerEndpoints.SearchDropdownEndpoints endpoints,
        MusicCardActionManager musicActions,
        ArtistCardActionManager artistActions
) {}
