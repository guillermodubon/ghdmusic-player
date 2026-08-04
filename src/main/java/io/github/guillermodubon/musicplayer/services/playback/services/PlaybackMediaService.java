package io.github.guillermodubon.musicplayer.services.playback.services;

import javafx.application.Platform;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.stage.Window;
import io.github.guillermodubon.musicplayer.controllers.layout.AppShellController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playbackDialogs.MissingLocalFileDialog;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.PlayerMenuBarController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.PlayerMenuController;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.playback.events.PlaybackEventBus;
import io.github.guillermodubon.musicplayer.services.playback.managers.PlaybackMediaResolver;
import io.github.guillermodubon.musicplayer.services.playback.state.PlaybackState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class PlaybackMediaService {

    private final PlaybackState state;
    private final PlaybackEventBus events;
    private final PlaybackMediaResolver resolver;
    private final ScheduledExecutorService executor;

    private PlayerMenuController playerMenuController;
    private PlayerMenuBarController playerMenuBarController;
    private MediaPlayer mediaPlayer;
    private final AtomicLong playbackToken = new AtomicLong();

    public PlaybackMediaService(PlaybackState state,
                                PlaybackEventBus events,
                                PlaybackMediaResolver resolver,
                                ScheduledExecutorService executor) {
        this.state = state;
        this.events = events;
        this.resolver = resolver;
        this.executor = executor;
    }

    public void setControllers(PlayerMenuController menuCtrl, PlayerMenuBarController barCtrl) {
        this.playerMenuController = menuCtrl;
        this.playerMenuBarController = barCtrl;
    }

    public PlayerMenuController getMenuController() {
        return playerMenuController;
    }

    public PlayerMenuBarController getBarController() {
        return playerMenuBarController;
    }

    public MediaPlayer getCurrentPlayer() {
        return mediaPlayer;
    }

    public void stopCurrent() {
        playbackToken.incrementAndGet();
        MediaPlayer current = mediaPlayer;
        mediaPlayer = null;
        if (current != null) {
            try {
                current.stop();
                current.dispose();
            } catch (Exception ignored) {
            }
        }
    }

    public void togglePlayPause() {
        if (mediaPlayer == null) return;
        if (mediaPlayer.getStatus() == MediaPlayer.Status.PLAYING) mediaPlayer.pause();
        else mediaPlayer.play();
    }

    public void playSong(Song song, Runnable onAdvance) {
        playSong(song, onAdvance, null);
    }

    public void playSong(Song song, Runnable onAdvance, Consumer<Song> onUnavailable) {
        stopCurrent();

        if (song == null) {
            if (onAdvance != null) executor.execute(onAdvance);
            return;
        }

        if (!song.isLocal()) {
            events.notifyTrackChanged();
            return;
        }

        long token = playbackToken.incrementAndGet();
        AtomicBoolean unavailableHandled = new AtomicBoolean(false);
        Optional<String> resolved = resolver.resolvePlayablePath(song);
        if (resolved.isEmpty()) {
            if (isCurrentPlaybackToken(token)) {
                handleMissingLocalFile(song, onUnavailable, unavailableHandled);
            }
            return;
        }

        state.setLastPlayedSong(song);

        Path mediaPath = Path.of(resolved.get());
        String uri = mediaPath.toUri().toString();

        Platform.runLater(() -> {
            if (!isCurrentPlaybackToken(token)) return;

            if (!isReadableMediaFile(mediaPath)) {
                handleMissingLocalFile(song, onUnavailable, unavailableHandled);
                return;
            }

            try {
                Media media = new Media(uri);
                MediaPlayer newPlayer = new MediaPlayer(media);
                newPlayer.setVolume(state.getLastVolume());

                if (!isCurrentPlaybackToken(token)) {
                    newPlayer.dispose();
                    return;
                }

                mediaPlayer = newPlayer;

                if (playerMenuBarController != null) {
                    playerMenuBarController.updateCurrentSong(song);
                    playerMenuBarController.setVolumeSlider(state.getLastVolume() * 100);
                    playerMenuBarController.bindTime(mediaPlayer);
                } else if (state.getOriginSource() != null) {
                    AppShellController shell = null;
                    try {
                        PlayerMenuController menu = playerMenuController;
                        if (menu != null && menu.getParentRoot() != null && menu.getParentRoot().getScene() != null) {
                            Object c = menu.getParentRoot().getScene().getRoot().getProperties().get("controller");
                            if (c instanceof AppShellController asc) shell = asc;
                        }
                    } catch (Exception ignored) {}

                    if (shell != null) {
                        shell.ensurePlayerMenuBarLoaded();
                    }
                }

                if (playerMenuController != null) {
                    playerMenuController.updateCurrentSong(song);
                }

                mediaPlayer.setOnEndOfMedia(() -> {
                    if (!isCurrentPlaybackToken(token)) return;
                    if (onAdvance != null) {
                        executor.schedule(onAdvance, 0, TimeUnit.MILLISECONDS);
                    }
                });

                mediaPlayer.setOnError(() -> {
                    if (!isCurrentPlaybackToken(token)) return;

                    if (!isReadableMediaFile(mediaPath)) {
                        handleMissingLocalFile(song, onUnavailable, unavailableHandled);
                    } else if (onAdvance != null) {
                        executor.schedule(onAdvance, 0, TimeUnit.MILLISECONDS);
                    }
                });

                mediaPlayer.play();
                events.notifyTrackChanged();

            } catch (Exception ex) {
                if (!isCurrentPlaybackToken(token)) return;

                if (!isReadableMediaFile(mediaPath)) {
                    handleMissingLocalFile(song, onUnavailable, unavailableHandled);
                } else if (onAdvance != null) {
                    executor.schedule(onAdvance, 0, TimeUnit.MILLISECONDS);
                }
            }
        });
    }

    private boolean isCurrentPlaybackToken(long token) {
        return token == playbackToken.get();
    }

    private boolean isReadableMediaFile(Path path) {
        try {
            return path != null
                    && Files.isRegularFile(path)
                    && Files.isReadable(path)
                    && Files.size(path) > 0L;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void handleMissingLocalFile(Song song,
                                        Consumer<Song> onUnavailable,
                                        AtomicBoolean unavailableHandled) {
        if (song == null || unavailableHandled == null || !unavailableHandled.compareAndSet(false, true)) {
            return;
        }
        stopCurrent();
        System.err.println("PlaybackManager: no se encontró archivo para: " + song.getTitle());
        resolver.markSongUnavailable(song);

        Platform.runLater(() -> {
            PlayerMenuController menu = playerMenuController;
            if (menu != null) {
                menu.onLocalSongUnavailable(song);
            }
            MissingLocalFileDialog.show(song, resolveDialogOwner(menu));
            events.notifyTrackChanged();
        });

        if (onUnavailable != null) {
            executor.execute(() -> onUnavailable.accept(song));
        }
    }

    private Window resolveDialogOwner(PlayerMenuController menu) {
        try {
            if (menu != null && menu.getParentRoot() != null && menu.getParentRoot().getScene() != null) {
                return menu.getParentRoot().getScene().getWindow();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public void reloadCurrentMediaIfNeeded() {
        executor.execute(() -> {
            try {
                Song current = state.getLastPlayedSong();
                if (current == null) return;

                String filePath = current.getFilePath();
                if (filePath == null || filePath.isBlank()) return;
                if (mediaPlayer == null) return;

                String currentSource = null;
                try {
                    Media m = mediaPlayer.getMedia();
                    if (m != null) currentSource = m.getSource();
                } catch (Exception ignored) {
                }

                String newUri = Path.of(filePath).toUri().toString();
                if (!Objects.equals(currentSource, newUri)) {
                    stopCurrent();
                    executor.schedule(() -> playSong(current, null), 50, TimeUnit.MILLISECONDS);
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });
    }
}
