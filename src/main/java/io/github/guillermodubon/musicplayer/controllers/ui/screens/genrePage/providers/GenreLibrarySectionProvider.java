package io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.providers;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.image.Image;
import javafx.scene.layout.VBox;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.CardArtistNameResolver;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.MusicCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.factory.CardFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.common.SectionCarouselFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.context.GenrePageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.context.GenrePageRenderContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.providers.base.BaseGenrePageSectionProvider;
import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class GenreLibrarySectionProvider extends BaseGenrePageSectionProvider {

    /** This local-only section may show a fuller library sample than remote sections. */
    private static final int MAX_LIBRARY_CARDS = 16;
    private static final String COVER_PREFERRED_TYPE = "xl";
    private static final double CARD_COVER_DECODE_SIZE = 320.0;

    public GenreLibrarySectionProvider(GenrePageContext context) {
        super(context);
    }

    @Override
    public CompletableFuture<Void> render(VBox container, GenrePageRenderContext renderContext) {
        if (container == null || renderContext == null) return CompletableFuture.completedFuture(null);

        VBox section = sectionBlock();
        container.getChildren().add(section);

        CompletableFuture<Void> completion = new CompletableFuture<>();

        supplyAsync(() -> loadLibraryCards(renderContext))
                .whenComplete((cards, th) -> Platform.runLater(() -> {
                    if (!renderContext.isAlive()) {
                        completion.complete(null);
                        return;
                    }

                    if (cards == null || cards.isEmpty()) {
                        hideSectionIfEmpty(container, section);
                        renderContext.shared().setLibraryHasCards(false);
                        completion.complete(null);
                        return;
                    }

                    section.getChildren().setAll(
                            sectionTitle(renderContext.genreName() + " from your library"),
                            SectionCarouselFactory.createMusicCarousel(cards)
                    );
                    renderContext.shared().setLibraryHasCards(true);
                    completion.complete(null);
                }));
        return completion;
    }

    private List<Parent> loadLibraryCards(GenrePageRenderContext rc) {
        List<Parent> out = new ArrayList<>();
        if (context.svc() == null) return out;

        var snapshot = context.memory().snapshot();
        rc.shared().setLibrarySnapshot(snapshot);
        List<Album> albums = snapshot.albums();
        List<Song> songs = snapshot.songs();
        Set<Long> renderedAlbumIds = new HashSet<>();
        Set<Long> renderedSongIds = new HashSet<>();
        Set<String> renderedCardKeys = new HashSet<>();

        for (Album a : albums) {
            if (!rc.isAlive() || out.size() >= MAX_LIBRARY_CARDS) break;
            if (a == null || a.getGenre() == null || a.getGenre().getGenreID() != rc.genreId()) continue;
            if (isSingleRelease(a)) continue;

            try {
                String title = a.getName() == null ? "Unknown" : a.getName();
                List<String> artistNames = CardArtistNameResolver.fromAlbum(a);
                String cardKey = cardKey(title, artistNames);
                if ((a.getAlbumID() > 0 && renderedAlbumIds.contains(a.getAlbumID()))
                        || renderedCardKeys.contains(cardKey)) {
                    continue;
                }

                // The library section is intentionally local-only. Its image
                // lookup can use the in-memory cache or persisted image data,
                // but it must never create Deezer work while Genre Page opens.
                Image cover = resolveLibraryCover(a);

                Parent card = CardFactory.createMusicCard(new MusicCardData(
                        String.valueOf(a.getAlbumID()),
                        cover == null ? defaultCover() : cover,
                        title,
                        artistNames == null || artistNames.isEmpty() ? List.of("Unknown") : artistNames,
                        context.musicActions().albumClick(null),
                        context.musicActions().artistNameClick(null)
                ));

                card.getProperties().put("id", a.getAlbumID());

                if (a.getArtist() != null) {
                    List<Long> ids = a.getArtist().stream()
                            .filter(Objects::nonNull)
                            .map(Artist::getArtistID)
                            .filter(id -> id != null && id > 0)
                            .toList();
                    if (!ids.isEmpty()) {
                        card.getProperties().put("artistIds", ids);
                        rc.shared().libraryArtistIds().addAll(ids);
                    }
                }

                out.add(card);
                if (a.getAlbumID() > 0) renderedAlbumIds.add(a.getAlbumID());
                renderedCardKeys.add(cardKey);
            } catch (Exception ignored) {}
        }

        for (Song s : songs) {
            if (!rc.isAlive() || out.size() >= MAX_LIBRARY_CARDS) break;
            if (s == null || s.getAlbum() == null || s.getAlbum().getGenre() == null) continue;
            if (s.getAlbum().getGenre().getGenreID() != rc.genreId()) continue;
            if (!isSingleRelease(s.getAlbum())) continue;

            try {
                String title = s.getTitle() == null ? "Unknown" : s.getTitle();
                List<String> artistNames = CardArtistNameResolver.fromSingle(s);
                long albumId = s.getAlbum().getAlbumID();
                String cardKey = cardKey(title, artistNames);
                if ((s.getSongID() > 0 && renderedSongIds.contains(s.getSongID()))
                        || (albumId > 0 && renderedAlbumIds.contains(albumId))
                        || renderedCardKeys.contains(cardKey)) {
                    continue;
                }

                Image cover = resolveLibraryCover(s.getAlbum());

                Parent card = CardFactory.createMusicCard(new MusicCardData(
                        String.valueOf(s.getSongID()),
                        cover == null ? defaultCover() : cover,
                        title,
                        artistNames == null || artistNames.isEmpty() ? List.of("Unknown") : artistNames,
                        context.musicActions().songClick(null),
                        context.musicActions().artistNameClick(null)
                ));

                card.getProperties().put("id", s.getSongID());
                card.getProperties().put("albumId", albumId);
                card.getProperties().put("type", "single");

                if (s.getArtist() != null) {
                    List<Long> ids = s.getArtist().stream()
                            .filter(Objects::nonNull)
                            .map(Artist::getArtistID)
                            .filter(id -> id != null && id > 0)
                            .toList();
                    if (!ids.isEmpty()) {
                        card.getProperties().put("artistIds", ids);
                        rc.shared().libraryArtistIds().addAll(ids);
                    }
                }

                out.add(card);
                if (s.getSongID() > 0) renderedSongIds.add(s.getSongID());
                if (albumId > 0) renderedAlbumIds.add(albumId);
                renderedCardKeys.add(cardKey);
            } catch (Exception ignored) {}
        }

        return out;
    }

    private Image resolveLibraryCover(Album album) {
        if (album == null) return null;

        Image cached = MediaImageResolver.cachedAlbumCover(
                album,
                COVER_PREFERRED_TYPE,
                CARD_COVER_DECODE_SIZE,
                CARD_COVER_DECODE_SIZE
        );
        if (cached != null && !cached.isError()) return cached;

        // albumCover(id, ...) reads the local image table only. Unlike the
        // model overload, it does not fall back to a remote cover URL.
        return MediaImageResolver.albumCover(
                album.getAlbumID(),
                COVER_PREFERRED_TYPE,
                CARD_COVER_DECODE_SIZE,
                CARD_COVER_DECODE_SIZE
        );
    }

    private boolean isSingleRelease(Album album) {
        if (album == null) return false;

        String recordType = album.getRecordType();
        if (recordType != null && recordType.trim().equalsIgnoreCase("single")) return true;
        if (album.getNumberOfTracks() == 1) return true;

        List<Song> albumSongs = album.getSongList();
        return albumSongs != null && albumSongs.size() == 1;
    }

    private String cardKey(String title, Collection<String> artistNames) {
        String normalizedTitle = normalize(title);
        List<String> normalizedArtists = artistNames == null
                ? List.of()
                : artistNames.stream()
                .filter(Objects::nonNull)
                .map(this::normalize)
                .filter(name -> !name.isBlank())
                .distinct()
                .sorted()
                .toList();
        return normalizedTitle + "|" + String.join(",", normalizedArtists);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
