package io.github.guillermodubon.musicplayer.controllers.ui.components.cards;

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.OverrunStyle;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.base.BaseCardController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.MusicCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.components.images.SquareImageViewSupport;

import java.util.ArrayList;
import java.util.List;

public class MusicCard extends BaseCardController<MusicCardData> {

    private static final String MARQUEE_DUPLICATE_KEY = "musicCardMarqueeDuplicate";
    private static final double CARD_HORIZONTAL_PADDING = 20;
    private static final double TEXT_HORIZONTAL_PADDING = 24;
    private static final double TEXT_MIN_WIDTH = 96;
    private static final double COVER_MIN_SIZE = 120;
    private static final double COVER_MAX_SIZE = 156;
    private static final double COVER_CLIP_ARC = 16;
    private static final double MARQUEE_GAP = 34;

    @FXML private StackPane rootPane;
    @FXML private StackPane coverShell;
    @FXML private ImageView coverView;
    @FXML private StackPane titleViewport;
    @FXML private HBox titleMarqueeBox;
    @FXML private Label titleLabel;
    @FXML private StackPane artistViewport;
    @FXML private HBox artistLinkContainer;
    @FXML private Label artistOverflowLabel;

    private Animation titleTransition;
    private Animation artistTransition;
    private boolean hovering;

    public void init(MusicCardData data) {
        if (data == null) throw new IllegalArgumentException("data == null");
        setModel(data, null);

        configureCardClipping();
        configureCover();
        configureTextClipping();

        setImageOrFallback(
                coverView,
                data.cover(),
                defaultImage("/io/github/guillermodubon/musicplayer/assets/images/byDefaultImages/defaultPlaylist.png")
        );

        titleLabel.setText(safeText(data.title(), "Unknown Title"));
        titleLabel.setWrapText(false);
        titleLabel.setTextOverrun(OverrunStyle.ELLIPSIS);

        renderAllArtists(data);
        normalizeArtistTextNodes();

        bindCardClick(rootPane, data.id(), data.onPlay());
        resetMarqueeState();
        Platform.runLater(this::resetMarqueeState);
    }

    /** Renders the complete, already-resolved artist list without reducing it to one label. */
    private void renderAllArtists(MusicCardData data) {
        List<String> artists = data == null ? List.of() : data.artists();
        if (data != null && data.artistLinksEnabled()) {
            fillArtistLinks(artistLinkContainer, artists, data.onArtistClick());
        } else {
            fillArtistLabels(artistLinkContainer, artists);
        }
    }

    @FXML
    protected void onHover(MouseEvent e) {
        hovering = true;
        if (rootPane != null && !rootPane.getStyleClass().contains("music-card-hover")) {
            rootPane.getStyleClass().add("music-card-hover");
        }
        startMarquee();
    }

    @FXML
    protected void onExit(MouseEvent e) {
        hovering = false;
        if (rootPane != null) {
            rootPane.getStyleClass().remove("music-card-hover");
        }
        resetMarqueeState();
    }

    @FXML
    protected void onPlay(MouseEvent e) {
        MusicCardData data = model;
        if (data != null && data.onPlay() != null && data.id() != null) {
            data.onPlay().accept(data.id());
        }
    }

    private void configureCover() {
        if (coverView == null) return;

        // Keep every artwork source (including user-uploaded playlist covers)
        // on the same high-quality rendering path. The resolver already
        // decodes card artwork at a HiDPI-friendly size; the ImageView must
        // not distort or resample it unnecessarily afterwards.
        SquareImageViewSupport.install(coverView);

        if (coverShell == null || rootPane == null) return;

        if (coverView.fitWidthProperty().isBound()) coverView.fitWidthProperty().unbind();
        if (coverView.fitHeightProperty().isBound()) coverView.fitHeightProperty().unbind();

        rootPane.widthProperty().addListener((obs, oldValue, newValue) ->
                resizeCoverShell(newValue == null ? 0 : newValue.doubleValue()));
        Platform.runLater(() -> resizeCoverShell(rootPane.getWidth() > 0 ? rootPane.getWidth() : rootPane.getPrefWidth()));

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(coverView.fitWidthProperty());
        clip.heightProperty().bind(coverView.fitHeightProperty());
        clip.setArcWidth(16);
        clip.setArcHeight(16);
        coverView.setClip(clip);
    }

    private void resizeCoverShell(double width) {
        if (coverShell == null || width <= 0) return;
        double size = clamp(width - CARD_HORIZONTAL_PADDING, COVER_MIN_SIZE, COVER_MAX_SIZE);
        coverShell.setMinWidth(size);
        coverShell.setPrefWidth(size);
        coverShell.setMaxWidth(size);
        coverShell.setMinHeight(size);
        coverShell.setPrefHeight(size);
        coverShell.setMaxHeight(size);
        coverView.setFitWidth(size);
        coverView.setFitHeight(size);
    }

    private void configureCardClipping() {
        clipRegion(coverShell, COVER_CLIP_ARC);
    }

    private void clipRegion(Region region, double arc) {
        if (region == null || region.getClip() != null) return;
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(region.widthProperty());
        clip.heightProperty().bind(region.heightProperty());
        clip.setArcWidth(arc);
        clip.setArcHeight(arc);
        region.setClip(clip);
    }

    private void configureTextClipping() {
        clipViewport(titleViewport);
        clipViewport(artistViewport);
        configureTickerTrack(titleMarqueeBox, titleViewport);
        configureTickerTrack(artistLinkContainer, artistViewport);

        resizeTextViewports(rootPane == null || rootPane.getWidth() <= 0 ? 0 : rootPane.getWidth());
        if (rootPane != null) {
            rootPane.widthProperty().addListener((obs, oldValue, newValue) ->
                    resizeTextViewports(newValue == null ? 0 : newValue.doubleValue()));
        }
        restoreTitleNormalState();

        if (artistViewport != null) {
            artistViewport.widthProperty().addListener((obs, oldValue, newValue) -> updateArtistOverflow());
        }
        if (artistLinkContainer != null) {
            artistLinkContainer.layoutBoundsProperty().addListener((obs, oldValue, newValue) -> updateArtistOverflow());
        }
    }

    private void clipViewport(StackPane viewport) {
        if (viewport == null || viewport.getClip() != null) return;
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(viewport.widthProperty());
        clip.heightProperty().bind(viewport.heightProperty());
        viewport.setClip(clip);
    }

    private void configureTickerTrack(HBox track, Region viewport) {
        if (track == null) return;
        track.setManaged(true);
        track.setLayoutX(0);
        if (track.layoutYProperty().isBound()) track.layoutYProperty().unbind();
        track.setLayoutY(0);
        track.setTranslateX(0);
    }

    private void resizeTextViewports(double cardWidth) {
        double width = cardWidth > 0 ? cardWidth : rootPane == null ? 0 : rootPane.getPrefWidth();
        double contentWidth = Math.max(TEXT_MIN_WIDTH, width - TEXT_HORIZONTAL_PADDING);
        lockRegionWidth(titleViewport, contentWidth);
        lockRegionWidth(artistViewport, contentWidth);
        updateArtistOverflow();
    }

    private void lockRegionWidth(Region region, double width) {
        if (region == null || width <= 0) return;
        region.setMinWidth(width);
        region.setPrefWidth(width);
        region.setMaxWidth(width);
    }

    private void startMarquee() {
        if (artistOverflowLabel != null) {
            artistOverflowLabel.setVisible(false);
        }

        Platform.runLater(() -> {
            titleTransition = startTitleMarquee(titleTransition);
            artistTransition = startArtistMarquee(artistTransition);
        });
    }

    private Animation startTitleMarquee(Animation current) {
        stopTransition(current);
        removeMarqueeDuplicates(titleMarqueeBox);
        if (titleMarqueeBox == null || titleViewport == null || titleLabel == null) return null;

        unbindTitleWidth();
        titleLabel.setTextOverrun(OverrunStyle.CLIP);
        titleLabel.setMinWidth(Region.USE_PREF_SIZE);
        titleLabel.setPrefWidth(Region.USE_COMPUTED_SIZE);
        titleLabel.setMaxWidth(Double.MAX_VALUE);
        titleLabel.applyCss();
        titleLabel.autosize();

        double originalWidth = nodePrefWidth(titleLabel);
        double viewportWidth = titleViewport.getWidth();
        if (originalWidth - viewportWidth <= 4 || originalWidth <= 0 || viewportWidth <= 0) {
            titleMarqueeBox.setTranslateX(0);
            restoreTitleNormalState();
            return null;
        }

        setTickerMode(titleMarqueeBox, titleViewport, true);
        appendTitleDuplicate();
        return playTicker(titleMarqueeBox, originalWidth + MARQUEE_GAP);
    }

    private Animation startArtistMarquee(Animation current) {
        stopTransition(current);
        removeMarqueeDuplicates(artistLinkContainer);
        if (artistLinkContainer == null || artistViewport == null) return null;

        normalizeArtistTextNodes();
        artistLinkContainer.applyCss();
        artistLinkContainer.autosize();
        double originalWidth = originalArtistContentWidth();
        double viewportWidth = artistViewport.getWidth();
        if (originalWidth - viewportWidth <= 4 || originalWidth <= 0 || viewportWidth <= 0) {
            artistLinkContainer.setTranslateX(0);
            return null;
        }

        appendArtistDuplicates();
        setTickerMode(artistLinkContainer, artistViewport, true);
        double cycleDistance = originalWidth + MARQUEE_GAP + (artistLinkContainer.getSpacing() * 2);
        return playTicker(artistLinkContainer, cycleDistance);
    }

    private void setTickerMode(HBox track, Region viewport, boolean active) {
        if (track == null) return;
        if (track.layoutYProperty().isBound()) {
            track.layoutYProperty().unbind();
        }
        track.setManaged(!active);
        track.setLayoutX(0);
        track.setTranslateX(0);
        if (active && viewport != null) {
            track.applyCss();
            track.autosize();
            double trackHeight = Math.max(track.prefHeight(-1), track.getLayoutBounds().getHeight());
            double viewportHeight = viewport.getHeight();
            track.setLayoutY(Math.max(0, (viewportHeight - trackHeight) / 2.0));
        } else {
            track.setLayoutY(0);
        }
        track.requestLayout();
    }

    private Animation playTicker(Node content, double cycleDistance) {
        if (content == null || cycleDistance <= 0) return null;
        content.setTranslateX(0);
        TranslateTransition loop = new TranslateTransition(Duration.seconds(durationForOverflow(cycleDistance)), content);
        loop.setFromX(0);
        loop.setToX(-cycleDistance);
        loop.setInterpolator(Interpolator.LINEAR);
        loop.setCycleCount(Animation.INDEFINITE);
        loop.play();
        return loop;
    }

    private void appendTitleDuplicate() {
        if (titleMarqueeBox == null || titleLabel == null) return;
        Region gap = marqueeGap();
        Label duplicate = new Label(titleLabel.getText());
        duplicate.getStyleClass().addAll(titleLabel.getStyleClass());
        duplicate.setWrapText(false);
        duplicate.setTextOverrun(OverrunStyle.CLIP);
        duplicate.setMouseTransparent(true);
        markMarqueeDuplicate(gap);
        markMarqueeDuplicate(duplicate);
        titleMarqueeBox.getChildren().addAll(gap, duplicate);
    }

    private void appendArtistDuplicates() {
        if (artistLinkContainer == null) return;
        List<Node> originalNodes = new ArrayList<>(artistLinkContainer.getChildren());
        Region gap = marqueeGap();
        markMarqueeDuplicate(gap);
        artistLinkContainer.getChildren().add(gap);
        for (Node original : originalNodes) {
            Node copy = copyArtistNode(original);
            if (copy == null) continue;
            markMarqueeDuplicate(copy);
            artistLinkContainer.getChildren().add(copy);
        }
    }

    private Node copyArtistNode(Node original) {
        if (original instanceof Hyperlink source) {
            Hyperlink copy = new Hyperlink(source.getText());
            copy.getStyleClass().setAll(source.getStyleClass());
            copy.setFocusTraversable(false);
            configureTickerText(copy);
            copy.setOnAction(e -> {
                MusicCardData data = model;
                if (data != null && data.onArtistClick() != null) {
                    data.onArtistClick().accept(source.getText());
                }
            });
            return copy;
        }
        if (original instanceof Label source) {
            Label copy = new Label(source.getText());
            copy.getStyleClass().setAll(source.getStyleClass());
            copy.setMouseTransparent(true);
            configureTickerText(copy);
            return copy;
        }
        return null;
    }

    private Region marqueeGap() {
        Region gap = new Region();
        gap.setMinWidth(MARQUEE_GAP);
        gap.setPrefWidth(MARQUEE_GAP);
        gap.setMaxWidth(MARQUEE_GAP);
        gap.setMouseTransparent(true);
        return gap;
    }

    private void markMarqueeDuplicate(Node node) {
        if (node != null) node.getProperties().put(MARQUEE_DUPLICATE_KEY, Boolean.TRUE);
    }

    private void removeMarqueeDuplicates(HBox box) {
        if (box == null) return;
        box.getChildren().removeIf(node -> Boolean.TRUE.equals(node.getProperties().get(MARQUEE_DUPLICATE_KEY)));
    }

    private double nodePrefWidth(Node node) {
        if (node instanceof Region region) {
            return Math.max(region.prefWidth(-1), region.getLayoutBounds().getWidth());
        }
        return node == null ? 0 : node.getLayoutBounds().getWidth();
    }

    private double originalArtistContentWidth() {
        if (artistLinkContainer == null) return 0;
        double width = 0;
        int count = 0;
        for (Node node : artistLinkContainer.getChildren()) {
            if (Boolean.TRUE.equals(node.getProperties().get(MARQUEE_DUPLICATE_KEY))) continue;
            node.applyCss();
            node.autosize();
            width += nodePrefWidth(node);
            count++;
        }
        if (count > 1) {
            width += artistLinkContainer.getSpacing() * (count - 1);
        }
        return width;
    }

    private void normalizeArtistTextNodes() {
        if (artistLinkContainer == null) return;
        for (Node node : artistLinkContainer.getChildren()) {
            if (Boolean.TRUE.equals(node.getProperties().get(MARQUEE_DUPLICATE_KEY))) continue;
            if (node instanceof Labeled labeled) {
                configureTickerText(labeled);
            }
        }
    }

    private void configureTickerText(Labeled labeled) {
        if (labeled == null) return;
        if (labeled.maxWidthProperty().isBound()) {
            labeled.maxWidthProperty().unbind();
        }
        labeled.setWrapText(false);
        labeled.setTextOverrun(OverrunStyle.CLIP);
        labeled.setMinWidth(Region.USE_PREF_SIZE);
        labeled.setPrefWidth(Region.USE_COMPUTED_SIZE);
        labeled.setMaxWidth(Double.MAX_VALUE);
    }

    private void restoreTitleNormalState() {
        if (titleLabel == null) return;
        unbindTitleWidth();
        titleLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        titleLabel.setWrapText(false);
        titleLabel.setMinWidth(0);
        if (titleViewport != null) {
            titleLabel.prefWidthProperty().bind(titleViewport.widthProperty());
            titleLabel.maxWidthProperty().bind(titleViewport.widthProperty());
        } else {
            titleLabel.setPrefWidth(Region.USE_COMPUTED_SIZE);
            titleLabel.setMaxWidth(Region.USE_COMPUTED_SIZE);
        }
    }

    private void unbindTitleWidth() {
        if (titleLabel == null) return;
        if (titleLabel.prefWidthProperty().isBound()) {
            titleLabel.prefWidthProperty().unbind();
        }
        if (titleLabel.maxWidthProperty().isBound()) {
            titleLabel.maxWidthProperty().unbind();
        }
    }

    private double durationForOverflow(double overflow) {
        return Math.max(2.8, overflow / 50.0);
    }

    private void resetMarqueeState() {
        stopTransition(titleTransition);
        stopTransition(artistTransition);
        titleTransition = null;
        artistTransition = null;

        removeMarqueeDuplicates(titleMarqueeBox);
        removeMarqueeDuplicates(artistLinkContainer);
        setTickerMode(titleMarqueeBox, titleViewport, false);
        setTickerMode(artistLinkContainer, artistViewport, false);
        if (titleMarqueeBox != null) titleMarqueeBox.setTranslateX(0);
        if (artistLinkContainer != null) artistLinkContainer.setTranslateX(0);

        restoreTitleNormalState();

        normalizeArtistTextNodes();
        updateArtistOverflow();
    }

    private void updateArtistOverflow() {
        if (artistOverflowLabel == null || artistViewport == null || artistLinkContainer == null) return;
        artistLinkContainer.applyCss();
        artistLinkContainer.autosize();
        boolean overflowing = !hovering && originalArtistContentWidth() > artistViewport.getWidth() + 4;
        artistOverflowLabel.setVisible(overflowing);
    }

    private void stopTransition(Animation transition) {
        if (transition != null) {
            transition.stop();
        }
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
