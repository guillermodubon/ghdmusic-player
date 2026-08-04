package io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data;

import javafx.scene.image.Image;

import java.util.function.Consumer;

public record RecentlyPlayedCardData(
        String id,
        Image cover,
        String title,
        Consumer<String> onPlay
) {}
