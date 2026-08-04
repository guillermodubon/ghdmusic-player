package io.github.guillermodubon.musicplayer.controllers.ui.components.icons;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public final class MeatballIconFactory {

    private static final String STYLE_CLASS = "meatball-icon";

    private MeatballIconFactory() {
    }

    public static Node vertical(double size, double dotSize, double spacing) {
        VBox icon = new VBox(spacing);
        icon.setAlignment(Pos.CENTER);
        icon.setMinSize(size, size);
        icon.setPrefSize(size, size);
        icon.setMaxSize(size, size);
        icon.setMouseTransparent(true);
        icon.setFocusTraversable(false);
        icon.getStyleClass().add(STYLE_CLASS);

        for (int i = 0; i < 3; i++) {
            Region dot = new Region();
            dot.setMinSize(dotSize, dotSize);
            dot.setPrefSize(dotSize, dotSize);
            dot.setMaxSize(dotSize, dotSize);
            icon.getChildren().add(dot);
        }

        return icon;
    }

    public static boolean setColor(Node icon, String color) {
        if (!(icon instanceof Parent parent) || !icon.getStyleClass().contains(STYLE_CLASS)) {
            return false;
        }

        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof Region region) {
                region.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 999;");
            }
        }
        return true;
    }
}
