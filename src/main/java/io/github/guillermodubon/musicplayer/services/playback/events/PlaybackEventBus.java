package io.github.guillermodubon.musicplayer.services.playback.events;

import javafx.application.Platform;

import java.util.concurrent.CopyOnWriteArrayList;

public class PlaybackEventBus {

    private final CopyOnWriteArrayList<Runnable> trackChangeListeners = new CopyOnWriteArrayList<>();

    public void addTrackChangeListener(Runnable listener) {
        if (listener != null) {
            trackChangeListeners.addIfAbsent(listener);
        }
    }

    public void removeTrackChangeListener(Runnable listener) {
        if (listener != null) {
            trackChangeListeners.remove(listener);
        }
    }

    public void notifyTrackChanged() {
        Runnable notify = () -> {
            for (Runnable listener : trackChangeListeners) {
                try {
                    listener.run();
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        };

        if (Platform.isFxApplicationThread()) {
            notify.run();
        } else {
            Platform.runLater(notify);
        }
    }
}
