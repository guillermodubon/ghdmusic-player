package io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.services;

import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.media.MediaPlayer;
import io.github.guillermodubon.musicplayer.controllers.ui.components.icons.AnimatedEqualizerIcon;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;

public final class PlayingSongIndicatorSupport {

    private final Node root;
    private final Label titleLabel;
    private final StackPane indicatorHost;
    private final AnimatedEqualizerIcon indicator;

    private MediaPlayer observedPlayer;
    private ChangeListener<MediaPlayer.Status> playbackStatusListener;
    private Song song;

    public PlayingSongIndicatorSupport(Node root, Label titleLabel, StackPane indicatorHost) {
        this.root = root;
        this.titleLabel = titleLabel;
        this.indicatorHost = indicatorHost;
        this.indicator = titleLabel == null ? null : new AnimatedEqualizerIcon();

        if (indicatorHost != null) {
            indicatorHost.getChildren().clear();
            indicatorHost.setVisible(false);
            indicatorHost.setManaged(false);
        }
        if (titleLabel != null) {
            titleLabel.setContentDisplay(ContentDisplay.RIGHT);
            titleLabel.setGraphicTextGap(4);
            titleLabel.setGraphic(null);
        }
    }

    public void refresh(Song song) {
        this.song = song;

        PlaybackManager manager = PlaybackManager.getInstance();
        Song current = manager.getCurrentSong();
        MediaPlayer player = manager.getCurrentPlayer();

        bindPlaybackStatus(player);

        boolean currentSong = sameSong(song, current);
        boolean actuallyPlaying = currentSong
                && player != null
                && player.getStatus() == MediaPlayer.Status.PLAYING;
        applyState(currentSong, actuallyPlaying);
    }

    public void deactivate() {
        song = null;
        unbindPlaybackStatus();
        applyState(false, false);
    }

    private void bindPlaybackStatus(MediaPlayer player) {
        if (observedPlayer == player) return;
        unbindPlaybackStatus();
        observedPlayer = player;
        if (observedPlayer == null) return;

        playbackStatusListener = (obs, oldStatus, newStatus) -> refresh(song);
        observedPlayer.statusProperty().addListener(playbackStatusListener);
    }

    private void unbindPlaybackStatus() {
        if (observedPlayer != null && playbackStatusListener != null) {
            try {
                observedPlayer.statusProperty().removeListener(playbackStatusListener);
            } catch (Exception ignored) {
            }
        }
        observedPlayer = null;
        playbackStatusListener = null;
    }

    private void applyState(boolean currentSong, boolean actuallyPlaying) {
        setStyleClass(root, "playing-song-item", currentSong);
        setStyleClass(titleLabel, "currently-playing-title", currentSong);

        if (indicatorHost != null) {
            indicatorHost.setVisible(false);
            indicatorHost.setManaged(false);
        }
        if (titleLabel != null) {
            titleLabel.setGraphic(currentSong ? indicator : null);
        }
        if (indicator != null) {
            indicator.setAnimating(actuallyPlaying);
        }
    }

    private void setStyleClass(Node node, String styleClass, boolean present) {
        if (node == null || styleClass == null || styleClass.isBlank()) return;
        if (present) {
            if (!node.getStyleClass().contains(styleClass)) {
                node.getStyleClass().add(styleClass);
            }
        } else {
            node.getStyleClass().remove(styleClass);
        }
    }

    private boolean sameSong(Song left, Song right) {
        if (left == right && left != null) return true;
        if (left == null || right == null) return false;
        if (left.getSongID() > 0 && right.getSongID() > 0) {
            return left.getSongID() == right.getSongID();
        }
        if (left.getFilePath() != null && right.getFilePath() != null
                && !left.getFilePath().isBlank() && !right.getFilePath().isBlank()) {
            return left.getFilePath().equalsIgnoreCase(right.getFilePath());
        }
        return left.getTitle() != null
                && right.getTitle() != null
                && left.getTitle().equalsIgnoreCase(right.getTitle());
    }
}
