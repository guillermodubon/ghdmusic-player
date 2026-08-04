package io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems;

import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.base.BaseSongCellController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.services.PlayingSongIndicatorSupport;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.util.function.Consumer;

public class RecommendedSongForPlaylistItemController extends BaseSongCellController {

    @FXML private Button btnAdd;
    @FXML private HBox rootBox;
    @FXML private StackPane playingIndicatorHost;

    private Consumer<Song> onPlay;
    private Consumer<Song> onAdd;
    private PlayingSongIndicatorSupport playingStateSupport;

    @FXML
    public void initialize() {
        playingStateSupport = new PlayingSongIndicatorSupport(rootBox, titleLabel, playingIndicatorHost);
    }

    public void init(Song song,
                     StartUpService svc,
                     Consumer<Song> onPlay,
                     Consumer<Song> onAdd) {
        this.song = song;
        this.svc = svc;
        this.onPlay = onPlay;
        this.onAdd = onAdd;

        normalizeRootStyle();

        bindSongBasics(song);

        Node clickable = coverView.getParent() != null ? coverView.getParent() : coverView;
        bindPlayAction(clickable, () -> {
            if (this.onPlay != null) this.onPlay.accept(this.song);
        });

        btnAdd.setOnAction(e -> {
            if (this.onAdd != null) this.onAdd.accept(this.song);
        });

        refreshPlayingState();
    }

    public void refreshPlayingState() {
        if (playingStateSupport != null) {
            playingStateSupport.refresh(song);
        }
    }

    public void deactivatePlayingState() {
        if (playingStateSupport != null) {
            playingStateSupport.deactivate();
        }
    }

    private void normalizeRootStyle() {
        if (rootBox == null) return;
        rootBox.setMinHeight(58);
        rootBox.setPrefHeight(58);
        rootBox.setMaxHeight(58);
        rootBox.setPickOnBounds(true);
        rootBox.setStyle(normalRootStyle());
        rootBox.hoverProperty().addListener((obs, oldValue, hovered) -> rootBox.setStyle(hovered ? hoverRootStyle() : normalRootStyle()));
    }

    private String normalRootStyle() {
        return """
                -fx-background-color: transparent;
                -fx-border-color: transparent;
                -fx-background-radius: 8;
                -fx-border-radius: 8;
                -fx-padding: 8 10 8 10;
                """;
    }

    private String hoverRootStyle() {
        return """
                -fx-background-color: #222222;
                -fx-border-color: transparent;
                -fx-background-radius: 8;
                -fx-border-radius: 8;
                -fx-padding: 8 10 8 10;
                """;
    }

    @Override
    protected void onArtistClicked(Artist artist) {
        super.onArtistClicked(artist);
    }
}

