package io.github.guillermodubon.musicplayer.application;

import io.github.guillermodubon.musicplayer.MusicPlayer;
import javafx.application.Application;

/**
 * Classpath-safe launcher used by the packaged application.
 *
 * <p>The Java launcher applies special handling when the configured main class
 * directly extends {@link Application}. Keeping this class as the packaged
 * entry point lets JavaFX start through its public API while preserving the
 * existing application lifecycle.</p>
 */
public final class MusicPlayerLauncher {

    private MusicPlayerLauncher() {
    }

    public static void main(String[] args) {
        Application.launch(MusicPlayer.class, args);
    }
}
