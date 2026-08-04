package io.github.guillermodubon.musicplayer.controllers.ui.screens.libraryCatalogListScreens;

import io.github.guillermodubon.musicplayer.models.ManifestEntry;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import io.github.guillermodubon.musicplayer.services.navigation.PlayerMenuNavigator;
import io.github.guillermodubon.musicplayer.controllers.ui.components.inputs.SearchBarController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.ArtistCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.factory.CardFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.libraryCatalogListScreens.common.CatalogType;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.libraryCatalogListScreens.common.LibraryCatalogFilterMenu;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.libraryCatalogListScreens.common.LibraryCatalogFilterPreferences;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.libraryCatalogListScreens.common.ProgressiveCardFlowRenderer;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.common.AmbientGradientSupport;
import io.github.guillermodubon.musicplayer.utils.LibraryModelDeduplicator;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.ArtistCardActionManager;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.ArtistOpenCoordinator;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.DeezerApiMetaData;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.utils.LocalSongVerifier;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

public class LibraryArtistsCatalogListController {

    private static final double ARTIST_CARD_WIDTH = 156;
    private static final double ARTIST_CARD_HEIGHT = 176;

    @FXML private BorderPane screenRoot;
    @FXML private Label titleLabel;
    @FXML private HBox catalogTools;
    @FXML private StackPane catalogSearchBox;
    @FXML private SearchBarController catalogSearchBarController;
    @FXML private MenuButton filterMenuButton;
    @FXML private Label emptyStateLabel;
    @FXML private ScrollPane scrollPane;
    @FXML private FlowPane artistFlow;

    private StartUpService svc;
    private BorderPane root;
    private ArtistCardActionManager artistActions;
    @SuppressWarnings("unused")
    private MusicCardActionManager musicActions;
    private BiConsumer<DeezerApiMetaData, File> downloadListener;
    private ProgressiveCardFlowRenderer<Artist> progressiveRenderer;
    private final AtomicLong contentVersion = new AtomicLong();
    private ArtistSort selectedSort = ArtistSort.RECENTLY_ADDED;
    private LibraryCatalogFilterMenu filterMenu;

    private boolean responsiveGridConfigured;
    private boolean contentLoadScheduled;
    private boolean catalogResolved;
    private int pendingRenderedCount;
    private Double pendingScrollValue;
    private List<Artist> allArtists = List.of();
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

    public void init(StartUpService svc, BorderPane root) {
        this.svc = svc;
        this.root = root;
        restoreSavedSort();
        applyAmbientBackground();
        Platform.runLater(this::applyAmbientBackground);

        PlayerMenuNavigator navigator = new PlayerMenuNavigator(svc);
        ArtistOpenCoordinator artistCoordinator = new ArtistOpenCoordinator(svc, navigator);
        this.artistActions = new ArtistCardActionManager(svc, navigator);
        this.musicActions = new MusicCardActionManager(svc, navigator, artistCoordinator);

        configureFilterMenu();
        configureSearchBar();
        if (titleLabel != null) titleLabel.setText("Artists from your library");
        configureResponsiveGrid();
        attachDownloadListener();
        scheduleContentLoad();
    }

    public CatalogType getType() {
        return null;
    }

    public Map<String, Object> captureState() {
        Map<String, Object> state = new HashMap<>();
        state.put("sort", selectedSort.name());
        state.put("scrollV", scrollPane == null ? 0.0 : scrollPane.getVvalue());
        state.put("renderedCards", progressiveRenderer == null ? 0 : progressiveRenderer.renderedCount());
        return state;
    }

    public void restoreState(Map<String, Object> state) {
        applyAmbientBackground();
        Platform.runLater(this::applyAmbientBackground);
        Object rendered = state == null ? null : state.get("renderedCards");
        pendingRenderedCount = rendered instanceof Number number ? Math.max(0, number.intValue()) : 0;
        Object scrollValue = state == null ? null : state.get("scrollV");
        pendingScrollValue = scrollValue instanceof Number number ? number.doubleValue() : null;
        Object storedSort = state == null ? null : state.get("sort");
        if (storedSort instanceof String value) {
            try {
                selectedSort = ArtistSort.valueOf(value);
            } catch (IllegalArgumentException ignored) {
                selectedSort = ArtistSort.RECENTLY_ADDED;
            }
        }

        configureFilterMenu();
        configureSearchBar();
        configureResponsiveGrid();
        attachDownloadListener();
        scheduleContentLoad();
    }

    public void onDetached() {
        contentVersion.incrementAndGet();
        if (downloadListener != null && svc != null) {
            try {
                svc.removeDownloadListener(downloadListener);
            } catch (Exception ignored) {
            }
            downloadListener = null;
        }
    }

    public void loadAllArtists() {
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
        if (artistFlow == null || scrollPane == null || svc == null) return;
        configureResponsiveGrid();

        long requestId = contentVersion.incrementAndGet();
        ArtistSort requestedSort = selectedSort;
        catalogResolved = false;
        progressiveRenderer.clear();
        updateEmptyState();
        if (pendingScrollValue == null) scrollPane.setVvalue(0);

        ProgressiveCardFlowRenderer.loadAsync(() -> resolveArtists(requestedSort))
                .exceptionally(error -> List.of())
                .thenAccept(artists -> Platform.runLater(() -> {
                    if (requestId != contentVersion.get()) return;
                    catalogResolved = true;
                    allArtists = artists == null ? List.of() : List.copyOf(artists);
                    progressiveRenderer.reset(filterArtists(allArtists, searchQuery));
                    if (pendingRenderedCount > 0) {
                        progressiveRenderer.renderAtLeast(pendingRenderedCount);
                    }
                    updateCatalogControls();
                    updateEmptyState();
                }));
    }

    private List<Artist> resolveArtists(ArtistSort sort) {
        List<Artist> artists = svc.getArtists() == null
                ? new ArrayList<>()
                : new ArrayList<>(LibraryModelDeduplicator.artists(svc.getArtists()));

        Comparator<Artist> alphabetical = Comparator.comparing(this::artistSortName);
        if (sort == ArtistSort.ALPHABETICAL) {
            artists.sort(alphabetical);
            return artists;
        }

        ArtistRecentTimes recentTimes = recentArtistTimes();
        artists.sort(Comparator
                .comparingLong((Artist artist) -> recentTimes.timeFor(artist))
                .reversed()
                .thenComparing(alphabetical));
        return artists;
    }

    private ArtistRecentTimes recentArtistTimes() {
        Map<Long, Long> byId = new HashMap<>();
        Map<String, Long> byName = new HashMap<>();
        Map<String, ManifestEntry> manifest = LocalSongVerifier.loadManifest(svc);
        List<Song> songs = LocalSongVerifier.verifiedLocalSongs(svc.getSongs(), manifest);

        for (Song song : songs) {
            if (song == null) continue;
            long timestamp = recentSongTime(song, manifest);
            addArtistTime(song.getArtist(), timestamp, byId, byName);
            if (song.getAlbum() != null) {
                addArtistTime(song.getAlbum().getArtist(), timestamp, byId, byName);
            }
        }
        return new ArtistRecentTimes(byId, byName);
    }

    private void addArtistTime(List<Artist> artists,
                               long timestamp,
                               Map<Long, Long> byId,
                               Map<String, Long> byName) {
        if (artists == null) return;
        for (Artist artist : artists) {
            if (artist == null) continue;
            if (artist.getArtistID() > 0) {
                byId.merge(artist.getArtistID(), timestamp, Math::max);
            }
            String name = artistSortName(artist);
            if (!name.isBlank()) byName.merge(name, timestamp, Math::max);
        }
    }

    private long recentSongTime(Song song, Map<String, ManifestEntry> manifest) {
        if (song == null) return 0L;
        long latest = fileLastModified(song.getFilePath());
        if (manifest == null || manifest.isEmpty()) return latest;
        for (ManifestEntry entry : manifest.values()) {
            if (entry != null && song.getSongID() > 0 && entry.getDeezerId() == song.getSongID()) {
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

    private String artistSortName(Artist artist) {
        if (artist == null || artist.getName() == null || artist.getName().isBlank()) return "\uFFFF";
        return artist.getName().trim().toLowerCase(Locale.ROOT);
    }

    private void configureFilterMenu() {
        if (filterMenu == null && filterMenuButton != null) {
            filterMenu = new LibraryCatalogFilterMenu(
                    filterMenuButton,
                    List.of(
                            new LibraryCatalogFilterMenu.Option(
                                    ArtistSort.RECENTLY_ADDED.name(), ArtistSort.RECENTLY_ADDED.label()),
                            new LibraryCatalogFilterMenu.Option(
                                    ArtistSort.ALPHABETICAL.name(), ArtistSort.ALPHABETICAL.label())
                    ),
                    selectedSort.name(),
                    option -> {
                        try {
                            applySort(ArtistSort.valueOf(option.id()));
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
        catalogSearchBarController.setPromptText("Search for artists in your library");
        if (searchListenerInstalled) return;

        searchListenerInstalled = true;
        catalogSearchBarController.getTextField().textProperty().addListener(
                (observable, oldValue, newValue) -> applySearchQuery(newValue)
        );
    }

    private void applySearchQuery(String query) {
        searchQuery = query == null ? "" : query.trim();
        if (!catalogResolved || progressiveRenderer == null) {
            updateEmptyState();
            return;
        }

        pendingRenderedCount = 0;
        pendingScrollValue = null;
        progressiveRenderer.reset(filterArtists(allArtists, searchQuery));
        if (scrollPane != null) scrollPane.setVvalue(0);
        updateEmptyState();
    }

    private List<Artist> filterArtists(List<Artist> artists, String query) {
        if (artists == null || artists.isEmpty() || query == null || query.isBlank()) {
            return artists == null ? List.of() : artists;
        }

        String normalizedQuery = query.trim().toLowerCase(Locale.ROOT);
        return artists.stream()
                .filter(artist -> artist != null
                        && artist.getName() != null
                        && artist.getName().toLowerCase(Locale.ROOT).contains(normalizedQuery))
                .toList();
    }

    private void updateCatalogControls() {
        boolean hasCards = catalogResolved && !allArtists.isEmpty();
        if (catalogTools != null) {
            catalogTools.setVisible(hasCards);
            catalogTools.setManaged(hasCards);
        }
        if (filterMenu != null) filterMenu.setVisible(hasCards);
    }

    private void applySort(ArtistSort option) {
        if (option == null || option == selectedSort) {
            if (filterMenu != null) filterMenu.setSelected(selectedSort.name());
            return;
        }
        selectedSort = option;
        LibraryCatalogFilterPreferences.saveArtistSort(option.name());
        pendingRenderedCount = 0;
        pendingScrollValue = null;
        if (filterMenu != null) filterMenu.setSelected(option.name());
        scheduleContentLoad();
    }

    private void restoreSavedSort() {
        String savedSort = LibraryCatalogFilterPreferences.loadArtistSort();
        if (savedSort == null || savedSort.isBlank()) return;
        try {
            selectedSort = ArtistSort.valueOf(savedSort);
        } catch (IllegalArgumentException ignored) {
            selectedSort = ArtistSort.RECENTLY_ADDED;
        }
    }

    private void configureResponsiveGrid() {
        if (responsiveGridConfigured || scrollPane == null || artistFlow == null) return;
        responsiveGridConfigured = true;

        artistFlow.prefWrapLengthProperty().bind(scrollPane.widthProperty().subtract(56));
        artistFlow.setMaxWidth(Double.MAX_VALUE);
        progressiveRenderer = new ProgressiveCardFlowRenderer<>(
                scrollPane,
                artistFlow,
                ARTIST_CARD_WIDTH,
                ARTIST_CARD_HEIGHT,
                this::createArtistCard,
                ignored -> {
                    updateEmptyState();
                    restoreScrollWhenReady();
                }
        );
        progressiveRenderer.install();
    }

    private void updateEmptyState() {
        if (emptyStateLabel == null) return;
        boolean noCatalog = catalogResolved && allArtists.isEmpty();
        boolean noResults = catalogResolved
                && !allArtists.isEmpty()
                && progressiveRenderer != null
                && progressiveRenderer.isEmpty()
                && !searchQuery.isBlank();
        boolean empty = noCatalog || noResults;
        emptyStateLabel.getStyleClass().remove("catalog-no-results");
        if (noResults) {
            emptyStateLabel.getStyleClass().add("catalog-no-results");
        }
        emptyStateLabel.setText(noResults ? noResultsMessage() : "You don't have any artists in your library");
        emptyStateLabel.setVisible(empty);
        emptyStateLabel.setManaged(empty);
    }

    private String noResultsMessage() {
        String query = searchQuery.replace("\"", "'");
        return "No results found for \"" + query + "\" in your artists";
    }

    private Parent createArtistCard(Artist artist) {
        if (artist == null) return null;
        try {
            Parent card = CardFactory.createArtistCard(new ArtistCardData(artist, artistActions.artistClick(root)));
            if (card instanceof Region region) {
                region.setMinWidth(ARTIST_CARD_WIDTH);
                region.setPrefWidth(ARTIST_CARD_WIDTH);
                region.setMaxWidth(ARTIST_CARD_WIDTH);
                region.setMinHeight(ARTIST_CARD_HEIGHT);
                region.setPrefHeight(ARTIST_CARD_HEIGHT);
                region.setMaxHeight(ARTIST_CARD_HEIGHT);
            }
            return card;
        } catch (Exception ignored) {
            return null;
        }
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

    private void attachDownloadListener() {
        if (svc == null || downloadListener != null) return;
        downloadListener = (meta, file) -> scheduleContentLoad();
        try {
            svc.addDownloadListener(downloadListener);
        } catch (Exception ignored) {
            downloadListener = null;
        }
    }

    private enum ArtistSort {
        RECENTLY_ADDED("Recently added"),
        ALPHABETICAL("Alphabetical");

        private final String label;

        ArtistSort(String label) {
            this.label = label;
        }

        private String label() {
            return label;
        }
    }

    private record ArtistRecentTimes(Map<Long, Long> byId, Map<String, Long> byName) {
        private long timeFor(Artist artist) {
            if (artist == null) return 0L;
            long byArtistId = artist.getArtistID() > 0
                    ? byId.getOrDefault(artist.getArtistID(), 0L)
                    : 0L;
            long byArtistName = byName.getOrDefault(
                    artist.getName() == null ? "" : artist.getName().trim().toLowerCase(Locale.ROOT),
                    0L
            );
            return Math.max(byArtistId, byArtistName);
        }
    }
}
