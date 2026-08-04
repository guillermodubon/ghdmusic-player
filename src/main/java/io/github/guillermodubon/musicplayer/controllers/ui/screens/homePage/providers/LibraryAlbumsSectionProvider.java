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
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class LibraryAlbumsSectionProvider extends BaseHomePageSectionProvider {

    public LibraryAlbumsSectionProvider(HomePageContext context) {
        super(context);
    }

    @Override
    public CompletableFuture<Void> render(VBox container, String filter, long renderId) {
        String f = norm(filter);
        List<Album> albums = new ArrayList<>(context.memory().fullAlbums());

        List<Parent> cards = new ArrayList<>();
        Set<Long> renderedIds = new HashSet<>();
        for (Album a : albums) {
            if (!isRenderActive(renderId)) return CompletableFuture.completedFuture(null);
            if (a == null) continue;
            if (a.getAlbumID() <= 0 || !renderedIds.add(a.getAlbumID())) continue;

            List<String> artistNames = CardArtistNameResolver.fromAlbum(a);

            if (!matchesFilter(a.getName(), artistNames, f)) continue;

            Image cover = MediaImageResolver.musicCardAlbumCover(a);

            try {
                Parent card = CardFactory.createMusicCard(new MusicCardData(
                        String.valueOf(a.getAlbumID()),
                        MusicCardHelper.coverOrDefault(cover, defaultCover()),
                        Optional.ofNullable(a.getName()).orElse("Album"),
                        artistNames.isEmpty() ? List.of("Unknown") : artistNames,
                        context.musicActions().albumClick(null),
                        context.musicActions().artistNameClick(null)
                ));
                styleMusicCard(card);
                cards.add(card);
                if (cards.size() >= MAX_CARDS_PER_SECTION) break;
            } catch (Exception ignored) {
            }
        }

        if (cards.isEmpty() || !isRenderActive(renderId)) return CompletableFuture.completedFuture(null);

        VBox section = sectionBlock(container, "Albums from your library", CatalogType.ALBUMS);
        section.getChildren().add(createMusicCarousel(cards));
        return CompletableFuture.completedFuture(null);
    }
}
