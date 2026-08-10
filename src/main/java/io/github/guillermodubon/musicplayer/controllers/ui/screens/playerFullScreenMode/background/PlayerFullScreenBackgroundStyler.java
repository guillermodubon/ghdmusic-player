package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.background;

import javafx.beans.value.ChangeListener;
import javafx.geometry.Rectangle2D;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.Window;
import io.github.guillermodubon.musicplayer.services.images.colors.CoverColorExtractor;
import io.github.guillermodubon.musicplayer.services.images.colors.CoverColorPalette;

import java.util.List;

/**
 * Builds the layered ambient background used by fullscreen playback.
 *
 * <p>The cover is used twice: once as a strongly blurred image and once as a
 * lightweight color source for the dynamic glows. Keeping the artwork and
 * tint layers separate makes the effect easy to tune without touching the
 * centered cover or the playback controls.</p>
 */
public final class PlayerFullScreenBackgroundStyler {

    private static final double BACKGROUND_OPACITY = 0.34;
    private static final double BLUR_RADIUS = 63.0;
    private static final double BACKGROUND_OVERFLOW = 60.0;
    private static final double GLOW_LAYER_SCALE = 1.30;
    private static final double BACKGROUND_SCALE = 1.15;

    private final Pane backgroundLayer;
    private final Region baseColorLayer;
    private final ImageView ambientArtwork;
    private final PlayerFullScreenColorBlobLayer animatedBlobLayer;
    private final Region primaryGlowLayer;
    private final Region secondaryGlowLayer;
    private final Region exposureMask;
    private final Region vignetteLayer;
    private final Region bottomGradientLayer;
    private final PlayerFullScreenAmbientMotionCoordinator backgroundMotion;
    private final ChangeListener<Number> widthListener;
    private final ChangeListener<Number> heightListener;
    private final ChangeListener<Scene> sceneListener;
    private final ChangeListener<Window> windowListener;
    private final ChangeListener<Boolean> windowShowingListener;
    private final ChangeListener<Boolean> windowIconifiedListener;
    private Window observedWindow;
    private boolean layersVisible;

    public PlayerFullScreenBackgroundStyler(Pane backgroundLayer) {
        this.backgroundLayer = backgroundLayer;
        this.baseColorLayer = createLayer("#111111");
        this.ambientArtwork = createAmbientArtwork();
        this.animatedBlobLayer = new PlayerFullScreenColorBlobLayer();
        this.primaryGlowLayer = createLayer("transparent");
        this.secondaryGlowLayer = createLayer("transparent");
        this.exposureMask = createLayer("rgba(0, 0, 0, 0.18)");
        this.vignetteLayer = createLayer("transparent");
        this.bottomGradientLayer = createLayer("transparent");
        this.backgroundMotion = new PlayerFullScreenAmbientMotionCoordinator(ambientArtwork);
        this.widthListener = (obs, oldValue, newValue) -> updateViewport();
        this.heightListener = (obs, oldValue, newValue) -> updateViewport();
        this.sceneListener = (obs, oldScene, newScene) -> handleSceneChanged(oldScene, newScene);
        this.windowListener = (obs, oldWindow, newWindow) -> {
            detachWindowListeners(oldWindow);
            attachWindowListeners(newWindow);
            refreshMotionVisibility();
        };
        this.windowShowingListener = (obs, oldValue, newValue) -> refreshMotionVisibility();
        this.windowIconifiedListener = (obs, oldValue, newValue) -> refreshMotionVisibility();

        if (backgroundLayer != null) {
            backgroundLayer.getChildren().setAll(
                    baseColorLayer,
                    ambientArtwork,
                    primaryGlowLayer,
                    secondaryGlowLayer,
                    exposureMask,
                    vignetteLayer,
                    bottomGradientLayer,
                    // Keep the animated fields above the static masks. They
                    // remain inside the background pane, so content and input
                    // are unaffected, but their broad color transitions are
                    // no longer muted by the vignette or bottom fade.
                    animatedBlobLayer.root()
            );
            backgroundLayer.widthProperty().addListener(widthListener);
            backgroundLayer.heightProperty().addListener(heightListener);
            backgroundLayer.sceneProperty().addListener(sceneListener);
            handleSceneChanged(null, backgroundLayer.getScene());
        }
    }

    public void apply(Image cover) {
        if (backgroundLayer == null || cover == null || cover.isError()) {
            clear();
            return;
        }

        applyArtwork(cover);
        applyDynamicPalette(CoverColorExtractor.extractFullscreenColors(cover, 3));
    }

    /** Applies only the artwork layer; safe to call before async palette work. */
    public void applyArtwork(Image cover) {
        if (backgroundLayer == null || cover == null || cover.isError()) {
            clear();
            return;
        }

        resetPalette();
        ambientArtwork.setImage(cover);
        ambientArtwork.setOpacity(BACKGROUND_OPACITY);
        setLayersVisible(true);
        updateViewport();
    }

    /** Applies a palette that was calculated away from the JavaFX thread. */
    public void applyPalette(List<CoverColorPalette> colors) {
        if (backgroundLayer == null) return;
        applyDynamicPalette(colors == null ? List.of() : colors);
    }

    public void clear() {
        ambientArtwork.setImage(null);
        ambientArtwork.setOpacity(0.0);
        resetPalette();
        setLayersVisible(false);
    }

    public void dispose() {
        clear();
        if (backgroundLayer != null) {
            backgroundLayer.widthProperty().removeListener(widthListener);
            backgroundLayer.heightProperty().removeListener(heightListener);
            backgroundLayer.sceneProperty().removeListener(sceneListener);
            Scene scene = backgroundLayer.getScene();
            if (scene != null) {
                scene.windowProperty().removeListener(windowListener);
            }
            detachWindowListeners(observedWindow);
            backgroundLayer.getChildren().clear();
        }
        animatedBlobLayer.dispose();
        backgroundMotion.dispose();
    }

    private ImageView createAmbientArtwork() {
        ImageView imageView = new ImageView();
        imageView.setPreserveRatio(false);
        imageView.setSmooth(true);
        imageView.setCache(true);
        imageView.setManaged(false);
        imageView.setMouseTransparent(true);
        imageView.setOpacity(0.0);
        imageView.setScaleX(BACKGROUND_SCALE);
        imageView.setScaleY(BACKGROUND_SCALE);

        // JavaFX caps one GaussianBlur radius at 63. Two chained passes give
        // a much wider diffusion while keeping the effect GPU-friendly.
        GaussianBlur firstBlur = new GaussianBlur(BLUR_RADIUS);
        GaussianBlur secondBlur = new GaussianBlur(BLUR_RADIUS);
        secondBlur.setInput(firstBlur);
        ColorAdjust colorAdjust = new ColorAdjust();
        colorAdjust.setSaturation(-0.30);
        colorAdjust.setBrightness(-0.10);
        colorAdjust.setContrast(-0.06);
        colorAdjust.setInput(secondBlur);
        imageView.setEffect(colorAdjust);
        return imageView;
    }

    private Region createLayer(String background) {
        Region layer = new Region();
        layer.setManaged(false);
        layer.setMouseTransparent(true);
        layer.setVisible(false);
        layer.setStyle("-fx-background-color: " + background + ";");
        return layer;
    }

    private void applyDynamicPalette(List<CoverColorPalette> colors) {
        CoverColorPalette primary = colors.isEmpty()
                ? new CoverColorPalette(17, 17, 17)
                : colors.get(0);
        CoverColorPalette secondary = colors.size() > 1
                ? colors.get(1)
                : primary;
        CoverColorPalette accent = colors.size() > 2
                ? colors.get(2)
                : secondary;

        baseColorLayer.setStyle(
                "-fx-background-color: " + primary.fullscreenDeepHex() + ";"
        );
        animatedBlobLayer.applyPalette(primary, secondary, accent);

        // The two glows use different centers so the color fields overlap
        // organically instead of splitting the screen into two halves.
        primaryGlowLayer.setStyle(
                "-fx-background-color: "
                        + "radial-gradient(center 20% 22%, radius 88%, "
                        + primary.fullscreenRgba(0.38) + " 0%, "
                        + primary.fullscreenRgba(0.12) + " 46%, "
                        + "transparent 100%);"
        );
        secondaryGlowLayer.setStyle(
                "-fx-background-color: "
                        + "radial-gradient(center 82% 34%, radius 92%, "
                        + secondary.fullscreenRgba(0.30) + " 0%, "
                        + secondary.fullscreenRgba(0.10) + " 48%, "
                        + "transparent 100%);"
        );

        exposureMask.setStyle(
                "-fx-background-color: rgba(0, 0, 0, 0.18);"
        );
        vignetteLayer.setStyle(
                "-fx-background-color: "
                        + "radial-gradient(center 50% 42%, radius 78%, "
                        + "transparent 34%, rgba(0, 0, 0, 0.08) 58%, "
                        + "rgba(0, 0, 0, 0.72) 100%);"
        );
        bottomGradientLayer.setStyle(
                "-fx-background-color: "
                        + "linear-gradient(to bottom, transparent 0%, "
                        + "rgba(0, 0, 0, 0.04) 42%, "
                        + "rgba(0, 0, 0, 0.34) 70%, "
                        + "rgba(0, 0, 0, 0.90) 100%);"
        );
    }

    private void resetPalette() {
        baseColorLayer.setStyle("-fx-background-color: #111111;");
        animatedBlobLayer.clearPalette();
        primaryGlowLayer.setStyle("-fx-background-color: transparent;");
        secondaryGlowLayer.setStyle("-fx-background-color: transparent;");
        exposureMask.setStyle("-fx-background-color: rgba(0, 0, 0, 0.18);");
        vignetteLayer.setStyle("-fx-background-color: transparent;");
        bottomGradientLayer.setStyle("-fx-background-color: transparent;");
    }

    private void setLayersVisible(boolean visible) {
        layersVisible = visible;
        baseColorLayer.setVisible(visible);
        ambientArtwork.setVisible(visible);
        animatedBlobLayer.setVisible(visible);
        primaryGlowLayer.setVisible(visible);
        secondaryGlowLayer.setVisible(visible);
        exposureMask.setVisible(visible);
        vignetteLayer.setVisible(visible);
        bottomGradientLayer.setVisible(visible);
        if (visible) {
            refreshMotionVisibility();
        } else {
            backgroundMotion.stop();
            animatedBlobLayer.stop();
        }
    }

    private void handleSceneChanged(Scene oldScene, Scene newScene) {
        if (oldScene != null) {
            oldScene.windowProperty().removeListener(windowListener);
            detachWindowListeners(oldScene.getWindow());
        }
        if (newScene != null) {
            newScene.windowProperty().addListener(windowListener);
            attachWindowListeners(newScene.getWindow());
        }
        refreshMotionVisibility();
    }

    private void attachWindowListeners(Window window) {
        if (window == null || window == observedWindow) {
            return;
        }
        observedWindow = window;
        window.showingProperty().addListener(windowShowingListener);
        if (window instanceof Stage stage) {
            stage.iconifiedProperty().addListener(windowIconifiedListener);
        }
    }

    private void detachWindowListeners(Window window) {
        if (window == null) {
            return;
        }
        window.showingProperty().removeListener(windowShowingListener);
        if (window instanceof Stage stage) {
            stage.iconifiedProperty().removeListener(windowIconifiedListener);
        }
        if (window == observedWindow) {
            observedWindow = null;
        }
    }

    private void refreshMotionVisibility() {
        if (!layersVisible) {
            backgroundMotion.stop();
            animatedBlobLayer.stop();
            return;
        }
        if (isRenderTargetVisible()) {
            backgroundMotion.play();
            animatedBlobLayer.play();
        } else {
            backgroundMotion.pause();
            animatedBlobLayer.pause();
        }
    }

    private boolean isRenderTargetVisible() {
        if (backgroundLayer == null || backgroundLayer.getScene() == null) {
            return false;
        }
        Window window = backgroundLayer.getScene().getWindow();
        if (window == null || !window.isShowing()) {
            return false;
        }
        return !(window instanceof Stage stage) || !stage.isIconified();
    }

    private void updateViewport() {
        if (backgroundLayer == null) return;

        double width = backgroundLayer.getWidth();
        double height = backgroundLayer.getHeight();
        Image image = ambientArtwork.getImage();
        if (width <= 1 || height <= 1 || image == null
                || image.getWidth() <= 1 || image.getHeight() <= 1) {
            resizeLayers(width, height);
            return;
        }

        int imageWidth = Math.max(1, (int) Math.ceil(image.getWidth()));
        int imageHeight = Math.max(1, (int) Math.ceil(image.getHeight()));
        double scale = Math.max(width / imageWidth, height / imageHeight);
        double viewportWidth = Math.min(imageWidth, width / scale);
        double viewportHeight = Math.min(imageHeight, height / scale);
        double viewportX = Math.max(0.0, (imageWidth - viewportWidth) / 2.0);
        double viewportY = Math.max(0.0, (imageHeight - viewportHeight) / 2.0);

        ambientArtwork.setViewport(new Rectangle2D(
                viewportX,
                viewportY,
                viewportWidth,
                viewportHeight
        ));

        // Let the artwork overflow the viewport before the scale transform so
        // the blur never exposes transparent or degraded edges.
        double artworkWidth = width + BACKGROUND_OVERFLOW;
        double artworkHeight = height + BACKGROUND_OVERFLOW;
        ambientArtwork.setFitWidth(artworkWidth);
        ambientArtwork.setFitHeight(artworkHeight);
        ambientArtwork.setLayoutX(-BACKGROUND_OVERFLOW / 2.0);
        ambientArtwork.setLayoutY(-BACKGROUND_OVERFLOW / 2.0);
        resizeLayers(width, height);
    }

    private void resizeLayers(double width, double height) {
        if (width <= 0 || height <= 0) return;
        resize(baseColorLayer, width, height);
        animatedBlobLayer.resize(width, height);
        if (layersVisible && isRenderTargetVisible()) {
            animatedBlobLayer.play();
        }
        resizeOversized(primaryGlowLayer, width, height, GLOW_LAYER_SCALE);
        resizeOversized(secondaryGlowLayer, width, height, GLOW_LAYER_SCALE);
        resize(exposureMask, width, height);
        resize(vignetteLayer, width, height);
        resize(bottomGradientLayer, width, height);
    }

    private void resize(Region layer, double width, double height) {
        layer.resizeRelocate(0.0, 0.0, width, height);
    }

    private void resizeOversized(
            Region layer,
            double width,
            double height,
            double scale
    ) {
        double oversizedWidth = width * scale;
        double oversizedHeight = height * scale;
        layer.resizeRelocate(
                (width - oversizedWidth) / 2.0,
                (height - oversizedHeight) / 2.0,
                oversizedWidth,
                oversizedHeight
        );
    }
}
