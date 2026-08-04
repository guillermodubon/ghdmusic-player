package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.services;

import javafx.application.Platform;
import javafx.scene.image.Image;
import io.github.guillermodubon.musicplayer.controllers.layout.AppShellController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.artwork.PlayerFullScreenArtworkResolver;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.layout.PlayerFullScreenShellAdapter;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.state.PlayerFullScreenModeState;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class PlayerFullScreenModeService {

    private final PlayerFullScreenModeState state = new PlayerFullScreenModeState();
    private final PlayerFullScreenShellAdapter shellAdapter = new PlayerFullScreenShellAdapter();
    private final PlayerFullScreenArtworkResolver artworkResolver = new PlayerFullScreenArtworkResolver();
    private final ExecutorService artworkExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "player-full-screen-artwork");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong artworkRun = new AtomicLong();
    private final AtomicBoolean playbackListenerRegistered = new AtomicBoolean(false);

    public boolean toggle(StartUpService svc, Song currentSong) {
        if (state.isActive()) {
            exit(svc);
            return false;
        }
        enter(svc, currentSong);
        return state.isActive();
    }

    public boolean isActive() {
        return state.isActive();
    }


    public void updateCurrentSong(StartUpService svc, Song currentSong) {
        if (!state.isActive()) return;
        AppShellController shell = shell(svc);
        if (shell == null) return;

        long run = artworkRun.incrementAndGet();
        Image immediate = artworkResolver.resolveImmediateCover(svc, currentSong);
        runOnFx(() -> {
            if (state.isActive() && artworkRun.get() == run) {
                shellAdapter.refreshActiveLayout(shell, state);
                shellAdapter.updateBackground(shell, immediate);
            }
        });

        CompletableFuture
                .supplyAsync(() -> artworkResolver.resolveBestCover(svc, currentSong), artworkExecutor)
                .thenAccept(image -> runOnFx(() -> {
                    if (state.isActive() && artworkRun.get() == run) {
                        shellAdapter.refreshActiveLayout(shell, state);
                        shellAdapter.updateBackground(shell, image);
                    }
                }));
    }

    private void enter(StartUpService svc, Song currentSong) {
        AppShellController shell = shell(svc);
        if (shell == null) return;

        ensurePlaybackListener(svc);
        Image immediate = artworkResolver.resolveImmediateCover(svc, currentSong);

        runOnFx(() -> {
            if (state.isActive()) return;
            boolean entered = shellAdapter.enter(shell, state, immediate);
            state.setActive(entered);
            if (entered) updateCurrentSong(svc, currentSong);
        });
    }

    private void exit(StartUpService svc) {
        AppShellController shell = shell(svc);
        runOnFx(() -> {
            if (shell != null) shellAdapter.exit(shell, state);
            state.setActive(false);
            artworkRun.incrementAndGet();
        });
    }

    private void ensurePlaybackListener(StartUpService svc) {
        if (!playbackListenerRegistered.compareAndSet(false, true)) return;

        PlaybackManager.getInstance().addTrackChangeListener(() -> {
            if (!state.isActive()) return;
            StartUpService currentSvc = svc != null ? svc : StartUpService.getInstance();
            updateCurrentSong(currentSvc, PlaybackManager.getInstance().getCurrentSong());
        });
    }

    private AppShellController shell(StartUpService svc) {
        StartUpService currentSvc = svc != null ? svc : StartUpService.getInstance();
        return currentSvc == null ? null : currentSvc.getAppShellController();
    }

    private void runOnFx(Runnable runnable) {
        if (runnable == null) return;
        if (Platform.isFxApplicationThread()) runnable.run();
        else Platform.runLater(runnable);
    }
}
