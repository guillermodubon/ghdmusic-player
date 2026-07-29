package io.github.guillermodubon.musicplayer.utils;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public final class ArtistPageImageCache {
    private static final ArtistPageImageCache INSTANCE = new ArtistPageImageCache();
    public static ArtistPageImageCache getInstance() { return INSTANCE; }

    private final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger inProgress = new AtomicInteger(0);
    private final int maxParallel = 4;

    private ArtistPageImageCache() {}

    public void load(String url, StackPane card) {
        load(url, card, () -> true);
    }

    /**
     * Delays remote cover work until a rendering slot is available. The active check prevents
     * images belonging to a page that has already been replaced from consuming the queue.
     */
    public void load(String url, StackPane card, BooleanSupplier isActive) {
        if (url == null || url.isBlank() || card == null) return;
        queue.add(() -> Platform.runLater(() -> {
            if (!isActive(isActive)) {
                finishLoad(new AtomicBoolean(false));
                return;
            }

            AtomicBoolean settled = new AtomicBoolean(false);
            try {
                Image img = MediaImageResolver.remoteCardImage(url);
                if (img == null) {
                    finishLoad(settled);
                    return;
                }
                img.progressProperty().addListener((obs, o, n) -> {
                    if (n != null && n.doubleValue() >= 1.0) {
                        if (isActive(isActive)) setImage(card, img);
                        finishLoad(settled);
                    }
                });
                img.errorProperty().addListener((obs, o, n) -> {
                    if (Boolean.TRUE.equals(n)) finishLoad(settled);
                });
                if (img.isError()) {
                    finishLoad(settled);
                    return;
                }
                if (img.getProgress() >= 1.0) {
                    if (isActive(isActive)) setImage(card, img);
                    finishLoad(settled);
                }
            } catch (Exception e) {
                finishLoad(settled);
            }
        }));
        schedule();
    }

    private boolean isActive(BooleanSupplier supplier) {
        try {
            return supplier == null || supplier.getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void finishLoad(AtomicBoolean settled) {
        if (settled == null || !settled.compareAndSet(false, true)) return;
        inProgress.decrementAndGet();
        schedule();
    }

    private void schedule() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::schedule);
            return;
        }
        while (inProgress.get() < maxParallel) {
            Runnable r = queue.poll();
            if (r == null) break;
            inProgress.incrementAndGet();
            r.run();
        }
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
}
