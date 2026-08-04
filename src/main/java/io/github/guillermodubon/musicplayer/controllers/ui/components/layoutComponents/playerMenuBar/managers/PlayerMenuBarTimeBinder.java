package io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.managers;

import javafx.beans.value.ChangeListener;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.input.MouseEvent;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;

public final class PlayerMenuBarTimeBinder {

    private final Slider timeSlider;
    private final Label currentTimeLabel;
    private final Label lengthLabel;
    private final Runnable onEndOfMedia;

    private MediaPlayer boundPlayer;
    private ChangeListener<Duration> timeListener;
    private javafx.event.EventHandler<MouseEvent> pressHandler;
    private javafx.event.EventHandler<MouseEvent> releaseHandler;
    private Runnable previousReadyHandler;
    private Runnable readyHandler;
    private Media boundMedia;
    private ChangeListener<Duration> durationListener;
    private boolean wasPlayingBeforeDrag = false;

    public PlayerMenuBarTimeBinder(Slider timeSlider,
                                   Label currentTimeLabel,
                                   Label lengthLabel,
                                   Runnable onEndOfMedia) {
        this.timeSlider = timeSlider;
        this.currentTimeLabel = currentTimeLabel;
        this.lengthLabel = lengthLabel;
        this.onEndOfMedia = onEndOfMedia;
    }

    public void bind(MediaPlayer newPlayer) {
        if (newPlayer == null) {
            unbind();
            return;
        }

        unbind();
        boundPlayer = newPlayer;

        timeListener = (obs, oldTime, newTime) -> {
            if (!timeSlider.isValueChanging()) {
                double sec = newTime.toSeconds();
                timeSlider.setValue(sec);
                currentTimeLabel.setText(formatTime(sec));
            }
        };
        newPlayer.currentTimeProperty().addListener(timeListener);

        previousReadyHandler = newPlayer.getOnReady();
        readyHandler = () -> {
            if (previousReadyHandler != null) {
                previousReadyHandler.run();
            }
            synchronizeDuration(newPlayer);
        };
        newPlayer.setOnReady(readyHandler);

        // Fullscreen can be opened after the player is already ready. In that
        // case onReady will not fire again, so synchronize immediately and
        // keep observing the media duration until it becomes available.
        boundMedia = newPlayer.getMedia();
        if (boundMedia != null) {
            durationListener = (obs, oldDuration, newDuration) ->
                    synchronizeDuration(newPlayer);
            boundMedia.durationProperty().addListener(durationListener);
        }
        synchronizeDuration(newPlayer);

        pressHandler = e -> {
            wasPlayingBeforeDrag = newPlayer.getStatus() == MediaPlayer.Status.PLAYING;
            newPlayer.pause();
        };

        releaseHandler = e -> {
            double seekTo = timeSlider.getValue();
            newPlayer.seek(Duration.seconds(seekTo));
            currentTimeLabel.setText(formatTime(seekTo));
            if (wasPlayingBeforeDrag) newPlayer.play();
        };

        timeSlider.addEventHandler(MouseEvent.MOUSE_PRESSED, pressHandler);
        timeSlider.addEventHandler(MouseEvent.MOUSE_RELEASED, releaseHandler);
    }

    public void unbind() {
        if (boundPlayer != null) {
            try {
                if (timeListener != null) boundPlayer.currentTimeProperty().removeListener(timeListener);
            } catch (Exception ignored) {
            }
        }

        if (pressHandler != null) {
            try {
                timeSlider.removeEventHandler(MouseEvent.MOUSE_PRESSED, pressHandler);
            } catch (Exception ignored) {
            }
        }

        if (releaseHandler != null) {
            try {
                timeSlider.removeEventHandler(MouseEvent.MOUSE_RELEASED, releaseHandler);
            } catch (Exception ignored) {
            }
        }

        if (boundPlayer != null && readyHandler != null
                && boundPlayer.getOnReady() == readyHandler) {
            boundPlayer.setOnReady(previousReadyHandler);
        }

        if (boundMedia != null && durationListener != null) {
            try {
                boundMedia.durationProperty().removeListener(durationListener);
            } catch (Exception ignored) {
            }
        }

        boundPlayer = null;
        timeListener = null;
        pressHandler = null;
        releaseHandler = null;
        previousReadyHandler = null;
        readyHandler = null;
        boundMedia = null;
        durationListener = null;
    }

    public void dispose() {
        unbind();
    }

    private String formatTime(double seconds) {
        int total = (int) Math.floor(seconds);
        int m = total / 60;
        int s = total % 60;
        return String.format("%d:%02d", m, s);
    }

    private void synchronizeDuration(MediaPlayer player) {
        if (player == null || player.getMedia() == null) return;

        Duration duration = player.getMedia().getDuration();
        if (duration == null || duration.isUnknown() || duration.isIndefinite()) {
            return;
        }

        double totalSeconds = duration.toSeconds();
        if (!Double.isFinite(totalSeconds) || totalSeconds <= 0.0) {
            return;
        }

        timeSlider.setMax(totalSeconds);
        lengthLabel.setText(formatTime(totalSeconds));

        if (!timeSlider.isValueChanging() && player.getCurrentTime() != null) {
            double currentSeconds = player.getCurrentTime().toSeconds();
            if (Double.isFinite(currentSeconds) && currentSeconds >= 0.0) {
                timeSlider.setValue(Math.min(currentSeconds, totalSeconds));
                currentTimeLabel.setText(formatTime(currentSeconds));
            }
        }
    }
}
