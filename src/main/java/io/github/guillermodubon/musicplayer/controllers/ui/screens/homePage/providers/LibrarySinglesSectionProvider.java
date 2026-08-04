package io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.providers;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.image.Image;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import io.github.guillermodubon.musicplayer.utils.MusicCardHelper;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.CardArtistNameResolver;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.MusicCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.factory.CardFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.context.HomePageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.providers.base.BaseHomePageSectionProvider;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.libraryCatalogListScreens.common.CatalogType;
import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class LibrarySinglesSectionProvider extends BaseHomePageSectionProvider {

    public LibrarySinglesSectionProvider(HomePageContext context) {
        super(context);
    }

    @Override
    public CompletableFuture<Void> render(VBox container, String filter, long renderId) {
        String f = norm(filter);
        List<Song> singles = new ArrayList<>(context.memory().singles());

        List<Parent> cards = new ArrayList<>();
        Set<Long> renderedIds = new HashSet<>();
        for (Song s : singles) {
            if (!isRenderActive(renderId)) return CompletableFuture.completedFuture(null);
            if (s == null) continue;
            if (s.getSongID() <= 0) continue;
            if (!renderedIds.add(s.getSongID())) continue;

            List<String> artistFilter = s.getAlbum() == null || s.getAlbum().getArtist() == null
                    ? List.of()
                    : s.getAlbum().getArtist().stream()
                    .map(a -> a == null ? "" : Optional.ofNullable(a.getName()).orElse(""))
                    .toList();

            if (!matchesFilter(s.getTitle(), artistFilter, f)) continue;

            Album alb = s.getAlbum();
            Image cover = alb == null ? null : MediaImageResolver.musicCardSongCover(s);

            List<String> artists = CardArtistNameResolver.fromSingle(s);

            try {
                Parent card = CardFactory.createMusicCard(new MusicCardData(
                        String.valueOf(s.getSongID()),
                        MusicCardHelper.coverOrDefault(cover, defaultCover()),
                        Optional.ofNullable(s.getTitle()).orElse("Unknown"),
                        artists.isEmpty() ? List.of("Unknown") : artists,
                        context.musicActions().songClick(null),
                        context.musicActions().artistNameClick(null)
                ));
                styleMusicCard(card);
                cards.add(card);
                if (cards.size() >= MAX_CARDS_PER_SECTION) break;
            } catch (Exception ignored) {
            }
        }

        if (cards.isEmpty() || !isRenderActive(renderId)) return CompletableFuture.completedFuture(null);

        VBox section = sectionBlock(container, "Singles from your library", CatalogType.SINGLES);
        section.getChildren().add(createMusicCarousel(cards));
        return CompletableFuture.completedFuture(null);
    }
}
