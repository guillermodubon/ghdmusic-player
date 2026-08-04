package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.actions;

import javafx.scene.Parent;
import javafx.scene.layout.BorderPane;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.managers.PlayerMenuPlaybackBridge;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.util.List;

/** Runtime dependencies supplied by PlayerMenuController to its action coordinator. */
public interface PlayerMenuActionHost {
    PlayerMenuContext actionContext();

    PlaybackManager actionPlaybackManager();

    StartUpService actionStartUpService();

    PlayerMenuPlaybackBridge actionPlaybackBridge();

    PlayerMenuContext.ContentType actionContentType();

    long actionPlaylistId();

    String actionCollectionTitle();

    Parent actionDownloadSidebarOwner();

    BorderPane actionParentRoot();

    BorderPane actionPlayerMenuRoot();

    MusicCardActionManager actionMusicCardManager();

    List<Song> actionAllSongs();

    List<Song> actionPlayableSongs();

    List<Song> actionDownloadableSongs();

    boolean actionIsPlaybackSource();

    boolean actionSongImmediatelyPlayable(Song song);

    void actionPersistPlaybackOrigin(Song song);

    void actionRefreshPlaybackContext();

    void actionRefreshQueue();

    void actionRefreshState();
}
