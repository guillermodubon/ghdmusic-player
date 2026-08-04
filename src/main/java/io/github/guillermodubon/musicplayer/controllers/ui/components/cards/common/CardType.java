package io.github.guillermodubon.musicplayer.controllers.ui.components.cards.common;

public enum CardType {
    ARTIST("/io/github/guillermodubon/musicplayer/Views/components/cards/artist/artist-card.fxml"),
    BIG_FEATURED_MUSIC("/io/github/guillermodubon/musicplayer/Views/components/cards/bigFeaturedMusic/big-featured-music-card.fxml"),
    MUSIC("/io/github/guillermodubon/musicplayer/Views/components/cards/music/music-card.fxml"),
    RECENTLY_PLAYED("/io/github/guillermodubon/musicplayer/Views/components/cards/recentlyPlayed/recently-played-card.fxml"),
    SEARCH_RESULT_ARTIST("/io/github/guillermodubon/musicplayer/Views/components/cards/searchResultsArtists/search-result-artist-card.fxml"),
    SEARCH_RESULT_MUSIC("/io/github/guillermodubon/musicplayer/Views/components/cards/searchResultMusic/search-result-music-card.fxml"),
    GENRE("/io/github/guillermodubon/musicplayer/Views/components/cards/genre/GenreCard.fxml");

    private final String fxmlPath;

    CardType(String fxmlPath) {
        this.fxmlPath = fxmlPath;
    }

    public String getFxmlPath() {
        return fxmlPath;
    }
}
