package io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data;

import javafx.scene.image.Image;

import java.util.List;
import java.util.function.Consumer;

public record SearchResultMusicCardData(
        String id,
        Image cover,
        String title,
        String type,
        List<String> artists,
        Consumer<String> onPlay,
        Consumer<String> onArtistClick
) {}
