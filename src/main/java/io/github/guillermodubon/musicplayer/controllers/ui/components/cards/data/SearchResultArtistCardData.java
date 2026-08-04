package io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data;

import io.github.guillermodubon.musicplayer.models.Artist;

import java.util.function.Consumer;

public record SearchResultArtistCardData(Artist artist, Consumer<Artist> onClick) {}
