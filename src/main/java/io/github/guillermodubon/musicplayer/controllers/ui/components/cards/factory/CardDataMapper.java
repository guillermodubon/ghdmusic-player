package io.github.guillermodubon.musicplayer.controllers.ui.components.cards.factory;

import javafx.scene.Node;
import javafx.scene.image.Image;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.*;

import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.ArtistCardActionManager;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.GenreCardActionManager;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Genre;
import io.github.guillermodubon.musicplayer.models.Song;

import java.util.List;

public final class CardDataMapper {

    private final MusicCardActionManager musicActions;
    private final ArtistCardActionManager artistActions;
    private final GenreCardActionManager genreActions;

    public CardDataMapper(MusicCardActionManager musicActions,
                          ArtistCardActionManager artistActions,
                          GenreCardActionManager genreActions) {
        this.musicActions = musicActions;
        this.artistActions = artistActions;
        this.genreActions = genreActions;
    }

    public MusicCardData toMusicCardData(Song song, Image cover, Node probe) {
        List<String> artists = CardArtistNameResolver.fromSong(song);

        return toMusicCardData(
                String.valueOf(song.getSongID()),
                cover,
                song.getTitle(),
                artists,
                probe
        );
    }

    public MusicCardData toMusicCardData(String id,
                                         Image cover,
                                         String title,
                                         List<String> artists,
                                         Node probe) {
        return new MusicCardData(
                id,
                cover,
                title,
                artists == null ? List.of() : artists,
                musicActions.songClick(probe),
                musicActions.artistNameClick(probe)
        );
    }

    public RecentlyPlayedCardData toRecentlyPlayedCardData(Song song, Image cover, Node probe) {
        return new RecentlyPlayedCardData(
                String.valueOf(song.getSongID()),
                cover,
                song.getTitle(),
                musicActions.songClick(probe)
        );
    }

    public SearchResultMusicCardData toSearchResultMusicCardData(
            String id, Image cover, String title, String type, List<String> artists, Node probe) {
        boolean playlist = type != null && "playlist".equalsIgnoreCase(type.trim());
        List<String> displayArtists = playlist
                ? MusicCardData.playlistCreators(artists)
                : artists;

        return new SearchResultMusicCardData(
                id,
                cover,
                title,
                type,
                displayArtists,
                musicActions.songClick(probe),
                musicActions.artistNameClick(probe)
        );
    }

    public ArtistCardData toArtistCardData(Artist artist, Node probe) {
        return new ArtistCardData(
                artist,
                artistActions.artistClick(probe)
        );
    }

    public SearchResultArtistCardData toSearchResultArtistCardData(Artist artist, Node probe) {
        return new SearchResultArtistCardData(
                artist,
                artistActions.artistClick(probe)
        );
    }

    public GenreCardData toGenreCardData(Genre genre, Image coverLocal, String coverUrl, Node probe) {
        if (genreActions == null) {
            throw new IllegalStateException("genreActions no fue configurado para CardDataMapper");
        }

        String genreName = (genre.getName() == null || genre.getName().isBlank())
                ? "Unknown"
                : genre.getName();

        return new GenreCardData(
                genre.getGenreID(),
                genreName,
                coverUrl,
                coverLocal,
                genreActions.genreClick(genreName, probe)
        );
    }
}
