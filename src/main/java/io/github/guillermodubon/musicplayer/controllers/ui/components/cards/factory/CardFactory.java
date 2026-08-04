package io.github.guillermodubon.musicplayer.controllers.ui.components.cards.factory;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.GenreCard;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.*;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.common.CardType;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.*;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

public final class CardFactory {
    private static final ConcurrentMap<CardType, URL> FXML_CACHE = new ConcurrentHashMap<>();

    private CardFactory() {}

    public static Parent createMusicCard(MusicCardData data) throws IOException {
        return load(CardType.MUSIC, MusicCard.class, ctrl -> ctrl.init(data));
    }

    public static Parent createBigFeaturedMusicCard(MusicCardData data) throws IOException {
        return load(CardType.BIG_FEATURED_MUSIC, BigFeaturedMusicCard.class, ctrl -> ctrl.init(data));
    }

    public static Parent createRecentlyPlayedCard(RecentlyPlayedCardData data) throws IOException {
        return load(CardType.RECENTLY_PLAYED, RecentlyPlayedMusicCard.class, ctrl ->
                ctrl.init(data.id(), data.cover(), data.title(), data.onPlay()));
    }

    public static Parent createSearchResultMusicCard(SearchResultMusicCardData data) throws IOException {
        return load(CardType.SEARCH_RESULT_MUSIC, SearchResultMusicCard.class, ctrl -> ctrl.init(data));
    }

    public static Parent createArtistCard(ArtistCardData data) throws IOException {
        return load(CardType.ARTIST, ArtistCard.class, ctrl -> ctrl.init(data.artist(), data.onClick()));
    }

    public static Parent createSearchResultArtistCard(SearchResultArtistCardData data) throws IOException {
        return load(CardType.SEARCH_RESULT_ARTIST, SearchResultArtistCard.class, ctrl ->
                ctrl.init(data.artist(), data.onClick()));
    }

    public static Parent createGenreCard(GenreCardData data) throws IOException {
        return load(CardType.GENRE, GenreCard.class, ctrl -> ctrl.init(data));
    }

    private static <C> Parent load(CardType type, Class<C> controllerClass, Consumer<C> initializer) throws IOException {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(controllerClass, "controllerClass");

        URL fxml = FXML_CACHE.computeIfAbsent(type, key -> CardFactory.class.getResource(key.getFxmlPath()));
        if (fxml == null) {
            throw new IOException("FXML no encontrado para " + type + ": " + type.getFxmlPath());
        }

        FXMLLoader loader = new FXMLLoader(fxml);
        Parent root = loader.load();

        Object controller = loader.getController();
        if (!controllerClass.isInstance(controller)) {
            throw new IOException("El controller cargado no coincide con " + controllerClass.getSimpleName());
        }

        // Providers that progressively enrich a card (for example, a deferred
        // cover image) can update its controller without rebuilding the card.
        root.getProperties().put("controller", controller);

        if (initializer != null) {
            initializer.accept(controllerClass.cast(controller));
        }

        return root;
    }
}


