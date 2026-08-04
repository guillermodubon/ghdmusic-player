package io.github.guillermodubon.musicplayer.controllers.ui.components.cards;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.base.BaseCardController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.MusicCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.components.effects.MarqueeTextSupport;

public class BigFeaturedMusicCard extends BaseCardController<MusicCardData> {

    @FXML private StackPane rootPane;
    @FXML private StackPane coverShell;
    @FXML private ImageView coverView;
    @FXML private StackPane titleViewport;
    @FXML private HBox titleTrack;
    @FXML private Label titleLabel;
    @FXML private StackPane artistViewport;
    @FXML private HBox artistTrack;
    @FXML private HBox artistLinkContainer;
    private boolean coverLayoutConfigured;
    private MarqueeTextSupport marqueeTextSupport;
    private boolean marqueeResizeListenerConfigured;

    public void init(MusicCardData data) {
        if (data == null) throw new IllegalArgumentException("data == null");
        setModel(data, null);

        bindCoverToContainer();

        setImageOrFallback(
                coverView,
                data.cover(),
                defaultImage("/io/github/guillermodubon/musicplayer/assets/images/byDefaultImages/defaultPlaylist.png")
        );
        resizeCoverToShell();

        titleLabel.setText(safeText(data.title(), "Unknown Title"));

        if (data.artistLinksEnabled()) {
            fillArtistLinks(artistLinkContainer, data.artists(), data.onArtistClick());
        } else {
            fillArtistLabels(artistLinkContainer, data.artists());
        }

        configureMarqueeSupport();

        bindCardClick(rootPane, data.id(), data.onPlay());
    }

    @FXML
    protected void onHover(MouseEvent e) {
        if (rootPane != null && !rootPane.getStyleClass().contains("big-featured-music-card-hover")) {
            rootPane.getStyleClass().add("big-featured-music-card-hover");
        }
        if (marqueeTextSupport != null) marqueeTextSupport.activate();
    }

    @FXML
    protected void onExit(MouseEvent e) {
        if (rootPane != null) {
            rootPane.getStyleClass().remove("big-featured-music-card-hover");
        }
        if (marqueeTextSupport != null) marqueeTextSupport.deactivate();
    }

    @FXML
    protected void onPlay(MouseEvent e) {
        MusicCardData data = model;
        if (data != null && data.onPlay() != null && data.id() != null) {
            data.onPlay().accept(data.id());
        }
    }

    private void bindCoverToContainer() {
        if (coverView == null || coverShell == null) return;

        if (coverLayoutConfigured) return;
        coverLayoutConfigured = true;

        // Keep the cover rectangular with a moderate corner radius. A large
        // arc turns wide cards into pills instead of rounded rectangles.
        bindClip(coverShell, 28);

        coverShell.setMinWidth(0);
        coverView.setManaged(false);
        coverView.setMouseTransparent(true);
        coverView.setPreserveRatio(false);
        coverView.setViewport(null);

        coverView.fitWidthProperty().bind(coverShell.widthProperty());
        coverView.fitHeightProperty().bind(coverShell.heightProperty());
    }

    private void resizeCoverToShell() {
        if (coverView == null) return;

        coverView.setPreserveRatio(false);
        coverView.setViewport(null);
    }

    private void configureMarqueeSupport() {
        if (titleViewport == null || titleTrack == null || titleLabel == null
                || artistViewport == null || artistTrack == null) {
            return;
        }

        // The viewports must fill the card assigned by the carousel, but they
        // must not publish the intrinsic width of a long artist list as the
        // card's preferred width.
        titleViewport.setMinWidth(0);
        titleViewport.setPrefWidth(0);
        titleViewport.setMaxWidth(Double.MAX_VALUE);
        artistViewport.setMinWidth(0);
        artistViewport.setPrefWidth(0);
        artistViewport.setMaxWidth(Double.MAX_VALUE);
        if (artistLinkContainer != null) {
            artistLinkContainer.setMinWidth(Region.USE_COMPUTED_SIZE);
            artistLinkContainer.setPrefWidth(Region.USE_COMPUTED_SIZE);
            artistLinkContainer.setMaxWidth(Region.USE_PREF_SIZE);
        }

        if (marqueeTextSupport == null) {
            marqueeTextSupport = new MarqueeTextSupport(
                    titleViewport,
                    titleTrack,
                    titleLabel,
                    artistViewport,
                    artistTrack
            );
            marqueeTextSupport.installHover(rootPane);
        }

        // A resize can happen while the pointer is still over the card. Reset
        // the ticker so it measures the new viewport instead of keeping the
        // translation calculated for the previous card width.
        if (!marqueeResizeListenerConfigured && rootPane != null) {
            marqueeResizeListenerConfigured = true;
            rootPane.widthProperty().addListener((obs, oldWidth, newWidth) -> {
                if (marqueeTextSupport != null) marqueeTextSupport.refresh();
            });
        }

        marqueeTextSupport.refresh();
    }

    private void bindClip(Region region, double arc) {
        if (region == null) return;

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(region.widthProperty());
        clip.heightProperty().bind(region.heightProperty());
        clip.setArcWidth(arc);
        clip.setArcHeight(arc);
        region.setClip(clip);
    }

}
