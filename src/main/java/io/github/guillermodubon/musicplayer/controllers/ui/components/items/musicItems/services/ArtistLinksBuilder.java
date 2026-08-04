package io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.services;

import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import io.github.guillermodubon.musicplayer.utils.NavigationHelper;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.utils.ArtistIdentity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class ArtistLinksBuilder {

    public String formatArtists(List<Artist> artists) {
        if (artists == null || artists.isEmpty()) return "";

        return artists.stream()
                .filter(Objects::nonNull)
                .map(Artist::getName)
                .filter(Objects::nonNull)
                .filter(n -> !n.isBlank())
                .collect(Collectors.joining(", "));
    }
}
