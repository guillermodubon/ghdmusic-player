package io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.providers.common;

import javafx.scene.image.Image;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.CardArtistNameResolver;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.MusicCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.context.ArtistPageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.providers.base.BaseArtistPageSectionProvider.CardRequest;
import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds release cards consistently for the independent albums and singles sections. */
public final class ArtistReleaseCardFactory {
    private ArtistReleaseCardFactory() {
    }

    public static Map<Long, Album> localAlbumsById(List<Album> albums) {
        Map<Long, Album> byId = new LinkedHashMap<>();
        if (albums == null) return byId;

        for (Album album : albums) {
            if (album == null || album.getAlbumID() <= 0) continue;
            byId.putIfAbsent(album.getAlbumID(), album);
        }
        return byId;
    }

    public static CardRequest albumCard(ArtistReleaseCatalog.Release release,
                                        Album localAlbum,
                                        ArtistPageContext context,
                                        Image fallbackCover) {
        if (release == null || context == null) return null;

        if (localAlbum != null) {
            Image localCover = MediaImageResolver.musicCardAlbumCover(localAlbum);
            return new CardRequest(
                    new MusicCardData(
                            String.valueOf(release.id()),
                            localCover == null ? fallbackCover : localCover,
                            hasText(localAlbum.getName()) ? localAlbum.getName() : release.title(),
                            CardArtistNameResolver.fromAlbum(localAlbum),
                            context.musicActions().albumClick(null),
                            context.musicActions().artistNameClick(null),
                            true
                    ),
                    localCover == null ? release.coverUrl() : null
            );
        }

        return new CardRequest(
                new MusicCardData(
                        String.valueOf(release.id()),
                        fallbackCover,
                        release.title(),
                        release.artists(),
                        id -> context.musicActions().albumClick(null).accept(id),
                        name -> context.musicActions().artistNameClick(null).accept(name),
                        true
                ),
                release.coverUrl()
        );
    }

    public static CardRequest singleCard(ArtistReleaseCatalog.Release release,
                                         Album localAlbum,
                                         ArtistPageContext context,
                                         Image fallbackCover) {
        if (release == null || context == null) return null;

        Song localSong = firstLocalSong(localAlbum);
        if (localSong != null) {
            Image localCover = MediaImageResolver.musicCardSongCover(localSong);
            return new CardRequest(
                    new MusicCardData(
                            String.valueOf(localSong.getSongID()),
                            localCover == null ? fallbackCover : localCover,
                            localSong.getTitle(),
                            CardArtistNameResolver.fromSingle(localSong),
                            context.musicActions().songClick(null),
                            context.musicActions().artistNameClick(null),
                            true
                    ),
                    localCover == null ? release.coverUrl() : null
            );
        }

        if (localAlbum != null) {
            Image localCover = MediaImageResolver.musicCardAlbumCover(localAlbum);
            return new CardRequest(
                    new MusicCardData(
                            String.valueOf(release.id()),
                            localCover == null ? fallbackCover : localCover,
                            hasText(localAlbum.getName()) ? localAlbum.getName() : release.title(),
                            CardArtistNameResolver.fromAlbum(localAlbum),
                            id -> context.musicActions().playFirstTrackFromAlbumAsSingle(id, null),
                            context.musicActions().artistNameClick(null),
                            true
                    ),
                    localCover == null ? release.coverUrl() : null
            );
        }

        return new CardRequest(
                new MusicCardData(
                        String.valueOf(release.id()),
                        fallbackCover,
                        release.title(),
                        release.artists(),
                        id -> context.musicActions().playFirstTrackFromAlbumAsSingle(id, null),
                        name -> context.musicActions().artistNameClick(null).accept(name),
                        true
                ),
                release.coverUrl()
        );
    }

    private static Song firstLocalSong(Album album) {
        if (album == null || album.getSongList() == null) return null;
        return album.getSongList().stream()
                .filter(Objects::nonNull)
                .filter(song -> song.getSongID() > 0)
                .findFirst()
                .orElse(null);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
