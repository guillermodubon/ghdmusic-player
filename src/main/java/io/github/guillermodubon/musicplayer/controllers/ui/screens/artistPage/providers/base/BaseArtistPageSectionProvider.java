package io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.providers.base;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.MusicCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.factory.CardFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.context.ArtistPageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.context.ArtistPageRenderContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.services.ArtistPageService;
import io.github.guillermodubon.musicplayer.utils.MusicCardHelper;
import io.github.guillermodubon.musicplayer.utils.ArtistPageImageCache;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.List;

public abstract class BaseArtistPageSectionProvider implements ArtistPageSectionProvider {
    private static final int CARD_RENDER_BATCH_SIZE = 4;

    //A bounded worker count keeps fast navigation from creating an unbounded number of HTTP threads.
    protected static final ExecutorService IO_POOL = Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r, "artist-page-io");
        t.setDaemon(true);
        return t;
    });

    protected final ArtistPageContext context;
    protected final ArtistPageService service;

    protected BaseArtistPageSectionProvider(ArtistPageContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.service = new ArtistPageService(context);
    }

    protected <T> CompletableFuture<T> supplyAsync(Callable<T> loader) {
        if (context.requestScope() != null) {
            return context.requestScope().supplyAsync(loader, IO_POOL);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return loader.call();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }, IO_POOL);
    }

    protected boolean alive(ArtistPageRenderContext rc) {
        return rc != null && rc.isAlive();
    }

    protected boolean isCurrent(ArtistPageRenderContext rc) {
        return alive(rc)
                && rc.shared() != null
                && rc.shared().isCurrent(rc.generation());
    }

    protected Image defaultCover() {
        try {
            return MusicCardHelper.loadDefaultCover();
        } catch (Exception ignored) {
            return new javafx.scene.image.WritableImage(1, 1);
        }
    }

    protected void setVisible(Label title, Node content, boolean visible) {
        Runnable r = () -> {
            if (title != null) {
                title.setVisible(visible);
                title.setManaged(visible);
            }
            if (content != null) {
                content.setVisible(visible);
                content.setManaged(visible);
            }
        };
        if (Platform.isFxApplicationThread()) r.run(); else Platform.runLater(r);
    }

    protected void clearFlow(FlowPane flow) {
        if (flow == null) return;
        Runnable r = flow.getChildren()::clear;
        if (Platform.isFxApplicationThread()) r.run(); else Platform.runLater(r);
    }

    /**
     * Keeps network and database work off the FX thread while constructing only a few FXML cards
     * per UI pulse. A render generation check makes stale results harmless after navigation.
     */
    protected void renderFlowCards(ArtistPageRenderContext rc,
                                   Label title,
                                   FlowPane flow,
                                   List<CardRequest> requests) {
        if (!isCurrent(rc) || flow == null) return;

        List<CardRequest> safeRequests = requests == null ? List.of() : List.copyOf(requests);
        flow.getChildren().clear();
        boolean visible = !safeRequests.isEmpty();
        setVisible(title, flow, visible);
        if (!visible) return;

        renderFlowBatch(rc, flow, safeRequests, 0);
    }

    protected List<StackPane> materializeCards(ArtistPageRenderContext rc, List<CardRequest> requests) {
        if (!isCurrent(rc) || requests == null || requests.isEmpty()) return List.of();

        List<StackPane> cards = new ArrayList<>(requests.size());
        for (CardRequest request : requests) {
            if (!isCurrent(rc)) return List.of();
            StackPane card = createCard(rc, request);
            if (card != null) cards.add(card);
        }
        return cards;
    }

    private void renderFlowBatch(ArtistPageRenderContext rc,
                                 FlowPane flow,
                                 List<CardRequest> requests,
                                 int startIndex) {
        if (!isCurrent(rc)) return;

        int endIndex = Math.min(requests.size(), startIndex + CARD_RENDER_BATCH_SIZE);
        List<StackPane> batch = new ArrayList<>(endIndex - startIndex);
        for (int index = startIndex; index < endIndex; index++) {
            StackPane card = createCard(rc, requests.get(index));
            if (card != null) batch.add(card);
        }
        if (!isCurrent(rc)) return;
        flow.getChildren().addAll(batch);

        if (endIndex < requests.size()) {
            Platform.runLater(() -> renderFlowBatch(rc, flow, requests, endIndex));
        }
    }

    private StackPane createCard(ArtistPageRenderContext rc, CardRequest request) {
        if (!isCurrent(rc) || request == null || request.data() == null) return null;
        try {
            Node node = CardFactory.createMusicCard(request.data());
            if (!(node instanceof StackPane card)) return null;

            if (request.remoteCoverUrl() != null && !request.remoteCoverUrl().isBlank()) {
                ArtistPageImageCache.getInstance().load(
                        request.remoteCoverUrl(),
                        card,
                        () -> isCurrent(rc)
                );
            }
            return card;
        } catch (Exception ignored) {
            return null;
        }
    }

    public record CardRequest(MusicCardData data, String remoteCoverUrl) {
        public CardRequest(MusicCardData data) {
            this(data, null);
        }
    }
}
