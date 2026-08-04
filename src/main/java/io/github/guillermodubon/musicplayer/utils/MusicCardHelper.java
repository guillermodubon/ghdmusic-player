package io.github.guillermodubon.musicplayer.utils;

import com.google.gson.*;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;
import io.github.guillermodubon.musicplayer.services.api.DeezerJsonCache;
import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.io.*;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public final class MusicCardHelper {

    public static BorderPane safeGetRootBorderPane(Node any) {
        if (any == null || any.getScene() == null) return null;
        Parent root = any.getScene().getRoot();
        return root instanceof BorderPane shellRoot
                ? resolveNavigationHost(shellRoot)
                : null;
    }

    /** Resolves AppShell's center host without exposing its layout details. */
    public static BorderPane resolveNavigationHost(BorderPane shellRoot) {
        if (shellRoot == null) {
            return null;
        }

        /* AppShell keeps the persistent player bar outside this host. */
        Object configuredCenter = shellRoot.getProperties().get("appCenterHost");
        return configuredCenter instanceof BorderPane centerHost
                ? centerHost
                : shellRoot;
    }

    public static <T> List<T> snapshot(Collection<T> c) { return c == null ? List.of() : new ArrayList<>(c); }


    public static Image loadDefaultCover() {
        return MediaImageResolver.defaultCover();
    }

    public static JsonObject fetchJsonObject(String urlStr) throws IOException {
        return DeezerJsonCache.getInstance().getJsonObject(urlStr);
    }

    /**
     * Use for opening content that has no playable local track. It verifies that
     * Deezer can currently provide the entity instead of relying on stale card
     * metadata cached earlier in the session.
     */
    public static JsonObject fetchFreshJsonObject(String urlStr) throws IOException {
        return DeezerJsonCache.getInstance().getFreshJsonObject(urlStr);
    }

    public static boolean isDeezerError(JsonObject payload) {
        return payload == null || (payload.has("error") && !payload.get("error").isJsonNull());
    }



    // Extracts "author" from the playlist if it exists (creator/name); falls back to an empty string.
    public static List<String> extractArtistNamesFromPlaylistJson(JsonObject playlistJson) {
        if (playlistJson == null) return List.of();
        try {
            if (playlistJson.has("creator") && playlistJson.get("creator").isJsonObject()) {
                JsonObject c = playlistJson.getAsJsonObject("creator");
                if (c.has("name") && !c.get("name").isJsonNull()) return List.of(c.get("name").getAsString());
                if (c.has("username") && !c.get("username").isJsonNull()) return List.of(c.get("username").getAsString());
            }
            if (playlistJson.has("user") && playlistJson.get("user").isJsonObject()) {
                JsonObject u = playlistJson.getAsJsonObject("user");
                if (u.has("name") && !u.get("name").isJsonNull()) return List.of(u.get("name").getAsString());
                if (u.has("username") && !u.get("username").isJsonNull()) return List.of(u.get("username").getAsString());
            }
        } catch (Exception ignored) {}
        return List.of();
    }

    // Extracts artist names from a Deezer track object (prioritizes contributors -> artist)
    public static List<String> extractArtistNamesFromTrackJson(JsonObject trackJson) {
        if (trackJson == null) return List.of();
        try {
            // contributors[]
            if (trackJson.has("contributors") && trackJson.get("contributors").isJsonArray()) {
                JsonArray contribs = trackJson.getAsJsonArray("contributors");
                List<String> names = new ArrayList<>();
                for (JsonElement ce : contribs) {
                    if (!ce.isJsonObject()) continue;
                    JsonObject co = ce.getAsJsonObject();
                    if (co.has("name") && !co.get("name").isJsonNull()) {
                        names.add(co.get("name").getAsString());
                    } else if (co.has("artist") && co.get("artist").isJsonObject()) {
                        JsonObject a = co.getAsJsonObject("artist");
                        if (a.has("name") && !a.get("name").isJsonNull()) names.add(a.get("name").getAsString());
                    }
                }
                if (!names.isEmpty()) return names;
            }

            // fallback: track.artist
            if (trackJson.has("artist") && trackJson.get("artist").isJsonObject()) {
                JsonObject a = trackJson.getAsJsonObject("artist");
                if (a.has("name") && !a.get("name").isJsonNull()) return List.of(a.get("name").getAsString());
            }

            // fallback: album.artist (rare)
            if (trackJson.has("album") && trackJson.get("album").isJsonObject()) {
                JsonObject al = trackJson.getAsJsonObject("album");
                if (al.has("artist") && al.get("artist").isJsonObject()) {
                    JsonObject a = al.getAsJsonObject("artist");
                    if (a.has("name") && !a.get("name").isJsonNull()) return List.of(a.get("name").getAsString());
                }
            }
        } catch (Exception ignored) {}
        return List.of();
    }

    /** Searches for an artist ID by name on Deezer (search/artist?q=name) and returns a list of IDs (with the best match first). */
    public static List<Long> searchArtistByNameOnDeezer(String name) {
        try {
            String q = URLEncoder.encode(name, StandardCharsets.UTF_8);
            JsonObject res = fetchJsonObject("https://api.deezer.com/search/artist?q=" + q);
            if (res == null || !res.has("data") || !res.get("data").isJsonArray()) return List.of();
            JsonArray arr = res.getAsJsonArray("data");
            List<Long> ids = new ArrayList<>();
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                long id = DeezerApiService.safeGetLong(o, "id", -1L);
                if (id > 0) ids.add(id);
            }
            return ids;
        } catch (Exception ex) {
            return List.of();
        }
    }

    /**
     * Resolve an artist by id or name using StartUpService snapshot first, then best-effort Deezer lookup.
     * This method is safe to call from a background thread (it may do network calls).
     *
     * @param maybeId possibly-known artist id (<=0 means unknown)
     * @param name    artist name (may be null)
     * @param svc     StartUpService (non-null)
     * @return resolved Artist instance with text metadata only; image loading is on-demand.
     */
    public static Artist resolveArtist(long maybeId, String name, StartUpService svc) {
        Objects.requireNonNull(svc, "StartUpService cannot be null");

        String safeName = name == null ? "" : name.trim();

        // 1) try by id
        if (maybeId > 0) {
            try {
                Optional<Artist> byId = snapshot(svc.getArtists()).stream()
                        .filter(a -> a != null && a.getArtistID() == maybeId)
                        .findFirst();
                if (byId.isPresent()) return byId.get();
            } catch (Exception ignored) {}
        }

        // 2) A valid ID is authoritative. Never replace it with a same-name
        // artist when the exact local record is not available.
        if (maybeId <= 0 && !safeName.isBlank()) {
            try {
                Optional<Artist> byName = snapshot(svc.getArtists()).stream()
                        .filter(a -> a != null && a.getName() != null && a.getName().equalsIgnoreCase(safeName))
                        .findFirst();
                if (byName.isPresent()) return byName.get();
            } catch (Exception ignored) {}
        }

        // 3) not found in memory -> create minimal instance and resolve the Deezer id only.
        Artist a = new Artist(maybeId > 0 ? maybeId : 0L, safeName.isBlank() ? "Unknown" : safeName, null, new ArrayList<>());
        try {
            if (maybeId <= 0 && !safeName.isBlank()) {
                List<Long> foundIds = searchArtistByNameOnDeezer(safeName);
                if (!foundIds.isEmpty()) {
                    long foundId = foundIds.get(0);
                    a = new Artist(foundId, safeName, null, new ArrayList<>());
                }
            }
        } catch (Exception ignored) {
            // don't fail resolution on network errors
        }

        return a;
    }

    /**
     * Return image or default if null.
     */
    public static Image coverOrDefault(Image maybe, Image defaultCover) {
        return maybe != null ? maybe : (defaultCover != null ? defaultCover : loadDefaultCover());
    }

    /**
     * Replace Hyperlink nodes found inside a card with Labels that look like plain text (no hover, no click).
     * Must be called on the FX thread.
     */
    public static void replaceArtistHyperlinksWithPlainLabels(Node root) {
        if (root == null) return;
        // ensure we run on FX thread
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> replaceArtistHyperlinksWithPlainLabels(root));
            return;
        }

        // traverse and collect hyperlinks with their parents and index positions so we can replace safely
        List<Replacement> replacements = new ArrayList<>();
        collectHyperlinks(root, null, replacements);

        for (Replacement r : replacements) {
            try {
                Parent parent = r.parent;
                Hyperlink hl = r.link;
                if (parent instanceof Pane) {
                    Pane p = (Pane) parent;
                    int idx = p.getChildren().indexOf(hl);
                    if (idx >= 0) {
                        Label lbl = new Label(hl.getText());
                        // style to look like plain text: inherit font, no underline, disabled mouse transparent for hover/click
                        lbl.setStyle("-fx-text-fill: -fx-text-base-color; -fx-underline: false;");
                        lbl.setMouseTransparent(true); // prevents hover and click
                        // replace in same index
                        p.getChildren().set(idx, lbl);
                    } else {
                        // fallback: just remove and add label at end
                        Label lbl = new Label(hl.getText());
                        lbl.setStyle("-fx-text-fill: -fx-text-base-color; -fx-underline: false;");
                        lbl.setMouseTransparent(true);
                        p.getChildren().remove(hl);
                        p.getChildren().add(lbl);
                    }
                } else if (parent instanceof javafx.scene.control.ToolBar || parent instanceof javafx.scene.layout.HBox || parent instanceof javafx.scene.layout.VBox) {
                    // generic Parent but not Pane API safe - try remove/add via reflection-like approach using getChildrenUnmodifiable (can't modify)
                    // Best-effort: set hyperlink to invisible and mouseTransparent
                    hl.setVisible(false);
                    hl.setMouseTransparent(true);
                    // add a sibling label if parent supports adding (rare). Skip for robustness.
                } else {
                    // fallback: disable link
                    hl.setMouseTransparent(true);
                    hl.setStyle("-fx-text-fill: -fx-text-base-color; -fx-underline: false;");
                }
            } catch (Exception ignored) {}
        }
    }

    // helper collector
    private static class Replacement { final Parent parent; final Hyperlink link; Replacement(Parent p, Hyperlink l){ parent=p; link=l;} }
    private static void collectHyperlinks(Node node, Parent parent, List<Replacement> out) {
        if (node == null) return;
        if (node instanceof Hyperlink) {
            out.add(new Replacement(parent, (Hyperlink) node));
            return;
        }
        if (node instanceof Parent p) {
            for (Node ch : p.getChildrenUnmodifiable()) collectHyperlinks(ch, p, out);
        }
    }

}
