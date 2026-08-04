package io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.searchDropdown.helpers;

import javafx.beans.value.ChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Screen;
import javafx.stage.Window;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.searchDropdown.services.SearchDropdownService;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.ArtistCardActionManager;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.SearchCandidate;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;
import io.github.guillermodubon.musicplayer.utils.SearchDropdownCardFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Builds search rows, loads covers and keeps the popup positioned. */
public final class SearchDropdownPopupRenderer {

    private static final double POPUP_EXTRA_WIDTH = 20.0;
    private static final double POPUP_MIN_WIDTH = 320.0;
    private static final double POPUP_MAX_WIDTH = 640.0;
    private static final double POPUP_SCREEN_MARGIN = 16.0;
    private static final double SEARCH_IMAGE_SIZE = 160.0;

    private final VBox root;
    private final VBox searchResultsBox;
    private final ScrollPane resultsScrollPane;
    private final Popup searchPopup;
    private final SearchDropdownKeyboardCoordinator keyboardCoordinator;
    private final SearchDropdownService searchService;
    private final MusicCardActionManager musicActions;
    private final ArtistCardActionManager artistActions;
    private final Consumer<String> openSearchResults;
    private final ExecutorService imageExecutor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "search-dropdown-image");
        thread.setDaemon(true);
        return thread;
    });
    private final List<Future<?>> currentImageFutures =
            Collections.synchronizedList(new ArrayList<>());
    private final Map<ImageView, ChangeListener<Image>> listenerHolderMap = new WeakHashMap<>();
    private final Button viewAllButton = new Button();

    private TextField boundField;

    public SearchDropdownPopupRenderer(
            VBox root,
            VBox searchResultsBox,
            ScrollPane resultsScrollPane,
            Popup searchPopup,
            SearchDropdownKeyboardCoordinator keyboardCoordinator,
            SearchDropdownService searchService,
            MusicCardActionManager musicActions,
            ArtistCardActionManager artistActions,
            Consumer<String> openSearchResults
    ) {
        this.root = root;
        this.searchResultsBox = searchResultsBox;
        this.resultsScrollPane = resultsScrollPane;
        this.searchPopup = searchPopup;
        this.keyboardCoordinator = keyboardCoordinator;
        this.searchService = searchService;
        this.musicActions = musicActions;
        this.artistActions = artistActions;
        this.openSearchResults = openSearchResults;

        viewAllButton.getStyleClass().add("search-view-all-btn");
        viewAllButton.setMaxWidth(Double.MAX_VALUE);
        viewAllButton.setOnAction(event -> {
            Object query = viewAllButton.getProperties().get("query");
            String value = query == null ? "" : query.toString();
            if (!value.isBlank()) {
                openSearchResults.accept(value);
                Platform.runLater(searchPopup::hide);
            }
        });
    }

    public void setBoundField(TextField boundField) {
        this.boundField = boundField;
    }

    public void showCandidatesInPopup(List<SearchCandidate> candidates, String query) {
        if (candidates == null || candidates.isEmpty()) {
            clearRenderedResults();
            searchPopup.hide();
            return;
        }

        keyboardCoordinator.reset();
        searchResultsBox.getChildren().clear();
        for (SearchCandidate candidate : candidates) {
            renderCandidate(candidate);
        }

        viewAllButton.setText("View all results for \"" + query + "\"");
        viewAllButton.getProperties().put("query", query);
        configureDropdownRow(viewAllButton);
        searchResultsBox.getChildren().add(viewAllButton);
        keyboardCoordinator.register(viewAllButton, viewAllButton::fire);

        positionPopupBelowField();
        resetScrollToTop();
    }

    public void showLoadingInPopup(String query, int token) {
        if (searchResultsBox == null) {
            return;
        }
        try {
            keyboardCoordinator.reset();
            searchResultsBox.getChildren().clear();
            ProgressIndicator spinner = new ProgressIndicator();
            spinner.setMaxSize(44, 44);
            spinner.getStyleClass().add("search-dropdown-spinner");

            StackPane holder = new StackPane(spinner);
            holder.setAlignment(Pos.CENTER);
            holder.setPadding(new Insets(28, 12, 28, 12));
            holder.setMinHeight(96);
            holder.setPrefHeight(112);
            holder.setMaxWidth(Double.MAX_VALUE);
            holder.getStyleClass().add("search-dropdown-loading");
            searchResultsBox.getChildren().add(holder);
            positionPopupBelowField();
            resetScrollToTop();
        } catch (Exception ignored) {
        }
    }

    public void clearRenderedResults() {
        try {
            keyboardCoordinator.reset();
            searchResultsBox.getChildren().clear();
        } catch (Exception ignored) {
        }
    }

    public void cancelImageLoads() {
        synchronized (currentImageFutures) {
            for (Future<?> future : currentImageFutures) {
                try {
                    if (future != null && !future.isDone()) {
                        future.cancel(true);
                    }
                } catch (Exception ignored) {
                }
            }
            currentImageFutures.clear();
        }
    }

    public void shutdown() {
        cancelImageLoads();
        imageExecutor.shutdownNow();
    }

    private void renderCandidate(SearchCandidate candidate) {
        try {
            StackPane card = createCardForCandidate(candidate);
            if (card == null) {
                return;
            }

            card.getProperties().put("candidateKey", candidate.candidateKey());
            configureDropdownRow(card);
            closePopupAfterClick(card);
            keyboardCoordinator.register(card, () -> activateCard(card));

            Image image = candidate.localCover();
            if (image != null) {
                setImageOnCardWithOverride(card, image, candidate.candidateKey());
            } else {
                scheduleImageFillForCard(card, candidate);
            }
            searchResultsBox.getChildren().add(card);
        } catch (Exception error) {
            error.printStackTrace();
        }
    }

    private StackPane createCardForCandidate(SearchCandidate candidate) {
        if (candidate == null) {
            return null;
        }
        try {
            if ("artist".equals(candidate.type())) {
                Artist artist = candidate.localArtist() != null
                        ? candidate.localArtist()
                        : searchService.parseArtistFromJson(
                        candidate.artistJson(),
                        candidate.coverUrl()
                );
                return (StackPane) SearchDropdownCardFactory.createArtistCard(
                        artist,
                        artistActions
                );
            }

            String typeLabel = switch (candidate.type()) {
                case "album" -> "Album";
                case "track" -> "Single";
                case "playlist" -> "Playlist";
                default -> "Unknown";
            };

            return (StackPane) SearchDropdownCardFactory.createMusicCard(
                    candidate.resolvedActionId(),
                    candidate.localCover() == null ? candidate.coverUrl() : null,
                    candidate.title() == null ? "Unknown" : candidate.title(),
                    typeLabel,
                    candidate.artistNames() == null
                            ? List.of()
                            : candidate.artistNames(),
                    musicActions,
                    artistActions
            );
        } catch (Exception error) {
            error.printStackTrace();
            return null;
        }
    }

    private void scheduleImageFillForCard(StackPane card, SearchCandidate candidate) {
        if (card == null
                || candidate == null
                || candidate.coverUrl() == null
                || candidate.coverUrl().isBlank()) {
            return;
        }

        String candidateKey = candidate.candidateKey();
        card.getProperties().put("candidateKey", candidateKey);
        Future<?> future = imageExecutor.submit(() -> {
            try {
                Image image = MediaImageResolver.remoteImage(
                        candidate.coverUrl(),
                        SEARCH_IMAGE_SIZE,
                        SEARCH_IMAGE_SIZE
                );
                if (image == null) {
                    return;
                }
                Platform.runLater(() -> {
                    try {
                        Object currentKey = card.getProperties().get("candidateKey");
                        if (candidateKey.equals(currentKey)) {
                            setImageOnCardWithOverride(card, image, candidateKey);
                        }
                    } catch (Exception ignored) {
                    }
                });
            } catch (Throwable ignored) {
            }
        });

        synchronized (currentImageFutures) {
            currentImageFutures.add(future);
        }
    }

    private void setImageOnCardWithOverride(Node card, Image image, String candidateKey) {
        if (card == null || image == null || candidateKey == null) {
            return;
        }

        List<ImageView> imageViews = new ArrayList<>();
        Deque<Node> pending = new ArrayDeque<>();
        pending.push(card);
        while (!pending.isEmpty()) {
            Node current = pending.pop();
            if (current instanceof ImageView imageView) {
                imageViews.add(imageView);
            }
            if (current instanceof Parent parent) {
                for (Node child : parent.getChildrenUnmodifiable()) {
                    pending.push(child);
                }
            }
        }

        if (imageViews.isEmpty()) {
            return;
        }

        ImageView target = imageViews.stream()
                .filter(view -> view.getStyleClass().contains("search-card-cover"))
                .findFirst()
                .orElse(imageViews.get(0));

        target.getProperties().put("overrideCandidateKey", candidateKey);
        target.getProperties().put("overrideImage", image);
        target.getProperties().putIfAbsent("overrideAttempts", new AtomicInteger(0));
        try {
            target.setImage(image);
        } catch (Exception ignored) {
        }

        Object listenerFlag = target.getProperties().get("overrideListenerAdded");
        if (listenerFlag instanceof Boolean && (Boolean) listenerFlag) {
            return;
        }

        final ImageView targetView = target;
        final String expectedKey = candidateKey;
        ChangeListener<Image> changeListener = (obs, oldImage, newImage) -> {
            try {
                Object keyObject = targetView.getProperties().get("overrideCandidateKey");
                Object overrideObject = targetView.getProperties().get("overrideImage");
                if (!(keyObject instanceof String) || overrideObject == null) {
                    Platform.runLater(() -> removeImageListener(targetView));
                    return;
                }

                if (!expectedKey.equals(keyObject) || newImage == overrideObject) {
                    return;
                }

                AtomicInteger attempts = (AtomicInteger) targetView
                        .getProperties()
                        .get("overrideAttempts");
                if (attempts == null) {
                    attempts = new AtomicInteger(0);
                    targetView.getProperties().put("overrideAttempts", attempts);
                }

                if (attempts.incrementAndGet() <= 3) {
                    Image desired = (Image) overrideObject;
                    Platform.runLater(() -> {
                        try {
                            Object currentKey = targetView.getProperties()
                                    .get("overrideCandidateKey");
                            if (expectedKey.equals(currentKey)) {
                                targetView.setImage(desired);
                            }
                        } catch (Exception ignored) {
                        }
                    });
                } else {
                    targetView.getProperties().remove("overrideCandidateKey");
                    targetView.getProperties().remove("overrideImage");
                    targetView.getProperties().remove("overrideAttempts");
                    Platform.runLater(() -> removeImageListener(targetView));
                }
            } catch (Exception ignored) {
            }
        };

        listenerHolderMap.put(targetView, changeListener);
        targetView.imageProperty().addListener(changeListener);
        targetView.getProperties().put("overrideListenerAdded", Boolean.TRUE);
    }

    private void removeImageListener(ImageView imageView) {
        try {
            ChangeListener<Image> listener = listenerHolderMap.remove(imageView);
            if (listener != null) {
                imageView.imageProperty().removeListener(listener);
            }
            imageView.getProperties().remove("overrideListenerAdded");
        } catch (Exception ignored) {
        }
    }

    private void activateCard(Node card) {
        if (card == null) {
            return;
        }
        MouseEvent click = new MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                0, 0, 0, 0,
                MouseButton.PRIMARY,
                1,
                false, false, false, false,
                false, false, false, false,
                false, false,
                null
        );
        card.fireEvent(click);
    }

    private void closePopupAfterClick(Node node) {
        if (node != null) {
            node.addEventHandler(
                    MouseEvent.MOUSE_CLICKED,
                    event -> Platform.runLater(searchPopup::hide)
            );
        }
    }

    private void positionPopupBelowField() {
        try {
            if (boundField == null) {
                return;
            }
            Scene scene = boundField.getScene();
            if (scene == null) {
                return;
            }
            Window owner = scene.getWindow();
            if (owner == null) {
                return;
            }

            Bounds bounds = boundField.localToScreen(boundField.getBoundsInLocal());
            if (bounds == null) {
                return;
            }
            double fieldWidth = Math.max(bounds.getWidth(), boundField.getWidth());
            double width = Math.max(
                    POPUP_MIN_WIDTH,
                    Math.min(POPUP_MAX_WIDTH, fieldWidth + POPUP_EXTRA_WIDTH)
            );
            Rectangle2D screenBounds = Screen.getScreensForRectangle(
                            bounds.getMinX(),
                            bounds.getMinY(),
                            bounds.getWidth(),
                            bounds.getHeight()
                    ).stream()
                    .findFirst()
                    .map(Screen::getVisualBounds)
                    .orElse(null);

            if (screenBounds != null) {
                width = Math.min(
                        width,
                        Math.max(260.0, screenBounds.getWidth() - POPUP_SCREEN_MARGIN * 2)
                );
            }

            double x = bounds.getMinX() + fieldWidth / 2.0 - width / 2.0;
            if (screenBounds != null) {
                double minX = screenBounds.getMinX() + POPUP_SCREEN_MARGIN;
                double maxX = screenBounds.getMaxX() - width - POPUP_SCREEN_MARGIN;
                x = Math.max(minX, Math.min(x, maxX));
            } else {
                x = Math.max(x, 0);
            }
            double y = bounds.getMaxY() + 4;

            root.setMinWidth(width);
            root.setPrefWidth(width);
            root.setMaxWidth(width);
            if (resultsScrollPane != null) {
                double innerWidth = Math.max(0, width - 16);
                resultsScrollPane.setMinWidth(innerWidth);
                resultsScrollPane.setPrefWidth(innerWidth);
                resultsScrollPane.setMaxWidth(innerWidth);
            }
            if (searchResultsBox != null) {
                double contentWidth = Math.max(0, width - 28);
                searchResultsBox.setMinWidth(contentWidth);
                searchResultsBox.setPrefWidth(contentWidth);
            }

            if (searchPopup.isShowing()) {
                searchPopup.setX(x);
                searchPopup.setY(y);
            } else {
                searchPopup.show(owner, x, y);
            }
            applyDropdownStylesNow();
            // The popup scene is created lazily by JavaFX. Applying CSS once
            // more on the next pulse prevents the first render from waiting
            // for a hover event to resolve the card stylesheet.
            Platform.runLater(this::applyDropdownStylesNow);
            installPopupKeyboardNavigation();
        } catch (Exception ignored) {
        }
    }

    private void applyDropdownStylesNow() {
        if (root == null) return;
        try {
            root.applyCss();
            root.layout();
            if (searchResultsBox != null) {
                searchResultsBox.applyCss();
                searchResultsBox.layout();
                // Each card owns its stylesheet through FXML. Reapply CSS to
                // the freshly inserted cards after a popup reopen so their
                // text never depends on a hover event to get the final style.
                for (Node child : searchResultsBox.getChildren()) {
                    if (child instanceof Parent card) {
                        card.applyCss();
                        card.layout();
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void installPopupKeyboardNavigation() {
        keyboardCoordinator.installOnPopupScene(root);
    }

    private void resetScrollToTop() {
        if (resultsScrollPane == null) {
            return;
        }
        resultsScrollPane.setVvalue(0.0);
        Platform.runLater(() -> resultsScrollPane.setVvalue(0.0));
    }

    private void configureDropdownRow(Node node) {
        if (node instanceof Region region) {
            region.setMinWidth(0);
            region.setMaxWidth(Double.MAX_VALUE);
        }
    }
}
