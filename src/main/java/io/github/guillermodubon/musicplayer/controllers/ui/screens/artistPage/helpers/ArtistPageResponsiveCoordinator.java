package io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.helpers;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;

import java.util.ArrayList;
import java.util.List;

/** Handles Artist page sizing, breakpoints, flow wrapping and header viewport. */
public final class ArtistPageResponsiveCoordinator {

    private static final double MIN_HEADER_HEIGHT = 250;
    private static final double MAX_HEADER_HEIGHT = 560;
    private static final double NARROW_BREAKPOINT = 560;
    private static final double COMPACT_BREAKPOINT = 840;

    private final ArtistPageViewBindings view;
    private final DoubleProperty horizontalContentInset = new SimpleDoubleProperty(56);
    private final List<FlowPane> responsiveFlows = new ArrayList<>();
    private boolean configured;
    private boolean layoutQueued;

    public ArtistPageResponsiveCoordinator(ArtistPageViewBindings view) {
        this.view = view;
    }

    public void configureLayoutBounds() {
        configureFlexibleContainers();
        requestResponsiveLayout();
        if (configured || view.headerRoot() == null || view.headerImage() == null) {
            return;
        }

        try {
            Rectangle clip = new Rectangle();
            clip.widthProperty().bind(view.headerRoot().widthProperty());
            clip.heightProperty().bind(view.headerRoot().heightProperty());
            view.headerRoot().setClip(clip);

            ImageView headerImage = view.headerImage();
            headerImage.setPreserveRatio(true);
            headerImage.setMouseTransparent(true);
            headerImage.setPickOnBounds(false);
            javafx.scene.layout.StackPane.setAlignment(headerImage, Pos.CENTER);
            javafx.scene.layout.StackPane.setMargin(headerImage, Insets.EMPTY);

            if (view.headerImageFrame() != null) {
                view.headerImageFrame().setMouseTransparent(true);
                view.headerImageFrame().setPickOnBounds(false);
                javafx.scene.layout.StackPane.setAlignment(
                        view.headerImageFrame(),
                        Pos.TOP_CENTER
                );
                javafx.scene.layout.StackPane.setMargin(
                        view.headerImageFrame(),
                        Insets.EMPTY
                );
            }

            if (view.headerBackgroundImage() != null) {
                view.headerBackgroundImage().setPreserveRatio(true);
                view.headerBackgroundImage().setMouseTransparent(true);
                view.headerBackgroundImage().setPickOnBounds(false);
                view.headerBackgroundImage().setEffect(new GaussianBlur(24));
                javafx.scene.layout.StackPane.setAlignment(
                        view.headerBackgroundImage(),
                        Pos.CENTER
                );
            }

            view.headerRoot().widthProperty().addListener(
                    (obs, oldValue, newValue) -> {
                        updateHeaderViewport();
                        requestResponsiveLayout();
                    }
            );
            view.headerRoot().heightProperty().addListener(
                    (obs, oldValue, newValue) -> {
                        updateHeaderViewport();
                        requestResponsiveLayout();
                    }
            );

            if (view.biographyTextHeader() != null
                    && !view.biographyTextHeader().wrappingWidthProperty().isBound()) {
                view.biographyTextHeader().wrappingWidthProperty().bind(
                        Bindings.max(
                                180d,
                                view.headerRoot().widthProperty()
                                        .subtract(horizontalContentInset)
                        )
                );
            }
            if (view.artistScrollPane() != null && view.biographyText() != null
                    && !view.biographyText().wrappingWidthProperty().isBound()) {
                view.biographyText().wrappingWidthProperty().bind(
                        Bindings.max(
                                180d,
                                view.artistScrollPane().widthProperty()
                                        .subtract(horizontalContentInset)
                        )
                );
            }

            configureResponsiveFlow(view.topTracksFlow());
            configureResponsiveFlow(view.albumsFlow());
            configureResponsiveFlow(view.singlesFlow());
            configureResponsiveFlow(view.playlistsFlow());

            if (view.artistScrollPane() != null) {
                view.artistScrollPane().widthProperty().addListener(
                        (obs, oldValue, newValue) -> requestResponsiveLayout()
                );
                view.artistScrollPane().viewportBoundsProperty().addListener(
                        (obs, oldValue, newValue) -> requestResponsiveLayout()
                );
            }
            if (view.centerVBox() != null) {
                view.centerVBox().widthProperty().addListener(
                        (obs, oldValue, newValue) -> requestResponsiveLayout()
                );
            }
            if (view.mainContent() != null) {
                view.mainContent().widthProperty().addListener(
                        (obs, oldValue, newValue) -> requestResponsiveLayout()
                );
            }
            if (view.pageRoot() != null) {
                view.pageRoot().widthProperty().addListener(
                        (obs, oldValue, newValue) -> requestResponsiveLayout()
                );
                view.pageRoot().heightProperty().addListener(
                        (obs, oldValue, newValue) -> requestResponsiveLayout()
                );
            }

            headerImage.imageProperty().addListener((obs, oldImage, newImage) -> {
                if (newImage != null) {
                    newImage.widthProperty().addListener(
                            (wObs, oldWidth, newWidth) -> updateHeaderViewport()
                    );
                    newImage.heightProperty().addListener(
                            (hObs, oldHeight, newHeight) -> updateHeaderViewport()
                    );
                }
                updateHeaderViewport();
            });
            if (view.headerBackgroundImage() != null) {
                view.headerBackgroundImage().imageProperty().addListener(
                        (obs, oldImage, newImage) -> updateHeaderViewport()
                );
            }

            configured = true;
            requestResponsiveLayout();
        } catch (Exception ignored) {
        }
    }

    public void requestResponsiveLayout() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::requestResponsiveLayout);
            return;
        }
        if (layoutQueued) {
            return;
        }
        layoutQueued = true;
        Platform.runLater(() -> {
            layoutQueued = false;
            updateResponsiveLayout();
        });
    }

    public void updateHeaderViewport() {
        if (view.headerRoot() == null) {
            return;
        }

        double width = view.headerRoot().getWidth();
        if (width <= 0) {
            width = view.headerRoot().getPrefWidth();
        }
        if (width <= 0) {
            return;
        }

        double responsiveHeight = responsiveHeaderHeight(width);
        if (Math.abs(view.headerRoot().getPrefHeight() - responsiveHeight) > 0.5) {
            view.headerRoot().setMinHeight(responsiveHeight);
            view.headerRoot().setPrefHeight(responsiveHeight);
            view.headerRoot().setMaxHeight(responsiveHeight);
        }

        double height = view.headerRoot().getHeight();
        if (height <= 0) {
            height = responsiveHeight;
        }
        double headerRatio = width / Math.max(1, height);

        Image background = view.headerBackgroundImage() == null
                ? null
                : view.headerBackgroundImage().getImage();
        if (view.headerBackgroundImage() != null) {
            double sourceRatio = imageRatio(background, headerRatio);
            double coverWidth = sourceRatio >= headerRatio
                    ? height * sourceRatio
                    : width;
            double coverHeight = sourceRatio >= headerRatio
                    ? height
                    : width / sourceRatio;
            view.headerBackgroundImage().setFitWidth(coverWidth);
            view.headerBackgroundImage().setFitHeight(coverHeight);
        }

        Image foreground = view.headerImage() == null
                ? null
                : view.headerImage().getImage();
        if (view.headerImage() != null) {
            double sourceRatio = imageRatio(foreground, 1.0);
            double maxWidth = width * 0.86;
            double containedHeight = height;
            double containedWidth = containedHeight * sourceRatio;
            if (containedWidth > maxWidth) {
                containedWidth = maxWidth;
                containedHeight = containedWidth / sourceRatio;
            }
            if (view.headerImageFrame() != null) {
                setFixedSize(view.headerImageFrame(), containedWidth, containedHeight);
            }
            view.headerImage().setFitWidth(Math.max(1, containedWidth));
            view.headerImage().setFitHeight(Math.max(1, containedHeight));
            view.headerImage().setViewport(null);
        }
    }

    private void configureFlexibleContainers() {
        if (view.artistScrollPane() != null) {
            view.artistScrollPane().setFitToWidth(true);
            view.artistScrollPane().setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            view.artistScrollPane().setMinWidth(0);
            view.artistScrollPane().setMaxWidth(Double.MAX_VALUE);
            view.artistScrollPane().setMaxHeight(Double.MAX_VALUE);
        }
        if (view.centerVBox() != null) {
            view.centerVBox().setMinWidth(0);
            view.centerVBox().setMaxWidth(Double.MAX_VALUE);
            view.centerVBox().setFillWidth(true);
        }
        if (view.mainContent() != null) {
            view.mainContent().setMinWidth(0);
            view.mainContent().setMaxWidth(Double.MAX_VALUE);
            view.mainContent().setFillWidth(true);
        }
    }

    private void updateResponsiveLayout() {
        double width = availableContentWidth();
        if (width <= 0) {
            return;
        }

        boolean narrow = width < NARROW_BREAKPOINT;
        boolean compact = width < COMPACT_BREAKPOINT;
        double horizontalPadding = narrow ? 14 : compact ? 20 : 28;
        double headerPadding = narrow ? 16 : compact ? 24 : 32;
        double headerHeight = responsiveHeaderHeight(width);

        horizontalContentInset.set(horizontalPadding * 2);
        if (view.pageRoot() != null) {
            setResponsiveClass(view.pageRoot(), "artist-page-compact", compact);
            setResponsiveClass(view.pageRoot(), "artist-page-narrow", narrow);
        }
        if (view.headerRoot() != null) {
            view.headerRoot().setMinHeight(headerHeight);
            view.headerRoot().setPrefHeight(headerHeight);
            view.headerRoot().setMaxHeight(headerHeight);
        }
        if (view.headerOverlay() != null) {
            view.headerOverlay().setMaxWidth(Double.MAX_VALUE);
            view.headerOverlay().setPadding(new Insets(
                    narrow ? 18 : compact ? 24 : 28,
                    headerPadding,
                    narrow ? 24 : compact ? 30 : 38,
                    headerPadding
            ));
        }
        if (view.headerInfo() != null) {
            view.headerInfo().setMaxWidth(Math.max(180, width - headerPadding * 2));
        }
        if (view.artistNameLabel() != null) {
            view.artistNameLabel().setWrapText(true);
            view.artistNameLabel().setMaxWidth(Math.max(180, width - headerPadding * 2));
        }
        if (view.mainContent() != null) {
            view.mainContent().setPadding(new Insets(
                    narrow ? 16 : compact ? 20 : 24,
                    horizontalPadding,
                    narrow ? 24 : compact ? 30 : 36,
                    horizontalPadding
            ));
            view.mainContent().setSpacing(narrow ? 14 : compact ? 18 : 22);
        }
        if (view.localCarouselHost() != null) {
            view.localCarouselHost().setMinWidth(0);
            view.localCarouselHost().setMaxWidth(Double.MAX_VALUE);
            view.localCarouselHost().setPrefWidth(Math.max(0, width));
        }
        for (FlowPane flow : responsiveFlows) {
            updateFlowLayout(flow, width);
        }
        updateHeaderViewport();
    }

    private double availableContentWidth() {
        double width = 0;
        if (view.artistScrollPane() != null) {
            width = view.artistScrollPane().getViewportBounds().getWidth();
            if (width <= 0) {
                width = view.artistScrollPane().getWidth();
            }
        }
        if (width <= 0 && view.centerVBox() != null) {
            width = view.centerVBox().getWidth();
        }
        if (width <= 0 && view.pageRoot() != null) {
            width = view.pageRoot().getWidth();
        }
        return width;
    }

    private double responsiveHeaderHeight(double width) {
        double preferred = width * (width < NARROW_BREAKPOINT ? 0.72 : 0.38);
        return Math.max(MIN_HEADER_HEIGHT, Math.min(MAX_HEADER_HEIGHT, preferred));
    }

    private void configureResponsiveFlow(FlowPane flow) {
        if (flow == null) {
            return;
        }
        if (!responsiveFlows.contains(flow)) {
            responsiveFlows.add(flow);
        }
        flow.setMaxWidth(Double.MAX_VALUE);
        flow.setMinWidth(0);
        requestResponsiveLayout();
    }

    private void updateFlowLayout(FlowPane flow, double fallbackWidth) {
        if (flow == null) {
            return;
        }
        double width = view.mainContent() == null ? 0 : view.mainContent().getWidth();
        if (width <= 0) {
            width = fallbackWidth;
        }
        Insets padding = view.mainContent() == null
                ? Insets.EMPTY
                : view.mainContent().getPadding();
        double horizontalInsets = padding == null
                ? 0
                : padding.getLeft() + padding.getRight();
        double available = Math.max(260, width - horizontalInsets);
        flow.setPrefWrapLength(available);
        flow.setPrefWidth(available);
        flow.setMaxWidth(Double.MAX_VALUE);
    }

    private void setResponsiveClass(Node node, String className, boolean enabled) {
        if (node == null || className == null) {
            return;
        }
        if (enabled) {
            if (!node.getStyleClass().contains(className)) {
                node.getStyleClass().add(className);
            }
        } else {
            node.getStyleClass().remove(className);
        }
    }

    private void setFixedSize(Region node, double width, double height) {
        if (node != null) {
            node.setMinSize(width, height);
            node.setPrefSize(width, height);
            node.setMaxSize(width, height);
        }
    }

    private double imageRatio(Image image, double fallback) {
        if (image == null || image.getHeight() <= 0 || image.getWidth() <= 0) {
            return fallback;
        }
        return image.getWidth() / image.getHeight();
    }
}
