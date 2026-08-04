package io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers;

import javafx.scene.Node;
import io.github.guillermodubon.musicplayer.services.navigation.PlayerMenuNavigator;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.models.Artist;

import java.util.function.Consumer;

public class MusicCardActionManager {

    private final SongPlaybackCoordinator songCoordinator;
    private final AlbumPlaybackCoordinator albumCoordinator;
    private final PlaylistPlaybackCoordinator playlistCoordinator;
    private final ArtistOpenCoordinator artistCoordinator;

    public MusicCardActionManager(StartUpService svc,
                                  PlayerMenuNavigator navigator,
                                  ArtistOpenCoordinator artistCoordinator) {
        this.songCoordinator = new SongPlaybackCoordinator(svc, navigator);
        this.albumCoordinator = new AlbumPlaybackCoordinator(svc, navigator);
        this.playlistCoordinator = new PlaylistPlaybackCoordinator(svc, navigator);
        this.artistCoordinator = artistCoordinator;

        if (navigator != null) {
            navigator.setMusicCardActionManager(this);
        }
    }

    public Consumer<String> songClick(Node probe) {
        return id -> songCoordinator.handle(id, probe);
    }

    public Consumer<String> albumClick(Node probe) {
        return id -> albumCoordinator.handle(id, probe);
    }

    public Consumer<String> playlistClick(Node probe) {
        return id -> playlistCoordinator.handle(id, probe);
    }

    public Consumer<String> artistNameClick(Node probe) {
        return name -> artistCoordinator.handle(name, probe);
    }

    /** Opens the exact artist represented by the supplied Deezer/local ID. */
    public Consumer<Artist> artistClick(Node probe) {
        return artist -> {
            if (artist != null) {
                artistCoordinator.handle(artist, probe);
            }
        };
    }

    public void playFirstTrackFromAlbumAsSingle(String albumIdStr, Node probe) {
        albumCoordinator.playFirstTrackFromAlbumAsSingle(albumIdStr, probe);
    }
}
