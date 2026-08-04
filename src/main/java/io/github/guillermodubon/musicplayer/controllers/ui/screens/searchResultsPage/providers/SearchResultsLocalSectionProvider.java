package io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.providers;

import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.image.Image;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.ArtistCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.CardArtistNameResolver;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.MusicCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.factory.CardFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.SearchResultsPageController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.context.SearchResultsPageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.context.SearchResultsPageRenderContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.searchResultsPage.providers.base.BaseSearchResultsPagePageSectionProvider;
import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;
import io.github.guillermodubon.musicplayer.utils.SearchResultsCardFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class SearchResultsLocalSectionProvider extends BaseSearchResultsPagePageSectionProvider {

    private enum LocalType { ARTIST, ALBUM, SONG, PLAYLIST }

    private record LocalResult(
            LocalType type,
            String id,
            String title,
            List<String> creators,
            Image cover,
            Artist artist
    ) {}

    public SearchResultsLocalSectionProvider(SearchResultsPageContext context, SearchResultsPageController.UiBindings ui) {
        super(context, ui);
    }

    @Override
    protected boolean isRemoteSection() {
        return false;
    }

    @Override
    public void render(SearchResultsPageRenderContext rc) {
        if (!isCurrent(rc)) return;
        prepareSectionSlot();

        final String query = rc.query() == null ? "" : rc.query().trim();
        loadAsync(rc, () -> buildLocalResults(query), results -> {
            List<Node> cards = new ArrayList<>();
            for (LocalResult result : results) {
                Node card = createCard(result);
                if (card != null) cards.add(card);
            }
            showCarouselSection(rc, "Library", cards);
        });
    }

    private List<LocalResult> buildLocalResults(String query) {
        String lower = normalize(query);
        if (lower.isBlank()) return List.of();

        List<Song> localSongs = service.snapshotSongs().stream()
                .filter(Objects::nonNull)
                .filter(Song::isLocal)
                .toList();
        if (localSongs.isEmpty()) return List.of();

        List<Artist> artistsSnapshot = service.snapshotArtists();
        List<Album> albumsSnapshot = service.snapshotAlbums();
        List<Playlist> playlistsSnapshot = service.snapshotPlaylists();

        List<LocalResult> results = new ArrayList<>();

        for (Artist artist : artistsSnapshot) {
            if (results.size() >= MAX_CARDS_PER_SECTION) break;
            if (artist == null || !contains(normalize(artist.getName()), lower)) continue;
            if (!hasLocalSongForArtist(artist, localSongs)) continue;
            results.add(new LocalResult(
                    LocalType.ARTIST,
                    String.valueOf(artist.getArtistID()),
                    artist.getName(),
                    List.of(),
                    null,
                    artist
            ));
        }

        Set<Long> localSongIds = localSongs.stream()
                .map(Song::getSongID)
                .filter(id -> id > 0)
                .collect(Collectors.toSet());

        LinkedHashMap<Long, Album> albumMap = new LinkedHashMap<>();
        for (Album album : albumsSnapshot) {
            if (album == null) continue;
            if (!hasLocalSongForAlbum(album, localSongIds, localSongs)) continue;
            List<String> albumArtists = CardArtistNameResolver.fromAlbum(album);
            boolean matches = contains(normalize(album.getName()), lower)
                    || albumArtists.stream().map(this::normalize).anyMatch(name -> contains(name, lower));
            if (!matches) continue;
            long key = album.getAlbumID() > 0 ? album.getAlbumID() : -System.identityHashCode(album);
            albumMap.putIfAbsent(key, album);
        }

        Set<Long> includedAlbumIds = albumMap.values().stream()
                .map(Album::getAlbumID)
                .filter(id -> id > 0)
                .collect(Collectors.toSet());

        for (Album album : albumMap.values()) {
            if (results.size() >= MAX_CARDS_PER_SECTION) break;
            results.add(new LocalResult(
                    LocalType.ALBUM,
                    String.valueOf(album.getAlbumID()),
                    album.getName(),
                    CardArtistNameResolver.fromAlbum(album),
                    MediaImageResolver.albumCover(album, "xl", 220, 220),
                    null
            ));
        }

        LinkedHashMap<Long, Song> singlesMap = new LinkedHashMap<>();
        for (Song song : localSongs) {
            if (song == null || song.getAlbum() == null) continue;
            Album album = song.getAlbum();
            boolean isSingleAlbum = album.getSongList() != null && album.getSongList().size() == 1;
            boolean matches = contains(normalize(song.getTitle()), lower)
                    || CardArtistNameResolver.fromSingle(song).stream().map(this::normalize).anyMatch(name -> contains(name, lower));
            if (!isSingleAlbum || !matches) continue;
            if (album.getAlbumID() > 0 && includedAlbumIds.contains(album.getAlbumID())) continue;
            long key = song.getSongID() > 0 ? song.getSongID() : -System.identityHashCode(song);
            singlesMap.putIfAbsent(key, song);
        }

        for (Song song : singlesMap.values()) {
            if (results.size() >= MAX_CARDS_PER_SECTION) break;
            results.add(new LocalResult(
                    LocalType.SONG,
                    String.valueOf(song.getSongID()),
                    song.getTitle(),
                    CardArtistNameResolver.fromSingle(song),
                    MediaImageResolver.songAlbumCover(song, "xl", 220, 220),
                    null
            ));
        }

        Set<String> localSongTitles = localSongs.stream()
                .map(Song::getTitle)
                .filter(Objects::nonNull)
                .map(this::normalize)
                .collect(Collectors.toSet());

        for (Playlist playlist : playlistsSnapshot) {
            if (results.size() >= MAX_CARDS_PER_SECTION) break;
            if (playlist == null || !hasLocalSongForPlaylist(playlist, localSongIds, localSongTitles)) continue;
            boolean matches = contains(normalize(playlist.getTitle()), lower)
                    || contains(normalize(playlist.getAuthorName()), lower);
            if (!matches) continue;
            results.add(new LocalResult(
                    LocalType.PLAYLIST,
                    String.valueOf(playlist.getId()),
                    playlist.getTitle(),
                    List.of(MusicCardData.playlistCreatorLabel(playlist.getAuthorName())),
                    MediaImageResolver.playlistCover(playlist, 220, 220),
                    null
            ));
        }

        return results;
    }

    private Node createCard(LocalResult result) {
        if (result == null) return null;
        try {
            if (result.type() == LocalType.ARTIST && result.artist() != null) {
                return CardFactory.createArtistCard(
                        new ArtistCardData(result.artist(), context.artistActions().artistClick(null))
                );
            }

            return SearchResultsCardFactory.createCard(
                    result.id(),
                    null,
                    result.cover(),
                    result.title(),
                    result.creators(),
                    id -> {
                        if (result.type() == LocalType.PLAYLIST) context.musicActions().playlistClick(null).accept(id);
                        else if (result.type() == LocalType.ALBUM) context.musicActions().albumClick(null).accept(id);
                        else context.musicActions().songClick(null).accept(id);
                    },
                    name -> context.musicActions().artistNameClick(null).accept(name),
                    result.type() == LocalType.PLAYLIST
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean hasLocalSongForArtist(Artist target, List<Song> localSongs) {
        if (target == null || localSongs == null) return false;
        for (Song song : localSongs) {
            if (song == null) continue;
            if (containsSameArtist(song.getArtist(), target)) return true;
            if (song.getAlbum() != null && containsSameArtist(song.getAlbum().getArtist(), target)) return true;
        }
        return false;
    }

    private boolean hasLocalSongForAlbum(Album album, Set<Long> localSongIds, List<Song> localSongs) {
        if (album == null) return false;

        if (album.getSongList() != null && localSongIds != null && !localSongIds.isEmpty()) {
            for (Song song : album.getSongList()) {
                if (song != null && song.getSongID() > 0 && localSongIds.contains(song.getSongID())) return true;
            }
        }

        long albumId = album.getAlbumID();
        String albumName = normalize(album.getName());
        if (localSongs == null || localSongs.isEmpty()) return false;
        for (Song song : localSongs) {
            if (song == null || song.getAlbum() == null) continue;
            Album songAlbum = song.getAlbum();
            if (albumId > 0 && songAlbum.getAlbumID() == albumId) return true;
            if (!albumName.isBlank() && albumName.equals(normalize(songAlbum.getName()))) return true;
        }
        return false;
    }

    private boolean hasLocalSongForPlaylist(Playlist playlist, Set<Long> localSongIds, Set<String> localSongTitles) {
        ObservableList<Song> songs = playlist == null ? null : playlist.getSongList();
        if (songs == null || songs.isEmpty()) return false;
        for (Song song : songs) {
            if (song == null) continue;
            if (song.getSongID() > 0 && localSongIds.contains(song.getSongID())) return true;
            String title = normalize(song.getTitle());
            if (!title.isBlank() && localSongTitles.contains(title)) return true;
        }
        return false;
    }

    private boolean containsSameArtist(List<Artist> artists, Artist target) {
        if (artists == null || target == null) return false;
        for (Artist artist : artists) {
            if (artist == null) continue;
            if (artist.getArtistID() > 0 || target.getArtistID() > 0) {
                if (artist.getArtistID() > 0
                        && target.getArtistID() > 0
                        && artist.getArtistID() == target.getArtistID()) {
                    return true;
                }
                continue;
            }
            String aName = normalize(artist.getName());
            String tName = normalize(target.getName());
            if (!aName.isBlank() && aName.equals(tName)) return true;
        }
        return false;
    }

    private List<String> artistNames(List<Artist> artists) {
        if (artists == null) return List.of();
        return artists.stream()
                .filter(Objects::nonNull)
                .map(Artist::getName)
                .filter(Objects::nonNull)
                .filter(name -> !name.isBlank())
                .toList();
    }

    private boolean contains(String source, String fragment) {
        return source != null && fragment != null && !fragment.isBlank() && source.contains(fragment);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
