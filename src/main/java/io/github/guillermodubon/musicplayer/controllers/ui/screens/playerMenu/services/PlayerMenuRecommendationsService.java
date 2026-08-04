package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services;

import javafx.collections.ObservableList;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.layout.VBox;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext.ContentType;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.managers.PlayerMenuArtistResolver;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.managers.PlayerMenuPlaybackBridge;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.footer.PlayerMenuPlaylistFooterService;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

public class PlayerMenuRecommendationsService {

    private final PlayerMenuPlaylistFooterService playlistFooterService;

    public PlayerMenuRecommendationsService(PlayerMenuContext context,
                                            StartUpService svc,
                                            PlaybackManager pm,
                                            PlayerMenuArtistResolver artistResolver,
                                            MusicCardActionManager musicActions,
                                            PlayerMenuPlaybackBridge playbackBridge,
                                            Image defaultCover) {
        this.playlistFooterService = new PlayerMenuPlaylistFooterService(
                context,
                svc,
                pm,
                artistResolver,
                musicActions,
                playbackBridge,
                defaultCover
        );
    }

    public void bindUi(VBox recContainer,
                       Label recTitleLabel,
                       Button addAllRecommendationsButton,
                       ListView<Song> recList,
                       TextField searchRec,
                       Button btnRefreshRec,
                       VBox footerPane,
                       VBox remoteSuggestionBox) {
        playlistFooterService.bindUi(
                recContainer,
                recTitleLabel,
                addAllRecommendationsButton,
                recList,
                searchRec,
                btnRefreshRec,
                footerPane,
                remoteSuggestionBox
        );
    }

    public void bindCallbacks(Runnable refreshSongListView,
                              Runnable refreshQueue,
                              Runnable refreshPlaybackContext) {
        playlistFooterService.bindCallbacks(refreshSongListView, refreshQueue, refreshPlaybackContext);
    }


    public void populateRecommendations() {
        playlistFooterService.populateRecommendations();
    }

    public void refreshPlaybackIndicators() {
        playlistFooterService.refreshPlaybackIndicators();
    }

    public void prepareFooterForDeferredLoad() {
        playlistFooterService.prepareForDeferredLoad();
    }

    public void onLocalSongUnavailable(Song song) {
        playlistFooterService.onLocalSongUnavailable(song);
    }

    public void refreshForView(Playlist playlist, ContentType type) {
        playlistFooterService.refreshForView(playlist, type);
    }
}
