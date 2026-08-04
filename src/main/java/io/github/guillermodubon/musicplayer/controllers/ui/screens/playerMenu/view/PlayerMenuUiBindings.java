package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.view;

import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import io.github.guillermodubon.musicplayer.controllers.ui.components.inputs.SearchBarController;
import io.github.guillermodubon.musicplayer.models.Song;

/**
 * FXML references used by the PlayerMenu view collaborators.
 *
 * Keeping the bindings in one immutable object prevents service constructors
 * from receiving long, ambiguous lists of JavaFX controls.
 */
public record PlayerMenuUiBindings(
        BorderPane playerMenuRoot,
        VBox playerMenuSurface,
        HBox playerMenuHeader,
        Region playerMenuHeaderFade,
        StackPane headerCoverShell,
        StackPane headerOptionsSlot,
        HBox searchSongRow,
        StackPane songSearchBox,
        SearchBarController songSearchBarController,
        HBox playerMenuActionButtons,
        Button playVisibleSongsButton,
        ToggleButton randomVisibleSongsButton,
        Button addVisibleSongsToPlaylistButton,
        Button downloadAllButton,
        ImageView headerCover,
        Label recordTypeLabel,
        Label headerTitle,
        StackPane creatorViewport,
        HBox creatorContainer,
        Label playlistDescLabel,
        Label dateLabel,
        ListView<Song> songsToPlayView,
        ScrollPane playerMenuScroll,
        Pane songListVirtualShell,
        VBox moreByArtistsContainer,
        VBox footerPane,
        VBox recContainer,
        Label recTitleLabel,
        Button addAllRecommendationsButton,
        ListView<Song> recList,
        StackPane recommendationSearchBox,
        SearchBarController recommendationSearchBarController,
        Button btnRefreshRec,
        MenuButton menuOptions,
        MenuItem miEdit,
        MenuItem miDelete,
        Label songCountLabel,
        CheckBox remoteSaveCheckBox,
        MenuButton playlistSortMenuButton,
        VBox remoteSuggestionBox,
        ListView<Song> songListView,
        ImageView headerCoverImage
) {

    public javafx.scene.control.TextField songSearchField() {
        return songSearchBarController == null ? null : songSearchBarController.getTextField();
    }

    public javafx.scene.control.TextField recommendationSearchField() {
        return recommendationSearchBarController == null
                ? null
                : recommendationSearchBarController.getTextField();
    }

    public ImageView resolvedHeaderCover() {
        return headerCover != null ? headerCover : headerCoverImage;
    }

    public ListView<Song> resolvedSongsView() {
        return songsToPlayView != null ? songsToPlayView : songListView;
    }
}
