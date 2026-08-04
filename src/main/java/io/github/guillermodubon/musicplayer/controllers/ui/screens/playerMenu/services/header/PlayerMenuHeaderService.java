package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.header;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.MusicCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext.ContentType;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.managers.PlayerMenuArtistResolver;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;
import io.github.guillermodubon.musicplayer.services.images.colors.CoverColorExtractor;
import io.github.guillermodubon.musicplayer.services.images.colors.CoverColorPalette;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.utils.DisplayDateFormatter;
import io.github.guillermodubon.musicplayer.utils.ArtistIdentity;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class PlayerMenuHeaderService {

    private static final String HEADER_COVER_PREFERRED_TYPE = "xl";
    private static final double HEADER_COVER_DECODE_SIZE = 640.0;
    private static final double ARTIST_AVATAR_DECODE_SIZE = 256.0;

    private final PlayerMenuContext context;
    private final PlayerMenuArtistResolver artistResolver;
    private final ExecutorService ioPool = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "player-menu-header-io");
        t.setDaemon(true);
        return t;
    });

    private StartUpService svc;
    private MusicCardActionManager musicActions;
    private ImageView headerCover;
    private HBox playerMenuHeader;
    private Region playerMenuHeaderFade;
    private Region actionsAndSearch;
    private Region firstSongsSurface;
    private Label recordTypeLabel;
    private Label headerTitle;
    private HBox creatorContainer;
    private Label playlistDescLabel;
    private Label dateLabel;
    /**
     * Keeps the most complete owner snapshot seen during the current view.
     * Remote single metadata can be refreshed more than once and a later
     * partial response must not remove owners that were already resolved.
     */
    private long ownerSnapshotRevision = Long.MIN_VALUE;
    private String ownerSnapshotScopeKey;
    private final Map<String, List<Artist>> ownerSnapshotsByRelease = new LinkedHashMap<>();
    private Image observedHeaderImage;
    private Image paletteSourceImage;
    private CoverColorPalette resolvedPalette;
    private boolean paletteResolutionPending;
    private long paletteGeneration;
    private volatile List<Album> cachedAlbumListRef;
    private volatile int cachedAlbumListSize = -1;
    private volatile Map<Long, List<Artist>> cachedAlbumOwners = Map.of();

    public PlayerMenuHeaderService(PlayerMenuContext context,
                                   StartUpService svc,
                                   MusicCardActionManager musicActions,
                                   PlayerMenuArtistResolver artistResolver) {
        this.context = context;
        this.svc = svc;
        this.musicActions = musicActions;
        this.artistResolver = artistResolver;
    }

    public void bindUi(ImageView headerCover,
                       HBox playerMenuHeader,
                       Region playerMenuHeaderFade,
                       Region actionsAndSearch,
                       Region firstSongsSurface,
                       Label recordTypeLabel,
                       Label headerTitle,
                       HBox creatorContainer,
                       Label playlistDescLabel,
                       Label dateLabel) {
        this.headerCover = headerCover;
        this.playerMenuHeader = playerMenuHeader;
        this.playerMenuHeaderFade = playerMenuHeaderFade;
        this.actionsAndSearch = actionsAndSearch;
        this.firstSongsSurface = firstSongsSurface;
        this.recordTypeLabel = recordTypeLabel;
        this.headerTitle = headerTitle;
        this.creatorContainer = creatorContainer;
        this.playlistDescLabel = playlistDescLabel;
        this.dateLabel = dateLabel;
    }

    public void refreshHeader() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::refreshHeader);
            return;
        }

        Playlist playlist = context.getCurrentPlaylistModel();
        ContentType type = context.getCurrentContentTypeInView();
        if (playlist == null) {
            clearCoverPalette();
            return;
        }
        long viewRevision = context.getViewRevision();
        long playlistId = playlist.getId();

        try {
            if (recordTypeLabel != null && type != null) {
                recordTypeLabel.setText(switch (type) {
                    case SINGLE -> "Single";
                    case ALBUM -> "Album";
                    case EPISODE -> "Episode";
                    case PLAYLIST -> "Playlist";
                });
            }

            if (headerTitle != null) {
                headerTitle.setText(Optional.ofNullable(playlist.getTitle()).orElse(""));
            }

            if (headerCover != null) {
                clearCoverPalette();
                Image cached = resolveCachedHeaderCover(playlist);
                setHeaderCoverImage(cached, viewRevision, playlistId);
                if (cached == null || cached.isError()) {
                    CompletableFuture
                            .supplyAsync(() -> resolveHeaderCover(playlist), ioPool)
                            .thenAccept(image -> Platform.runLater(() ->
                                    setHeaderCoverImage(image, viewRevision, playlistId)
                            ))
                            .exceptionally(ignored -> null);
                }
            }

            refreshCreators(playlist, type, viewRevision, playlistId);
            refreshDescription(playlist);

            if (dateLabel != null) {
                dateLabel.setText(DisplayDateFormatter.toDayMonthYear(playlist.getDate()));
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private Image resolveHeaderCover(Playlist playlist) {
        Image img = playlist == null
                ? null
                : MediaImageResolver.playlistCover(
                        playlist,
                        HEADER_COVER_DECODE_SIZE,
                        HEADER_COVER_DECODE_SIZE
                );
        if (img == null && shouldUseSongCoverFallback(playlist)
                && playlist.getSongList() != null && !playlist.getSongList().isEmpty()) {
            Song first = playlist.getSongList().get(0);
            img = MediaImageResolver.songAlbumCover(
                    first,
                    HEADER_COVER_PREFERRED_TYPE,
                    HEADER_COVER_DECODE_SIZE,
                    HEADER_COVER_DECODE_SIZE
            );
        }
        if (img == null) {
            img = MediaImageResolver.defaultCover(
                    HEADER_COVER_DECODE_SIZE,
                    HEADER_COVER_DECODE_SIZE
            );
        }
        return img;
    }

    private Image resolveCachedHeaderCover(Playlist playlist) {
        Image image = playlist == null
                ? null
                : MediaImageResolver.cachedPlaylistCover(
                        playlist,
                        HEADER_COVER_DECODE_SIZE,
                        HEADER_COVER_DECODE_SIZE
                );
        if (image == null && shouldUseSongCoverFallback(playlist)
                && playlist.getSongList() != null
                && !playlist.getSongList().isEmpty()) {
            image = MediaImageResolver.cachedSongAlbumCover(
                    playlist.getSongList().get(0),
                    HEADER_COVER_PREFERRED_TYPE,
                    HEADER_COVER_DECODE_SIZE,
                    HEADER_COVER_DECODE_SIZE
            );
        }
        return image;
    }

    /**
     * A user-created playlist owns its cover independently of its songs. If
     * no custom BLOB exists, the correct result is the default playlist cover,
     * never the artwork of the first song. Remote releases may still use the
     * first-song fallback while their playlist artwork is being resolved.
     */
    private boolean shouldUseSongCoverFallback(Playlist playlist) {
        return playlist != null && !isUserCreatedPlaylist(playlist);
    }

    private boolean isUserCreatedPlaylist(Playlist playlist) {
        if (playlist == null) return false;

        String author = Optional.ofNullable(playlist.getAuthorName())
                .map(String::trim)
                .orElse("");
        if (author.equalsIgnoreCase("User")) return true;

        // Keep the decision stable when a navigation model arrives with
        // incomplete author metadata but the startup cache has the local row.
        if (svc == null || svc.getPlaylists() == null || playlist.getId() <= 0) return false;
        return svc.getPlaylists().stream()
                .filter(candidate -> candidate != null && candidate.getId() == playlist.getId())
                .map(Playlist::getAuthorName)
                .filter(candidate -> candidate != null)
                .map(String::trim)
                .anyMatch(candidate -> candidate.equalsIgnoreCase("User"));
    }

    private void setHeaderCoverImage(Image image, long viewRevision, long playlistId) {
        if (headerCover == null) return;
        Image fallback = MediaImageResolver.defaultCover(
                HEADER_COVER_DECODE_SIZE,
                HEADER_COVER_DECODE_SIZE
        );
        if (!isCurrentHeaderView(viewRevision, playlistId)) return;
        if (image == null || image.isError()) {
            headerCover.setImage(fallback);
            clearCoverPalette();
            return;
        }

        if (headerCover.getImage() != image) {
            headerCover.setImage(image);
        }
        applyCoverPaletteWhenReady(image, viewRevision, playlistId);
        if (observedHeaderImage != image) {
            observedHeaderImage = image;
            image.errorProperty().addListener((obs, wasError, isError) -> {
                if (Boolean.TRUE.equals(isError)) {
                    Platform.runLater(() -> {
                        if (headerCover != null && isCurrentHeaderView(viewRevision, playlistId)) {
                            headerCover.setImage(fallback);
                            clearCoverPalette();
                        }
                    });
                }
            });
        }
        if (image.isError()) {
            headerCover.setImage(fallback);
            clearCoverPalette();
        }
    }

    private void applyCoverPaletteWhenReady(Image image, long viewRevision, long playlistId) {
        if (!isCurrentHeaderView(viewRevision, playlistId)) return;

        if (image == paletteSourceImage && resolvedPalette != null) {
            applyCoverPalette(image, viewRevision, playlistId, resolvedPalette);
            return;
        }
        if (image == paletteSourceImage && paletteResolutionPending) return;

        paletteSourceImage = image;
        resolvedPalette = null;
        paletteResolutionPending = true;
        long generation = ++paletteGeneration;

        if (image.getProgress() >= 1.0) {
            resolveCoverPalette(image, viewRevision, playlistId, generation, 0);
            return;
        }

        image.progressProperty().addListener((obs, oldProgress, newProgress) -> {
            if (newProgress != null && newProgress.doubleValue() >= 1.0) {
                resolveCoverPalette(image, viewRevision, playlistId, generation, 0);
            }
        });
    }

    private void resolveCoverPalette(Image image,
                                     long viewRevision,
                                     long playlistId,
                                     long generation,
                                     int attempt) {
        if (!isCurrentHeaderView(viewRevision, playlistId)
                || paletteSourceImage != image
                || paletteGeneration != generation) {
            return;
        }

        CompletableFuture
                .supplyAsync(() -> CoverColorExtractor.extract(image).orElse(null), ioPool)
                .handle((palette, error) -> palette)
                .thenAccept(palette -> Platform.runLater(() -> {
                    if (!isCurrentHeaderView(viewRevision, playlistId)
                            || paletteSourceImage != image
                            || paletteGeneration != generation) {
                        return;
                    }

                    if (palette != null) {
                        resolvedPalette = palette;
                        paletteResolutionPending = false;
                        applyCoverPalette(image, viewRevision, playlistId, palette);
                        return;
                    }

                    // Image progress can reach 1.0 just before JavaFX exposes
                    // its PixelReader. Retry briefly instead of permanently
                    // leaving the header on its fallback style.
                    if (attempt < 3) {
                        CompletableFuture.delayedExecutor(45, TimeUnit.MILLISECONDS)
                                .execute(() -> Platform.runLater(() -> resolveCoverPalette(
                                        image,
                                        viewRevision,
                                        playlistId,
                                        generation,
                                        attempt + 1
                                )));
                    } else {
                        paletteResolutionPending = false;
                    }
                }));
    }

    private void applyCoverPalette(Image image,
                                   long viewRevision,
                                   long playlistId,
                                   CoverColorPalette palette) {
        if (!isCurrentHeaderView(viewRevision, playlistId)) return;
        PlayerMenuHeaderGradientStyler.apply(
                playerMenuHeader,
                playerMenuHeaderFade,
                actionsAndSearch,
                firstSongsSurface,
                palette
        );
    }

    private void clearCoverPalette() {
        paletteGeneration++;
        paletteSourceImage = null;
        resolvedPalette = null;
        paletteResolutionPending = false;
        PlayerMenuHeaderGradientStyler.clear(
                playerMenuHeader,
                playerMenuHeaderFade,
                actionsAndSearch,
                firstSongsSurface
        );
    }

    private void refreshCreators(Playlist playlist, ContentType type, long viewRevision, long playlistId) {
        if (creatorContainer == null) return;
        creatorContainer.getChildren().clear();

        if (playlist == null || playlist.getSongList() == null || playlist.getSongList().isEmpty()) {
            setCreatorViewportVisible(false);
            return;
        }

        String snapshotKey = ownerSnapshotKey(type, playlist, playlistId);
        resetOwnerSnapshotsIfNeeded(viewRevision, snapshotKey);

        if (type == ContentType.ALBUM || type == ContentType.EPISODE) {
            addArtistNodes(rememberCompleteOwners(
                    snapshotKey,
                    resolveAlbumCreators(playlist)
            ), viewRevision, playlistId);
            if (creatorContainer.getChildren().isEmpty()) {
                addVariousArtistsNode();
            }
        } else if (type == ContentType.SINGLE) {
            Song single = playlist.getSongList().get(0);
            if (isLocalFileWithoutMetadata(single)) {
                addLocalFileNode();
            } else if (single != null) {
                addArtistNodes(rememberCompleteOwners(
                        snapshotKey,
                        resolveSingleCreators(single)
                ), viewRevision, playlistId);
            }
        }

        setCreatorViewportVisible(!creatorContainer.getChildren().isEmpty());
    }

    private void resetOwnerSnapshotsIfNeeded(long viewRevision, String activeScopeKey) {
        if (ownerSnapshotRevision == viewRevision) return;

        boolean sameRelease = ownerSnapshotScopeKey != null
                && ownerSnapshotScopeKey.equals(activeScopeKey);
        if (!sameRelease) {
            ownerSnapshotsByRelease.clear();
        } else {
            ownerSnapshotsByRelease.keySet().removeIf(key -> !key.equals(activeScopeKey));
        }
        ownerSnapshotRevision = viewRevision;
        ownerSnapshotScopeKey = activeScopeKey;
    }

    private String ownerSnapshotKey(ContentType type, Playlist playlist, long playlistId) {
        long releaseId = 0L;
        if (playlist != null && playlist.getSongList() != null) {
            for (Song song : playlist.getSongList()) {
                if (song == null || song.getAlbum() == null) continue;
                if (song.getAlbum().getAlbumID() > 0) {
                    releaseId = song.getAlbum().getAlbumID();
                    break;
                }
            }
        }

        // A single keeps the track ID as its stable identity. Its album ID
        // can be missing in the first response and become available later.
        // Using the track ID prevents that enrichment from discarding the
        // owner snapshot already rendered for the same single.
        long identity = type == ContentType.SINGLE
                ? playlistId
                : releaseId > 0 ? releaseId : playlistId;
        return (type == null ? "UNKNOWN" : type.name()) + ":" + identity;
    }

    /**
     * Merges by Deezer artist ID first and only uses the name for legacy
     * records. This deliberately accumulates owners instead of replacing the
     * previous snapshot, which protects the header from partial async data.
     */
    private List<Artist> rememberCompleteOwners(String key, List<Artist> resolved) {
        if (key == null || key.isBlank()) return resolved == null ? List.of() : resolved;

        Map<String, Artist> merged = new LinkedHashMap<>();
        mergeArtistsByIdentity(merged, ownerSnapshotsByRelease.get(key));
        mergeArtistsByIdentity(merged, resolved);

        List<Artist> complete = new ArrayList<>(merged.values());
        if (!complete.isEmpty()) {
            ownerSnapshotsByRelease.put(key, complete);
        }
        return complete;
    }

    private void setCreatorViewportVisible(boolean visible) {
        Node parent = creatorContainer == null ? null : creatorContainer.getParent();
        if (parent == null) return;
        parent.setVisible(visible);
        parent.setManaged(visible);
    }

    private void addLocalFileNode() {
        if (creatorContainer == null) return;
        Label label = new Label(MusicCardData.LOCAL_FILE_ARTIST_LABEL);
        label.getStyleClass().addAll(
                "player-menu-creator-link",
                "player-menu-local-file-label"
        );
        creatorContainer.getChildren().add(label);
    }

    private void addVariousArtistsNode() {
        if (creatorContainer == null) return;
        Label label = new Label(MusicCardData.VARIOUS_ARTISTS_LABEL);
        label.getStyleClass().addAll("player-menu-creator-link", "artist-plain-label");
        label.setMouseTransparent(true);
        creatorContainer.getChildren().add(label);
    }

    private boolean isLocalFileWithoutMetadata(Song song) {
        return song != null && song.isLocal() && song.getSongID() == 0L;
    }

    /**
     * Resolves album owners from every available model source. A playlist can
     * contain songs whose album metadata was hydrated at different times, so
     * using only the first song can silently drop co-owners. This method only
     * reads album artist lists; track artists are deliberately not used here
     * because they may be collaborators on individual songs.
     */
    private List<Artist> resolveAlbumCreators(Playlist playlist) {
        Map<String, Artist> creators = new LinkedHashMap<>();
        if (playlist == null || playlist.getSongList() == null) return List.of();

        long albumId = 0L;
        for (Song song : playlist.getSongList()) {
            if (song == null || song.getAlbum() == null) continue;
            Album album = song.getAlbum();
            if (albumId <= 0 && album.getAlbumID() > 0) albumId = album.getAlbumID();
            mergeArtistsByIdentity(creators, album.getArtist());
        }

        mergeKnownAlbumCreators(creators, albumId);
        return new ArrayList<>(creators.values());
    }

    /**
     * Singles use album owners when available, matching MusicCard's release
     * semantics. The song artist list is only the fallback for local/legacy
     * records that have no album-owner metadata at all.
     */
    private List<Artist> resolveSingleCreators(Song song) {
        Map<String, Artist> creators = new LinkedHashMap<>();
        if (song == null) return List.of();

        long albumId = 0L;
        if (song.getAlbum() != null) {
            albumId = song.getAlbum().getAlbumID();
            mergeArtistsByIdentity(creators, song.getAlbum().getArtist());
        }
        mergeKnownAlbumCreators(creators, albumId);

        if (creators.isEmpty()) {
            mergeArtistsByIdentity(creators, song.getArtist());
        }
        return new ArrayList<>(creators.values());
    }

    private void mergeKnownAlbumCreators(Map<String, Artist> target, long albumId) {
        if (target == null || albumId <= 0) return;
        mergeArtistsByIdentity(target, buildKnownAlbumOwners().get(albumId));
    }

    private Map<Long, List<Artist>> buildKnownAlbumOwners() {
        List<Album> source = svc == null ? null : svc.getAlbums();
        int sourceSize = source == null ? 0 : source.size();
        Map<Long, List<Artist>> cached = cachedAlbumOwners;
        if (source == cachedAlbumListRef && sourceSize == cachedAlbumListSize && cached != null) {
            return cached;
        }

        synchronized (this) {
            cached = cachedAlbumOwners;
            if (source == cachedAlbumListRef && sourceSize == cachedAlbumListSize && cached != null) {
                return cached;
            }

            Map<Long, List<Artist>> rebuilt = new LinkedHashMap<>();
            try {
                for (Album album : source == null ? List.<Album>of() : new ArrayList<>(source)) {
                    if (album == null || album.getAlbumID() <= 0) continue;
                    List<Artist> owners = album.getArtist() == null
                            ? List.of() : new ArrayList<>(album.getArtist());
                    if (!owners.isEmpty()) rebuilt.putIfAbsent(album.getAlbumID(), owners);
                }
            } catch (Exception ignored) {
            }

            cachedAlbumListRef = source;
            cachedAlbumListSize = sourceSize;
            cachedAlbumOwners = rebuilt;
            return rebuilt;
        }
    }

    private void mergeArtistsByIdentity(Map<String, Artist> target, List<Artist> candidates) {
        if (target == null || candidates == null) return;

        for (Artist artist : candidates) {
            if (artist == null) continue;
            String name = artist.getName() == null ? "" : artist.getName().trim();
            if (name.isBlank() && artist.getArtistID() <= 0) continue;

            if (artist.getArtistID() > 0) {
                target.putIfAbsent("id:" + artist.getArtistID(), artist);
                // An ID-bearing object is more reliable than a legacy
                // name-only object with the same display name.
                target.remove("name:" + normalizeArtistName(name));
                continue;
            }

            String nameKey = "name:" + normalizeArtistName(name);
            boolean alreadyResolvedById = target.keySet().stream()
                    .filter(key -> key.startsWith("id:"))
                    .map(key -> target.get(key))
                    .anyMatch(existing -> existing != null
                            && normalizeArtistName(existing.getName()).equals(normalizeArtistName(name)));
            if (!alreadyResolvedById) target.putIfAbsent(nameKey, artist);
        }
    }

    private String normalizeArtistName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private void addArtistNodes(List<Artist> artists, long viewRevision, long playlistId) {
        if (artists == null) return;

        Set<String> seenArtists = new LinkedHashSet<>();
        for (Artist artist : artists) {
            if (artist == null) continue;

            long artistId = artist.getArtistID();
            String normalizedName = artist.getName() == null
                    ? ""
                    : artist.getName().trim().toLowerCase(Locale.ROOT);

            String identity = artistId > 0
                    ? "id:" + artistId
                    : "name:" + normalizedName;
            if (!seenArtists.add(identity)) continue;

            creatorContainer.getChildren().add(createArtistNode(artist, viewRevision, playlistId));
        }
    }

    private void refreshDescription(Playlist playlist) {
        if (playlistDescLabel == null) return;
        String description = playlist == null ? "" : Optional.ofNullable(playlist.getDescription()).orElse("");
        playlistDescLabel.setText(description);
        playlistDescLabel.setVisible(!description.isBlank());
        playlistDescLabel.setManaged(!description.isBlank());
    }

    private Node createArtistNode(Artist artist,
                                  long viewRevision,
                                  long playlistId) {
        ImageView iv = new ImageView();
        iv.setFitWidth(48);
        iv.setFitHeight(48);
        iv.setPreserveRatio(false);
        iv.getStyleClass().add("player-menu-artist-avatar");
        iv.setClip(new Circle(24, 24, 24));

        Image initialPortrait = artist == null || artistResolver == null
                ? null
                : artistResolver.cachedSmallPortrait(artist);
        iv.setImage(initialPortrait == null
                ? MediaImageResolver.defaultArtist(
                        ARTIST_AVATAR_DECODE_SIZE,
                        ARTIST_AVATAR_DECODE_SIZE
                )
                : initialPortrait);

        String artistName = ArtistIdentity.displayName(
                artist == null ? null : artist.getName()
        );
        Node artistControl;
        if (ArtistIdentity.isVariousArtists(artist)) {
            Label label = new Label(artistName);
            label.getStyleClass().addAll("player-menu-creator-link", "artist-plain-label");
            label.setMouseTransparent(true);
            artistControl = label;
        } else {
            Hyperlink link = new Hyperlink(artistName);
            link.getStyleClass().addAll("app-hyperlink", "player-menu-creator-link");
            link.setOnAction(e -> {
                if (musicActions != null && artist != null && artist.getName() != null) {
                    musicActions.artistClick(link).accept(artist);
                }
            });
            artistControl = link;
        }

        HBox cell = new HBox(10, iv, artistControl);
        cell.setAlignment(Pos.CENTER_LEFT);
        cell.getStyleClass().add("player-menu-creator-chip");

        if (artist != null && artistResolver != null && !ArtistIdentity.isVariousArtists(artist)) {
            // All portraits are resolved, but the three-thread pool keeps a
            // long artist list from saturating either SQLite or Deezer.
            CompletableFuture
                    .supplyAsync(() -> artistResolver.resolveSmallPortrait(artist), ioPool)
                    .thenAccept(img -> {
                        if (img == null || img.isError()) return;
                        Platform.runLater(() -> {
                            if (isCurrentHeaderView(viewRevision, playlistId)) {
                                iv.setImage(img);
                            }
                        });
                    });
        }

        return cell;
    }

    private boolean isCurrentHeaderView(long viewRevision, long playlistId) {
        Playlist current = context.getCurrentPlaylistModel();
        return context.isViewRevisionCurrent(viewRevision)
                && current != null
                && current.getId() == playlistId;
    }
}
