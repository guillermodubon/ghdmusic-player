package io.github.guillermodubon.musicplayer.controllers.ui.screens.common;

import javafx.geometry.Insets;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;

/** Shared ambient background used by the main content screens. */
public final class AmbientGradientSupport {

    private static final double GRADIENT_HEIGHT = 340.0;

    private AmbientGradientSupport() {
    }

    public static void applyTopAmbientGradient(Region target) {
        if (target == null) return;

        LinearGradient topGradient = new LinearGradient(
                0, 0,
                0, GRADIENT_HEIGHT,
                false,
                CycleMethod.NO_CYCLE,
                new Stop(0.00, Color.web("#00283D", 0.44)),
                new Stop(0.14, Color.web("#00283D", 0.32)),
                new Stop(0.30, Color.web("#00283D", 0.20)),
                new Stop(0.46, Color.web("#00283D", 0.08)),
                new Stop(0.64, Color.web("#111111")),
                new Stop(1.00, Color.web("#111111"))
        );

        target.setBackground(new Background(
                new BackgroundFill(topGradient, CornerRadii.EMPTY, Insets.EMPTY)
        ));
    }
}
