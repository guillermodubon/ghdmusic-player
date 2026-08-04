package io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.providers;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.CardArtistNameResolver;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.MusicCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.ArtistPageController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.context.ArtistPageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.context.ArtistPageRenderContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.providers.base.BaseArtistPageSectionProvider;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.common.SectionCarouselFactory;
import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;

import java.sql.SQLException;
import java.util.*;

public class LocalArtistPageSectionProvider extends BaseArtistPageSectionProvider {
    private static final int MAX_LOCAL_CARDS = 16;

    private final ArtistPageController.UiBindings ui;

    public LocalArtistPageSectionProvider(ArtistPageContext context, ArtistPageController.UiBindings ui) {
        super(context);
        this.ui = ui;
    }

    @Override
    public void render(ArtistPageRenderContext rc) {
        if (!isCurrent(rc) || ui == null || rc.artist() == null) return;

        clearFlow(ui.localFlow());
        clearCarouselHost();

        supplyAsync(() -> buildLocalCards(rc))
                .whenComplete((cards, th) -> Platform.runLater(() -> {
                    if (!isCurrent(rc)) return;

                    if (cards != null && !cards.isEmpty()) {
                        List<StackPane> cardNodes = materializeCards(rc, cards);
                        if (cardNodes.isEmpty()) {
                            setVisible(ui.localTitle(), ui.localCarouselHost() != null ? ui.localCarouselHost() : ui.localFlow(), false);
                            return;
                        }
                        if (ui.localCarouselHost() != null) {
                            ui.localCarouselHost().getChildren().setAll(SectionCarouselFactory.createMusicCarousel(cardNodes));
                            setVisible(ui.localTitle(), ui.localCarouselHost(), true);
                        } else if (ui.localFlow() != null) {
                            ui.localFlow().getChildren().setAll(cardNodes);
                            setVisible(ui.localTitle(), ui.localFlow(), true);
                        }
                        if (rc.shared() != null) rc.shared().setLocalHasCards(true);
                    } else {
                        setVisible(ui.localTitle(), ui.localCarouselHost() != null ? ui.localCarouselHost() : ui.localFlow(), false);
                    }
                }));
    }

    private void clearCarouselHost() {
        if (ui.localCarouselHost() == null) return;
        Runnable r = () -> ui.localCarouselHost().getChildren().clear();
        if (Platform.isFxApplicationThread()) r.run(); else Platform.runLater(r);
    }

    private List<CardRequest> buildLocalCards(ArtistPageRenderContext rc) {
        if (!isCurrent(rc)) return List.of();

        final Artist target = rc.artist();
        final var artistMatch = service.artistMatches(target);

        List<Album> albumsSnapshot = service.snapshotAlbums();
        List<Song> songsSnapshot = service.snapshotSongs();

        Map<Long, Album> albumMap = new LinkedHashMap<>();

        for (Album a : albumsSnapshot) {
            if (!isCurrent(rc)) return List.of();
            if (a == null) continue;

            List<Song> localSongsOfArtist = new ArrayList<>();
            if (a.getSongList() != null) {
                for (Song s : a.getSongList()) {
                    if (s == null || !s.isLocal()) continue;
                    if (s.getArtist() != null && s.getArtist().stream().anyMatch(artistMatch)) {
                        localSongsOfArtist.add(s);
                    }
                }
            }

            boolean albumHasArtistDirect = a.getArtist() != null && a.getArtist().stream().anyMatch(artistMatch);
            if (localSongsOfArtist.isEmpty() && !albumHasArtistDirect) continue;

            Album copy = shallowCopy(a);
            copy.getSongList().addAll(localSongsOfArtist);
            albumMap.put(copy.getAlbumID() > 0 ? copy.getAlbumID() : -System.identityHashCode(copy), copy);
        }

        try {
            Set<Long> idsSeen = new HashSet<>(albumMap.keySet());

            for (Song memSong : songsSnapshot) {
                if (!isCurrent(rc)) return List.of();
                if (memSong == null || !memSong.isLocal()) continue;
                if (memSong.getArtist() == null || !memSong.getArtist().stream().anyMatch(artistMatch)) continue;

                long songId = memSong.getSongID();
                if (songId <= 0) continue;

                Album memAlb = memSong.getAlbum();
                long key = (memAlb != null && memAlb.getAlbumID() > 0) ? memAlb.getAlbumID() : -System.identityHashCode(memSong);
                if (!idsSeen.contains(key)) {
                    albumMap.put(key, shallowCopy(memAlb));
                    idsSeen.add(key);
                }
                albumMap.get(key).getSongList().add(memSong);
            }

            if (albumMap.isEmpty()) {
                long artistId = service.resolveArtistId(target);
                if (artistId > 0) {
                    List<Album> dbAlbums = service.findAlbumsByArtistId(artistId);
                    for (Album a : dbAlbums) {
                        if (!isCurrent(rc)) return List.of();
                        if (a == null) continue;
                        List<Song> songs = service.findSongsByAlbum(a.getAlbumID());
                        Album copy = shallowCopy(a);
                        if (songs != null) copy.getSongList().addAll(songs);
                        albumMap.put(copy.getAlbumID() > 0 ? copy.getAlbumID() : -System.identityHashCode(copy), copy);
                    }
                }
            }
        } catch (SQLException ignored) {
        }

        List<CardRequest> cards = new ArrayList<>();

        for (Album a : albumMap.values()) {
            if (!isCurrent(rc)) return List.of();
            if (cards.size() >= MAX_LOCAL_CARDS) break;
            if (a == null) continue;

            boolean isSingle = a.getNumberOfTracks() <= 1 || a.getSongList().size() == 1;
            List<String> artists = CardArtistNameResolver.fromAlbum(a);

            if (isSingle) {
                Song s = a.getSongList().stream().filter(Song::isLocal).findFirst().orElse(null);
                if (s == null) continue;

                Image cover = MediaImageResolver.musicCardSongCover(s);
                cards.add(new CardRequest(new MusicCardData(
                            String.valueOf(s.getSongID()),
                            cover,
                            s.getTitle(),
                            CardArtistNameResolver.fromSingle(s),
                            context.musicActions().songClick(null),
                            context.musicActions().artistNameClick(null)
                )));
            } else {
                Image cover = MediaImageResolver.musicCardAlbumCover(a);
                cards.add(new CardRequest(new MusicCardData(
                            String.valueOf(a.getAlbumID()),
                            cover,
                            a.getName(),
                            artists,
                            context.musicActions().albumClick(null),
                            context.musicActions().artistNameClick(null)
                )));
            }
        }

        return cards;
    }

    private Album shallowCopy(Album a) {
        if (a == null) {
            return new Album(0L, "", new ArrayList<>(), null, null, null, new ArrayList<>(), new ArrayList<>(), 0);
        }
        Album copy = new Album(
                a.getAlbumID(),
                a.getName(),
                a.getArtist() == null ? new ArrayList<>() : new ArrayList<>(a.getArtist()),
                a.getGenre(),
                a.getRecordType(),
                a.getReleaseDate(),
                new ArrayList<>(),
                new ArrayList<>(),
                a.getNumberOfTracks()
        );
        copy.setCoverUrl(a.getCoverUrl());
        return copy;
    }
}
