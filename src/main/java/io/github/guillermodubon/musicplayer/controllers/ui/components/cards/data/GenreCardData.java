package io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data;

import javafx.scene.image.Image;

import java.util.function.Consumer;

public record GenreCardData(
        int genreId,
        String title,
        String coverUrl,
        Image coverLocal,
        Consumer<Integer> onClick
) {}

