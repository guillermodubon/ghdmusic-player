package io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.helpers;

import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.utils.ArtistIdentity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Shared artist-link renderer for player surfaces.
 *
 * <p>It keeps artist identity ID-first, so every surface opens the same
 * artist page while retaining its own visual style through CSS classes.</p>
 */
public final class PlayerArtistLinksRenderer {

    private PlayerArtistLinksRenderer() {
    }

    public static void render(HBox container,
                              Song song,
                              BiConsumer<Node, Artist> artistNavigation,
                              String artistLinkStyle,
                              String separatorStyle,
                              String emptyStyle) {
        if (container == null) return;

        container.getChildren().clear();
        List<Artist> artists = resolveArtists(song);
        if (artists.isEmpty()) {
            Label empty = new Label("Unknown artist");
            empty.getStyleClass().add(emptyStyle);
            container.getChildren().add(empty);
            return;
        }

        for (int index = 0; index < artists.size(); index++) {
            Artist artist = artists.get(index);
            String name = ArtistIdentity.displayName(artist == null ? null : artist.getName());
            if (ArtistIdentity.isVariousArtists(name)) {
                Label label = new Label(name);
                label.getStyleClass().addAll(artistLinkStyle, "artist-plain-label");
                label.setMouseTransparent(true);
                container.getChildren().add(label);
            } else {
                Hyperlink link = new Hyperlink(name);
                link.getStyleClass().addAll("app-hyperlink", artistLinkStyle);
                link.setFocusTraversable(false);
                link.setOnAction(event -> {
                    if (artistNavigation != null) {
                        artistNavigation.accept(link, artist);
                    }
                });
                container.getChildren().add(link);
            }

            if (index < artists.size() - 1) {
                Label separator = new Label(", ");
                separator.getStyleClass().add(separatorStyle);
                separator.setMouseTransparent(true);
                container.getChildren().add(separator);
            }
        }
    }

    private static List<Artist> resolveArtists(Song song) {
        if (song == null) return List.of();

        LinkedHashSet<String> identities = new LinkedHashSet<>();
        List<Artist> artists = new ArrayList<>();
        addArtists(artists, identities, song.getArtist());
        if (artists.isEmpty() && song.getAlbum() != null) {
            addArtists(artists, identities, song.getAlbum().getArtist());
        }
        return artists;
    }

    private static void addArtists(List<Artist> target,
                                   Set<String> identities,
                                   List<Artist> artists) {
        if (target == null || identities == null || artists == null) return;
        for (Artist artist : artists) {
            if (artist == null || artist.getName() == null || artist.getName().isBlank()) continue;
            String identity = artist.getArtistID() > 0
                    ? "id:" + artist.getArtistID()
                    : "name:" + artist.getName().trim().toLowerCase(Locale.ROOT);
            if (identities.add(identity)) {
                target.add(artist);
            }
        }
    }
}
