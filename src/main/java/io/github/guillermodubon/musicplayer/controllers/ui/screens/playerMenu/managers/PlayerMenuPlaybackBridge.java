package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.managers;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.media.MediaPlayer;
import io.github.guillermodubon.musicplayer.controllers.layout.AppShellController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.PlayerMenuController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.queuePane.QueueController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.PlayerMenuBarController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext.ContentType;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class PlayerMenuPlaybackBridge {

    private static final String PLAYER_MENU_BAR_FXML =
            "/io/github/guillermodubon/musicplayer/Views/components/layoutComponents/playerMenuBar/PlayerMenuBar.fxml";

    private final PlayerMenuContext context;
    private final PlaybackManager playbackManager;
    private final StartUpService svc;
    private final Supplier<PlayerMenuController> menuControllerSupplier;
    private final Consumer<Song> playbackOriginPersister;

    public PlayerMenuPlaybackBridge(PlayerMenuContext context,
                                    StartUpService svc,
                                    PlaybackManager playbackManager,
                                    Supplier<PlayerMenuController> menuControllerSupplier,
                                    Consumer<Song> playbackOriginPersister) {
        this.context = context;
        this.svc = svc;
        this.playbackManager = playbackManager == null ? PlaybackManager.getInstance() : playbackManager;
        this.menuControllerSupplier = menuControllerSupplier;
        this.playbackOriginPersister = playbackOriginPersister;
    }

    public void playSongFromView(Song song) {
        if (song == null) return;

        /*
         * A reused cell can deliver a late click after its item changed. Only
         * the exact model currently owned by this PlayerMenu may start
         * playback. This also prevents a non-local visual item from entering
         * the fallback single-song path and accidentally reusing another
         * library file.
        */
        Song currentViewSong = findExactSongInCurrentView(song);
        if (currentViewSong == null || currentViewSong != song
                || !currentViewSong.isLocal()) {
            return;
        }

        context.ensurePlaylistNameFallback();
        playbackManager.setOriginSource(context.getPlaylistName());

        ensurePlayerMenuBarLoaded();
        bindControllersToPlaybackManager();

        List<Song> savedQueue = new ArrayList<>(playbackManager.getQueue());

        List<Song> playList = rebuildPlayableListFromCurrentView(currentViewSong);

        int idx = indexOfSong(playList, currentViewSong);

        if (playList.isEmpty() || idx < 0) {
            // The selected item must already be part of the current view.
            // Do not create a new fallback flow from a stale cell reference.
            return;
        } else {
            context.setCurrentSongList(playList);

            playbackManager.playSongs(
                    playList,
                    idx,
                    context.getCurrentPlaylistInViewId(),
                    context.getCurrentContentTypeInView()
            );
        }

        persistPlaybackOriginIfNeeded(currentViewSong);

        savedQueue.forEach(playbackManager::enqueue);

        QueueController qc = QueueController.getInstance();
        if (qc != null) {
            qc.refreshAll();
        }
    }

    private Song findExactSongInCurrentView(Song requestedSong) {
        if (requestedSong == null || context.getMasterSongList() == null) {
            return null;
        }

        for (Song current : context.getMasterSongList()) {
            if (current == requestedSong) {
                return current;
            }
        }
        return null;
    }

    public void refreshPlaybackContext() {
        Song cur = playbackManager.getCurrentSong();
        if (cur == null || !context.isBarLoaded()) return;

        PlayerMenuBarController bar = context.getPlayerMenuBarController();
        if (bar != null) {
            bar.updateCurrentSong(cur);
            bar.setVolumeSlider(playbackManager.getLastVolume() * 100);
            MediaPlayer player = playbackManager.getCurrentPlayer();
            if (player != null) {
                bar.bindTime(player);
            }
        }

        QueueController qc = QueueController.getInstance();
        if (qc != null) {
            qc.refreshAll();
        }
    }

    public void ensurePlayerMenuBarLoaded() {
        PlayerMenuBarController appShellBar = ensureAppShellPlayerMenuBar();
        if (appShellBar != null) {
            context.setPlayerMenuBarController(appShellBar);
            context.setBarLoaded(true);
            bindControllersToPlaybackManager();
            return;
        }

        if (context.isBarLoaded() && context.getPlayerMenuBarController() != null) {
            bindControllersToPlaybackManager();
            return;
        }

        try {
            Parent barRoot = loadPlayerMenuBar();
            if (barRoot == null) return;

            context.setBarLoaded(true);
            bindControllersToPlaybackManager();

            if (context.getParentRoot() != null) {
                context.getParentRoot().setBottom(barRoot);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Parent loadPlayerMenuBar() throws IOException {
        FXMLLoader barLoader = new FXMLLoader(getClass().getResource(PLAYER_MENU_BAR_FXML));
        Parent barRoot = barLoader.load();

        Object ctrl = barLoader.getController();
        if (ctrl instanceof PlayerMenuBarController barCtrl) {
            barCtrl.init(svc, null, context.getParentRoot());
            context.setPlayerMenuBarController(barCtrl);
        }

        return barRoot;
    }

    private PlayerMenuBarController ensureAppShellPlayerMenuBar() {
        try {
            AppShellController shell = svc == null ? null : svc.getAppShellController();
            if (shell == null) return null;

            shell.ensurePlayerMenuBarLoaded();
            return shell.getPlayerMenuBarController();
        } catch (Exception ignored) {
            return null;
        }
    }

    private void bindControllersToPlaybackManager() {
        PlayerMenuController menuController = menuControllerSupplier == null ? null : menuControllerSupplier.get();
        if (menuController != null) {
            playbackManager.setMenuController(menuController);
        }

        if (context.getPlayerMenuBarController() != null) {
            playbackManager.setBarController(context.getPlayerMenuBarController());
        }
    }

    private void persistPlaybackOriginIfNeeded(Song song) {
        if (playbackOriginPersister == null || song == null) return;
        try {
            playbackOriginPersister.accept(song);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private List<Song> rebuildPlayableListFromCurrentView(Song selectedSong) {
        List<Song> rebuilt = new ArrayList<>();

        List<Song> master = context.getMasterSongList();

        if (master == null || master.isEmpty()) {
            context.setCurrentSongList(List.of());
            return rebuilt;
        }

        for (Song candidate : master) {
            // Keep the selected item in the playback flow even when its file
            // disappeared. PlaybackMediaService must receive it so it can
            // show the missing-file dialog and trigger the visual refresh.
            if (candidate == selectedSong) {
                rebuilt.add(candidate);
                continue;
            }

            // Only retain candidates whose own persisted path is playable.
            // Resolving every candidate here can match a different local file
            // when metadata is incomplete, which corrupts the playback flow.
            if (!isPlayableLocalSong(candidate, false)) {
                continue;
            }

            if (!containsSong(rebuilt, candidate)) {
                rebuilt.add(candidate);
            }
        }

        context.setCurrentSongList(rebuilt);
        return rebuilt;
    }

    private boolean isPlayableLocalSong(Song song, boolean allowPathResolution) {
        if (song == null || !song.isLocal()) {
            return false;
        }

        if (hasUsableFile(song.getFilePath())) {
            return true;
        }

        // Do not resolve a different file when this song still carries its
        // original, now-invalid path.
        if (song.getFilePath() != null && !song.getFilePath().isBlank()) {
            return false;
        }

        if (!allowPathResolution || svc == null) {
            return false;
        }

        try {
            Optional<String> resolved = svc.resolvePathForSong(song);

            if (resolved.isPresent() && hasUsableFile(resolved.get())) {
                song.setFilePath(resolved.get());
                return true;
            }
        } catch (Exception ignored) {
        }

        return false;
    }

    private boolean hasUsableFile(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }

        try {
            File file = new File(filePath);

            return file.exists()
                    && file.isFile()
                    && file.canRead()
                    && file.length() > 0L;
        } catch (Exception ignored) {
            return false;
        }
    }

    private int indexOfSong(List<Song> songs, Song target) {
        if (songs == null || target == null) {
            return -1;
        }

        for (int i = 0; i < songs.size(); i++) {
            if (sameSong(songs.get(i), target)) {
                return i;
            }
        }

        return -1;
    }

    private boolean containsSong(List<Song> songs, Song target) {
        return indexOfSong(songs, target) >= 0;
    }

    private boolean sameSong(Song left, Song right) {
        if (left == null || right == null) {
            return false;
        }

        long leftId = left.getSongID();
        long rightId = right.getSongID();

        if (leftId > 0 && rightId > 0) {
            return leftId == rightId;
        }

        String leftTitle = left.getTitle() == null ? "" : left.getTitle().trim().toLowerCase();
        String rightTitle = right.getTitle() == null ? "" : right.getTitle().trim().toLowerCase();

        return !leftTitle.isBlank() && leftTitle.equals(rightTitle);
    }
}
