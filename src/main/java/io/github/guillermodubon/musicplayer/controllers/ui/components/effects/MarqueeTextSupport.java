package io.github.guillermodubon.musicplayer.controllers.ui.components.effects;

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.OverrunStyle;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.image.ImageView;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

public final class MarqueeTextSupport {

    private static final String DUPLICATE_KEY = "sharedMarqueeDuplicate";
    private static final double DEFAULT_GAP = 34;
    private static final double STANDARD_PIXELS_PER_SECOND = 50.0;

    private final StackPane titleViewport;
    private final HBox titleTrack;
    private final Label titleLabel;
    private final StackPane artistViewport;
    private final HBox artistTrack;
    private final double gapSize;
    private double pixelsPerSecond = STANDARD_PIXELS_PER_SECOND;

    private Animation titleTransition;
    private Animation artistTransition;
    private Node hoverNode;
    private final List<Node> hoverNodes = new ArrayList<>();
    private boolean hoverActive;
    private boolean alwaysActive;
    private Runnable beforeStart = () -> {};

    public MarqueeTextSupport(StackPane titleViewport,
                              HBox titleTrack,
                              Label titleLabel,
                              StackPane artistViewport,
                              HBox artistTrack) {
        this(titleViewport, titleTrack, titleLabel, artistViewport, artistTrack, DEFAULT_GAP);
    }

    public MarqueeTextSupport(StackPane titleViewport,
                              HBox titleTrack,
                              Label titleLabel,
                              StackPane artistViewport,
                              HBox artistTrack,
                              double gapSize) {
        this.titleViewport = titleViewport;
        this.titleTrack = titleTrack;
        this.titleLabel = titleLabel;
        this.artistViewport = artistViewport;
        this.artistTrack = artistTrack;
        this.gapSize = gapSize;
        configure();
    }

    public void installHover(Node... hoverTargets) {
        if (hoverTargets == null || hoverTargets.length == 0) return;

        for (Node hoverTarget : hoverTargets) {
            if (hoverTarget == null || hoverNodes.contains(hoverTarget)) continue;
            hoverNodes.add(hoverTarget);
            if (hoverNode == null) hoverNode = hoverTarget;

            hoverTarget.addEventHandler(MouseEvent.MOUSE_ENTERED, event -> activateForHover());
            hoverTarget.addEventHandler(MouseEvent.MOUSE_MOVED, event -> activateForHover());
            hoverTarget.addEventHandler(MouseEvent.MOUSE_EXITED, event -> resetAfterHoverExit());
            hoverTarget.hoverProperty().addListener((obs, wasHover, isHover) -> {
                if (Boolean.TRUE.equals(isHover)) {
                    activateForHover();
                } else {
                    resetAfterHoverExit();
                }
            });
        }
    }

    public void setBeforeStart(Runnable beforeStart) {
        this.beforeStart = beforeStart == null ? () -> {} : beforeStart;
    }

    /** Sets the ticker speed without changing its distance or layout behavior. */
    public void setPixelsPerSecond(double pixelsPerSecond) {
        if (pixelsPerSecond > 0) {
            this.pixelsPerSecond = Math.max(20.0, pixelsPerSecond);
        }
    }

    public void refresh() {
        reset();
        Platform.runLater(() -> {
            reset();
            if (alwaysActive) {
                activateAlwaysInternal();
            } else if (isAnyHoverTargetActive()) {
                activateForHover();
            }
        });
    }

    public void activate() {
        activateForHover();
    }

    /** Starts the marquee continuously, without requiring a hover target. */
    public void activateAlways() {
        alwaysActive = true;
        activateAlwaysInternal();
    }

    private void activateAlwaysInternal() {
        if (hoverActive) return;
        hoverActive = true;
        start();
    }

    public void deactivate() {
        alwaysActive = false;
        resetAfterHoverExit();
    }

    private void activateForHover() {
        if (hoverActive) return;
        hoverActive = true;
        start();
    }

    private void resetAfterHoverExit() {
        Platform.runLater(() -> {
            if (alwaysActive) return;
            if (!isAnyHoverTargetActive()) reset();
        });
    }

    private boolean isAnyHoverTargetActive() {
        if (!hoverNodes.isEmpty()) {
            return hoverNodes.stream().anyMatch(Node::isHover);
        }
        return hoverNode != null && hoverNode.isHover();
    }

    private void configure() {
        clipViewport(titleViewport);
        clipViewport(artistViewport);
        configureTrack(titleTrack);
        configureTrack(artistTrack);
        restoreTitleNormalState();
        normalizeArtistNodes();
    }

    private void start() {
        Platform.runLater(() -> {
            beforeStart.run();
            titleTransition = startTitleMarquee(titleTransition);
            artistTransition = startArtistMarquee(artistTransition);
        });
    }

    private Animation startTitleMarquee(Animation current) {
        stop(current);
        removeDuplicates(titleTrack);
        if (titleViewport == null || titleTrack == null || titleLabel == null) return null;

        unbindTitleWidth();
        titleLabel.setTextOverrun(OverrunStyle.CLIP);
        titleLabel.setWrapText(false);
        titleLabel.setMinWidth(Region.USE_PREF_SIZE);
        titleLabel.setPrefWidth(Region.USE_COMPUTED_SIZE);
        titleLabel.setMaxWidth(Double.MAX_VALUE);
        titleLabel.applyCss();
        titleLabel.autosize();

        double originalWidth = prefWidth(titleLabel);
        double viewportWidth = titleViewport.getWidth();
        if (originalWidth <= 0 || viewportWidth <= 0 || originalWidth - viewportWidth <= 4) {
            restoreTitleNormalState();
            return null;
        }

        appendTitleDuplicate();
        // Lock the track only after its duplicate is present. Locking it to
        // the first title alone clips the incoming copy, leaving an empty
        // viewport while the original exits on the left.
        setTickerMode(titleTrack, titleViewport, true);
        return play(titleTrack, originalWidth + gapSize);
    }

    private Animation startArtistMarquee(Animation current) {
        stop(current);
        removeDuplicates(artistTrack);
        if (artistViewport == null || artistTrack == null) return null;

        normalizeArtistNodes();
        artistTrack.applyCss();
        artistTrack.autosize();
        double originalWidth = originalArtistWidth();
        double viewportWidth = artistViewport.getWidth();
        if (originalWidth <= 0 || viewportWidth <= 0 || originalWidth - viewportWidth <= 4) {
            if (artistTrack != null) artistTrack.setTranslateX(0);
            return null;
        }

        appendArtistDuplicates();
        setTickerMode(artistTrack, artistViewport, true);
        return play(artistTrack, originalWidth + gapSize + artistTrack.getSpacing());
    }

    private Animation play(Node content, double distance) {
        if (content == null || distance <= 0) return null;
        content.setTranslateX(0);
        TranslateTransition transition = new TranslateTransition(Duration.seconds(durationFor(distance)), content);
        transition.setFromX(0);
        transition.setToX(-distance);
        transition.setInterpolator(Interpolator.LINEAR);
        transition.setCycleCount(Animation.INDEFINITE);
        transition.play();
        return transition;
    }

    private void appendTitleDuplicate() {
        if (titleTrack == null || titleLabel == null) return;
        Region gap = gap();
        Label duplicate = new Label(titleLabel.getText());
        duplicate.getStyleClass().setAll(titleLabel.getStyleClass());
        duplicate.setWrapText(false);
        duplicate.setTextOverrun(OverrunStyle.CLIP);
        duplicate.setMouseTransparent(true);
        markDuplicate(gap);
        markDuplicate(duplicate);
        titleTrack.getChildren().addAll(gap, duplicate);
    }

    private void appendArtistDuplicates() {
        if (artistTrack == null) return;
        List<Node> originalNodes = new ArrayList<>(artistTrack.getChildren());
        Region gap = gap();
        markDuplicate(gap);
        artistTrack.getChildren().add(gap);
        for (Node original : originalNodes) {
            Node copy = copyNode(original);
            if (copy == null) continue;
            markDuplicate(copy);
            artistTrack.getChildren().add(copy);
        }
    }

    private Node copyNode(Node original) {
        if (original instanceof Hyperlink source) {
            Hyperlink copy = new Hyperlink(source.getText());
            copy.getStyleClass().setAll(source.getStyleClass());
            copy.setFocusTraversable(false);
            copy.setOnAction(source.getOnAction());
            configureTickerText(copy);
            return copy;
        }
        if (original instanceof Label source) {
            Label copy = new Label(source.getText());
            copy.getStyleClass().setAll(source.getStyleClass());
            copy.setMouseTransparent(true);
            configureTickerText(copy);
            return copy;
        }
        if (original instanceof ImageView source) {
            ImageView copy = new ImageView();
            copy.imageProperty().bind(source.imageProperty());
            copy.setFitWidth(source.getFitWidth());
            copy.setFitHeight(source.getFitHeight());
            copy.setPreserveRatio(source.isPreserveRatio());
            copy.setSmooth(source.isSmooth());
            copy.getStyleClass().setAll(source.getStyleClass());
            if (source.getClip() instanceof Circle circle) {
                copy.setClip(new Circle(circle.getCenterX(), circle.getCenterY(), circle.getRadius()));
            }
            copy.setMouseTransparent(true);
            return copy;
        }
        if (original instanceof HBox source) {
            HBox copy = new HBox(source.getSpacing());
            copy.setAlignment(source.getAlignment());
            copy.getStyleClass().setAll(source.getStyleClass());
            copyContainerChildren(source, copy);
            return copy;
        }
        if (original instanceof VBox source) {
            VBox copy = new VBox(source.getSpacing());
            copy.setAlignment(source.getAlignment());
            copy.getStyleClass().setAll(source.getStyleClass());
            copyContainerChildren(source, copy);
            return copy;
        }
        if (original instanceof StackPane source) {
            StackPane copy = new StackPane();
            copy.setAlignment(source.getAlignment());
            copy.getStyleClass().setAll(source.getStyleClass());
            copyContainerChildren(source, copy);
            return copy;
        }
        return null;
    }

    private void copyContainerChildren(Pane source, Pane target) {
        for (Node child : source.getChildren()) {
            Node copy = copyNode(child);
            if (copy != null) target.getChildren().add(copy);
        }
    }

    public void reset() {
        hoverActive = false;
        stop(titleTransition);
        stop(artistTransition);
        titleTransition = null;
        artistTransition = null;

        removeDuplicates(titleTrack);
        removeDuplicates(artistTrack);
        setTickerMode(titleTrack, titleViewport, false);
        setTickerMode(artistTrack, artistViewport, false);
        if (titleTrack != null) titleTrack.setTranslateX(0);
        if (artistTrack != null) artistTrack.setTranslateX(0);
        restoreTitleNormalState();
        normalizeArtistNodes();
    }

    private void setTickerMode(HBox track, Region viewport, boolean active) {
        if (track == null) return;
        unlockTrackWidth(track);
        track.setLayoutX(0);
        track.setTranslateX(0);
        if (active && viewport != null) {
            // A StackPane bases its preferred size on managed children. Keep
            // the viewport's current height reserved before the scrolling
            // track becomes unmanaged; otherwise it can collapse, fire a
            // hover exit and continuously restart the ticker.
            reserveViewportHeight(viewport, track);
            // The duplicated ticker must not contribute its expanded width to
            // the parent layout; otherwise the surrounding header can shift.
            track.setManaged(false);
            track.applyCss();
            track.autosize();
            double trackWidth = Math.max(track.prefWidth(-1), track.getLayoutBounds().getWidth());
            lockTrackWidth(track, trackWidth);
            double trackHeight = Math.max(track.prefHeight(-1), track.getLayoutBounds().getHeight());
            double viewportHeight = viewport.getHeight();
            track.setLayoutY(Math.max(0, (viewportHeight - trackHeight) / 2.0));
            track.layout();
        } else {
            track.setManaged(true);
            track.setLayoutY(0);
            releaseViewportHeight(viewport);
        }
        track.requestLayout();
    }

    private void reserveViewportHeight(Region viewport, HBox track) {
        if (viewport == null || track == null) return;
        double trackHeight = Math.max(track.prefHeight(-1), track.getLayoutBounds().getHeight());
        double height = Math.max(viewport.getHeight(), trackHeight);
        if (height <= 0) return;

        viewport.setMinHeight(height);
        viewport.setPrefHeight(height);
        viewport.setMaxHeight(height);
    }

    private void releaseViewportHeight(Region viewport) {
        if (viewport == null) return;
        viewport.setMinHeight(Region.USE_COMPUTED_SIZE);
        viewport.setPrefHeight(Region.USE_COMPUTED_SIZE);
        viewport.setMaxHeight(Region.USE_COMPUTED_SIZE);
    }

    private void lockTrackWidth(HBox track, double width) {
        if (track == null || width <= 0) return;
        track.setMinWidth(width);
        track.setPrefWidth(width);
        track.setMaxWidth(width);
    }

    private void unlockTrackWidth(HBox track) {
        if (track == null) return;
        track.setMinWidth(Region.USE_COMPUTED_SIZE);
        track.setPrefWidth(Region.USE_COMPUTED_SIZE);
        // The artist track must keep its intrinsic width while idle so a long
        // list cannot become the preferred width of the whole card. During
        // marquee playback setTickerMode() locks it to the duplicated track
        // width explicitly.
        track.setMaxWidth(track == artistTrack ? Region.USE_PREF_SIZE : Double.MAX_VALUE);
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
        if (titleLabel.prefWidthProperty().isBound()) titleLabel.prefWidthProperty().unbind();
        if (titleLabel.maxWidthProperty().isBound()) titleLabel.maxWidthProperty().unbind();
    }

    private void normalizeArtistNodes() {
        if (artistTrack == null) return;
        for (Node node : artistTrack.getChildren()) {
            if (isDuplicate(node)) continue;
            normalizeArtistNode(node);
        }
    }

    private void normalizeArtistNode(Node node) {
        if (node instanceof Labeled labeled) {
            configureTickerText(labeled);
        }
        if (node instanceof Pane pane) {
            for (Node child : pane.getChildren()) {
                normalizeArtistNode(child);
            }
        }
    }

    private void configureTickerText(Labeled labeled) {
        if (labeled == null) return;
        if (labeled.maxWidthProperty().isBound()) labeled.maxWidthProperty().unbind();
        if (labeled.prefWidthProperty().isBound()) labeled.prefWidthProperty().unbind();
        labeled.setWrapText(false);
        labeled.setTextOverrun(OverrunStyle.CLIP);
        labeled.setMinWidth(Region.USE_PREF_SIZE);
        labeled.setPrefWidth(Region.USE_COMPUTED_SIZE);
        labeled.setMaxWidth(Double.MAX_VALUE);
    }

    private double originalArtistWidth() {
        if (artistTrack == null) return 0;
        double width = 0;
        int count = 0;
        for (Node node : artistTrack.getChildren()) {
            if (isDuplicate(node)) continue;
            node.applyCss();
            node.autosize();
            width += prefWidth(node);
            count++;
        }
        if (count > 1) width += artistTrack.getSpacing() * (count - 1);
        return width;
    }

    private double prefWidth(Node node) {
        if (node instanceof Region region) {
            return Math.max(region.prefWidth(-1), region.getLayoutBounds().getWidth());
        }
        return node == null ? 0 : node.getLayoutBounds().getWidth();
    }

    private void clipViewport(StackPane viewport) {
        if (viewport == null || viewport.getClip() != null) return;
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(viewport.widthProperty());
        clip.heightProperty().bind(viewport.heightProperty());
        viewport.setClip(clip);
    }

    private void configureTrack(HBox track) {
        if (track == null) return;
        track.setManaged(true);
        track.setLayoutX(0);
        track.setLayoutY(0);
        track.setTranslateX(0);
        if (track == artistTrack) track.setMaxWidth(Region.USE_PREF_SIZE);
    }

    private Region gap() {
        Region gap = new Region();
        gap.setMinWidth(gapSize);
        gap.setPrefWidth(gapSize);
        gap.setMaxWidth(gapSize);
        gap.setMouseTransparent(true);
        return gap;
    }

    private void removeDuplicates(HBox track) {
        if (track == null) return;
        List<Node> duplicates = track.getChildren().stream()
                .filter(this::isDuplicate)
                .toList();
        duplicates.forEach(this::releaseDuplicateResources);
        track.getChildren().removeAll(duplicates);
    }

    private void releaseDuplicateResources(Node node) {
        if (node instanceof ImageView imageView && imageView.imageProperty().isBound()) {
            imageView.imageProperty().unbind();
        }
        if (node instanceof Pane pane) {
            pane.getChildren().forEach(this::releaseDuplicateResources);
        }
    }

    private void markDuplicate(Node node) {
        if (node != null) node.getProperties().put(DUPLICATE_KEY, Boolean.TRUE);
    }

    private boolean isDuplicate(Node node) {
        return node != null && Boolean.TRUE.equals(node.getProperties().get(DUPLICATE_KEY));
    }

    private void stop(Animation animation) {
        if (animation != null) animation.stop();
    }

    private double durationFor(double distance) {
        return Math.max(2.8, distance / pixelsPerSecond);
    }
}
