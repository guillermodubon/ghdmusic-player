package io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.providers;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.MusicCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.factory.CardFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.context.HomePageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.providers.base.BaseHomePageSectionProvider;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.libraryCatalogListScreens.common.CatalogType;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public class LibraryOtherSongsSectionProvider extends BaseHomePageSectionProvider {

    public LibraryOtherSongsSectionProvider(HomePageContext context) {
        super(context);
    }

    @Override
    public CompletableFuture<Void> render(VBox container, String filter, long renderId) {
        if (!isRenderActive(renderId)) return CompletableFuture.completedFuture(null);

        String f = norm(filter);

        if (context.svc() == null || context.svc().noMetadataSongs == null || context.svc().noMetadataSongs.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        List<Parent> cards = new ArrayList<>();

        for (var pair : context.svc().noMetadataSongs) {
            if (!isRenderActive(renderId)) return CompletableFuture.completedFuture(null);
            if (pair == null || pair.getKey() == null) continue;
            if (isPersistedSingle(pair.getKey(), pair.getValue())) continue;
            if (!pair.getKey().toLowerCase(Locale.ROOT).contains(f)) continue;

            String title = pair.getKey();
            String id = "no_meta_" + URLEncoder.encode(title == null ? "unknown" : title, StandardCharsets.UTF_8);
            try {
                Parent card = CardFactory.createMusicCard(MusicCardData.localFile(
                        id,
                        defaultCover(),
                        title,
                        context.musicActions().songClick(null)
                ));
                styleMusicCard(card);
                cards.add(card);
                if (cards.size() >= MAX_CARDS_PER_SECTION) break;
            } catch (Exception ignored) {}
        }

        if (cards.isEmpty()) return CompletableFuture.completedFuture(null);
        if (!isRenderActive(renderId)) return CompletableFuture.completedFuture(null);

        VBox section = sectionBlock(container, "Other songs from your library", CatalogType.SINGLES);
        section.getChildren().add(createMusicCarousel(cards));
        return CompletableFuture.completedFuture(null);
    }

    private boolean isPersistedSingle(String title, String path) {
        if (context.svc() == null || context.svc().getSongs() == null) return false;
        return context.svc().getSongs().stream().anyMatch(song -> {
            if (song == null || !song.isLocal()) return false;
            if (path != null && song.getFilePath() != null && path.equalsIgnoreCase(song.getFilePath())) return true;
            return title != null && song.getTitle() != null && title.equalsIgnoreCase(song.getTitle());
        });
    }
}
