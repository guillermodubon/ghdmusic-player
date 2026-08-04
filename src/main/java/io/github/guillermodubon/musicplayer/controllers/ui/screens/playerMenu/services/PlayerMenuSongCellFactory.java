package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services;

import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.util.Callback;
import io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.PlayableSongItemController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.SongItemVisualController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.io.File;
import java.io.IOException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Creates and updates virtualized song cells for the PlayerMenu list.
 *
 * Cell rendering is intentionally isolated from list height, search and
 * local-state reconciliation so JavaFX cell reuse remains easy to reason about.
 */
final class PlayerMenuSongCellFactory {
    private static final double CELL_HEIGHT = 58.0;
    private static final String LIST_COVER_PREFERRED_TYPE = "xl";
    /** Keep the same high-resolution artwork used by the header and previews. */
    private static final double LIST_COVER_DECODE_SIZE = 320.0;

    private final PlayerMenuContext context;
    private final PlayerMenuSongLocalState localState;

    private StartUpService startUpService;
    private MusicCardActionManager musicActions;
    private Consumer<Song> onSongClicked = song -> {};
    private Consumer<Song> onAddToQueue = song -> {};
    private Consumer<Song> onRemoteDetailsRequested = song -> {};
    private BiConsumer<Song, File> onDownloadCompleted = (song, file) -> {};
    private PlayerMenuContext.ContentType activeContentType;
    private Playlist activePlaylist;

    PlayerMenuSongCellFactory(PlayerMenuContext context,
                               PlayerMenuSongLocalState localState) {
        this.context = context;
        this.localState = localState;
    }

    void bindServices(StartUpService startUpService,
                      MusicCardActionManager musicActions,
                      Consumer<Song> onSongClicked,
                      Consumer<Song> onAddToQueue) {
        this.startUpService = startUpService;
        this.musicActions = musicActions;
        this.onSongClicked = onSongClicked == null ? song -> {} : onSongClicked;
        this.onAddToQueue = onAddToQueue == null ? song -> {} : onAddToQueue;
    }

    void bindSource(PlayerMenuContext.ContentType activeContentType, Playlist activePlaylist) {
        this.activeContentType = activeContentType;
        this.activePlaylist = activePlaylist;
    }

    void setRemoteDetailsRequester(Consumer<Song> requester) {
        this.onRemoteDetailsRequested = requester == null ? song -> {} : requester;
    }

    void setDownloadCompleted(BiConsumer<Song, File> callback) {
        this.onDownloadCompleted = callback == null ? (song, file) -> {} : callback;
    }

    Callback<ListView<Song>, ListCell<Song>> callback() {
        return this::createCell;
    }

    void refreshVisibleCellForSong(ListView<Song> listView, Song changedSong) {
        if (listView == null
                || changedSong == null
                || listView.getScene() == null
                || !listView.isVisible()
                || !listView.isManaged()) {
            return;
        }

        try {
            listView.applyCss();
            listView.layout();
            boolean replacedDirectly = false;

            for (Node node : listView.lookupAll(".list-cell")) {
                if (node instanceof SongCell cell && cell.refreshIfMatches(changedSong)) {
                    replacedDirectly = true;
                }
            }

            if (!replacedDirectly) {
                listView.refresh();
            }
            listView.requestLayout();
        } catch (Exception ignored) {
            try {
                listView.refresh();
                listView.requestLayout();
            } catch (Exception ignoredAgain) {
            }
        }
    }

    void refreshPlaybackIndicators(ListView<Song> listView) {
        if (listView == null) return;

        // Playback changes only affect the visual state of existing cells.
        // Avoid ListView.refresh(), which re-runs local-file detection for
        // every item and can make unrelated remote songs look playable.
        for (Node node : listView.lookupAll(".list-cell")) {
            if (node instanceof SongCell cell) {
                cell.refreshPlaybackIndicator();
            }
        }
    }

    private ListCell<Song> createCell(ListView<Song> owner) {
        return new SongCell(owner);
    }

    private final class SongCell extends ListCell<Song> {
        private final ListView<Song> owner;
        private HBox playableGraphic;
        private PlayableSongItemController playableController;
        private HBox visualGraphic;
        private SongItemVisualController visualController;

        private SongCell(ListView<Song> owner) {
            this.owner = owner;
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            setPadding(Insets.EMPTY);
            getStyleClass().add("player-menu-song-list-cell");
        }

        @Override
        protected void updateItem(Song song, boolean empty) {
            super.updateItem(song, empty);
            getStyleClass().removeAll(
                    "playlist-reorder-drop-before",
                    "playlist-reorder-drop-after"
            );

            if (empty || song == null) {
                setOnMousePressed(null);
                if (playableController != null) {
                    playableController.deactivatePlayingState();
                }
                syncKeyboardSelection();
                setGraphic(null);
                setText(null);
                return;
            }

            try {
                if (localState.shouldUsePlayableSongItem(song)) {
                    HBox graphic = ensurePlayableGraphic();
                    playableController.setStartUpService(startUpService);
                    playableController.setCoverImageQuality(
                            LIST_COVER_PREFERRED_TYPE,
                            LIST_COVER_DECODE_SIZE,
                            LIST_COVER_DECODE_SIZE
                    );
                    playableController.setDeferCoverResolution(true);
                    playableController.setDeferPlayUntilRelease(true);
                    playableController.init(
                            song,
                            onSongClicked,
                            name -> {
                                if (musicActions == null || name == null || name.isBlank()) return;
                                musicActions.artistNameClick(graphic).accept(name);
                            },
                            onAddToQueue
                    );
                    setText(null);
                    setGraphic(graphic);
                    playableController.loadCoverAsync(song);
                    syncKeyboardSelection();
                    bindCellPlayAction(this, song);
                } else {
                    if (playableController != null) {
                        playableController.deactivatePlayingState();
                    }

                    HBox graphic = ensureVisualGraphic();
                    visualController.setStartUpService(startUpService);
                    visualController.setArtistHydrationDelegated(!song.isLocal());
                    visualController.setCoverImageQuality(
                            LIST_COVER_PREFERRED_TYPE,
                            LIST_COVER_DECODE_SIZE,
                            LIST_COVER_DECODE_SIZE
                    );
                    visualController.setDeferCoverResolution(true);
                    visualController.setArtistClickHandler(name -> {
                        if (musicActions == null || name == null || name.isBlank()) return;
                        musicActions.artistNameClick(graphic).accept(name);
                    });

                    Playlist sourceModel = activePlaylist != null
                            ? activePlaylist
                            : context.getCurrentPlaylistModel();
                    Long sourceId = null;
                    if (sourceModel != null && sourceModel.getId() > 0) {
                        sourceId = sourceModel.getId();
                    } else if (context.getCurrentPlaylistInViewId() > 0) {
                        sourceId = context.getCurrentPlaylistInViewId();
                    }

                    String sourceTitle = sourceModel != null
                            && sourceModel.getTitle() != null
                            && !sourceModel.getTitle().isBlank()
                            ? sourceModel.getTitle()
                            : context.getPlaylistName();
                    String sourceType = activeContentType == null ? null : activeContentType.name();
                    visualController.configureDownloadSource(sourceId, sourceTitle, sourceType, sourceModel);
                    visualController.setOnDownloadCompleted((downloadedSong, finalFile) -> {
                        if (isAttachedToVisiblePlayerMenu()) {
                            onDownloadCompleted.accept(downloadedSong, finalFile);
                        }
                    });
                    visualController.init(song);

                    setText(null);
                    setGraphic(graphic);
                    visualController.loadCoverAsync(song);
                    syncKeyboardSelection();
                    setOnMousePressed(null);
                    onRemoteDetailsRequested.accept(song);
                }
            } catch (Exception ex) {
                ex.printStackTrace();
                setOnMousePressed(null);
                setText(song.getTitle() == null ? "" : song.getTitle());
                setGraphic(null);
            }
        }

        @Override
        public void updateSelected(boolean selected) {
            super.updateSelected(selected);
            syncKeyboardSelection();
        }

        private void syncKeyboardSelection() {
            boolean selected = isSelected() && getItem() != null && !isEmpty();
            if (playableController != null) {
                playableController.setKeyboardSelected(selected && getGraphic() == playableGraphic);
            }
            if (visualController != null) {
                visualController.setKeyboardSelected(selected && getGraphic() == visualGraphic);
            }
        }

        private HBox ensurePlayableGraphic() throws IOException {
            if (playableGraphic != null && playableController != null) return playableGraphic;
            FXMLLoader loader = new FXMLLoader(PlayerMenuSongCellFactory.class.getResource(
                    "/io/github/guillermodubon/musicplayer/Views/components/items/PlayableSongItemController.fxml"
            ));
            playableGraphic = loader.load();
            playableController = loader.getController();
            prepareGraphic(playableGraphic, owner);
            return playableGraphic;
        }

        private HBox ensureVisualGraphic() throws IOException {
            if (visualGraphic != null && visualController != null) return visualGraphic;
            FXMLLoader loader = new FXMLLoader(PlayerMenuSongCellFactory.class.getResource(
                    "/io/github/guillermodubon/musicplayer/Views/components/items/SongItemVisual.fxml"
            ));
            visualGraphic = loader.load();
            visualController = loader.getController();
            prepareGraphic(visualGraphic, owner);
            return visualGraphic;
        }

        private boolean refreshIfMatches(Song changedSong) {
            if (changedSong == null
                    || isEmpty()
                    || getIndex() < 0
                    || owner == null
                    || owner.getItems() == null
                    || getIndex() >= owner.getItems().size()) {
                return false;
            }

            Song currentItem = owner.getItems().get(getIndex());
            if (!localState.sameSongForImmediateRefresh(currentItem, changedSong)
                    || !currentItem.isLocal()
                    || !localState.hasUsableAudioFile(currentItem.getFilePath())) {
                return false;
            }

            // A track ID can occur in multiple albums. Render the item held
            // by this list position so its album-specific artist metadata is
            // not replaced by the first matching occurrence.
            updateItem(currentItem, false);
            requestLayout();
            if (owner != null) owner.requestLayout();
            return getGraphic() == playableGraphic;
        }

        private void refreshPlaybackIndicator() {
            if (playableController != null && getGraphic() == playableGraphic) {
                playableController.refreshPlayingState();
            }
        }

        private boolean displaysPlayableGraphic() {
            return getGraphic() == playableGraphic;
        }

        private boolean isAttachedToVisiblePlayerMenu() {
            if (owner == null || owner.getScene() == null || getScene() == null) return false;
            if (owner.getScene() != getScene()) return false;
            if (!owner.isVisible() || !owner.isManaged() || !isVisible() || !isManaged()) return false;
            return owner.getParent() != null && getParent() != null;
        }
    }

    private void prepareGraphic(HBox graphic, ListView<Song> owner) {
        if (graphic == null) return;
        graphic.setMinHeight(CELL_HEIGHT);
        graphic.setPrefHeight(CELL_HEIGHT);
        graphic.setMaxHeight(CELL_HEIGHT);
        graphic.setMaxWidth(Double.MAX_VALUE);
        if (owner != null && !graphic.prefWidthProperty().isBound()) {
            graphic.prefWidthProperty().bind(owner.widthProperty().subtract(CELL_HEIGHT));
        }
    }

    private void bindCellPlayAction(ListCell<Song> cell, Song song) {
        if (cell == null) return;

        /*
         * VirtualFlow reuses ListCell instances. Keep the rendered model as
         * part of the handler contract so a late mouse event cannot invoke
         * playback for the song that occupied this cell previously.
         */
        Song renderedSong = song;
        cell.setOnMouseReleased(event -> {
            Song currentItem = cell.getItem();
            if (event == null
                    || event.isConsumed()
                    || renderedSong == null
                    || currentItem != renderedSong
                    || !(cell instanceof SongCell songCell)
                    || !songCell.displaysPlayableGraphic()
                    || event.getButton() != MouseButton.PRIMARY
                    || event.getClickCount() != 1
                    || isInteractiveClickTarget(event.getTarget())) {
                return;
            }

            onSongClicked.accept(currentItem);
            event.consume();
        });
    }

    private boolean isInteractiveClickTarget(Object target) {
        if (!(target instanceof Node node)) return false;
        Node current = node;
        while (current != null) {
            if (current instanceof ButtonBase) return true;
            current = current.getParent();
        }
        return false;
    }
}
