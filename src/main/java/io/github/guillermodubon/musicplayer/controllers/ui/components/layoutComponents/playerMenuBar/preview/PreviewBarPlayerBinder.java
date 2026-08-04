package io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.preview;

import javafx.beans.value.ChangeListener;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.input.MouseEvent;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;

import java.util.function.Consumer;

public class PreviewBarPlayerBinder {

    private final Button playPauseBtn;
    private final Slider timeSlider;
    private final Label currentTimeLabel;
    private final Label lengthLabel;
    private final Slider volumeSlider;
    private final Consumer<Boolean> playbackStateConsumer;

    private MediaPlayer player;
    private ChangeListener<Duration> timeListener;
    private ChangeListener<MediaPlayer.Status> statusListener;
    private javafx.event.EventHandler<MouseEvent> pressHandler;
    private javafx.event.EventHandler<MouseEvent> releaseHandler;
    private boolean wasPlayingBeforeDrag = false;
    private boolean seeking = false;

    public PreviewBarPlayerBinder(Button playPauseBtn,
                                  Slider timeSlider,
                                  Label currentTimeLabel,
                                  Label lengthLabel,
                                  Slider volumeSlider,
                                  Consumer<Boolean> playbackStateConsumer) {
        this.playPauseBtn = playPauseBtn;
        this.timeSlider = timeSlider;
        this.currentTimeLabel = currentTimeLabel;
        this.lengthLabel = lengthLabel;
        this.volumeSlider = volumeSlider;
        this.playbackStateConsumer = playbackStateConsumer == null ? playing -> {} : playbackStateConsumer;

        this.volumeSlider.valueProperty().addListener((obs, o, n) -> {
            if (player != null) {
                player.setVolume(n.doubleValue() / 100.0);
            }
        });
    }

    public void bind(MediaPlayer mp, double initialVolume) {
        clearBindings();

        this.player = mp;
        if (player == null) return;

        player.setVolume(initialVolume);
        volumeSlider.setValue(initialVolume * 100);

        player.setOnReady(() -> {
            double total = player.getMedia().getDuration().toSeconds();
            timeSlider.setMax(total);
            lengthLabel.setText(formatTime(total));
        });

        timeListener = (obs, oldT, newT) -> {
            if (!seeking && !timeSlider.isValueChanging()) {
                double sec = newT.toSeconds();
                timeSlider.setValue(sec);
                currentTimeLabel.setText(formatTime(sec));
            }
        };
        player.currentTimeProperty().addListener(timeListener);

        player.setOnEndOfMedia(() -> {
            player.stop();
            player.seek(Duration.ZERO);
            notifyPlaybackState(false);
        });

        statusListener = (obs, oldStatus, newStatus) -> notifyPlaybackState(newStatus == MediaPlayer.Status.PLAYING);
        player.statusProperty().addListener(statusListener);

        pressHandler = e -> beginSeekGesture();

        releaseHandler = e -> {
            double seekTo = timeSlider.getValue();
            seekTo(seekTo);
            endSeekGesture();
        };

        timeSlider.addEventHandler(MouseEvent.MOUSE_PRESSED, pressHandler);
        timeSlider.addEventHandler(MouseEvent.MOUSE_RELEASED, releaseHandler);

        player.play();
        notifyPlaybackState(true);
    }

    public void togglePlayPause() {
        if (player == null) return;

        var status = player.getStatus();
        if (status == MediaPlayer.Status.PLAYING) {
            player.pause();
            notifyPlaybackState(false);
        } else {
            player.play();
            notifyPlaybackState(true);
        }
    }

    public void seekTo(double seconds) {
        if (player == null || timeSlider == null) return;

        double safe = Math.max(timeSlider.getMin(), Math.min(timeSlider.getMax(), seconds));
        player.seek(Duration.seconds(safe));
        timeSlider.setValue(safe);
        currentTimeLabel.setText(formatTime(safe));
    }

    public void beginSeekGesture() {
        if (player == null) return;
        seeking = true;
        wasPlayingBeforeDrag = player.getStatus() == MediaPlayer.Status.PLAYING;
        player.pause();
    }

    public void endSeekGesture() {
        if (player == null) {
            seeking = false;
            return;
        }
        seeking = false;
        if (wasPlayingBeforeDrag) {
            player.play();
        }
        wasPlayingBeforeDrag = false;
    }

    private void notifyPlaybackState(boolean playing) {
        playbackStateConsumer.accept(playing);
    }

    private String formatTime(double seconds) {
        int total = (int) Math.floor(seconds);
        int m = total / 60;
        int s = total % 60;
        return String.format("%d:%02d", m, s);
    }

    private void clearBindings() {
        if (player != null) {
            if (timeListener != null) player.currentTimeProperty().removeListener(timeListener);
            if (statusListener != null) player.statusProperty().removeListener(statusListener);
            if (pressHandler != null) timeSlider.removeEventHandler(MouseEvent.MOUSE_PRESSED, pressHandler);
            if (releaseHandler != null) timeSlider.removeEventHandler(MouseEvent.MOUSE_RELEASED, releaseHandler);
        }
        timeListener = null;
        statusListener = null;
        pressHandler = null;
        releaseHandler = null;
        seeking = false;
        wasPlayingBeforeDrag = false;
    }

    public void dispose() {
        clearBindings();
        if (player != null) {
            try {
                player.stop();
                player.dispose();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }
}
