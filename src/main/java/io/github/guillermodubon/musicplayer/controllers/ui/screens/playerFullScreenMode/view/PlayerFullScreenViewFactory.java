package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.view;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Button;
import javafx.scene.control.OverrunStyle;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.HBox;
import javafx.scene.shape.Rectangle;
import io.github.guillermodubon.musicplayer.controllers.ui.components.effects.MarqueeTextSupport;
import io.github.guillermodubon.musicplayer.controllers.ui.components.icons.SvgIconFactory;

import java.util.function.BooleanSupplier;

/** Builds the centered, cover-first node tree for internal fullscreen playback. */
public final class PlayerFullScreenViewFactory {

    private static final String CLOSE_ICON =
            "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/"
                    + "close_27dp_FAFAFA_FILL0_wght400_GRAD0_opsz24.svg";

    public PlayerFullScreenView create(
            Runnable onEscape,
            BooleanSupplier active,
            Runnable updateArtworkViewport
    ) {
        StackPane root = createRoot(onEscape);
        Pane ambientBackground = createAmbientBackground();
        StackPane coverContainer = createCoverContainer();
        ImageView cover = createCoverImageView();
        Rectangle coverClip = createCoverClip(coverContainer);
        cover.setClip(coverClip);
        coverContainer.getChildren().setAll(cover);

        Label title = createTextLabel("player-fullscreen-song-title");
        StackPane titleViewport = createTitleViewport();
        HBox titleTrack = new HBox(title);
        titleTrack.setAlignment(Pos.CENTER_LEFT);
        titleViewport.getChildren().setAll(titleTrack);

        HBox artistsContainer = createArtistsContainer();
        VBox copy = new VBox(8, titleViewport, artistsContainer);
        copy.setAlignment(Pos.CENTER_LEFT);
        copy.setMinWidth(0);
        // Reserve a lane for the actions button so text can never render
        // beneath it on long song and artist names.
        copy.prefWidthProperty().bind(coverContainer.widthProperty().subtract(48));
        copy.maxWidthProperty().bind(coverContainer.widthProperty().subtract(48));
        copy.getStyleClass().add("player-fullscreen-song-copy");

        Button actionsMenuButton = new Button();
        actionsMenuButton.setFocusTraversable(false);
        actionsMenuButton.getStyleClass().add("player-fullscreen-actions-button");

        StackPane copyContainer = new StackPane(copy, actionsMenuButton);
        copyContainer.setMinWidth(0);
        copyContainer.prefWidthProperty().bind(coverContainer.widthProperty());
        copyContainer.maxWidthProperty().bind(coverContainer.widthProperty());
        copyContainer.setAlignment(Pos.CENTER_LEFT);
        StackPane.setAlignment(copy, Pos.CENTER_LEFT);
        StackPane.setAlignment(actionsMenuButton, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(actionsMenuButton, new Insets(0, 0, 2, 0));

        MarqueeTextSupport titleMarquee = new MarqueeTextSupport(
                titleViewport, titleTrack, title, null, null
        );
        titleMarquee.installHover(titleViewport);
        titleViewport.widthProperty().addListener((obs, oldWidth, newWidth) ->
                titleMarquee.refresh()
        );
        title.textProperty().addListener((obs, oldText, newText) ->
                titleMarquee.refresh()
        );

        Button closeButton = createCloseButton(onEscape);

        VBox nowPlaying = new VBox(18, coverContainer, copyContainer);
        nowPlaying.setAlignment(Pos.CENTER);
        nowPlaying.setPickOnBounds(false);
        nowPlaying.setMinSize(0, 0);
        nowPlaying.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        nowPlaying.getStyleClass().add("player-fullscreen-now-playing");

        StackPane.setAlignment(nowPlaying, Pos.CENTER);
        StackPane.setAlignment(ambientBackground, Pos.CENTER);
        root.getChildren().setAll(ambientBackground, nowPlaying, closeButton);

        root.widthProperty().addListener((obs, oldValue, newValue) -> {
            if (active.getAsBoolean()) Platform.runLater(updateArtworkViewport);
        });
        root.heightProperty().addListener((obs, oldValue, newValue) -> {
            if (active.getAsBoolean()) Platform.runLater(updateArtworkViewport);
        });

        return new PlayerFullScreenView(
                root,
                coverContainer,
                nowPlaying,
                ambientBackground,
                coverContainer,
                cover,
                title,
                artistsContainer,
                actionsMenuButton,
                closeButton,
                coverClip
        );
    }

    private Pane createAmbientBackground() {
        Pane background = new Pane();
        background.setMinSize(0, 0);
        background.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        background.setMouseTransparent(true);
        background.getStyleClass().add("player-fullscreen-ambient-background");
        return background;
    }

    private StackPane createRoot(Runnable onEscape) {
        StackPane root = new StackPane();
        root.setMinSize(0, 0);
        root.setPrefSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
        root.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        root.setFocusTraversable(true);
        root.setPickOnBounds(true);
        root.getStyleClass().add("player-fullscreen-root");
        root.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                onEscape.run();
                event.consume();
            }
        });
        return root;
    }

    private StackPane createCoverContainer() {
        StackPane container = new StackPane();
        container.setMinSize(0, 0);
        container.setPrefSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
        container.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        container.getStyleClass().addAll(
                "player-fullscreen-artwork-container",
                "player-fullscreen-song-cover-container"
        );
        return container;
    }

    private ImageView createCoverImageView() {
        ImageView image = new ImageView();
        image.setPreserveRatio(true);
        image.setSmooth(true);
        image.setCache(false);
        image.setMouseTransparent(true);
        return image;
    }

    private Rectangle createCoverClip(StackPane container) {
        Rectangle clip = new Rectangle();
        clip.setArcWidth(24);
        clip.setArcHeight(24);
        clip.widthProperty().bind(container.widthProperty());
        clip.heightProperty().bind(container.heightProperty());
        return clip;
    }

    private Label createTextLabel(String styleClass) {
        Label label = new Label();
        label.setWrapText(false);
        label.setTextOverrun(OverrunStyle.ELLIPSIS);
        label.setMaxWidth(900);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private StackPane createTitleViewport() {
        StackPane viewport = new StackPane();
        viewport.setAlignment(Pos.CENTER_LEFT);
        viewport.setMinWidth(0);
        viewport.setPrefWidth(0);
        viewport.setMaxWidth(Double.MAX_VALUE);
        viewport.setMinHeight(28);
        viewport.setPrefHeight(28);
        viewport.setMaxHeight(28);
        viewport.getStyleClass().add("player-fullscreen-song-title-viewport");
        return viewport;
    }

    private HBox createArtistsContainer() {
        HBox container = new HBox();
        container.setAlignment(Pos.CENTER_LEFT);
        container.setMinWidth(0);
        container.setPrefWidth(0);
        container.setMaxWidth(Double.MAX_VALUE);
        container.getStyleClass().add("player-fullscreen-song-artists");
        return container;
    }

    private Button createCloseButton(Runnable onEscape) {
        Button button = new Button();
        button.setFocusTraversable(false);
        button.setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
        button.getStyleClass().add("player-fullscreen-close-button");
        button.setGraphic(SvgIconFactory.icon(CLOSE_ICON, 22.0));
        SvgIconFactory.setIconColor(button.getGraphic(), "#FAFAFA");
        button.setOnAction(event -> onEscape.run());
        StackPane.setAlignment(button, Pos.TOP_LEFT);
        StackPane.setMargin(button, new Insets(20, 0, 0, 24));
        return button;
    }
}
