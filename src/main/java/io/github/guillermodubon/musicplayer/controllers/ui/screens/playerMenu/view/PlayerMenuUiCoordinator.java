package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.view;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.control.ListView;
import io.github.guillermodubon.musicplayer.controllers.ui.components.effects.MarqueeTextSupport;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.actions.PlayerMenuActionCoordinator;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext.ContentType;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.layout.PlayerMenuResponsiveLayout;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.PlayerMenuSongListService;
import io.github.guillermodubon.musicplayer.models.Playlist;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Coordinates presentation-only behavior for the PlayerMenu screen. */
public final class PlayerMenuUiCoordinator {
    private static final int VIRTUALIZED_VISIBLE_ROWS = 12;
    private static final double LIST_MIN_HEIGHT = 180.0;
    private static final double LIST_MAX_HEIGHT = 820.0;

    private final PlayerMenuUiBindings ui;
    private final PlayerMenuContext context;
    private final PlayerMenuActionCoordinator actionCoordinator;
    private MarqueeTextSupport headerArtistMarquee;
    private PlayerMenuResponsiveLayout responsiveLayout;
    private final AtomicBoolean songListStateRefreshQueued = new AtomicBoolean(false);

    public PlayerMenuUiCoordinator(PlayerMenuUiBindings ui,
                                   PlayerMenuContext context,
                                   PlayerMenuActionCoordinator actionCoordinator) {
        this.ui = Objects.requireNonNull(ui, "ui");
        this.context = Objects.requireNonNull(context, "context");
        this.actionCoordinator = Objects.requireNonNull(actionCoordinator, "actionCoordinator");
    }

    public void initialize(Runnable onSongListChanged) {
        bindSearchBarComponents();
        configureResponsiveLayout();
        configureHeaderArtistMarquee();
        configureActionButtons();
        configureSongListStateListener(onSongListChanged);
    }

    public void syncSongListUiState(ContentType type,
                                    boolean emptyUserPlaylist,
                                    PlayerMenuSongListService songListService,
                                    Runnable refreshPlaylistHeaderActions) {
        setManagedVisible(ui.searchSongRow(), !emptyUserPlaylist);
        setManagedVisible(ui.playerMenuActionButtons(), !emptyUserPlaylist);

        boolean single = type == ContentType.SINGLE;
        setManagedVisible(ui.randomVisibleSongsButton(), !emptyUserPlaylist && !single);
        setManagedVisible(ui.downloadAllButton(), !emptyUserPlaylist && !single);

        actionCoordinator.updateActionState();
        if (songListService != null) songListService.refreshListState();
        actionCoordinator.updateTooltips();
        if (refreshPlaylistHeaderActions != null) refreshPlaylistHeaderActions.run();
    }

    public void configureRemoteSaveCheckBoxInitialState() {
        if (ui.remoteSaveCheckBox() == null) return;

        ui.remoteSaveCheckBox().setText("");
        ui.remoteSaveCheckBox().setSelected(false);
        ui.remoteSaveCheckBox().setVisible(false);
        ui.remoteSaveCheckBox().setManaged(false);
        ui.remoteSaveCheckBox().setDisable(false);
        ui.remoteSaveCheckBox().setFocusTraversable(false);

        if (!ui.remoteSaveCheckBox().getStyleClass().contains("remote-library-check-button")) {
            ui.remoteSaveCheckBox().getStyleClass().add("remote-library-check-button");
        }
    }

    public void adjustListHeightFallback(ListView<?> listView) {
        if (listView == null) return;
        double cell = listView.getFixedCellSize() > 0 ? listView.getFixedCellSize() : 24;
        int count = listView.getItems() == null ? 0 : listView.getItems().size();
        double height = count == 0
                ? LIST_MIN_HEIGHT
                : count == 1
                ? Math.max(58.0, cell + 12.0)
                : Math.min(LIST_MAX_HEIGHT,
                        Math.max(LIST_MIN_HEIGHT,
                                Math.min(count, VIRTUALIZED_VISIBLE_ROWS) * cell + 8));
        listView.setMinHeight(height);
        listView.setPrefHeight(height);
        listView.setMaxHeight(height);
    }

    public void updateSongSearchPrompt(Playlist playlist, ContentType type) {
        if (ui.songSearchBarController() == null) return;
        String title = playlist == null ? "" : Objects.toString(playlist.getTitle(), "").trim();
        String fallback = type == ContentType.PLAYLIST ? "playlist" : "album";
        ui.songSearchBarController().setPromptText(
                "Search in " + (title.isBlank() ? fallback : title));
    }

    public void setManagedVisible(Node node, boolean visible) {
        if (node == null) return;
        node.setVisible(visible);
        node.setManaged(visible);
    }

    public javafx.scene.control.TextField songSearchField() {
        return ui.songSearchField();
    }

    public javafx.scene.control.TextField recommendationSearchField() {
        return ui.recommendationSearchField();
    }

    public ListView<io.github.guillermodubon.musicplayer.models.Song> songsView() {
        return ui.resolvedSongsView();
    }

    public PlayerMenuUiBindings bindings() {
        return ui;
    }

    /** Finalizes responsive geometry after the header and virtualized list update together. */
    public void settleResponsiveLayout() {
        if (responsiveLayout != null) responsiveLayout.settleAfterContentUpdate();
    }

    private void bindSearchBarComponents() {
        if (ui.songSearchBarController() != null) {
            ui.songSearchBarController().useMinimalUnderlineStyle();
            ui.songSearchBarController().setPromptText("Search in list...");
        }
        if (ui.recommendationSearchBarController() != null) {
            ui.recommendationSearchBarController().useMinimalUnderlineStyle();
            ui.recommendationSearchBarController().setPromptText("Search through your library");
        }
    }

    private void configureActionButtons() {
        actionCoordinator.configure(
                ui.playVisibleSongsButton(),
                ui.randomVisibleSongsButton(),
                ui.addVisibleSongsToPlaylistButton(),
                ui.downloadAllButton()
        );
    }

    private void configureSongListStateListener(Runnable onSongListChanged) {
        if (onSongListChanged == null) return;
        context.getMasterSongList().addListener((ListChangeListener<io.github.guillermodubon.musicplayer.models.Song>) change -> {
            if (!songListStateRefreshQueued.compareAndSet(false, true)) return;
            Platform.runLater(() -> {
                try {
                    onSongListChanged.run();
                } finally {
                    songListStateRefreshQueued.set(false);
                }
            });
        });
    }

    private void configureResponsiveLayout() {
        if (responsiveLayout == null) {
            responsiveLayout = new PlayerMenuResponsiveLayout(
                    ui.playerMenuRoot(),
                    ui.playerMenuSurface(),
                    ui.playerMenuHeader(),
                    ui.headerCoverShell(),
                    ui.headerOptionsSlot(),
                    ui.songSearchBox(),
                    ui.recommendationSearchBox(),
                    ui.songListVirtualShell(),
                    ui.resolvedHeaderCover(),
                    ui.headerTitle(),
                    ui.playerMenuScroll()
            );
        }
        responsiveLayout.configure();
    }

    private void refreshResponsiveLayout() {
        if (responsiveLayout != null) responsiveLayout.refresh();
    }

    private void configureHeaderArtistMarquee() {
        if (ui.creatorViewport() == null || ui.creatorContainer() == null || headerArtistMarquee != null) {
            return;
        }

        headerArtistMarquee = new MarqueeTextSupport(
                null, null, null, ui.creatorViewport(), ui.creatorContainer());
        headerArtistMarquee.installHover(ui.creatorViewport());
        headerArtistMarquee.setBeforeStart(this::loadDeferredHeaderArtistPortraits);
        ui.creatorViewport().widthProperty().addListener((obs, oldValue, newValue) -> refreshHeaderArtistMarquee());
        ui.creatorContainer().getChildren().addListener((ListChangeListener<Node>) change -> {
            boolean originalContentChanged = false;
            while (change.next()) {
                originalContentChanged |= change.getAddedSubList().stream()
                        .anyMatch(this::isOriginalMarqueeNode);
                originalContentChanged |= change.getRemoved().stream()
                        .anyMatch(this::isOriginalMarqueeNode);
            }
            if (originalContentChanged) refreshHeaderArtistMarquee();
        });
    }

    private boolean isOriginalMarqueeNode(Node node) {
        return node != null && !Boolean.TRUE.equals(
                node.getProperties().get("sharedMarqueeDuplicate"));
    }

    private void loadDeferredHeaderArtistPortraits() {
        if (ui.creatorContainer() == null) return;
        for (Node node : ui.creatorContainer().getChildren()) {
            Object loader = node.getProperties().remove("deferredArtistPortraitLoader");
            if (loader instanceof Runnable runnable) runnable.run();
        }
    }

    private void refreshHeaderArtistMarquee() {
        if (headerArtistMarquee != null) headerArtistMarquee.refresh();
    }
}
