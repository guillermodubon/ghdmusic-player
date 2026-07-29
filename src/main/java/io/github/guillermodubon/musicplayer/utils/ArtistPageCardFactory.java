package io.github.guillermodubon.musicplayer.utils;

import javafx.scene.Parent;
import javafx.scene.image.Image;
import io.github.guillermodubon.musicplayer.utils.MusicCardHelper;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.MusicCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.factory.CardFactory;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

public final class ArtistPageCardFactory {

    private ArtistPageCardFactory() {}

    public static Image defaultCover() {
        return MusicCardHelper.loadDefaultCover();
    }

    public static Parent createCard(String id,
                                    String coverUrl,
                                    String title,
                                    List<String> creators,
                                    Consumer<String> onCardClick,
                                    Consumer<String> onArtistClick,
                                    boolean playlistCard) throws IOException {
        return createCard(id, coverUrl, null, title, creators, onCardClick, onArtistClick, playlistCard);
    }

    public static Parent createCard(String id,
                                    String coverUrl,
                                    Image localCover,
                                    String title,
                                    List<String> creators,
                                    Consumer<String> onCardClick,
                                    Consumer<String> onArtistClick,
                                    boolean playlistCard) throws IOException {
        Image cover = localCover != null
                ? localCover
                : (coverUrl == null || coverUrl.isBlank()
                ? defaultCover()
                : MediaImageResolver.remoteCardImage(coverUrl));
        if (cover == null) cover = defaultCover();
        MusicCardData data = playlistCard
                ? MusicCardData.playlist(
                String.valueOf(id),
                cover,
                title == null ? "Unknown" : title,
                creators,
                onCardClick,
                onArtistClick
        )
                : new MusicCardData(
                String.valueOf(id),
                cover,
                title == null ? "Unknown" : title,
                creators == null || creators.isEmpty() ? List.of("Unknown") : creators,
                onCardClick,
                onArtistClick
        );
        return CardFactory.createMusicCard(data);
    }
}
