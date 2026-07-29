package io.github.guillermodubon.musicplayer.utils;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import io.github.guillermodubon.musicplayer.models.Artist;

public final class ArtistPageUiHelpers {
    private ArtistPageUiHelpers() {}


    public static void setVisible(Label title, Node content, boolean visible) {
        Runnable r = () -> {
            if (content != null) {
                content.setVisible(visible);
                content.setManaged(visible);
            }
            if (title != null) {
                title.setVisible(visible);
                title.setManaged(visible);
            }
        };
        if (Platform.isFxApplicationThread()) r.run(); else Platform.runLater(r);
    }


}
