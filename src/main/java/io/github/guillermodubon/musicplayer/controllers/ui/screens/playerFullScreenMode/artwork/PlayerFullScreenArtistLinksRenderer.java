package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerFullScreenMode.artwork;

import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.utils.ArtistIdentity;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** Builds the artist row used by both fullscreen presentations. */
public final class PlayerFullScreenArtistLinksRenderer {

    private static final String LINK_STYLE = "player-fullscreen-artist-link";
    private static final String SEPARATOR_STYLE = "player-fullscreen-artist-separator";
    private static final String EMPTY_STYLE = "player-fullscreen-artist-empty";

    private PlayerFullScreenArtistLinksRenderer() {
    }

    public static void render(
            HBox container,
            Song song,
            BiConsumer<Node, Artist> artistNavigation
    ) {
        if (container == null) {
            return;
        }

        container.getChildren().clear();
        container.setMouseTransparent(false);
        container.setPickOnBounds(false);

        List<Artist> artists = resolveArtists(song);
        if (artists.isEmpty()) {
            Label empty = new Label("Unknown artist");
            empty.getStyleClass().add(EMPTY_STYLE);
            empty.setMouseTransparent(true);
            container.getChildren().add(empty);
            return;
        }

        for (int index = 0; index < artists.size(); index++) {
            Artist artist = artists.get(index);
            String name = ArtistIdentity.displayName(artist.getName());

            if (isNonNavigableArtist(artist, name)) {
                Label label = new Label(name);
                label.getStyleClass().addAll(LINK_STYLE, "artist-plain-label");
                label.setMouseTransparent(true);
                container.getChildren().add(label);
            } else {
                container.getChildren().add(createArtistLink(artist, name, artistNavigation));
            }

            if (index < artists.size() - 1) {
                Label separator = new Label(", ");
                separator.getStyleClass().add(SEPARATOR_STYLE);
                separator.setMouseTransparent(true);
                container.getChildren().add(separator);
            }
        }
    }

    private static Hyperlink createArtistLink(
            Artist artist,
            String name,
            BiConsumer<Node, Artist> artistNavigation
    ) {
        AtomicBoolean navigationStarted = new AtomicBoolean();
        Hyperlink link = new Hyperlink(name);
        link.getStyleClass().addAll("app-hyperlink", LINK_STYLE);
        link.setContentDisplay(javafx.scene.control.ContentDisplay.TEXT_ONLY);
        link.setMnemonicParsing(false);
        link.setFocusTraversable(false);
        link.setDisable(false);
        link.setMouseTransparent(false);
        link.setPickOnBounds(true);
        link.setCursor(Cursor.HAND);
        Runnable navigate = () -> {
            if (artistNavigation != null && navigationStarted.compareAndSet(false, true)) {
                artistNavigation.accept(link, artist);
            }
        };
        // Start navigation on press as well as keyboard activation. This is
        // deliberate: a moving fullscreen composition can be detached while
        // a standard Hyperlink is armed, which prevents its onAction event
        // from arriving on mouse release. The one-shot guard avoids a second
        // navigation if JavaFX subsequently emits the action event.
        link.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                navigate.run();
                event.consume();
            }
        });
        link.setOnAction(event -> {
            navigate.run();
            event.consume();
        });
        return link;
    }

    private static boolean isNonNavigableArtist(Artist artist, String name) {
        if (ArtistIdentity.isVariousArtists(name)) {
            return true;
        }
        return artist.getArtistID() <= 0 && "unknown".equalsIgnoreCase(name);
    }

    private static List<Artist> resolveArtists(Song song) {
        if (song == null) {
            return List.of();
        }

        LinkedHashSet<String> identities = new LinkedHashSet<>();
        List<Artist> artists = new ArrayList<>();
        addArtists(artists, identities, song.getArtist());
        if (artists.isEmpty() && song.getAlbum() != null) {
            addArtists(artists, identities, song.getAlbum().getArtist());
        }
        return artists;
    }

    private static void addArtists(
            List<Artist> target,
            Set<String> identities,
            List<Artist> source
    ) {
        if (source == null) {
            return;
        }
        for (Artist artist : source) {
            if (artist == null || artist.getName() == null || artist.getName().isBlank()) {
                continue;
            }
            String identity = artist.getArtistID() > 0
                    ? "id:" + artist.getArtistID()
                    : "name:" + artist.getName().trim().toLowerCase(Locale.ROOT);
            if (identities.add(identity)) {
                target.add(artist);
            }
        }
    }
}
