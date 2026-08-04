package io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.providers;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.layout.VBox;
import io.github.guillermodubon.musicplayer.repository.dao.playlist.PlaylistDao;
import io.github.guillermodubon.musicplayer.repository.dao.playlist.PlaylistDaoImpl;
import io.github.guillermodubon.musicplayer.utils.MusicCardHelper;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.MusicCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.factory.CardFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.context.HomePageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.providers.base.BaseHomePageSectionProvider;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.libraryCatalogListScreens.common.CatalogType;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class LibraryPlaylistsSectionProvider extends BaseHomePageSectionProvider {

    public LibraryPlaylistsSectionProvider(HomePageContext context) {
        super(context);
    }

    @Override
    public CompletableFuture<Void> render(VBox container, String filter, long renderId) {
        List<Playlist> memory = new ArrayList<>(context.memory().playlists());
        VBox section = sectionBlock(container, "Your playlists", CatalogType.PLAYLISTS);
        setSectionContent(section, emptyState("Loading your playlists..."));

        CompletableFuture<Void> completion = new CompletableFuture<>();
        supplyAsync(() -> loadCombinedPlaylists(memory))
                .whenComplete((playlists, error) -> Platform.runLater(() -> {
                    try {
                        if (isRenderActive(renderId)) {
                            List<Parent> cards = createCards(playlists, filter, renderId);
                            if (cards.isEmpty()) {
                                removeSection(section);
                            } else {
                                setSectionContent(section, createMusicCarousel(cards));
                            }
                        }
                    } finally {
                        completion.complete(null);
                    }
                }));
        return completion;
    }

    private List<Playlist> loadCombinedPlaylists(List<Playlist> memory) {
        LinkedHashMap<Long, Playlist> combined = new LinkedHashMap<>();
        if (memory != null) {
            for (Playlist playlist : memory) {
                if (playlist == null) continue;
                combined.putIfAbsent(playlist.getId(), playlist);
            }
        }

        try {
            PlaylistDao playlistDao = new PlaylistDaoImpl(null);
            List<Playlist> allFromDb = playlistDao.findAll();
            if (allFromDb != null) {
                for (Playlist playlist : allFromDb) {
                    if (playlist == null) continue;
                    combined.putIfAbsent(playlist.getId(), playlist);
                }
            }
        } catch (Exception ignored) {
        }

        return new ArrayList<>(combined.values());
    }

    private List<Parent> createCards(List<Playlist> playlists, String filter, long renderId) {
        String f = norm(filter);
        List<Parent> cards = new ArrayList<>();
        Set<Long> renderedIds = new HashSet<>();
        for (Playlist pl : playlists == null ? List.<Playlist>of() : playlists) {
            if (!isRenderActive(renderId)) return List.of();
            if (pl == null) continue;
            if (!renderedIds.add(pl.getId())) continue;

            String title = Optional.ofNullable(pl.getTitle()).orElse("");
            String desc = Optional.ofNullable(pl.getDescription()).orElse("");
            String author = Optional.ofNullable(pl.getAuthorName()).orElse("CustomPlaylist");
            String displayCreator = MusicCardData.playlistCreatorLabel(author);

            boolean matches = title.toLowerCase(Locale.ROOT).contains(f)
                    || desc.toLowerCase(Locale.ROOT).contains(f)
                    || author.toLowerCase(Locale.ROOT).contains(f);

            if (!matches) continue;

            int count = (pl.getSongList() != null) ? pl.getSongList().size() : 0;
            Image cover = MediaImageResolver.musicCardPlaylistCover(pl);

            try {
                Parent card = CardFactory.createMusicCard(MusicCardData.playlist(
                        String.valueOf(pl.getId()),
                        MusicCardHelper.coverOrDefault(cover, defaultCover()),
                        Optional.ofNullable(pl.getTitle()).orElse("Playlist"),
                        List.of(displayCreator),
                        context.musicActions().playlistClick(null),
                        context.musicActions().artistNameClick(null)
                ));

                card.getProperties().put("isLocalPlaylist", Boolean.TRUE);

                if (!"User".equalsIgnoreCase(Optional.ofNullable(pl.getAuthorName()).orElse(""))) {
                    card.getProperties().put("savedFromDeezer", Boolean.TRUE);
                }

                try {
                    Tooltip.install(card, new Tooltip(count + " canción" + (count == 1 ? "" : "es")));
                } catch (Exception ignored) {
                }

                styleMusicCard(card);
                cards.add(card);
                if (cards.size() >= MAX_CARDS_PER_SECTION) break;
            } catch (Exception ignored) {
            }
        }
        return cards;
    }
}
