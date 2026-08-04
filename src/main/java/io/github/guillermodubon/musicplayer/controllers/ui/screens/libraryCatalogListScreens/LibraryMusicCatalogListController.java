package io.github.guillermodubon.musicplayer.controllers.ui.screens.libraryCatalogListScreens;

import io.github.guillermodubon.musicplayer.models.ManifestEntry;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import io.github.guillermodubon.musicplayer.services.navigation.PlayerMenuNavigator;
import io.github.guillermodubon.musicplayer.controllers.ui.components.inputs.SearchBarController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.CardArtistNameResolver;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.MusicCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.factory.CardFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.libraryCatalogListScreens.common.CatalogType;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.libraryCatalogListScreens.common.LibraryCatalogFilterMenu;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.libraryCatalogListScreens.common.LibraryCatalogFilterPreferences;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.libraryCatalogListScreens.common.ProgressiveCardFlowRenderer;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.common.AmbientGradientSupport;
import io.github.guillermodubon.musicplayer.utils.LibraryModelDeduplicator;
import io.github.guillermodubon.musicplayer.utils.LocalSongVerifier;
import io.github.guillermodubon.musicplayer.utils.MusicCardHelper;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.ArtistCardActionManager;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.ArtistOpenCoordinator;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.GenreCardActionManager;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.DeezerApiMetaData;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.manifest.ManifestService;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

public class LibraryMusicCatalogListController {

    private static final double MUSIC_CARD_WIDTH = 176;
    private static final double MUSIC_CARD_HEIGHT = 254;

    @FXML private BorderPane screenRoot;
    @FXML private Label titleLabel;
    @FXML private HBox catalogTools;
    @FXML private StackPane catalogSearchBox;
    @FXML private SearchBarController catalogSearchBarController;
    @FXML private MenuButton filterMenuButton;
    @FXML private Label emptyStateLabel;
    @FXML private ScrollPane scrollPane;
    @FXML private FlowPane cardContainer;

    private StartUpService svc;
    private BorderPane root;
    private CatalogType type;
    private CatalogSort selectedSort = CatalogSort.RECENTLY_ADDED;
    private Image defaultCover;
    private MusicCardActionManager musicActions;
    private ArtistCardActionManager artistActions;
    @SuppressWarnings("unused")
    private GenreCardActionManager genreActions;
    private BiConsumer<DeezerApiMetaData, File> downloadListener;
    private ProgressiveCardFlowRenderer<CatalogEntry> progressiveRenderer;
    private final AtomicLong contentVersion = new AtomicLong();

    private boolean responsiveGridConfigured;
    private boolean contentLoadScheduled;
    private boolean catalogResolved;
    private int pendingRenderedCount;
    private Double pendingScrollValue;
    private LibraryCatalogFilterMenu filterMenu;
    private List<CatalogEntry> allEntries = List.of();
    private String searchQuery = "";
    private boolean searchListenerInstalled;

    @FXML
    private void initialize() {
        applyAmbientBackground();
        configureSearchBar();
        configureFilterMenu();
        Platform.runLater(this::applyAmbientBackground);
    }

    private void applyAmbientBackground() {
        AmbientGradientSupport.applyTopAmbientGradient(screenRoot);
    }

    public void init(StartUpService svc, BorderPane root, CatalogType type) {
        this.svc = svc;
        this.root = root;
        this.type = type;
        restoreSavedSort(type);
        applyAmbientBackground();
        Platform.runLater(this::applyAmbientBackground);
        this.defaultCover = MediaImageResolver.defaultCover();

        PlayerMenuNavigator navigator = new PlayerMenuNavigator(svc);
        ArtistOpenCoordinator artistCoordinator = new ArtistOpenCoordinator(svc, navigator);
        this.musicActions = new MusicCardActionManager(svc, navigator, artistCoordinator);
        this.artistActions = new ArtistCardActionManager(svc, navigator);
        this.genreActions = new GenreCardActionManager(svc);

        configureFilterMenu();
        configureSearchBar();
        updateTitle();
        configureResponsiveGrid();
        registerDownloadListener();
        scheduleContentLoad();
    }

    public CatalogType getType() {
        return type;
    }

    public Map<String, Object> captureState() {
        Map<String, Object> state = new HashMap<>();
        state.put("type", type == null ? null : type.name());
        state.put("sort", selectedSort.name());
        state.put("scrollV", scrollPane == null ? 0.0 : scrollPane.getVvalue());
        state.put("renderedCards", progressiveRenderer == null ? 0 : progressiveRenderer.renderedCount());
        return state;
    }

    public void restoreState(Map<String, Object> state) {
        applyAmbientBackground();
        Platform.runLater(this::applyAmbientBackground);
        if (state == null) return;
        try {
            Object storedType = state.get("type");
            if (storedType instanceof String value && !value.isBlank()) {
                type = CatalogType.valueOf(value);
            }
        } catch (Exception ignored) {
        }

        Object rendered = state.get("renderedCards");
        pendingRenderedCount = rendered instanceof Number number ? Math.max(0, number.intValue()) : 0;
        Object scrollValue = state.get("scrollV");
        pendingScrollValue = scrollValue instanceof Number number ? number.doubleValue() : null;
        Object storedSort = state.get("sort");
        if (storedSort instanceof String value) {
            try {
                selectedSort = CatalogSort.valueOf(value);
            } catch (IllegalArgumentException ignored) {
                selectedSort = CatalogSort.RECENTLY_ADDED;
            }
        }

        configureFilterMenu();
        configureSearchBar();
        updateTitle();
        configureResponsiveGrid();
        registerDownloadListener();
        scheduleContentLoad();
    }

    private void scheduleContentLoad() {
        if (contentLoadScheduled) return;
        contentLoadScheduled = true;
        Platform.runLater(() -> {
            contentLoadScheduled = false;
            loadContent();
        });
    }

    private void loadContent() {
        if (cardContainer == null || scrollPane == null || svc == null || type == null) return;
        configureResponsiveGrid();

        long requestId = contentVersion.incrementAndGet();
        CatalogType requestedType = type;
        CatalogSort requestedSort = selectedSort;
        catalogResolved = false;
        progressiveRenderer.clear();
        updateEmptyState();
        if (pendingScrollValue == null) scrollPane.setVvalue(0);

        ProgressiveCardFlowRenderer.loadAsync(() -> resolveEntries(requestedType, requestedSort))
                .exceptionally(error -> List.of())
                .thenAccept(entries -> Platform.runLater(() -> {
                    if (requestId != contentVersion.get() || requestedType != type) return;
                    catalogResolved = true;
                    allEntries = entries == null ? List.of() : List.copyOf(entries);
                    progressiveRenderer.reset(filterEntries(allEntries, searchQuery));
                    if (pendingRenderedCount > 0) {
                        progressiveRenderer.renderAtLeast(pendingRenderedCount);
                    }
                    updateCatalogControls();
                    updateEmptyState();
                }));
    }

    private List<CatalogEntry> resolveEntries(CatalogType requestedType, CatalogSort requestedSort) {
        return switch (requestedType) {
            case PLAYLISTS -> resolvePlaylistEntries(requestedSort);
            case ALBUMS -> resolveAlbumEntries(requestedSort);
            case SINGLES -> resolveSingleEntries(requestedSort);
        };
    }

    private List<CatalogEntry> resolvePlaylistEntries(CatalogSort requestedSort) {
        List<CatalogEntry> entries = LibraryModelDeduplicator.playlists(svc.getPlaylists())
                .stream()
                .filter(playlist -> playlist != null)
                .map(CatalogEntry::playlist)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (requestedSort == CatalogSort.ALPHABETICAL) {
            entries.sort(Comparator.comparing(entry -> sortText(entryName(entry))));
        }
        return entries;
    }

    private List<CatalogEntry> resolveAlbumEntries(CatalogSort requestedSort) {
        Map<String, ManifestEntry> manifest = LocalSongVerifier.loadManifest(svc);
        List<Song> localSongs = LocalSongVerifier.verifiedLocalSongs(
                LibraryModelDeduplicator.songs(svc.getSongs()),
                manifest
        );
        Set<Long> albumIdsWithLocalSongs = new HashSet<>();
        for (Song song : localSongs) {
            if (song == null || song.getAlbum() == null) continue;
            long albumId = song.getAlbum().getAlbumID();
            if (albumId > 0) albumIdsWithLocalSongs.add(albumId);
        }

        Map<Long, Long> recentByAlbum = recentAlbumTimes(localSongs, manifest);
        List<CatalogEntry> entries = new ArrayList<>();
        for (Album album : LibraryModelDeduplicator.albums(svc.getAlbums())) {
            if (album == null || album.getNumberOfTracks() <= 1) continue;
            if (album.getAlbumID() <= 0 || !albumIdsWithLocalSongs.contains(album.getAlbumID())) continue;
            entries.add(CatalogEntry.album(album, recentByAlbum.getOrDefault(album.getAlbumID(), 0L)));
        }
        sortEntries(entries, requestedSort);
        return entries;
    }

    private List<CatalogEntry> resolveSingleEntries(CatalogSort requestedSort) {
        Map<String, ManifestEntry> manifest = LocalSongVerifier.loadManifest(svc);
        List<Song> localSongs = LocalSongVerifier.verifiedLocalSongs(svc.getSongs(), manifest);
        List<CatalogEntry> entries = new ArrayList<>();
        Set<String> persistedPaths = new HashSet<>();
        Set<String> persistedTitles = new HashSet<>();

        for (Song song : localSongs) {
            if (!isSingle(song)) continue;
            entries.add(CatalogEntry.song(song, recentSongTime(song, manifest)));
            if (song.getFilePath() != null && !song.getFilePath().isBlank()) {
                persistedPaths.add(song.getFilePath().trim().toLowerCase());
            }
            if (song.getTitle() != null && !song.getTitle().isBlank()) {
                persistedTitles.add(song.getTitle().trim().toLowerCase());
            }
        }

        if (svc.noMetadataSongs != null) {
            for (var pair : svc.noMetadataSongs) {
                if (pair == null || pair.getKey() == null) continue;
                String title = pair.getKey();
                String path = pair.getValue();
                boolean persisted = (path != null && persistedPaths.contains(path.trim().toLowerCase()))
                        || persistedTitles.contains(title.trim().toLowerCase());
                if (!persisted) entries.add(CatalogEntry.noMetadata(title, fileLastModified(path)));
            }
        }
        sortEntries(entries, requestedSort);
        return entries;
    }

    private boolean isSingle(Song song) {
        if (song == null || song.getAlbum() == null) return false;
        Album album = song.getAlbum();
        try {
            if (album.getNumberOfTracks() > 0) return album.getNumberOfTracks() == 1;
            return album.getSongList() != null && album.getSongList().size() == 1;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Map<Long, Long> recentAlbumTimes(List<Song> songs, Map<String, ManifestEntry> manifest) {
        Map<Long, Long> result = new HashMap<>();
        if (songs == null) return result;
        for (Song song : songs) {
            if (song == null || song.getAlbum() == null) continue;
            long albumId = song.getAlbum().getAlbumID();
            if (albumId <= 0) continue;
            result.merge(albumId, recentSongTime(song, manifest), Math::max);
        }
        return result;
    }

    private long recentSongTime(Song song, Map<String, ManifestEntry> manifest) {
        if (song == null) return 0L;
        long latest = fileLastModified(song.getFilePath());
        if (manifest == null || manifest.isEmpty()) return latest;

        for (ManifestEntry entry : manifest.values()) {
            if (entry == null) continue;
            if (song.getSongID() > 0 && entry.getDeezerId() == song.getSongID()) {
                latest = Math.max(latest, entry.getLastModified());
            }
        }
        return Math.max(0L, latest);
    }

    private long fileLastModified(String path) {
        if (path == null || path.isBlank()) return 0L;
        try {
            return Math.max(0L, new File(path).lastModified());
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private void sortEntries(List<CatalogEntry> entries, CatalogSort sort) {
        if (entries == null || entries.size() < 2) return;

        Comparator<CatalogEntry> byName = Comparator
                .comparing((CatalogEntry entry) -> sortText(entryName(entry)))
                .thenComparing(Comparator.comparingLong(CatalogEntry::recentlyAddedAt).reversed());
        Comparator<CatalogEntry> byCreator = Comparator
                .comparing((CatalogEntry entry) -> sortText(entryCreator(entry)))
                .thenComparing(byName);
        Comparator<CatalogEntry> byRecent = Comparator
                .comparingLong(CatalogEntry::recentlyAddedAt)
                .reversed()
                .thenComparing(byName);
        Comparator<CatalogEntry> byRelease = (left, right) -> {
            LocalDate leftDate = parseReleaseDate(entryReleaseDate(left));
            LocalDate rightDate = parseReleaseDate(entryReleaseDate(right));
            if (leftDate == null && rightDate == null) return byName.compare(left, right);
            if (leftDate == null) return 1;
            if (rightDate == null) return -1;
            int result = rightDate.compareTo(leftDate);
            return result != 0 ? result : byName.compare(left, right);
        };

        entries.sort(switch (sort == null ? CatalogSort.RECENTLY_ADDED : sort) {
            case ALPHABETICAL -> byName;
            case CREATOR -> byCreator;
            case RELEASE_DATE -> byRelease;
            case RECENTLY_ADDED -> byRecent;
        });
    }

    private String entryName(CatalogEntry entry) {
        if (entry == null) return "";
        return switch (entry.kind()) {
            case ALBUM -> entry.album() == null ? "" : entry.album().getName();
            case SONG -> entry.song() == null ? "" : entry.song().getTitle();
            case PLAYLIST -> entry.playlist() == null ? "" : entry.playlist().getTitle();
            case NO_METADATA -> entry.title();
        };
    }

    private String entryCreator(CatalogEntry entry) {
        if (entry == null) return "";
        return switch (entry.kind()) {
            case ALBUM -> firstValue(CardArtistNameResolver.fromAlbum(entry.album()));
            case SONG -> firstValue(CardArtistNameResolver.fromSingle(entry.song()));
            case PLAYLIST -> entry.playlist() == null ? "" : entry.playlist().getAuthorName();
            case NO_METADATA -> "";
        };
    }

    private String entryReleaseDate(CatalogEntry entry) {
        if (entry == null) return null;
        return switch (entry.kind()) {
            case ALBUM -> entry.album() == null ? null : entry.album().getReleaseDate();
            case SONG -> entry.song() == null || entry.song().getAlbum() == null
                    ? null : entry.song().getAlbum().getReleaseDate();
            case PLAYLIST, NO_METADATA -> null;
        };
    }

    private String firstValue(List<String> values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private String sortText(String value) {
        return value == null || value.isBlank()
                ? "\uFFFF"
                : value.trim().toLowerCase(Locale.ROOT);
    }

    private LocalDate parseReleaseDate(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        try {
            return LocalDate.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception ignored) {
        }
        try {
            return LocalDate.parse(normalized, DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        } catch (Exception ignored) {
        }
        try {
            return LocalDate.parse(normalized, DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        } catch (Exception ignored) {
            return null;
        }
    }

    private void configureFilterMenu() {
        if (filterMenu == null && filterMenuButton != null && type != null) {
            List<LibraryCatalogFilterMenu.Option> options = type == CatalogType.PLAYLISTS
                    ? List.of(
                            new LibraryCatalogFilterMenu.Option(
                                    CatalogSort.RECENTLY_ADDED.name(), CatalogSort.RECENTLY_ADDED.label()),
                            new LibraryCatalogFilterMenu.Option(
                                    CatalogSort.ALPHABETICAL.name(), CatalogSort.ALPHABETICAL.label())
                    )
                    : List.of(
                            new LibraryCatalogFilterMenu.Option(
                                    CatalogSort.RECENTLY_ADDED.name(), CatalogSort.RECENTLY_ADDED.label()),
                            new LibraryCatalogFilterMenu.Option(
                                    CatalogSort.ALPHABETICAL.name(), CatalogSort.ALPHABETICAL.label()),
                            new LibraryCatalogFilterMenu.Option(
                                    CatalogSort.CREATOR.name(), CatalogSort.CREATOR.label()),
                            new LibraryCatalogFilterMenu.Option(
                                    CatalogSort.RELEASE_DATE.name(), CatalogSort.RELEASE_DATE.label())
                    );
            filterMenu = new LibraryCatalogFilterMenu(
                    filterMenuButton,
                    options,
                    selectedSort.name(),
                    option -> {
                        try {
                            applySort(CatalogSort.valueOf(option.id()));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
            );
        }
        if (filterMenu != null) {
            filterMenu.setSelected(selectedSort.name());
            filterMenu.setVisible(false);
        }
    }

    private void configureSearchBar() {
        if (catalogSearchBarController == null) return;

        catalogSearchBarController.useMinimalUnderlineStyle();
        catalogSearchBarController.setPromptText(searchPrompt());
        if (searchListenerInstalled) return;

        searchListenerInstalled = true;
        catalogSearchBarController.getTextField().textProperty().addListener(
                (observable, oldValue, newValue) -> applySearchQuery(newValue)
        );
    }

    private String searchPrompt() {
        if (type == null) return "Search in your library";
        return switch (type) {
            case ALBUMS -> "Search for albums in your library";
            case SINGLES -> "Search for singles in your library";
            case PLAYLISTS -> "Search for playlists in your library";
        };
    }

    private void applySearchQuery(String query) {
        searchQuery = query == null ? "" : query.trim();
        if (!catalogResolved || progressiveRenderer == null) {
            updateEmptyState();
            return;
        }

        pendingRenderedCount = 0;
        pendingScrollValue = null;
        progressiveRenderer.reset(filterEntries(allEntries, searchQuery));
        if (scrollPane != null) scrollPane.setVvalue(0);
        updateEmptyState();
    }

    private List<CatalogEntry> filterEntries(List<CatalogEntry> entries, String query) {
        if (entries == null || entries.isEmpty() || query == null || query.isBlank()) {
            return entries == null ? List.of() : entries;
        }

        String normalizedQuery = query.trim().toLowerCase(Locale.ROOT);
        return entries.stream()
                .filter(entry -> matchesSearch(entry, normalizedQuery))
                .toList();
    }

    private boolean matchesSearch(CatalogEntry entry, String normalizedQuery) {
        if (entry == null || normalizedQuery == null || normalizedQuery.isBlank()) return true;
        if (sortText(entryName(entry)).contains(normalizedQuery)) return true;
        return type != CatalogType.PLAYLISTS
                && sortText(entryCreator(entry)).contains(normalizedQuery);
    }

    private void updateCatalogControls() {
        boolean hasCards = catalogResolved && !allEntries.isEmpty();
        if (catalogTools != null) {
            catalogTools.setVisible(hasCards);
            catalogTools.setManaged(hasCards);
        }
        if (filterMenu != null) {
            boolean filterAvailable = hasCards
                    && filterMenu.selectedOption() != null;
            filterMenu.setVisible(filterAvailable);
        }
    }

    private void applySort(CatalogSort option) {
        if (option == null || option == selectedSort) {
            if (filterMenu != null) filterMenu.setSelected(selectedSort.name());
            return;
        }
        selectedSort = option;
        LibraryCatalogFilterPreferences.saveMusicSort(type, option.name());
        pendingRenderedCount = 0;
        pendingScrollValue = null;
        if (filterMenu != null) filterMenu.setSelected(option.name());
        scheduleContentLoad();
    }

    private void restoreSavedSort(CatalogType catalogType) {
        String savedSort = LibraryCatalogFilterPreferences.loadMusicSort(catalogType);
        if (savedSort == null || savedSort.isBlank()) return;
        try {
            selectedSort = CatalogSort.valueOf(savedSort);
        } catch (IllegalArgumentException ignored) {
            selectedSort = CatalogSort.RECENTLY_ADDED;
        }
    }

    private Parent createCard(CatalogEntry entry) {
        if (entry == null) return null;
        try {
            return switch (entry.kind()) {
                case PLAYLIST -> createPlaylistCard(entry.playlist());
                case ALBUM -> createAlbumCard(entry.album());
                case SONG -> createSongCard(entry.song());
                case NO_METADATA -> createNoMetadataCard(entry.title());
            };
        } catch (Exception ignored) {
            return null;
        }
    }

    private Parent createPlaylistCard(Playlist playlist) throws IOException {
        if (playlist == null) return null;
        MusicCardData data = MusicCardData.playlist(
                String.valueOf(playlist.getId()),
                MusicCardHelper.coverOrDefault(MediaImageResolver.musicCardPlaylistCover(playlist), defaultCover),
                Optional.ofNullable(playlist.getTitle()).orElse("Playlist"),
                List.of(MusicCardData.playlistCreatorLabel(playlist.getAuthorName())),
                musicActions.playlistClick(root),
                musicActions.artistNameClick(root)
        );
        return styleCard(CardFactory.createMusicCard(data));
    }

    private Parent createAlbumCard(Album album) throws IOException {
        if (album == null) return null;
        MusicCardData data = new MusicCardData(
                String.valueOf(album.getAlbumID()),
                MusicCardHelper.coverOrDefault(MediaImageResolver.musicCardAlbumCover(album), defaultCover),
                Optional.ofNullable(album.getName()).orElse("Album"),
                CardArtistNameResolver.fromAlbum(album),
                musicActions.albumClick(root),
                musicActions.artistNameClick(root)
        );
        return styleCard(CardFactory.createMusicCard(data));
    }

    private Parent createSongCard(Song song) throws IOException {
        if (song == null) return null;
        if (isLocalFileWithoutMetadata(song)) {
            return styleCard(CardFactory.createMusicCard(MusicCardData.localFile(
                    String.valueOf(song.getSongID()),
                    MusicCardHelper.coverOrDefault(MediaImageResolver.musicCardSongCover(song), defaultCover),
                    Optional.ofNullable(song.getTitle()).orElse("Unknown"),
                    musicActions.songClick(root)
            )));
        }
        MusicCardData data = new MusicCardData(
                String.valueOf(song.getSongID()),
                MusicCardHelper.coverOrDefault(MediaImageResolver.musicCardSongCover(song), defaultCover),
                Optional.ofNullable(song.getTitle()).orElse("Unknown"),
                type == CatalogType.SINGLES
                        ? CardArtistNameResolver.fromSingle(song)
                        : CardArtistNameResolver.fromSong(song),
                musicActions.songClick(root),
                musicActions.artistNameClick(root)
        );
        return styleCard(CardFactory.createMusicCard(data));
    }

    private Parent createNoMetadataCard(String title) throws IOException {
        MusicCardData data = MusicCardData.localFile(
                "no_meta_" + Optional.ofNullable(title).orElse("unknown"),
                defaultCover,
                Optional.ofNullable(title).orElse("Unknown"),
                musicActions.songClick(root)
        );
        return styleCard(CardFactory.createMusicCard(data));
    }

    private boolean isLocalFileWithoutMetadata(Song song) {
        return song != null && song.isLocal() && song.getSongID() == 0L;
    }

    private Parent styleCard(Parent card) {
        if (card instanceof Region region) {
            region.setMinWidth(MUSIC_CARD_WIDTH);
            region.setPrefWidth(MUSIC_CARD_WIDTH);
            region.setMaxWidth(MUSIC_CARD_WIDTH);
            region.setMinHeight(MUSIC_CARD_HEIGHT);
            region.setPrefHeight(MUSIC_CARD_HEIGHT);
            region.setMaxHeight(MUSIC_CARD_HEIGHT);
        }
        return card;
    }

    private void updateTitle() {
        if (titleLabel != null) {
            titleLabel.setText(type == null ? "" : switch (type) {
                case PLAYLISTS -> "Playlists from your library";
                case ALBUMS -> "Albums from your library";
                case SINGLES -> "Singles from your library";
            });
        }
        configureFilterMenu();
    }

    private void configureResponsiveGrid() {
        if (responsiveGridConfigured || scrollPane == null || cardContainer == null) return;
        responsiveGridConfigured = true;
        cardContainer.prefWrapLengthProperty().bind(scrollPane.widthProperty().subtract(56));
        cardContainer.setMaxWidth(Double.MAX_VALUE);
        progressiveRenderer = new ProgressiveCardFlowRenderer<>(
                scrollPane,
                cardContainer,
                MUSIC_CARD_WIDTH,
                MUSIC_CARD_HEIGHT,
                this::createCard,
                ignored -> {
                    updateEmptyState();
                    restoreScrollWhenReady();
                }
        );
        progressiveRenderer.install();
    }

    private void updateEmptyState() {
        if (emptyStateLabel == null) return;
        boolean noCatalog = catalogResolved && allEntries.isEmpty();
        boolean noResults = catalogResolved
                && !allEntries.isEmpty()
                && progressiveRenderer != null
                && progressiveRenderer.isEmpty()
                && !searchQuery.isBlank();
        boolean empty = noCatalog || noResults;
        emptyStateLabel.getStyleClass().remove("catalog-no-results");
        if (noResults) {
            emptyStateLabel.getStyleClass().add("catalog-no-results");
        }
        emptyStateLabel.setText(noResults ? noResultsMessage() : emptyMessage());
        emptyStateLabel.setVisible(empty);
        emptyStateLabel.setManaged(empty);
    }

    private String noResultsMessage() {
        String query = searchQuery.replace("\"", "'");
        String category = switch (type) {
            case ALBUMS -> "albums";
            case SINGLES -> "singles";
            case PLAYLISTS -> "playlists";
        };
        return "No results found for \"" + query + "\" in your " + category;
    }

    private String emptyMessage() {
        if (type == null) return "";
        return switch (type) {
            case ALBUMS -> "You don't have any albums in your library";
            case SINGLES -> "You don't have any singles in your library";
            case PLAYLISTS -> "You don't have any playlists in your library";
        };
    }

    private void restoreScrollWhenReady() {
        if (pendingScrollValue == null || progressiveRenderer == null) return;
        int target = Math.min(progressiveRenderer.totalCount(), Math.max(0, pendingRenderedCount));
        if (progressiveRenderer.renderedCount() < target) return;
        double value = pendingScrollValue;
        pendingScrollValue = null;
        pendingRenderedCount = 0;
        Platform.runLater(() -> {
            if (scrollPane != null) scrollPane.setVvalue(value);
        });
    }

    private void registerDownloadListener() {
        if (svc == null || downloadListener != null) return;
        downloadListener = (meta, file) -> scheduleContentLoad();
        try {
            svc.addDownloadListener(downloadListener);
        } catch (Exception ignored) {
            downloadListener = null;
        }
    }

    private enum CatalogSort {
        RECENTLY_ADDED("Recently added"),
        ALPHABETICAL("Alphabetical"),
        CREATOR("Creator"),
        RELEASE_DATE("Release date");

        private final String label;

        CatalogSort(String label) {
            this.label = label;
        }

        private String label() {
            return label;
        }
    }

    private enum EntryKind { PLAYLIST, ALBUM, SONG, NO_METADATA }

    private record CatalogEntry(
            EntryKind kind,
            Playlist playlist,
            Album album,
            Song song,
            String title,
            long recentlyAddedAt
    ) {
        private static CatalogEntry playlist(Playlist value) {
            return new CatalogEntry(EntryKind.PLAYLIST, value, null, null, null, 0L);
        }

        private static CatalogEntry album(Album value, long recentlyAddedAt) {
            return new CatalogEntry(EntryKind.ALBUM, null, value, null, null, recentlyAddedAt);
        }

        private static CatalogEntry song(Song value, long recentlyAddedAt) {
            return new CatalogEntry(EntryKind.SONG, null, null, value, null, recentlyAddedAt);
        }

        private static CatalogEntry noMetadata(String title, long recentlyAddedAt) {
            return new CatalogEntry(EntryKind.NO_METADATA, null, null, null, title, recentlyAddedAt);
        }
    }
}
