package io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.providers.base;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.*;
import io.github.guillermodubon.musicplayer.repository.dao.album.AlbumDao;
import io.github.guillermodubon.musicplayer.repository.dao.album.AlbumDaoImpl;
import io.github.guillermodubon.musicplayer.repository.dao.playlist.PlaylistDao;
import io.github.guillermodubon.musicplayer.repository.dao.playlist.PlaylistDaoImpl;
import io.github.guillermodubon.musicplayer.utils.MusicCardHelper;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.common.SectionCarouselFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.homePage.context.HomePageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.libraryCatalogListScreens.common.CatalogType;
import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public abstract class BaseHomePageSectionProvider implements HomePageSectionProvider {

    protected static final ExecutorService IO_POOL = Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r);
        t.setName("home-page-io");
        t.setDaemon(true);
        return t;
    });

    protected static final int MAX_CARDS_PER_SECTION = 16;
    protected static final int MAX_ARTISTS_TO_QUERY = 6;
    protected static final int MAX_TOP_PER_ARTIST = 3;
    protected static final int MAX_GENRE_PLAYLISTS_PER_GENRE = 3;
    protected static final int MAX_GENRES_TO_QUERY = 6;
    private static final long JSON_CACHE_TTL_MILLIS = 4 * 60 * 1000L;
    private static final int JSON_CACHE_MAX_ENTRIES = 160;
    private static final double HOME_MUSIC_CARD_WIDTH = 176;
    private static final double HOME_MUSIC_CARD_HEIGHT = 254;
    private static final ConcurrentMap<String, CachedJson> JSON_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, CompletableFuture<JsonObject>> JSON_IN_FLIGHT = new ConcurrentHashMap<>();

    protected final HomePageContext context;

    protected BaseHomePageSectionProvider(HomePageContext context) {
        this.context = Objects.requireNonNull(context, "context");
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

    protected String norm(String filter) {
        return filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
    }

    protected Label sectionTitle(String text) {
        Label lbl = new Label(text);
        lbl.getStyleClass().addAll("app-text-section-title", "mainmenu-section-title");
        return lbl;
    }

    protected VBox sectionBlock(VBox container, String title) {
        return sectionBlock(container, title, null);
    }

    /**
     * Builds a library section header with a lightweight route to its full catalog.
     */
    protected VBox sectionBlock(VBox container, String title, CatalogType catalogType) {
        VBox section = new VBox(10);
        section.getStyleClass().add("mainmenu-section");
        section.setFillWidth(true);
        section.setMaxWidth(Double.MAX_VALUE);
        section.setMinHeight(Region.USE_COMPUTED_SIZE);
        section.setPrefHeight(Region.USE_COMPUTED_SIZE);
        section.setMaxHeight(Region.USE_COMPUTED_SIZE);
        VBox.setVgrow(section, Priority.NEVER);
        if (catalogType == null || context.catalogNavigator() == null) {
            section.getChildren().add(sectionTitle(title));
        } else {
            Label titleLabel = sectionTitle(title);
            Hyperlink showAll = new Hyperlink("Show all");
            showAll.getStyleClass().add("home-section-show-all");
            showAll.setFocusTraversable(false);
            showAll.setOnAction(event -> context.catalogNavigator().accept(catalogType));

            HBox header = new HBox();
            header.getStyleClass().add("home-section-header");
            header.setAlignment(Pos.CENTER_LEFT);
            header.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(titleLabel, Priority.ALWAYS);
            header.getChildren().setAll(titleLabel, showAll);
            section.getChildren().add(header);
        }
        container.getChildren().add(section);
        return section;
    }

    protected void setSectionContent(VBox section, Node content) {
        if (section == null || content == null || section.getChildren().isEmpty()) return;
        Node title = section.getChildren().get(0);
        section.getChildren().setAll(title, content);
        // Content is commonly published from an asynchronous provider. Make
        // the section and its parent relayout immediately so the next section
        // is positioned using the new content height on the next pulse.
        section.requestLayout();
        Parent parent = section.getParent();
        if (parent != null) parent.requestLayout();
    }

    protected void removeSection(VBox section) {
        if (section == null) return;
        Parent parent = section.getParent();
        if (parent instanceof Pane pane) {
            pane.getChildren().remove(section);
            return;
        }
        section.setVisible(false);
        section.setManaged(false);
    }

    protected StackPane createMusicCarousel(List<? extends Node> cards) {
        return SectionCarouselFactory.createMusicCarousel(cards);
    }

    protected StackPane createFeaturedCarousel(List<? extends Node> cards) {
        return SectionCarouselFactory.createFeaturedCarousel(cards);
    }

    protected Image defaultCover() {
        Image img = null;
        try {
            img = MusicCardHelper.loadDefaultCover();
        } catch (Exception ignored) {}
        return img != null ? img : new WritableImage(1, 1);
    }

    protected boolean matchesFilter(String text, Collection<String> extras, String filter) {
        if (filter == null || filter.isBlank()) return true;
        String f = filter.toLowerCase(Locale.ROOT);
        if (text != null && text.toLowerCase(Locale.ROOT).contains(f)) return true;
        if (extras == null) return false;
        for (String s : extras) {
            if (s != null && s.toLowerCase(Locale.ROOT).contains(f)) return true;
        }
        return false;
    }

    protected JsonObject getJson(String url) {
        if (url == null || url.isBlank() || context.deezer() == null) return null;

        long now = System.currentTimeMillis();
        CachedJson cached = JSON_CACHE.get(url);
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.copy();
        }

        CompletableFuture<JsonObject> created = new CompletableFuture<>();
        CompletableFuture<JsonObject> existing = JSON_IN_FLIGHT.putIfAbsent(url, created);

        if (existing == null) {
            try {
                JsonObject value = fetchJsonForCache(url);
                created.complete(value);
                return value == null ? null : value.deepCopy();
            } catch (Throwable throwable) {
                created.complete(null);
                return null;
            } finally {
                JSON_IN_FLIGHT.remove(url, created);
            }
        }

        try {
            JsonObject value = existing.getNow(null);
            if (value != null) return value.deepCopy();
            value = existing.join();
            return value == null ? null : value.deepCopy();
        } catch (Exception ignored) {
            return null;
        }
    }

    private JsonObject fetchJsonForCache(String url) {
        try {
            JsonObject value = context.deezer().getJson(url);
            if (value != null) {
                trimJsonCacheIfNeeded();
                JSON_CACHE.put(url, new CachedJson(value.deepCopy(), System.currentTimeMillis() + JSON_CACHE_TTL_MILLIS));
            }
            return value;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void trimJsonCacheIfNeeded() {
        if (JSON_CACHE.size() < JSON_CACHE_MAX_ENTRIES) return;
        long now = System.currentTimeMillis();
        JSON_CACHE.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().expiresAtMillis() <= now);
        if (JSON_CACHE.size() < JSON_CACHE_MAX_ENTRIES) return;

        int removeCount = Math.max(1, JSON_CACHE.size() - JSON_CACHE_MAX_ENTRIES + 1);
        Iterator<String> keys = JSON_CACHE.keySet().iterator();
        while (keys.hasNext() && removeCount-- > 0) {
            keys.next();
            keys.remove();
        }
    }

    private record CachedJson(JsonObject value, long expiresAtMillis) {
        private JsonObject copy() {
            return value == null ? null : value.deepCopy();
        }
    }

    protected String extractArtistDisplayName(JsonObject obj) {
        if (obj == null) return null;

        try {
            if (obj.has("name") && !obj.get("name").isJsonNull()) {
                String v = obj.get("name").getAsString();
                if (v != null && !v.isBlank()) return v.trim();
            }
        } catch (Exception ignored) {}

        try {
            if (obj.has("title") && !obj.get("title").isJsonNull()) {
                String v = obj.get("title").getAsString();
                if (v != null && !v.isBlank()) return v.trim();
            }
        } catch (Exception ignored) {}

        return null;
    }

    protected LinkedHashSet<String> extractArtistNamesFromResource(JsonObject obj) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (obj == null) return names;

        try {
            if (obj.has("artist") && obj.get("artist").isJsonObject()) {
                String name = extractArtistDisplayName(obj.getAsJsonObject("artist"));
                if (name != null) names.add(name);
            }
        } catch (Exception ignored) {}

        try {
            if (obj.has("contributors") && obj.get("contributors").isJsonArray()) {
                for (JsonElement el : obj.getAsJsonArray("contributors")) {
                    if (!el.isJsonObject()) continue;
                    String name = extractArtistDisplayName(el.getAsJsonObject());
                    if (name != null) names.add(name);
                }
            }
        } catch (Exception ignored) {}

        return names;
    }

    protected LinkedHashSet<String> extractAlbumArtistNamesFromResource(JsonObject obj) {
        return new LinkedHashSet<>(io.github.guillermodubon.musicplayer.utils.AlbumArtistResolver.names(obj));
    }

    protected List<String> normalizeArtistNames(Collection<String> raw) {
        if (raw == null) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : raw) {
            if (s == null) continue;
            String v = s.trim();
            if (!v.isBlank()) out.add(v);
        }
        return List.copyOf(out);
    }

    protected List<String> resolveTrackArtistNames(long trackId, JsonObject baseJson) {
        if (trackId <= 0) return List.of("Unknown");

        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.addAll(extractArtistNamesFromResource(baseJson));

        if (names.size() <= 1 && context.endpoints() != null) {
            try {
                JsonObject detail = getJson(context.endpoints().trackById(trackId));
                if (detail != null) names.addAll(extractArtistNamesFromResource(detail));
            } catch (Exception ignored) {}
        }

        List<String> out = normalizeArtistNames(names);
        return out.isEmpty() ? List.of("Unknown") : out;
    }


    protected boolean isRenderActive(long renderId) {
        return context.renderVersion() != null && context.renderVersion().get() == renderId;
    }

    protected FlowPane createCardRow() {
        FlowPane fp = new FlowPane(14, 14);
        fp.getStyleClass().add("mainmenu-row");
        fp.setPrefWrapLength(1080);
        fp.setMaxWidth(Double.MAX_VALUE);
        fp.setPadding(new Insets(4, 0, 0, 0));
        return fp;
    }

    protected void styleMusicCard(Node card) {
        if (card instanceof Region region) {
            region.setPrefWidth(HOME_MUSIC_CARD_WIDTH);
            region.setMinWidth(HOME_MUSIC_CARD_WIDTH);
            region.setMaxWidth(HOME_MUSIC_CARD_WIDTH);
            region.setPrefHeight(HOME_MUSIC_CARD_HEIGHT);
            region.setMinHeight(HOME_MUSIC_CARD_HEIGHT);
            region.setMaxHeight(HOME_MUSIC_CARD_HEIGHT);
            HBox.setHgrow(region, Priority.NEVER);
        }
    }

    protected void styleRecentlyPlayedCard(Node card) {
        if (card instanceof Region region) {
            if (!region.getStyleClass().contains("recently-played-card")) {
                region.getStyleClass().add("recently-played-card");
            }
            if (!region.getStyleClass().contains("recently-played-glass-card")) {
                region.getStyleClass().add("recently-played-glass-card");
            }
            region.setPrefWidth(284);
            region.setMinWidth(220);
            region.setMaxWidth(340);
            region.setPrefHeight(70);
            region.setMinHeight(70);
            region.setMaxHeight(80);
        }
    }

    protected Label emptyState(String text) {
        Label label = new Label(text);
        label.getStyleClass().addAll("app-text-body", "mainmenu-empty-state");
        return label;
    }


}
