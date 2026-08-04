package io.github.guillermodubon.musicplayer.controllers.ui.components.sections;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.Parent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

import java.util.ArrayList;
import java.util.List;

public final class ResponsiveCardCarousel {

    private static final double MUSIC_CARD_WIDTH = 176;
    private static final double MUSIC_CARD_MIN_WIDTH = 140;
    private static final double MUSIC_CARD_HEIGHT = 254;
    private static final double MUSIC_CAROUSEL_HEIGHT = 286;
    private static final double MUSIC_CARD_GAP = 18;
    private static final double CAROUSEL_EDGE_PADDING = 24;
    private static final double FEATURED_CARD_GAP = 18;
    private static final double FEATURED_CARD_WIDTH_FACTOR = 1.0;
    private static final double FEATURED_CARD_MIN_WIDTH = 220;
    private static final double FEATURED_CARD_MAX_WIDTH = 360;
    private static final double FEATURED_CARD_MIN_HEIGHT = 320;
    private static final double FEATURED_CARD_MAX_HEIGHT = 480;
    private static final double FEATURED_CARD_HEIGHT_FACTOR = 1.25;
    private static final double FEATURED_CAROUSEL_VERTICAL_PADDING = 8;
    private static final double FEATURED_MEASUREMENT_FALLBACK_WIDTH = 320;
    private static final double CAROUSEL_BUTTON_SIZE = 38;
    private static final String CAROUSEL_BUTTON_STYLE = """
            -fx-background-color: #0d0d0d;
            -fx-border-color: #0d0d0d;
            -fx-text-fill: #FFFFFF;
            -fx-background-radius: 999;
            -fx-border-radius: 999;
            -fx-border-width: 1;
            -fx-padding: 0;
            """;

    private ResponsiveCardCarousel() {
    }

    public static StackPane createMusicCarousel(List<? extends Node> cards) {
        ObservableList<Node> items = FXCollections.observableArrayList();
        if (cards != null) items.addAll(cards);
        return createCarousel(items, false);
    }

    public static StackPane createMusicCarousel(ObservableList<Node> cards) {
        return createCarousel(cards == null ? FXCollections.observableArrayList() : cards, false);
    }

    public static StackPane createFeaturedCarousel(List<? extends Node> cards) {
        ObservableList<Node> items = FXCollections.observableArrayList();
        if (cards != null) items.addAll(cards);
        return createCarousel(items, true);
    }

    private static StackPane createCarousel(ObservableList<Node> cards, boolean featured) {
        StackPane root = new StackPane();
        root.getStyleClass().add(featured ? "home-featured-carousel" : "home-card-carousel");
        root.getStyleClass().add(featured ? "section-featured-carousel" : "section-card-carousel");
        root.getStyleClass().add(featured ? "app-featured-carousel" : "app-card-carousel");
        root.setMaxWidth(Double.MAX_VALUE);
        root.setPickOnBounds(true);
        Rectangle rootClip = new Rectangle();
        rootClip.widthProperty().bind(root.widthProperty());
        rootClip.heightProperty().bind(root.heightProperty());
        root.setClip(rootClip);
        if (featured) {
            root.setMinHeight(FEATURED_CARD_MIN_HEIGHT + FEATURED_CAROUSEL_VERTICAL_PADDING);
            root.setPrefHeight(FEATURED_CARD_MIN_HEIGHT + FEATURED_CAROUSEL_VERTICAL_PADDING);
            root.setMaxHeight(Region.USE_COMPUTED_SIZE);
        }
        if (!featured) {
            root.setMinHeight(MUSIC_CAROUSEL_HEIGHT);
            root.setPrefHeight(MUSIC_CAROUSEL_HEIGHT);
            root.setMaxHeight(MUSIC_CAROUSEL_HEIGHT);
        }

        ObservableList<Node> safeCards = cards == null ? FXCollections.observableArrayList() : cards;

        HBox track = new HBox(featured ? FEATURED_CARD_GAP : MUSIC_CARD_GAP);
        track.getStyleClass().addAll(
                "home-carousel-track",
                "section-carousel-track",
                "app-carousel-track",
                featured ? "home-featured-carousel-track" : "home-card-carousel-track",
                featured ? "section-featured-carousel-track" : "section-card-carousel-track",
                featured ? "app-featured-carousel-track" : "app-card-carousel-track"
        );
        StackPane.setAlignment(track, featured ? Pos.CENTER : Pos.CENTER_LEFT);
        track.setAlignment(Pos.CENTER_LEFT);
        track.setFillHeight(false);
        track.setMaxWidth(Region.USE_PREF_SIZE);
        track.setPickOnBounds(false);

        if (!featured) {
            for (Node card : safeCards) {
                styleMusicCarouselCard(card, MUSIC_CARD_WIDTH);
            }
            track.setMinHeight(MUSIC_CARD_HEIGHT);
            track.setPrefHeight(MUSIC_CARD_HEIGHT);
            track.setMaxHeight(MUSIC_CARD_HEIGHT);
            track.setPadding(Insets.EMPTY);
        }
        StackPane.setMargin(track, new Insets(0, CAROUSEL_EDGE_PADDING, 0, CAROUSEL_EDGE_PADDING));

        Button previous = carouselButton("<");
        Button next = carouselButton(">");
        StackPane.setAlignment(previous, Pos.CENTER_LEFT);
        StackPane.setAlignment(next, Pos.CENTER_RIGHT);

        root.getChildren().setAll(track, previous, next);

        int[] index = {0};
        boolean[] hovering = {false};
        boolean[] updating = {false};
        Runnable[] updateRef = new Runnable[1];

        Runnable update = () -> {
            if (updating[0]) return;

            // A carousel can receive a final layout/hover callback after its
            // section has been replaced. Never mutate or pick detached nodes.
            if (root.getScene() == null) {
                hovering[0] = false;
                setOverlayButtonVisible(previous, false);
                setOverlayButtonVisible(next, false);
                return;
            }

            updating[0] = true;
            try {
                if (safeCards.isEmpty()) {
                    track.getChildren().clear();
                    setOverlayButtonVisible(previous, false);
                    setOverlayButtonVisible(next, false);
                    if (featured) {
                        root.setMinHeight(0);
                        root.setPrefHeight(0);
                        root.setMaxHeight(Region.USE_COMPUTED_SIZE);
                    }
                    return;
                }

                boolean measuredWidth = root.getWidth() > 1;
                int perPage = carouselCardsPerPage(root, featured);
                int start = Math.max(0, Math.min(index[0], Math.max(0, safeCards.size() - 1)));
                int pageOffset = start % perPage;
                if (pageOffset != 0) start -= pageOffset;
                index[0] = start;

                int end = Math.min(start + perPage, safeCards.size());
                List<Node> visible = new ArrayList<>(safeCards.subList(start, end));
                int sizingCount = featured ? Math.min(perPage, safeCards.size()) : visible.size();
                double width = carouselCardWidth(root, sizingCount, featured);
                double trackWidth = carouselTrackWidth(visible.size(), width, featured);
                double sideInset = carouselEdgePadding(root.getWidth());
                StackPane.setMargin(track, new Insets(0, sideInset, 0, sideInset));
                // Do not let the fallback measurement create a minimum width before
                // JavaFX has attached the carousel to its real viewport.
                track.setMinWidth(measuredWidth ? trackWidth : 0);
                track.setPrefWidth(trackWidth);
                track.setMaxWidth(trackWidth);

                for (Node card : visible) {
                    if (featured) styleFeaturedCarouselCard(card, width, measuredWidth);
                    else styleMusicCarouselCard(card, width);
                    attachCarouselHover(card, hovering, () -> {
                        if (updateRef[0] != null) updateRef[0].run();
                    });
                }

                // Hover updates only need to refresh the overlay buttons. Avoid
                // detaching/re-attaching the same cards while the pointer is on
                // one of them; doing so can leave JavaFX picking a node whose
                // Scene has already become null.
                if (!sameChildren(track, visible)) {
                    hovering[0] = false;
                    track.getChildren().setAll(visible);
                }

                if (featured) {
                    double carouselHeight = featuredCardHeight(width) + FEATURED_CAROUSEL_VERTICAL_PADDING;
                    track.setMinHeight(carouselHeight - FEATURED_CAROUSEL_VERTICAL_PADDING);
                    track.setPrefHeight(carouselHeight - FEATURED_CAROUSEL_VERTICAL_PADDING);
                    track.setMaxHeight(carouselHeight - FEATURED_CAROUSEL_VERTICAL_PADDING);
                    root.setMinHeight(carouselHeight);
                    root.setPrefHeight(carouselHeight);
                    root.setMaxHeight(carouselHeight);
                }
                positionCarouselButtons(previous, next, root, visible.size(), width, featured);
                setOverlayButtonVisible(previous, hovering[0] && start > 0);
                setOverlayButtonVisible(next, hovering[0] && end < safeCards.size());
                root.requestLayout();
            } finally {
                updating[0] = false;
            }
        };
        updateRef[0] = update;

        previous.setOnAction(event -> {
            index[0] = Math.max(0, index[0] - carouselCardsPerPage(root, featured));
            update.run();
        });
        next.setOnAction(event -> {
            index[0] = Math.min(Math.max(0, safeCards.size() - 1), index[0] + carouselCardsPerPage(root, featured));
            update.run();
        });
        attachOverlayButtonHover(previous, hovering, updateRef);
        attachOverlayButtonHover(next, hovering, updateRef);

        root.setOnMouseEntered(event -> {
            if (!featured) return;
            hovering[0] = true;
            update.run();
        });
        root.setOnMouseExited(event -> {
            hovering[0] = false;
            update.run();
        });
        root.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) {
                hovering[0] = false;
                setOverlayButtonVisible(previous, false);
                setOverlayButtonVisible(next, false);
            } else {
                update.run();
            }
        });
        root.widthProperty().addListener((obs, oldValue, newValue) -> update.run());
        root.parentProperty().addListener((obs, oldParent, newParent) -> bindToParentWidth(root, newParent, update));
        safeCards.addListener((ListChangeListener<Node>) change -> update.run());
        Platform.runLater(() -> {
            bindToParentWidth(root, root.getParent(), update);
            update.run();
        });
        update.run();

        return root;
    }

    private static Button carouselButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().addAll("home-carousel-button", "section-carousel-button", "app-carousel-button");
        // Card-local stylesheets can otherwise reapply the app primary button color.
        button.setStyle(CAROUSEL_BUTTON_STYLE);
        button.setFocusTraversable(false);
        button.setManaged(true);
        button.setMinSize(CAROUSEL_BUTTON_SIZE, CAROUSEL_BUTTON_SIZE);
        button.setPrefSize(CAROUSEL_BUTTON_SIZE, CAROUSEL_BUTTON_SIZE);
        button.setMaxSize(CAROUSEL_BUTTON_SIZE, CAROUSEL_BUTTON_SIZE);
        setOverlayButtonVisible(button, false);
        return button;
    }

    private static void positionCarouselButtons(Button previous,
                                                Button next,
                                                Region root,
                                                int visibleCount,
                                                double cardWidth,
                                                boolean featured) {
        if (previous == null || next == null || visibleCount <= 0) return;

        double rootWidth = root == null ? 0 : root.getWidth();
        if (rootWidth <= 0) rootWidth = featured ? FEATURED_MEASUREMENT_FALLBACK_WIDTH : 1040;

        double sideInset = carouselEdgePadding(rootWidth);
        double cardsWidth = carouselTrackWidth(visibleCount, cardWidth, featured);
        double availableWidth = Math.max(0, rootWidth - (sideInset * 2));
        double contentLeft = featured
                ? sideInset + Math.max(0, (availableWidth - cardsWidth) / 2.0)
                : sideInset;
        double maxLeft = Math.max(0, rootWidth - CAROUSEL_BUTTON_SIZE);
        double previousLeft = clamp(contentLeft - (CAROUSEL_BUTTON_SIZE / 2.0), 0, maxLeft);
        double nextLeft = clamp(contentLeft + cardsWidth - (CAROUSEL_BUTTON_SIZE / 2.0), 0, maxLeft);

        StackPane.setAlignment(previous, Pos.CENTER_LEFT);
        StackPane.setAlignment(next, Pos.CENTER_LEFT);
        StackPane.setMargin(previous, new Insets(0, 0, 0, previousLeft));
        StackPane.setMargin(next, new Insets(0, 0, 0, nextLeft));
    }

    private static void attachCarouselHover(Node card, boolean[] hovering, Runnable update) {
        if (card == null || Boolean.TRUE.equals(card.getProperties().get("homeCarouselHoverBound"))) return;
        card.getProperties().put("homeCarouselHoverBound", Boolean.TRUE);
        card.addEventHandler(MouseEvent.MOUSE_ENTERED, event -> {
            hovering[0] = true;
            update.run();
        });
        card.addEventHandler(MouseEvent.MOUSE_EXITED, event -> {
            hovering[0] = false;
            update.run();
        });
    }

    private static boolean sameChildren(HBox track, List<Node> visible) {
        if (track == null || visible == null || track.getChildren().size() != visible.size()) return false;
        for (int index = 0; index < visible.size(); index++) {
            if (track.getChildren().get(index) != visible.get(index)) return false;
        }
        return true;
    }

    private static double carouselTrackWidth(int visibleCount, double cardWidth, boolean featured) {
        int count = Math.max(0, visibleCount);
        if (count == 0) return 0;
        double gap = featured ? FEATURED_CARD_GAP : MUSIC_CARD_GAP;
        return (cardWidth * count) + (gap * Math.max(0, count - 1));
    }

    private static void attachOverlayButtonHover(Button button, boolean[] hovering, Runnable[] updateRef) {
        if (button == null || Boolean.TRUE.equals(button.getProperties().get("homeCarouselButtonHoverBound"))) return;
        button.getProperties().put("homeCarouselButtonHoverBound", Boolean.TRUE);
        button.addEventHandler(MouseEvent.MOUSE_ENTERED, event -> {
            hovering[0] = true;
            if (updateRef[0] != null) updateRef[0].run();
        });
        button.addEventHandler(MouseEvent.MOUSE_EXITED, event -> {
            hovering[0] = false;
            if (updateRef[0] != null) updateRef[0].run();
        });
    }

    private static int carouselCardsPerPage(Region root, boolean featured) {
        double width = root == null ? 0 : root.getWidth();
        // One card is the safest initial layout. The actual viewport width is
        // applied on the next pulse and the page is recalculated there.
        if (width <= 0) return 1;

        if (featured) {
            double sideInset = carouselEdgePadding(width);
            double available = Math.max(0, width - (sideInset * 2));
            int count = (int) Math.floor(
                    (available + FEATURED_CARD_GAP)
                            / (FEATURED_CARD_MIN_WIDTH + FEATURED_CARD_GAP)
            );
            // Keep every card that fits the current viewport. The carousel
            // still pages through the remaining cards, but the visible page
            // is no longer limited to three items on wider screens.
            return Math.max(1, count);
        }

        double cardWidth = normalCarouselCardWidth(width);
        double available = Math.max(cardWidth, width - (carouselEdgePadding(width) * 2));
        int count = (int) Math.floor((available + MUSIC_CARD_GAP) / (cardWidth + MUSIC_CARD_GAP));
        return Math.max(1, Math.min(12, count));
    }

    private static double carouselCardWidth(Region root, int visibleCount, boolean featured) {
        int count = Math.max(1, visibleCount);
        double width = root == null ? 0 : root.getWidth();
        if (width <= 0) width = featured ? FEATURED_MEASUREMENT_FALLBACK_WIDTH : 1040;

        double gap = (featured ? FEATURED_CARD_GAP : MUSIC_CARD_GAP) * Math.max(0, count - 1);
        double sideInset = carouselEdgePadding(width);
        double available = Math.max(MUSIC_CARD_MIN_WIDTH, width - (sideInset * 2) - gap);
        if (featured) {
            double candidate = (available / count) * FEATURED_CARD_WIDTH_FACTOR;
            double minimum = width <= FEATURED_MEASUREMENT_FALLBACK_WIDTH
                    ? Math.min(FEATURED_CARD_MIN_WIDTH, candidate)
                    : FEATURED_CARD_MIN_WIDTH;
            return clamp(candidate, minimum, FEATURED_CARD_MAX_WIDTH);
        }
        return normalCarouselCardWidth(width);
    }

    private static double normalCarouselCardWidth(double carouselWidth) {
        double sideInset = carouselEdgePadding(carouselWidth);
        double available = carouselWidth <= 0
                ? 1040 - (sideInset * 2)
                : carouselWidth - (sideInset * 2);
        return clamp(available, MUSIC_CARD_MIN_WIDTH, MUSIC_CARD_WIDTH);
    }

    private static void bindToParentWidth(StackPane root, Parent parent, Runnable update) {
        if (root == null) return;

        if (root.prefWidthProperty().isBound()) {
            root.prefWidthProperty().unbind();
        }
        root.setMinWidth(0);
        root.setMaxWidth(Double.MAX_VALUE);

        if (parent instanceof Region region) {
            root.prefWidthProperty().bind(region.widthProperty());
        }

        if (update != null) Platform.runLater(update);
    }

    private static double carouselEdgePadding(double width) {
        if (width > 0 && width < 520) return 8;
        if (width > 0 && width < 780) return 14;
        return CAROUSEL_EDGE_PADDING;
    }

    private static void styleMusicCarouselCard(Node card, double width) {
        if (card instanceof Region region) {
            double safeWidth = clamp(width, MUSIC_CARD_MIN_WIDTH, MUSIC_CARD_WIDTH);
            region.setPrefWidth(safeWidth);
            region.setMinWidth(safeWidth);
            region.setMaxWidth(safeWidth);
            region.setPrefHeight(MUSIC_CARD_HEIGHT);
            region.setMinHeight(MUSIC_CARD_HEIGHT);
            region.setMaxHeight(MUSIC_CARD_HEIGHT);
            HBox.setHgrow(region, Priority.NEVER);
        }
    }

    private static void styleFeaturedCarouselCard(Node card, double width, boolean measuredWidth) {
        if (card instanceof Region region) {
            double safeWidth = clamp(width, 1, FEATURED_CARD_MAX_WIDTH);
            double safeHeight = featuredCardHeight(safeWidth);
            region.setPrefWidth(safeWidth);
            region.setMinWidth(measuredWidth ? safeWidth : 0);
            region.setMaxWidth(safeWidth);
            region.setPrefHeight(safeHeight);
            region.setMinHeight(FEATURED_CARD_MIN_HEIGHT);
            region.setMaxHeight(safeHeight);
            HBox.setHgrow(region, Priority.NEVER);
        }
    }

    private static double featuredCardHeight(double width) {
        return clamp(width * FEATURED_CARD_HEIGHT_FACTOR,
                FEATURED_CARD_MIN_HEIGHT,
                FEATURED_CARD_MAX_HEIGHT);
    }

    private static void setOverlayButtonVisible(Button button, boolean visible) {
        if (button == null) return;
        button.setVisible(visible);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
