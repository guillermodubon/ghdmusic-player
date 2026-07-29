package io.github.guillermodubon.musicplayer.utils;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public final class SearchResultsImageCache {
    private static final double CARD_IMAGE_SIZE = 220;
    private static final SearchResultsImageCache INSTANCE = new SearchResultsImageCache();
    public static SearchResultsImageCache getInstance() { return INSTANCE; }

    private final ExecutorService imagePool = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "search-results-image");
        thread.setDaemon(true);
        return thread;
    });
    private final Semaphore loadingSlots = new Semaphore(4);

    private SearchResultsImageCache() {}

    public void load(String coverUrl, StackPane card) {
        load(coverUrl, card, () -> true);
    }

    /** Keeps image decoding and remote-image setup off the FX application thread. */
    public void load(String coverUrl, StackPane card, BooleanSupplier active) {
        if (coverUrl == null || coverUrl.isBlank() || card == null) return;
        imagePool.execute(() -> {
            boolean acquired = false;
            try {
                loadingSlots.acquire();
                acquired = true;
                if (!isActive(active)) return;

                Image image = MediaImageResolver.remoteImage(coverUrl, CARD_IMAGE_SIZE, CARD_IMAGE_SIZE);
                if (image == null || image.isError()) return;
                AtomicBoolean settled = new AtomicBoolean(false);
                Platform.runLater(() -> observeAndApply(image, card, active, settled));
                acquired = false;
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                if (acquired) loadingSlots.release();
            }
        });
    }

    private void setImage(StackPane card, Image img) {
        Node iv = findFirstImageView(card);
        if (iv instanceof ImageView imageView) imageView.setImage(img);
        else card.getProperties().put("resolvedImage", img);
    }

    private Node findFirstImageView(Node node) {
        if (node == null) return null;
        if (node instanceof ImageView) return node;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node found = findFirstImageView(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private boolean isActive(BooleanSupplier active) {
        return active == null || active.getAsBoolean();
    }

    private void observeAndApply(Image image,
                                 StackPane card,
                                 BooleanSupplier active,
                                 AtomicBoolean settled) {
        if (image == null || image.isError() || !isActive(active)) {
            releaseSlot(settled);
            return;
        }

        image.progressProperty().addListener((obs, oldValue, progress) -> {
            if (progress != null && progress.doubleValue() >= 1.0) {
                if (isActive(active)) setImage(card, image);
                releaseSlot(settled);
            }
        });
        image.errorProperty().addListener((obs, oldValue, error) -> {
            if (Boolean.TRUE.equals(error)) releaseSlot(settled);
        });

        if (image.getProgress() >= 1.0) {
            if (isActive(active)) setImage(card, image);
            releaseSlot(settled);
        }
    }

    private void releaseSlot(AtomicBoolean settled) {
        if (settled != null && settled.compareAndSet(false, true)) loadingSlots.release();
    }
}
