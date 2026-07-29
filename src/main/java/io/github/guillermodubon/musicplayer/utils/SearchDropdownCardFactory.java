package io.github.guillermodubon.musicplayer.utils;

import javafx.scene.Parent;
import javafx.scene.image.Image;
import io.github.guillermodubon.musicplayer.utils.MusicCardHelper;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.MusicCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.SearchResultArtistCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.SearchResultMusicCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.factory.CardFactory;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.ArtistCardActionManager;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public final class SearchDropdownCardFactory {

    private SearchDropdownCardFactory() {}

    public static Image defaultCover() {
        return MusicCardHelper.loadDefaultCover();
    }

    public static Parent createArtistCard(Artist artist,
                                          ArtistCardActionManager artistActions) throws IOException {
        if (artist == null) throw new IllegalArgumentException("artist == null");
        if (artistActions == null) throw new IllegalArgumentException("artistActions == null");

        return CardFactory.createSearchResultArtistCard(
                new SearchResultArtistCardData(
                        artist,
                        artistActions.artistClick(null)
                )
        );
    }

    public static Parent createMusicCard(String id,
                                         String coverUrl,
                                         String title,
                                         String type,
                                         List<String> creators,
                                         MusicCardActionManager musicActions,
                                         ArtistCardActionManager artistActions) throws IOException {
        if (musicActions == null) throw new IllegalArgumentException("musicActions == null");
        if (artistActions == null) throw new IllegalArgumentException("artistActions == null");

        Image cover = (coverUrl == null || coverUrl.isBlank())
                ? defaultCover()
                : MediaImageResolver.remoteImage(coverUrl, 96, 96);
        if (cover == null) cover = defaultCover();

        String normalizedType = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        List<String> displayCreators = "playlist".equals(normalizedType)
                ? MusicCardData.playlistCreators(creators)
                : creators == null || creators.isEmpty() ? List.of("Unknown") : creators;

        return CardFactory.createSearchResultMusicCard(
                new SearchResultMusicCardData(
                        String.valueOf(id),
                        cover,
                        title == null ? "Unknown" : title,
                        type == null ? "Unknown" : type,
                        displayCreators,
                        clickActionForType(type, musicActions),
                        musicActions.artistNameClick(null)
                )
        );
    }

    private static Consumer<String> clickActionForType(String type, MusicCardActionManager musicActions) {
        String normalized = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "album" -> musicActions.albumClick(null);
            case "playlist" -> musicActions.playlistClick(null);
            default -> musicActions.songClick(null);
        };
    }
}
