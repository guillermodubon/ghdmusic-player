package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.footer;

import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.MusicCardData;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.factory.CardFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.components.icons.SvgIconFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.components.sections.ResponsiveCardCarousel;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;
import io.github.guillermodubon.musicplayer.utils.ArtistIdentity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.footer.PlayerMenuMoreByArtistsModels.FooterCardSpec;

/** Builds footer titles, cards and connection-error content. */
public final class PlayerMenuMoreByArtistsRenderer {

    private static final int MAX_FOOTER_CARDS_PER_ARTIST = 12;
    private static final String CONNECTION_ERROR_ICON =
            "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/wifi_off_27dp_AFAFAF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String CONNECTION_ERROR_MESSAGE =
            "This section could not be loaded. Please check your internet connection and try again.";

    private MusicCardActionManager musicActions;

    public PlayerMenuMoreByArtistsRenderer(MusicCardActionManager musicActions) {
        this.musicActions = musicActions;
    }

    public void bindActions(MusicCardActionManager musicActions) {
        this.musicActions = musicActions;
    }

    public HBox createArtistTitle(Artist artist) {
        HBox row = new HBox(0);
        row.setAlignment(Pos.BASELINE_LEFT);
        row.getStyleClass().add("player-menu-more-by-artist-title-row");

        Label prefix = new Label("More by ");
        prefix.getStyleClass().add("player-menu-more-by-artist-title-text");
        String artistName = artist == null ? null : artist.getName();
        String safeName = artistName == null || artistName.isBlank() ? "Unknown" : artistName;

        if (ArtistIdentity.isVariousArtists(safeName)) {
            Label label = new Label(safeName);
            label.getStyleClass().addAll(
                    "player-menu-more-by-artist-title-link",
                    "artist-plain-label"
            );
            label.setMouseTransparent(true);
            row.getChildren().addAll(prefix, label);
            return row;
        }

        Hyperlink artistLink = new Hyperlink(safeName);
        artistLink.getStyleClass().addAll(
                "app-hyperlink",
                "player-menu-more-by-artist-title-link"
        );
        artistLink.setOnAction(event -> {
            if (musicActions != null && artist != null) {
                musicActions.artistClick(artistLink).accept(artist);
            }
        });
        row.getChildren().addAll(prefix, artistLink);
        return row;
    }

    public StackPane createCarousel(ObservableList<Node> cards) {
        return ResponsiveCardCarousel.createMusicCarousel(cards);
    }

    public int renderSpecs(
            ObservableList<Node> cards,
            Node clickContext,
            List<FooterCardSpec> specs,
            long currentAlbumId,
            long currentSongId,
            Artist ownerArtist,
            Consumer<Artist> artistClick
    ) {
        if (cards == null || specs == null || specs.isEmpty()) {
            return 0;
        }

        Set<String> presentKeys = cards.stream()
                .map(node -> String.valueOf(node.getProperties().getOrDefault("footerCardKey", "")))
                .filter(key -> key != null && !key.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        int available = MAX_FOOTER_CARDS_PER_ARTIST - presentKeys.size();
        if (available <= 0) {
            return 0;
        }

        List<Parent> toAdd = new ArrayList<>();
        for (FooterCardSpec spec : specs) {
            if (spec == null || spec.id() <= 0
                    || (isAlbum(spec) && spec.id() == currentAlbumId)
                    || ("song".equals(spec.type()) && spec.id() == currentSongId)
                    || !presentKeys.add(spec.key())) {
                continue;
            }

            try {
                Image cover = spec.localCover();
                if (cover == null && spec.coverUrl() != null && !spec.coverUrl().isBlank()) {
                    cover = MediaImageResolver.remoteImage(spec.coverUrl(), 320, 320);
                }
                Parent card = CardFactory.createMusicCard(new MusicCardData(
                        String.valueOf(spec.id()),
                        cover,
                        spec.title(),
                        spec.artists() == null ? List.of() : spec.artists(),
                        onCardClick(spec, clickContext),
                        name -> {
                            if (artistClick == null) return;
                            artistClick.accept(resolveArtistForCardName(name, ownerArtist));
                        }
                ));
                card.getProperties().put("footerCardKey", spec.key());
                card.getProperties().put("artistNames", spec.artists());
                if ("song".equals(spec.type())) {
                    card.getProperties().put("songId", spec.id());
                } else {
                    card.getProperties().put("albumId", spec.id());
                }
                toAdd.add(card);
                if (toAdd.size() >= available) {
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        cards.addAll(toAdd);
        return toAdd.size();
    }

    private Artist resolveArtistForCardName(String name, Artist ownerArtist) {
        if (ownerArtist != null
                && ownerArtist.getName() != null
                && name != null
                && ownerArtist.getName().trim().equalsIgnoreCase(name.trim())) {
            return ownerArtist;
        }
        return new Artist(0L, name, null, new ArrayList<>());
    }

    public void showLoadError(Pane target, VBox footerPane) {
        if (target == null) {
            return;
        }
        Node icon = SvgIconFactory.icon(CONNECTION_ERROR_ICON, 28);
        Label message = new Label(CONNECTION_ERROR_MESSAGE);
        message.getStyleClass().add("player-menu-footer-load-error-message");
        message.setWrapText(true);
        message.setMaxWidth(460);
        VBox error = new VBox(10, icon, message);
        error.getStyleClass().add("player-menu-footer-load-error");
        error.setAlignment(Pos.CENTER);
        error.setMaxWidth(Double.MAX_VALUE);
        error.setMinHeight(112);
        target.getChildren().setAll(error);
        target.setVisible(true);
        target.setManaged(true);
        if (footerPane != null) {
            footerPane.setVisible(true);
            footerPane.setManaged(true);
        }
    }

    private Consumer<String> onCardClick(FooterCardSpec spec, Node clickContext) {
        if (musicActions == null) {
            return ignored -> { };
        }
        if ("song".equals(spec.type())) {
            return id -> musicActions.songClick(clickContext).accept(id);
        }
        if ("singleAlbum".equals(spec.type())) {
            return id -> musicActions.playFirstTrackFromAlbumAsSingle(id, clickContext);
        }
        return id -> musicActions.albumClick(clickContext).accept(id);
    }

    private boolean isAlbum(FooterCardSpec spec) {
        return "album".equals(spec.type()) || "singleAlbum".equals(spec.type());
    }
}
