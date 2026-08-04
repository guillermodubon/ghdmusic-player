package io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.providers.common;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.context.HomePageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.providers.*;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.providers.base.HomePageSectionProvider;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

// HomePageSectionRegistry
public class HomePageSectionRegistry {

    private static final String ACTIVE_RENDER_KEY = "homePage.activeRender";
    private final List<SectionEntry> providers;
    private final AtomicLong renderVersion;

    public HomePageSectionRegistry(HomePageContext context) {
        this.renderVersion = context.renderVersion();
        this.providers = List.of(
                entry(new RecentlyPlayedSectionProvider(context), false, false, false, true),
                entry(new RecommendationsSectionProvider(context), false, false, true),
                entry(new LibraryAlbumsSectionProvider(context), true),
                entry(new LibrarySinglesSectionProvider(context), true),
                entry(new LibraryPlaylistsSectionProvider(context), true),
                entry(new LibraryOtherSongsSectionProvider(context), true, true),
                entry(new CustomMixesSectionProvider(context), false),
                entry(new PopularAlbumsSectionProvider(context), false),
                entry(new FeaturedPlaylistsSectionProvider(context), false),
                entry(new ArtistsYouMightLikeSectionProvider(context), false)
        );
    }

    public void renderAll(VBox container,
                          String filter,
                          long renderId,
                          Consumer<RenderSummary> onCompleted) {
        if (container == null || !isCurrent(renderId)) return;
        container.getProperties().put(ACTIVE_RENDER_KEY, renderId);
        container.getChildren().clear();
        RenderTracker tracker = new RenderTracker(providers.size(), onCompleted);
        for (SectionEntry entry : providers) {
            VBox slot = createSectionSlot();
            container.getChildren().add(slot);
            renderProvider(container, entry, slot, renderId, filter, tracker);
        }
    }

    private VBox createSectionSlot() {
        VBox slot = new VBox();
        slot.setFillWidth(true);
        slot.setMaxWidth(Double.MAX_VALUE);
        // A provider starts asynchronously. Keep its slot collapsed until it
        // has actual content so a pending section cannot create a visual gap.
        slot.setMinHeight(0);
        slot.setPrefHeight(0);
        slot.setMaxHeight(Region.USE_COMPUTED_SIZE);
        slot.setVisible(false);
        slot.setManaged(false);
        VBox.setVgrow(slot, Priority.NEVER);
        return slot;
    }

    private void renderProvider(VBox container,
                                SectionEntry entry,
                                VBox slot,
                                long renderId,
                                String filter,
                                RenderTracker tracker) {
        CompletableFuture<Void> completion;
        try {
            completion = entry.provider().render(slot, filter, renderId);
        } catch (Throwable ignored) {
            completion = CompletableFuture.completedFuture(null);
        }

        if (completion == null) completion = CompletableFuture.completedFuture(null);
        completion.handle((ignored, error) -> null)
                .whenComplete((ignored, error) -> Platform.runLater(() -> {
                    if (!ownsActiveRender(container, renderId)) return;
                    boolean visible = finalizeSlot(container, slot, renderId);
                    tracker.complete(
                            entry.library(),
                            entry.otherSongsLibrary(),
                            entry.recommendations(),
                            entry.recentlyPlayed(),
                            visible,
                            isCurrent(renderId)
                    );
                }));
    }

    private boolean finalizeSlot(VBox container, VBox slot, long renderId) {
        if (container == null || slot == null || !ownsActiveRender(container, renderId)) return false;
        boolean hasContent = !slot.getChildren().isEmpty();
        Parent parent = slot.getParent();
        if (parent == container) {
            int index = container.getChildren().indexOf(slot);
            if (index >= 0) {
                if (hasContent) {
                    List<Node> sections = new ArrayList<>(slot.getChildren());
                    slot.getChildren().clear();
                    for (Node section : sections) {
                        if (section instanceof Region region) {
                            region.setMaxWidth(Double.MAX_VALUE);
                            region.setMinHeight(Region.USE_COMPUTED_SIZE);
                            region.setPrefHeight(Region.USE_COMPUTED_SIZE);
                            region.setMaxHeight(Region.USE_COMPUTED_SIZE);
                            VBox.setVgrow(region, Priority.NEVER);
                        }
                    }
                    container.getChildren().remove(index);
                    container.getChildren().addAll(index, sections);
                } else {
                    container.getChildren().remove(slot);
                }
                container.requestLayout();
                return hasContent;
            }
        }
        return hasContent;
    }

    private SectionEntry entry(HomePageSectionProvider provider, boolean library) {
        return entry(provider, library, false, false, false);
    }

    private SectionEntry entry(HomePageSectionProvider provider,
                               boolean library,
                               boolean otherSongsLibrary) {
        return entry(provider, library, otherSongsLibrary, false, false);
    }

    private SectionEntry entry(HomePageSectionProvider provider,
                               boolean library,
                               boolean otherSongsLibrary,
                               boolean recommendations) {
        return entry(provider, library, otherSongsLibrary, recommendations, false);
    }

    private SectionEntry entry(HomePageSectionProvider provider,
                               boolean library,
                               boolean otherSongsLibrary,
                               boolean recommendations,
                               boolean recentlyPlayed) {
        return new SectionEntry(provider, library, otherSongsLibrary, recommendations, recentlyPlayed);
    }

    private boolean isCurrent(long renderId) {
        return renderVersion != null && renderVersion.get() == renderId;
    }

    private boolean ownsActiveRender(VBox container, long renderId) {
        if (!isCurrent(renderId) || container == null) return false;
        Object activeRender = container.getProperties().get(ACTIVE_RENDER_KEY);
        return activeRender instanceof Number value && value.longValue() == renderId;
    }

    public record RenderSummary(boolean hasSections,
                                boolean hasNonLibrarySections,
                                boolean hasOnlyOtherSongsLibrary,
                                boolean hasOnlyDiscoveryPromptSections) {
        public boolean hasOnlyLibrarySections() {
            return hasSections && !hasNonLibrarySections;
        }
    }

    private record SectionEntry(HomePageSectionProvider provider,
                                boolean library,
                                boolean otherSongsLibrary,
                                boolean recommendations,
                                boolean recentlyPlayed) {
    }

    private static final class RenderTracker {
        private final AtomicInteger remaining;
        private final Consumer<RenderSummary> callback;
        private boolean hasSections;
        private boolean hasNonLibrarySections;
        private boolean hasOtherSongsLibrary;
        private boolean hasAnotherSection;
        private boolean hasUnsupportedSection;

        private RenderTracker(int total, Consumer<RenderSummary> callback) {
            this.remaining = new AtomicInteger(Math.max(0, total));
            this.callback = callback;
        }

        private void complete(boolean library,
                              boolean otherSongsLibrary,
                              boolean recommendations,
                              boolean recentlyPlayed,
                              boolean visible,
                              boolean active) {
            if (!active) return;
            if (visible) {
                hasSections = true;
                if (!library) hasNonLibrarySections = true;
                if (otherSongsLibrary) hasOtherSongsLibrary = true;
                else hasAnotherSection = true;
                if (!otherSongsLibrary && !recommendations && !recentlyPlayed) {
                    hasUnsupportedSection = true;
                }
            }
            if (remaining.decrementAndGet() == 0 && callback != null) {
                callback.accept(new RenderSummary(
                        hasSections,
                        hasNonLibrarySections,
                        hasOtherSongsLibrary && !hasAnotherSection,
                        hasSections && !hasUnsupportedSection
                ));
            }
        }
    }
}
