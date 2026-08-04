package io.github.guillermodubon.musicplayer.controllers.ui.components.popups;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Tooltip;
import javafx.util.Duration;

import java.net.URL;

public final class SmallPopupTooltip {

    private static final String STYLESHEET =
            "/io/github/guillermodubon/musicplayer/Views/components/popups/small-popup-tooltip.css";
    private static final String STYLE_CLASS = "small-popup-tooltip";

    private SmallPopupTooltip() {
    }

    public static Tooltip install(Node owner, String text) {
        if (owner == null) return null;

        Tooltip tooltip = create(text);
        Tooltip.install(owner, tooltip);

        attachStylesheet(owner.getScene());
        owner.sceneProperty().addListener((obs, oldScene, newScene) -> attachStylesheet(newScene));
        return tooltip;
    }

    public static Tooltip create(String text) {
        Tooltip tooltip = new Tooltip(text == null ? "" : text);
        tooltip.getStyleClass().add(STYLE_CLASS);
        tooltip.setShowDelay(Duration.millis(250));
        tooltip.setHideDelay(Duration.millis(80));
        tooltip.setShowDuration(Duration.seconds(8));
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(240);
        tooltip.setOnShowing(e -> attachStylesheet(tooltip.getScene()));
        return tooltip;
    }

    private static void attachStylesheet(Scene scene) {
        if (scene == null) return;

        URL url = SmallPopupTooltip.class.getResource(STYLESHEET);
        if (url == null) return;

        String external = url.toExternalForm();
        if (!scene.getStylesheets().contains(external)) {
            scene.getStylesheets().add(external);
        }
    }
}
