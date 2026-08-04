package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.footer;

import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext.ContentType;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.managers.PlayerMenuArtistResolver;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.managers.PlayerMenuPlaybackBridge;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

/** Coordinates the local and remote playlist footer modules. */
public class PlayerMenuPlaylistFooterService {

    private static final String LOADING_REMOTE_PLAYLIST_AUTHOR = "__LOADING_REMOTE_PLAYLIST__";

    private final PlayerMenuContext context;
    private final PlayerMenuPlaylistLocalLibrary localRecommendations;
    private final PlayerMenuPlaylistRemoteSuggestions remoteSuggestions;

    private VBox footerPane;

    public PlayerMenuPlaylistFooterService(PlayerMenuContext context,
                                           StartUpService svc,
                                           PlaybackManager pm,
                                           PlayerMenuArtistResolver artistResolver,
                                           MusicCardActionManager musicActions,
                                           PlayerMenuPlaybackBridge playbackBridge,
                                           javafx.scene.image.Image defaultCover) {
        this.context = context;
        this.localRecommendations = new PlayerMenuPlaylistLocalLibrary(
                context, svc, pm, playbackBridge, null
        );
        this.remoteSuggestions = new PlayerMenuPlaylistRemoteSuggestions(svc, musicActions, null);
    }

    public void bindUi(VBox recContainer,
                       Label recTitleLabel,
                       Button addAllRecommendationsButton,
                       ListView<Song> recList,
                       TextField searchRec,
                       Button btnRefreshRec,
                       VBox footerPane,
                       VBox remoteSuggestionBox) {
        this.footerPane = footerPane;
        localRecommendations.bindUi(
                recContainer,
                recTitleLabel,
                addAllRecommendationsButton,
                recList,
                searchRec,
                btnRefreshRec,
                footerPane
        );
        remoteSuggestions.bindUi(footerPane, remoteSuggestionBox);
    }

    public void bindCallbacks(Runnable refreshSongListView,
                              Runnable refreshQueue,
                              Runnable refreshPlaybackContext) {
        localRecommendations.bindCallbacks(
                refreshSongListView, refreshQueue, refreshPlaybackContext
        );
    }

    public ObservableList<Song> getDisplayedRecommendations() {
        return localRecommendations.displayedRecommendations();
    }

    public void refreshPlaybackIndicators() {
        localRecommendations.refreshPlaybackIndicators();
    }

    /** Hides both playlist footer variants until the active view is known. */
    public void prepareForDeferredLoad() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::prepareForDeferredLoad);
            return;
        }
        localRecommendations.clear();
        remoteSuggestions.hide();
        setFooterVisible(false);
    }

    public void onLocalSongUnavailable(Song song) {
        localRecommendations.onLocalSongUnavailable(song);
    }

    public void populateRecommendations() {
        localRecommendations.populateRecommendations();
    }

    public void refreshForView(Playlist playlist, ContentType type) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> refreshForView(playlist, type));
            return;
        }

        if (isLocalSongWithoutMetadata(playlist, type)) {
            localRecommendations.clear();
            remoteSuggestions.hide();
            setFooterVisible(false);
            return;
        }

        if (type != ContentType.PLAYLIST) {
            localRecommendations.clear();
            remoteSuggestions.hide();
            setFooterVisible(true);
            return;
        }

        if (localRecommendations.isLoadingRemotePlaylist(playlist)) {
            localRecommendations.clear();
            remoteSuggestions.hide();
            setFooterVisible(false);
            return;
        }

        boolean remoteView = localRecommendations.isRemotePlaylistView(playlist);
        if (remoteView) {
            localRecommendations.clear();
            remoteSuggestions.showFor(playlist);
        } else {
            remoteSuggestions.hide();
            localRecommendations.prepareLocalPlaylist(playlist);
        }

        setFooterVisible(remoteView || localRecommendations.hasLocalRecommendationCandidates());
    }

    private boolean isLocalSongWithoutMetadata(Playlist playlist, ContentType type) {
        if (type != ContentType.SINGLE
                || playlist == null
                || playlist.getSongList() == null
                || playlist.getSongList().isEmpty()) {
            return false;
        }

        Song song = playlist.getSongList().get(0);
        return song != null && song.isLocal() && song.getSongID() == 0L;
    }

    public void adjustListHeight(ListView<?> listView) {
        localRecommendations.adjustListHeight(listView);
    }

    private void setFooterVisible(boolean visible) {
        if (footerPane == null) return;
        footerPane.setVisible(visible);
        footerPane.setManaged(visible);
    }
}
