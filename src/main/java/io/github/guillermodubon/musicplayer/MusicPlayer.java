package io.github.guillermodubon.musicplayer;

import io.github.guillermodubon.musicplayer.application.MusicPlayerApplicationBootstrap;
import javafx.application.Application;
import javafx.stage.Stage;

/**
 * JavaFX entry point for GHDMusic.
 *
 * <p>Application wiring belongs to {@link MusicPlayerApplicationBootstrap};
 * this class only exposes the JavaFX lifecycle.</p>
 */
public final class MusicPlayer extends Application {

    private final MusicPlayerApplicationBootstrap applicationBootstrap =
            new MusicPlayerApplicationBootstrap();

    @Override
    public void start(Stage stage) throws Exception {
        applicationBootstrap.start(stage);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
