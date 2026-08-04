package io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.providers.base;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.context.GenrePageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.genrePage.context.GenrePageRenderContext;
import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.utils.GenreDetailsControllerUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;

public abstract class BaseGenrePageSectionProvider implements GenrePageSectionProvider {

    private static final AtomicInteger IO_THREAD_SEQUENCE = new AtomicInteger();
    private static final AtomicInteger HTTP_THREAD_SEQUENCE = new AtomicInteger();

    // One worker per GenrePage section. Nested Deezer requests remain separately bounded.
    protected static final ExecutorService IO_POOL = Executors.newFixedThreadPool(4, daemonFactory("genre-page-io-", IO_THREAD_SEQUENCE));
    protected static final ExecutorService HTTP_FETCH_EXECUTOR = Executors.newFixedThreadPool(4, daemonFactory("genre-page-http-", HTTP_THREAD_SEQUENCE));

    protected static final int MAX_DISPLAY = 10;
    protected static final int GENRE_CARDS_WRAP_PREF = 900;

    protected final GenrePageContext context;

    protected BaseGenrePageSectionProvider(GenrePageContext context) {
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

    protected <T> CompletableFuture<T> supplyHttpAsync(Callable<T> loader) {
        if (context.requestScope() != null) {
            return context.requestScope().supplyAsync(loader, HTTP_FETCH_EXECUTOR);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return loader.call();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }, HTTP_FETCH_EXECUTOR);
    }

    protected String norm(String filter) {
        return filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
    }

    protected Label sectionTitle(String text) {
        Label lbl = new Label(text);
        lbl.getStyleClass().addAll("app-section-title", "genre-details-section-title");
        return lbl;
    }

    protected VBox sectionBlock() {
        VBox section = new VBox(10);
        section.getStyleClass().addAll("app-section", "genre-details-section");
        return section;
    }

    protected FlowPane flowSection(VBox screenContainer) {
        FlowPane pane = new FlowPane(16, 24);
        pane.getStyleClass().addAll("app-card-grid", "genre-details-flow");
        pane.setMaxWidth(Double.MAX_VALUE);
        pane.setPrefWrapLength(GENRE_CARDS_WRAP_PREF);
        pane.setPadding(new Insets(4, 0, 0, 0));
        bindFlowWrap(pane, screenContainer);
        return pane;
    }

    private void bindFlowWrap(FlowPane pane, Region screenContainer) {
        if (pane == null || screenContainer == null) return;
        pane.prefWrapLengthProperty().bind(Bindings.max(260, screenContainer.widthProperty().subtract(56)));
    }

    protected void hideSectionIfEmpty(VBox container, VBox section) {
        if (container != null && section != null) {
            container.getChildren().remove(section);
        }
    }

    protected String encodeQuery(String query) {
        return URLEncoder.encode(query == null ? "" : query.trim(), StandardCharsets.UTF_8);
    }


    protected Image defaultCover() {
        Image img = GenreDetailsControllerUtils.defaultCover();
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
        try {
            return context.deezer() == null ? null : context.deezer().getObject(url);
        } catch (Exception ignored) {
            return null;
        }
    }

    protected JsonArray getArray(String url) {
        try {
            return context.deezer() == null ? null : context.deezer().getArray(url);
        } catch (Exception ignored) {
            return null;
        }
    }

    protected List<String> resolveTrackArtistNames(long trackId, JsonObject baseJson) {
        JsonObject detail = null;
        if (requiresArtistDetails(baseJson) && context.endpoints() != null && trackId > 0) {
            detail = getJson(context.endpoints().trackById(trackId));
        }
        return resolveArtistNames(baseJson, detail);
    }

    protected List<String> resolveTrackArtistNames(JsonObject baseJson, JsonObject detail) {
        return resolveArtistNames(baseJson, detail);
    }

    protected List<String> resolveAlbumArtistNames(long albumId, JsonObject baseJson) {
        JsonObject detail = null;
        if (requiresArtistDetails(baseJson) && context.endpoints() != null && albumId > 0) {
            detail = getJson(context.endpoints().albumById(albumId));
        }
        return resolveArtistNames(baseJson, detail);
    }

    protected List<String> resolveAlbumArtistNames(JsonObject baseJson, JsonObject detail) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (detail != null) names.addAll(GenreDetailsControllerUtils.extractAlbumArtistNamesFromResource(detail));
        if (names.isEmpty()) names.addAll(GenreDetailsControllerUtils.extractAlbumArtistNamesFromResource(baseJson));
        return names.isEmpty() ? List.of("Unknown") : List.copyOf(names);
    }

    /**
     * Loads missing collaborator data concurrently. The endpoint requests stay bounded
     * by HTTP_FETCH_EXECUTOR and are cancelled together when GenrePage is abandoned.
     */
    protected Map<Long, JsonObject> loadArtistDetails(JsonArray resources, LongFunction<String> detailEndpoint) {
        if (resources == null || resources.isEmpty() || detailEndpoint == null || context.endpoints() == null) {
            return Map.of();
        }

        Map<Long, CompletableFuture<JsonObject>> requests = new LinkedHashMap<>();
        for (var element : resources) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject resource = element.getAsJsonObject();
            long id = GenreDetailsControllerUtils.safeGetLong(resource, "id", -1L);
            if (id <= 0 || !requiresArtistDetails(resource) || requests.containsKey(id)) continue;

            String endpoint = detailEndpoint.apply(id);
            if (endpoint == null || endpoint.isBlank()) continue;
            requests.put(id, supplyHttpAsync(() -> getJson(endpoint)).exceptionally(error -> null));
        }

        if (requests.isEmpty()) return Map.of();
        CompletableFuture.allOf(requests.values().toArray(new CompletableFuture[0])).join();

        Map<Long, JsonObject> details = new HashMap<>();
        requests.forEach((id, future) -> {
            JsonObject detail = future.getNow(null);
            if (detail != null) details.put(id, detail);
        });
        return details;
    }

    private List<String> resolveArtistNames(JsonObject baseJson, JsonObject detail) {
        LinkedHashSet<String> names = new LinkedHashSet<>(GenreDetailsControllerUtils.extractArtistNamesFromResource(baseJson));
        if (detail != null) {
            names.addAll(GenreDetailsControllerUtils.extractArtistNamesFromResource(detail));
        }
        return names.isEmpty() ? List.of("Unknown") : List.copyOf(names);
    }

    private boolean requiresArtistDetails(JsonObject resource) {
        if (resource == null || resource.has("contributors")) return false;
        return GenreDetailsControllerUtils.extractArtistNamesFromResource(resource).size() <= 1;
    }

    protected LinkedHashMap<Long, String> libraryArtistCandidates(GenrePageRenderContext rc) {
        LinkedHashMap<Long, String> candidates = new LinkedHashMap<>();
        if (rc == null || context.svc() == null) return candidates;

        var librarySnapshot = rc.shared().librarySnapshot();
        List<Album> albums = librarySnapshot == null
                ? Optional.ofNullable(context.svc().getAlbums()).map(ArrayList::new).orElseGet(ArrayList::new)
                : librarySnapshot.albums();
        for (Album album : albums) {
            if (album == null || album.getGenre() == null || album.getGenre().getGenreID() != rc.genreId()) continue;
            addArtists(candidates, album.getArtist());
        }

        List<Song> songs = librarySnapshot == null
                ? Optional.ofNullable(context.svc().getSongs()).map(ArrayList::new).orElseGet(ArrayList::new)
                : librarySnapshot.songs();
        for (Song song : songs) {
            if (song == null || song.getAlbum() == null || song.getAlbum().getGenre() == null) continue;
            if (song.getAlbum().getGenre().getGenreID() != rc.genreId()) continue;
            addArtists(candidates, song.getArtist());
        }

        for (Long artistId : rc.shared().libraryArtistIds()) {
            if (artistId == null || artistId <= 0 || candidates.containsKey(artistId)) continue;
            candidates.put(artistId, artistNameById(artistId));
        }

        return candidates;
    }


    protected void addArtistCandidates(LinkedHashMap<Long, String> candidates, JsonObject resource) {
        if (candidates == null || resource == null) return;
        try {
            if (resource.has("artist") && resource.get("artist").isJsonObject()) {
                addArtistCandidate(candidates, resource.getAsJsonObject("artist"));
            }
        } catch (Exception ignored) {
        }

        try {
            if (resource.has("contributors") && resource.get("contributors").isJsonArray()) {
                for (var element : resource.getAsJsonArray("contributors")) {
                    if (element != null && element.isJsonObject()) {
                        addArtistCandidate(candidates, element.getAsJsonObject());
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    protected void ensureArtistObject(JsonObject resource, long artistId, String artistName) {
        if (resource == null || resource.has("artist") || artistId <= 0) return;
        JsonObject artist = new JsonObject();
        artist.addProperty("id", artistId);
        artist.addProperty("name", artistName == null || artistName.isBlank() ? artistNameById(artistId) : artistName);
        resource.add("artist", artist);
    }

    private void addArtists(LinkedHashMap<Long, String> candidates, List<Artist> artists) {
        if (artists == null) return;
        for (Artist artist : artists) {
            if (artist == null || artist.getArtistID() <= 0) continue;
            candidates.putIfAbsent(artist.getArtistID(), artist.getName());
        }
    }

    private void addArtistCandidate(LinkedHashMap<Long, String> candidates, JsonObject artistJson) {
        long id = GenreDetailsControllerUtils.safeGetLong(artistJson, "id", -1L);
        if (id <= 0 || candidates.containsKey(id)) return;
        candidates.put(id, GenreDetailsControllerUtils.extractArtistDisplayName(artistJson));
    }

    protected String artistNameById(long artistId) {
        if (artistId <= 0 || context.memory() == null) return "Unknown";
        for (Artist artist : context.memory().artists()) {
            if (artist != null && artist.getArtistID() == artistId) {
                String name = artist.getName();
                return name == null || name.isBlank() ? "Unknown" : name;
            }
        }
        return "Unknown";
    }

    private static ThreadFactory daemonFactory(String prefix, AtomicInteger sequence) {
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
