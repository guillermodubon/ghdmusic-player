package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services;

import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.managers.PlayerMenuArtistResolver;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.footer.PlayerMenuMoreByArtistsFooterService;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.header.PlayerMenuHeaderService;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

public class PlayerMenuHeaderFooterService {

    private final PlayerMenuContext context;
    private final PlayerMenuHeaderService headerService;
    private final PlayerMenuMoreByArtistsFooterService moreByArtistsFooterService;

    public PlayerMenuHeaderFooterService(PlayerMenuContext context,
                                         StartUpService svc,
                                         MusicCardActionManager musicActions,
                                         PlayerMenuArtistResolver artistResolver) {
        this.context = context;
        this.headerService = new PlayerMenuHeaderService(context, svc, musicActions, artistResolver);
        this.moreByArtistsFooterService = new PlayerMenuMoreByArtistsFooterService(context, svc, musicActions, artistResolver);
    }

    public void bindUi(ImageView headerCover,
                       HBox playerMenuHeader,
                       Region playerMenuHeaderFade,
                       HBox searchSongRow,
                       Region songListVirtualShell,
                       Label recordTypeLabel,
                       Label headerTitle,
                       HBox creatorContainer,
                       Label playlistDescLabel,
                       Label dateLabel,
                       VBox moreByArtistsContainer,
                       VBox footerPane,
                       ScrollPane playerMenuScroll,
                       MenuButton menuOptions,
                       MenuItem miEdit,
                       Label songCountLabel,
                       CheckBox remoteSaveCheckBox,
                       VBox recContainer,
                       ListView<Song> recList,
                       TextField searchRec,
                       VBox remoteSuggestionBox) {
        headerService.bindUi(
                headerCover,
                playerMenuHeader,
                playerMenuHeaderFade,
                searchSongRow,
                songListVirtualShell,
                recordTypeLabel,
                headerTitle,
                creatorContainer,
                playlistDescLabel,
                dateLabel
        );
        moreByArtistsFooterService.bindUi(moreByArtistsContainer, footerPane, playerMenuScroll);
    }

    public void refreshHeader() {
        headerService.refreshHeader();
    }

    public void prepareFooterForDeferredLoad() {
        moreByArtistsFooterService.prepareForDeferredLoad();
    }

    public void refreshFooter() {
        Playlist playlist = context.getCurrentPlaylistModel();
        PlayerMenuContext.ContentType type = context.getCurrentContentTypeInView();
        moreByArtistsFooterService.refreshForView(type, playlist);
    }
}
