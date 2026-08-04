package io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.preview;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.CacheHint;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.MediaPlayer;
import javafx.stage.Window;
import io.github.guillermodubon.musicplayer.controllers.ui.components.effects.MarqueeTextSupport;
import io.github.guillermodubon.musicplayer.controllers.ui.components.icons.SvgIconFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.helpers.PlayerMenuBarArtworkResolver;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.helpers.PlayerMenuSliderStyler;
import io.github.guillermodubon.musicplayer.utils.ArtistIdentity;

import java.util.Arrays;
import java.util.function.Consumer;

public class PreviewBarController {
    private enum SliderTarget {
        NONE,
        TIME,
        VOLUME
    }

    private enum LayoutDensity {
        NARROW,
        REGULAR,
        WIDE
    }

    private static final String ICON_ROOT = "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/";
    private static final String ICON_PLAY = ICON_ROOT + "play_arrow_27dp_FFFFFF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_PAUSE = ICON_ROOT + "pause_27dp_FFFFFF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_VOLUME = ICON_ROOT + "volume_up_27dp_FFFFFF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_VOLUME_OFF = ICON_ROOT + "volume_off_27dp_FFFFFF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_NORMAL = "#FFFFFF";
    private static final String ICON_HOVER = "#F4F4F4";
    private static final String ICON_BUTTON_STYLE = """
            -fx-background-color: transparent;
            -fx-background-insets: 0;
            -fx-border-color: transparent;
            -fx-border-width: 0;
            -fx-effect: null;
            -fx-focus-color: transparent;
            -fx-faint-focus-color: transparent;
            -fx-padding: 0;
            """;

    @FXML private BorderPane root;
    @FXML private StackPane coverShell;
    @FXML private ImageView coverImageView;
    @FXML private VBox trackTextBox;
    @FXML private StackPane titleViewport;
    @FXML private HBox titleMarqueeBox;
    @FXML private Label titleLabel;
    @FXML private StackPane artistViewport;
    @FXML private HBox artistLinksContainer;
    @FXML private Button playPauseBtn;
    @FXML private VBox playbackColumn;
    @FXML private HBox timeRow;
    @FXML private Slider timeSlider;
    @FXML private StackPane timeSliderShell;
    @FXML private Region timeSliderBase;
    @FXML private Region timeSliderFill;
    @FXML private Label currentTimeLabel;
    @FXML private Label lengthLabel;
    @FXML private StackPane volumeIconHost;
    @FXML private HBox volumeBox;
    @FXML private StackPane volumeSliderShell;
    @FXML private Region volumeSliderBase;
    @FXML private Region volumeSliderFill;
    @FXML private Slider volumeSlider;

    private final PlayerMenuBarArtworkResolver artworkResolver = new PlayerMenuBarArtworkResolver();
    private PreviewBarPlayerBinder playerBinder;
    private Node playPauseIcon;
    private Node volumeIcon;
    private Window dragTarget;
    private double dragOffsetX;
    private double dragOffsetY;
    private boolean draggingPreview;
    private boolean seekingPreview;
    private SliderTarget activeSliderTarget = SliderTarget.NONE;
    private SliderTarget keyboardSliderTarget = SliderTarget.TIME;
    private MarqueeTextSupport marqueeTextSupport;
    private LayoutDensity appliedLayoutDensity;

    @FXML
    public void initialize() {
        ensureInteractiveControls();
        configureMarquee();
        configureResponsiveTextLayout();
        installIcons();
        configureSliders();
        installDragHandlers();
        installTextMarqueeHitTesting();
        playerBinder = new PreviewBarPlayerBinder(
                playPauseBtn,
                timeSlider,
                currentTimeLabel,
                lengthLabel,
                volumeSlider,
                this::updatePlayPauseIcon
        );
        installPlaybackInteractionHandlers();
        installKeyboardSliderTargetTracking();
    }

    public void setInfo(String title, String artist, Image cover) {
        setInfo(title, artist, cover, null);
    }

    public void setInfo(String title, String artist, Image cover, Consumer<String> artistAction) {
        titleLabel.setText(title == null ? "" : title);
        populateArtistLinks(artist, artistAction);
        if (coverImageView != null) {
            // The preview receives a higher-resolution source image. Caching
            // the small, static render keeps it sharp without repeated
            // filtering work while the window is moved or repainted.
            coverImageView.setSmooth(true);
            coverImageView.setCache(true);
            coverImageView.setCacheHint(CacheHint.QUALITY);
            coverImageView.setImage(cover != null ? cover : artworkResolver.getDefaultCover());
        }
        refreshMarquee();
    }

    public void bindPlayer(MediaPlayer mp, double initialVolume) {
        if (playerBinder == null) {
            playerBinder = new PreviewBarPlayerBinder(
                    playPauseBtn,
                    timeSlider,
                    currentTimeLabel,
                    lengthLabel,
                    volumeSlider,
                    this::updatePlayPauseIcon
            );
        }
        playerBinder.bind(mp, initialVolume);
        updateVolumeIcon();
    }

    public void attachDragTarget(Window window) {
        this.dragTarget = window;
    }

    public void dispose() {
        if (playerBinder != null) {
            playerBinder.dispose();
        }
    }

    public boolean handleGlobalKeyboardShortcut(KeyEvent event) {
        if (event == null || event.isConsumed()) return false;

        if (event.getCode() == KeyCode.SPACE) {
            if (playerBinder != null) {
                playerBinder.togglePlayPause();
            }
            return true;
        }

        if (event.getCode() == KeyCode.LEFT || event.getCode() == KeyCode.RIGHT) {
            double delta = event.getCode() == KeyCode.RIGHT ? 5.0 : -5.0;
            if (keyboardSliderTarget == SliderTarget.VOLUME) {
                adjustPreviewVolume(delta);
            } else {
                adjustPreviewTime(delta);
            }
            return true;
        }

        return false;
    }

    private void installIcons() {
        playPauseBtn.setText("");
        playPauseBtn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        playPauseBtn.setStyle(ICON_BUTTON_STYLE);
        updatePlayPauseIcon(false);
        playPauseBtn.hoverProperty().addListener((obs, oldValue, newValue) -> updatePlayPauseIconColor());
        playPauseBtn.focusedProperty().addListener((obs, oldValue, newValue) -> updatePlayPauseIconColor());

        if (volumeSlider != null) {
            volumeSlider.valueProperty().addListener((obs, oldValue, newValue) -> updateVolumeIcon());
        }
        updateVolumeIcon();
    }

    private void configureSliders() {
        PlayerMenuSliderStyler.configureTimeSliderLayout(timeRow, timeSliderShell, timeSlider, 300, 500, 620);
        PlayerMenuSliderStyler.configureFixedSliderShell(volumeSliderShell, volumeSlider, 138, 158, 176);
        PlayerMenuSliderStyler.configureProgressFill(timeSlider, timeSliderShell, timeSliderBase, timeSliderFill);
        PlayerMenuSliderStyler.configureProgressFill(volumeSlider, volumeSliderShell, volumeSliderBase, volumeSliderFill);
        clearPreviewSliderClips();
    }

    private void installPlaybackInteractionHandlers() {
        if (root == null) return;

        root.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() != MouseButton.PRIMARY) return;

            if (containsScenePoint(playPauseBtn, event)) {
                if (playerBinder != null) {
                    playerBinder.togglePlayPause();
                }
                event.consume();
                return;
            }

            SliderTarget sliderTarget = resolveSliderTarget(event);
            if (sliderTarget == SliderTarget.TIME) {
                activeSliderTarget = SliderTarget.TIME;
                keyboardSliderTarget = SliderTarget.TIME;
                seekingPreview = true;
                if (playerBinder != null) {
                    playerBinder.beginSeekGesture();
                }
                seekPreviewFromMouse(event);
                event.consume();
                return;
            }

            if (sliderTarget == SliderTarget.VOLUME) {
                activeSliderTarget = SliderTarget.VOLUME;
                keyboardSliderTarget = SliderTarget.VOLUME;
                setVolumeFromMouse(event);
                event.consume();
            }
        });

        root.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (!event.isPrimaryButtonDown()) return;

            if (activeSliderTarget == SliderTarget.TIME && seekingPreview) {
                seekPreviewFromMouse(event);
                event.consume();
                return;
            }

            if (activeSliderTarget == SliderTarget.VOLUME) {
                setVolumeFromMouse(event);
                event.consume();
            }
        });

        root.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (activeSliderTarget == SliderTarget.TIME && seekingPreview) {
                seekPreviewFromMouse(event);
                if (playerBinder != null) {
                    playerBinder.endSeekGesture();
                }
                seekingPreview = false;
                activeSliderTarget = SliderTarget.NONE;
                event.consume();
                return;
            }

            if (activeSliderTarget == SliderTarget.VOLUME) {
                setVolumeFromMouse(event);
                activeSliderTarget = SliderTarget.NONE;
                event.consume();
                return;
            }

            seekingPreview = false;
            activeSliderTarget = SliderTarget.NONE;
        });
    }

    private SliderTarget resolveSliderTarget(MouseEvent event) {
        boolean inTime = isNodeInside(event.getTarget(), timeSliderShell)
                || containsScenePoint(timeSliderShell, event);
        boolean inVolume = isNodeInside(event.getTarget(), volumeSliderShell)
                || containsScenePoint(volumeSliderShell, event);

        if (inTime && inVolume) {
            return nearestSliderTarget(event);
        }
        if (inTime) return SliderTarget.TIME;
        if (inVolume) return SliderTarget.VOLUME;
        return SliderTarget.NONE;
    }

    private SliderTarget nearestSliderTarget(MouseEvent event) {
        double timeDistance = distanceToNodeCenter(timeSliderShell, event);
        double volumeDistance = distanceToNodeCenter(volumeSliderShell, event);
        if (Double.isInfinite(timeDistance) && Double.isInfinite(volumeDistance)) {
            return SliderTarget.NONE;
        }
        return timeDistance <= volumeDistance ? SliderTarget.TIME : SliderTarget.VOLUME;
    }

    private double distanceToNodeCenter(Node node, MouseEvent event) {
        if (node == null) return Double.POSITIVE_INFINITY;
        Bounds bounds = node.localToScene(node.getBoundsInLocal());
        if (bounds == null) return Double.POSITIVE_INFINITY;

        double centerX = bounds.getMinX() + bounds.getWidth() / 2.0;
        double centerY = bounds.getMinY() + bounds.getHeight() / 2.0;
        double dx = event.getSceneX() - centerX;
        double dy = event.getSceneY() - centerY;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private boolean containsScenePoint(Node node, MouseEvent event) {
        if (node == null) return false;
        Bounds bounds = node.localToScene(node.getBoundsInLocal());
        return bounds != null && bounds.contains(event.getSceneX(), event.getSceneY());
    }

    private boolean isNodeInside(Object target, Node expectedAncestor) {
        if (!(target instanceof Node node) || expectedAncestor == null) return false;
        Node current = node;
        while (current != null) {
            if (current == expectedAncestor) return true;
            current = current.getParent();
        }
        return false;
    }

    private void seekPreviewFromMouse(MouseEvent event) {
        if (timeSliderShell == null || timeSlider == null) return;
        double width = timeSliderShell.getWidth();
        if (width <= 0) return;

        double localX = timeSliderShell.sceneToLocal(event.getSceneX(), event.getSceneY()).getX();
        double ratio = Math.max(0.0, Math.min(1.0, localX / width));
        double value = timeSlider.getMin() + ratio * (timeSlider.getMax() - timeSlider.getMin());
        seekPreviewTo(value);
    }

    private void setVolumeFromMouse(MouseEvent event) {
        if (volumeSliderShell == null || volumeSlider == null) return;
        double width = volumeSliderShell.getWidth();
        if (width <= 0) return;

        double localX = volumeSliderShell.sceneToLocal(event.getSceneX(), event.getSceneY()).getX();
        double ratio = Math.max(0.0, Math.min(1.0, localX / width));
        double value = volumeSlider.getMin() + ratio * (volumeSlider.getMax() - volumeSlider.getMin());
        volumeSlider.setValue(value);
    }

    private void clearPreviewSliderClips() {
        if (timeRow != null) timeRow.setClip(null);
        if (timeSliderShell != null) timeSliderShell.setClip(null);
        if (volumeSliderShell != null) volumeSliderShell.setClip(null);
    }

    private void installKeyboardSliderTargetTracking() {
        markKeyboardTarget(timeRow, SliderTarget.TIME);
        markKeyboardTarget(timeSliderShell, SliderTarget.TIME);
        markKeyboardTarget(playbackColumn, SliderTarget.TIME);
        markKeyboardTarget(volumeBox, SliderTarget.VOLUME);
        markKeyboardTarget(volumeSliderShell, SliderTarget.VOLUME);
    }

    private void markKeyboardTarget(Node node, SliderTarget target) {
        if (node == null || target == null) return;
        node.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                keyboardSliderTarget = target;
            }
        });
        node.focusedProperty().addListener((obs, oldValue, focused) -> {
            if (focused) {
                keyboardSliderTarget = target;
            }
        });
    }

    private void installDragHandlers() {
        if (root == null) return;

        root.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            if (dragTarget == null || isInteractiveTarget(event.getTarget())) return;
            draggingPreview = true;
            dragOffsetX = event.getScreenX() - dragTarget.getX();
            dragOffsetY = event.getScreenY() - dragTarget.getY();
            event.consume();
        });

        root.addEventHandler(MouseEvent.MOUSE_DRAGGED, event -> {
            if (dragTarget == null || !draggingPreview) return;
            dragTarget.setX(event.getScreenX() - dragOffsetX);
            dragTarget.setY(event.getScreenY() - dragOffsetY);
            event.consume();
        });

        root.addEventHandler(MouseEvent.MOUSE_RELEASED, event -> draggingPreview = false);
    }

    private void installTextMarqueeHitTesting() {
        if (root == null) return;

        root.addEventFilter(MouseEvent.MOUSE_MOVED, this::updateMarqueeFromPointer);
        root.addEventFilter(MouseEvent.MOUSE_ENTERED, this::updateMarqueeFromPointer);
        root.addEventFilter(MouseEvent.MOUSE_EXITED, event -> deactivatePreviewMarquee());
    }

    private void updateMarqueeFromPointer(MouseEvent event) {
        if (isPointerOverTrackText(event)) {
            activatePreviewMarquee();
        } else {
            deactivatePreviewMarquee();
        }
    }

    private boolean isPointerOverTrackText(MouseEvent event) {
        return containsScenePoint(trackTextBox, event)
                || containsScenePoint(titleViewport, event)
                || containsScenePoint(titleLabel, event)
                || containsScenePoint(artistViewport, event)
                || containsScenePoint(artistLinksContainer, event);
    }

    private void activatePreviewMarquee() {
        if (marqueeTextSupport != null) {
            marqueeTextSupport.activate();
        }
    }

    private void deactivatePreviewMarquee() {
        if (marqueeTextSupport != null) {
            marqueeTextSupport.deactivate();
        }
    }

    private boolean isInteractiveTarget(Object target) {
        if (!(target instanceof Node node)) return false;
        Node current = node;
        while (current != null) {
            if (current instanceof Slider || current instanceof ButtonBase
                    || current == timeSliderShell
                    || current == playbackColumn
                    || current == volumeBox
                    || current == volumeSliderShell
                    || current.getStyleClass().contains("preview-slider-shell")) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private void ensureInteractiveControls() {
        if (root != null) root.setPickOnBounds(true);
        if (trackTextBox != null) {
            trackTextBox.setMouseTransparent(false);
            trackTextBox.setPickOnBounds(true);
        }
        if (titleViewport != null) titleViewport.setPickOnBounds(true);
        if (artistViewport != null) artistViewport.setPickOnBounds(true);
        if (playPauseBtn != null) {
            playPauseBtn.setMouseTransparent(false);
            playPauseBtn.setPickOnBounds(true);
        }
        if (timeSlider != null) timeSlider.setMouseTransparent(true);
        if (timeRow != null) timeRow.setMouseTransparent(false);
        if (playbackColumn != null) playbackColumn.setMouseTransparent(false);
        if (timeSliderShell != null) {
            timeSliderShell.setMouseTransparent(false);
            timeSliderShell.setPickOnBounds(true);
        }
        if (volumeSlider != null) volumeSlider.setMouseTransparent(true);
        if (volumeBox != null) volumeBox.setMouseTransparent(false);
        if (volumeSliderShell != null) {
            volumeSliderShell.setMouseTransparent(false);
            volumeSliderShell.setPickOnBounds(true);
        }
    }

    private void seekPreviewTo(double seconds) {
        if (playerBinder != null) {
            playerBinder.seekTo(seconds);
        }
    }

    private void adjustPreviewTime(double deltaSeconds) {
        if (timeSlider == null) return;

        double next = Math.max(timeSlider.getMin(), Math.min(timeSlider.getMax(), timeSlider.getValue() + deltaSeconds));
        if (playerBinder != null) {
            playerBinder.seekTo(next);
        } else {
            timeSlider.setValue(next);
        }
    }

    private void adjustPreviewVolume(double deltaPercent) {
        if (volumeSlider == null) return;

        double next = Math.max(volumeSlider.getMin(), Math.min(volumeSlider.getMax(), volumeSlider.getValue() + deltaPercent));
        volumeSlider.setValue(next);
    }

    private void configureMarquee() {
        marqueeTextSupport = new MarqueeTextSupport(
                titleViewport,
                titleMarqueeBox,
                titleLabel,
                artistViewport,
                artistLinksContainer
        );
        marqueeTextSupport.installHover(
                trackTextBox,
                titleViewport,
                titleLabel,
                artistViewport,
                artistLinksContainer
        );

        if (titleViewport != null) {
            titleViewport.widthProperty().addListener((obs, oldValue, newValue) -> refreshMarquee());
        }
        if (artistViewport != null) {
            artistViewport.widthProperty().addListener((obs, oldValue, newValue) -> refreshMarquee());
        }
        if (trackTextBox != null) {
            trackTextBox.widthProperty().addListener((obs, oldValue, newValue) -> refreshMarquee());
        }
    }

    private void refreshMarquee() {
        if (marqueeTextSupport != null) {
            marqueeTextSupport.refresh();
        }
    }

    private void configureResponsiveTextLayout() {
        if (root == null) return;
        root.widthProperty().addListener((obs, oldValue, newValue) ->
                applyResponsiveTextLayout(newValue.doubleValue()));
        Platform.runLater(() -> applyResponsiveTextLayout(root.getWidth()));
    }

    private void applyResponsiveTextLayout(double width) {
        if (trackTextBox == null || width <= 0) return;

        LayoutDensity density = width < 930
                ? LayoutDensity.NARROW
                : width < 1030 ? LayoutDensity.REGULAR : LayoutDensity.WIDE;
        if (density == appliedLayoutDensity) {
            refreshMarquee();
            return;
        }
        appliedLayoutDensity = density;

        switch (density) {
            case NARROW -> setTrackTextWidth(154, 184, 224);
            case REGULAR -> setTrackTextWidth(170, 220, 280);
            case WIDE -> setTrackTextWidth(190, 260, 340);
        }

        if (titleViewport != null) {
            titleViewport.setMinWidth(0);
            titleViewport.setMaxWidth(Double.MAX_VALUE);
        }
        if (artistViewport != null) {
            artistViewport.setMinWidth(0);
            artistViewport.setMaxWidth(Double.MAX_VALUE);
        }

        refreshMarquee();
    }

    private void setTrackTextWidth(double min, double pref, double max) {
        trackTextBox.setMinWidth(min);
        trackTextBox.setPrefWidth(pref);
        trackTextBox.setMaxWidth(max);
        HBox.setHgrow(trackTextBox, Priority.NEVER);
    }

    private void populateArtistLinks(String artistsText, Consumer<String> artistAction) {
        if (artistLinksContainer == null) return;
        artistLinksContainer.getChildren().clear();
        artistLinksContainer.setMouseTransparent(false);

        String safe = artistsText == null ? "" : artistsText.trim();
        if (safe.isBlank()) {
            Label empty = new Label("Unknown artist");
            empty.getStyleClass().add("preview-artist-empty");
            artistLinksContainer.getChildren().add(empty);
            refreshMarquee();
            return;
        }

        String[] names = Arrays.stream(safe.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toArray(String[]::new);

        for (int i = 0; i < names.length; i++) {
            String name = ArtistIdentity.displayName(names[i]);
            if (ArtistIdentity.isVariousArtists(name)) {
                Label label = new Label(name);
                label.getStyleClass().addAll("preview-artist-link", "artist-plain-label");
                label.setMouseTransparent(true);
                artistLinksContainer.getChildren().add(label);
            } else {
                Hyperlink link = new Hyperlink(name);
                link.getStyleClass().addAll("app-hyperlink", "preview-artist-link");
                link.setFocusTraversable(false);
                link.setMouseTransparent(false);
                link.setOnAction(event -> {
                    event.consume();
                    if (artistAction != null) {
                        artistAction.accept(name);
                    }
                });
                artistLinksContainer.getChildren().add(link);
            }

            if (i < names.length - 1) {
                Label separator = new Label(", ");
                separator.getStyleClass().add("preview-artist-separator");
                artistLinksContainer.getChildren().add(separator);
            }
        }
        refreshMarquee();
        Platform.runLater(this::refreshMarquee);
    }

    private void updatePlayPauseIcon(boolean playing) {
        if (playPauseBtn == null) return;
        playPauseIcon = SvgIconFactory.icon(playing ? ICON_PAUSE : ICON_PLAY, 30);
        playPauseIcon.setMouseTransparent(true);
        playPauseBtn.setGraphic(playPauseIcon);
        updatePlayPauseIconColor();
    }

    private void updatePlayPauseIconColor() {
        if (playPauseIcon == null || playPauseBtn == null) return;
        SvgIconFactory.setIconColor(playPauseIcon, playPauseBtn.isHover() || playPauseBtn.isFocused() ? ICON_HOVER : ICON_NORMAL);
    }

    private void updateVolumeIcon() {
        if (volumeIconHost == null || volumeSlider == null) return;
        boolean muted = volumeSlider.getValue() <= volumeSlider.getMin();
        String iconPath = muted ? ICON_VOLUME_OFF : ICON_VOLUME;
        volumeIcon = SvgIconFactory.icon(iconPath, 22);
        SvgIconFactory.setIconColor(volumeIcon, ICON_NORMAL);
        volumeIconHost.getChildren().setAll(volumeIcon);
        StackPane.setAlignment(volumeIcon, Pos.CENTER);
    }
}
