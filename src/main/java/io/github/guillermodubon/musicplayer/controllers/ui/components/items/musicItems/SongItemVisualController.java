package io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems;

import javafx.application.Platform;
import javafx.concurrent.WorkerStateEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import org.controlsfx.control.PopOver;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistContentManagementDialogs.ManagePlaylistSongsDialogController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistContentManagementDialogs.ManagePlaylistSongsPopoverSupport;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistManagementDialogs.PlaylistManagementDialogLauncher;
import io.github.guillermodubon.musicplayer.controllers.ui.components.icons.SvgIconFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.components.popups.SmallPopupTooltip;
import io.github.guillermodubon.musicplayer.models.*;
import io.github.guillermodubon.musicplayer.services.downloads.DownloadTask;
import io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.base.BaseSongCellController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.services.ArtistLinksBuilder;
import io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.services.PreviewService;
import io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.services.SongCoverResolver;
import io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.services.SongArtistResolver;
import io.github.guillermodubon.musicplayer.services.downloads.DownloadManager;
import io.github.guillermodubon.musicplayer.services.downloads.services.SongDownloadTaskFactory;
import io.github.guillermodubon.musicplayer.services.downloads.preferences.DownloadPreferences;
import io.github.guillermodubon.musicplayer.utils.NavigationHelper;
import io.github.guillermodubon.musicplayer.utils.ArtistIdentity;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class SongItemVisualController extends BaseSongCellController {
    private static final String ICON_PREVIEW = "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/sound_sampler_27dp_FFFFFF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_DOWNLOAD = "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/download_for_offline_27dp_FFFFFF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_ADD_TO_PLAYLIST = "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/library_add_27dp_AFAFAF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_NORMAL = "#F4F4F4";
    private static final String ICON_HOVER = "#FFFFFF";

    @FXML private Button btnPreview;
    @FXML private Button btnDownload;
    @FXML private Button btnAddToPlaylist;
    @FXML private HBox rootBox;

    private Song currentSong;
    private Runnable onLoaded;
    private Consumer<String> artistClickHandler = name -> {};
    private boolean artistHydrationDelegated;

    private final List<Artist> displayedArtists = new ArrayList<>();

    private final SongCoverResolver coverResolver = new SongCoverResolver();
    private final ArtistLinksBuilder artistLinksBuilder = new ArtistLinksBuilder();
    private final PreviewService previewService = new PreviewService();
    private final DownloadManager downloadManager = DownloadManager.getInstance();

    private Long sourceCollectionId;
    private String sourceCollectionTitle;
    private String sourceCollectionType;

    private Playlist sourcePlaylistModel;

    private BiConsumer<Song, File> onDownloadCompleted =
            (song, file) -> {};

    public void setOnDownloadCompleted(
            BiConsumer<Song, File> onDownloadCompleted
    ) {
        this.onDownloadCompleted =
                onDownloadCompleted == null
                        ? (song, file) -> {}
                        : onDownloadCompleted;
    }

    @FXML
    public void initialize() {
        installIconButton(btnPreview, ICON_PREVIEW, "Hear a preview");
        installIconButton(btnDownload, ICON_DOWNLOAD, "Download this song");
        installIconButton(btnAddToPlaylist, ICON_ADD_TO_PLAYLIST, "Add to Playlist");
        SmallPopupTooltip.install(btnPreview, "Hear a preview");
        SmallPopupTooltip.install(btnDownload, "Download this song");
        SmallPopupTooltip.install(btnAddToPlaylist, "Add to Playlist");
        configureTitleMarquee(rootBox);
        configureArtistMarquee(rootBox);
    }


    public void setArtistClickHandler(Consumer<String> artistClickHandler) {
        this.artistClickHandler = artistClickHandler == null ? name -> {} : artistClickHandler;
    }

    /**
     * PlayerMenu delegates remote-song hydration to its coalescing details
     * service, while local songs continue using StartUpService directly.
     */
    public void setArtistHydrationDelegated(boolean delegated) {
        this.artistHydrationDelegated = delegated;
    }

    public void init(Song s) {
        long expectedGeneration = beginSongRender(s);
        this.currentSong = s;

        displayedArtists.clear();

        Image cover = isDeferCoverResolution()
                ? coverResolver.resolveCachedCover(
                        s,
                        coverPreferredType,
                        coverDecodeWidth,
                        coverDecodeHeight
                )
                : coverResolver.resolveCover(
                        s,
                        coverPreferredType,
                        coverDecodeWidth,
                        coverDecodeHeight
                );
        coverView.setImage(cover);

        titleLabel.setText(s != null && s.getTitle() != null ? s.getTitle() : "");
        refreshTitleMarquee();

        List<Artist> artistsToShow = resolveVisualArtists(s);
        displayedArtists.addAll(artistsToShow);
        renderArtistLinks(artistsToShow);

        notifyLoadedIfAny();

        loadAsyncArtistsIfNeeded(expectedGeneration, s);
        bindPreviewAction();
        bindDownloadAction();
        bindAddToPlaylistAction();
    }

    private List<Artist> resolveVisualArtists(Song s) {
        return SongArtistResolver.resolveParticipants(s);
    }

    private void loadAsyncArtistsIfNeeded(long expectedGeneration, Song expectedSong) {
        if (artistHydrationDelegated
                || svc == null
                || currentSong == null
                || currentSong.getSongID() <= 0) return;

        long tid = currentSong.getSongID();
        List<Artist> cached = svc.getCachedTrackArtists(tid);

        if (cached != null && !cached.isEmpty()) {
            mergeArtistsAndRefresh(cached, expectedGeneration, expectedSong);
            return;
        }

        svc.ensureTrackArtistsLoadedAsync(tid, expectedSong, () ->
                Platform.runLater(() -> {
                    if (!isCurrentRender(expectedGeneration)
                            || !isCurrentSong(expectedSong)
                            || currentSong == null
                            || currentSong.getSongID() != tid) return;
                    List<Artist> got = svc.getCachedTrackArtists(tid);
                    mergeArtistsAndRefresh(got, expectedGeneration, expectedSong);
                }));
    }

    private void mergeArtistsAndRefresh(List<Artist> extra,
                                        long expectedGeneration,
                                        Song expectedSong) {
        if (extra == null || extra.isEmpty()) return;
        if (!isCurrentRender(expectedGeneration) || !isCurrentSong(expectedSong)) return;

        List<Artist> merged = SongArtistResolver.merge(displayedArtists, extra);

        displayedArtists.clear();
        displayedArtists.addAll(merged);

        List<Artist> finalMerged = merged;
        Platform.runLater(() -> {
            if (!isCurrentRender(expectedGeneration) || !isCurrentSong(expectedSong)) return;
            renderArtistLinks(finalMerged);
            notifyLoadedIfAny();
        });
    }

    private void bindPreviewAction() {
        btnPreview.setOnAction(e -> {
            playPreview();
        });
    }

    public void playPreview() {
        if (currentSong == null) return;
        String artistsText = artistLinksBuilder.formatArtists(displayedArtists);
        Node owner = btnPreview != null ? btnPreview : rootBox;
        previewService.handlePreview(currentSong, artistsText, owner, svc, coverResolver);
    }

    public void setKeyboardSelected(boolean selected) {
        if (rootBox == null) return;
        rootBox.getStyleClass().remove("keyboard-selected");
        if (selected) {
            rootBox.getStyleClass().add("keyboard-selected");
        }
    }

    private void renderArtistLinks(List<Artist> artists) {
        if (artistContainer == null) return;
        artistContainer.getChildren().clear();

        if (artists == null || artists.isEmpty()) {
            Label unknown = new Label("Unknown");
            unknown.getStyleClass().add("song-item-artist-text");
            artistContainer.getChildren().add(unknown);
            refreshArtistMarquee();
            return;
        }

        for (int i = 0; i < artists.size(); i++) {
            Artist artist = artists.get(i);
            if (artist == null) continue;

            String name = ArtistIdentity.displayName(artist.getName());

            if (ArtistIdentity.isVariousArtists(artist)) {
                Label label = new Label(name);
                label.getStyleClass().addAll("song-item-artist-link", "artist-plain-label");
                label.setMouseTransparent(true);
                artistContainer.getChildren().add(label);
            } else {
                Hyperlink link = new Hyperlink(name);
                link.getStyleClass().addAll("app-hyperlink", "song-item-artist-link");
                link.setOnAction(e -> openArtist(artist, name));
                artistContainer.getChildren().add(link);
            }

            if (i < artists.size() - 1) {
                Label separator = new Label(", ");
                separator.getStyleClass().add("song-item-artist-separator");
                artistContainer.getChildren().add(separator);
            }
        }
        refreshArtistMarquee();
    }

    private void openArtist(Artist artist, String fallbackName) {
        try {
            if (artist != null && artist.getArtistID() > 0 && rootBox != null && rootBox.getScene() != null) {
                if (rootBox.getScene().getRoot() instanceof BorderPane root) {
                    NavigationHelper.showArtistScreen(artist, svc, root);
                    return;
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        if (fallbackName != null && !fallbackName.isBlank()) {
            artistClickHandler.accept(fallbackName);
        }
    }

    private void bindDownloadAction() {
        if (btnDownload == null) {
            return;
        }

        btnDownload.setOnAction(event -> {
            if (currentSong == null) {
                return;
            }

            /*
             * Captures the specific song that initiated the download.
             *
             * Do not use currentSong within the completion event,
             * because the cell might have been reused by VirtualFlow.
             */
            Song requestedSong = currentSong;

            DownloadTask task;

            boolean hasExactPlayerMenuSource =
                    sourcePlaylistModel != null
                            && sourceCollectionId != null
                            && sourceCollectionId > 0
                            && sourceCollectionType != null
                            && !sourceCollectionType.isBlank();

            if (hasExactPlayerMenuSource) {
                task = SongDownloadTaskFactory.create(
                        requestedSong,
                        resolveTargetDir(),
                        sourceCollectionId,
                        sourceCollectionTitle,
                        sourceCollectionType,
                        sourcePlaylistModel
                );
            } else {
                task = SongDownloadTaskFactory.create(
                        requestedSong,
                        resolveTargetDir()
                );
            }

            if (task == null) {
                return;
            }

            /*
             * The SUCCEEDED event occurs when DownloadTask.call() has finished.
             *
             * In your pipeline, call() waits for metadata, persistence,
             * manifest, and publication to complete before returning.
             * Therefore, completedFile should already represent the final file.
             */
            task.addEventHandler(
                    WorkerStateEvent.WORKER_STATE_SUCCEEDED,
                    completedEvent -> {
                        File finalFile = task.getCompletedFile();

                        if (finalFile == null
                                || !finalFile.exists()
                                || !finalFile.isFile()
                                || !finalFile.canRead()
                                || finalFile.length() <= 0L) {
                            return;
                        }

                        /*
                         * The specific remote song and the final file are sent
                         * directly to the PlayerMenu that created this cell.
                         */
                        onDownloadCompleted.accept(
                                requestedSong,
                                finalFile
                        );
                    }
            );

            boolean accepted =
                    downloadManager.enqueueTask(task);

            if (!accepted) {
                return;
            }

            Platform.runLater(() -> {
                try {
                    if (btnDownload != null
                            && btnDownload.getScene() != null) {
                        downloadManager.showSidebar(
                                btnDownload.getScene().getRoot()
                        );
                    }
                } catch (Exception ignored) {
                }
            });
        });
    }

    private void bindAddToPlaylistAction() {
        if (btnAddToPlaylist == null) return;
        btnAddToPlaylist.setOnAction(e -> showPlaylistManagementPopover());
    }

    private void showPlaylistManagementPopover() {
        if (currentSong == null) return;

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(
                    "/io/github/guillermodubon/musicplayer/Views/components/dialogs/playlistContentManagementDialogs/ManagePlaylistSongsDialog.fxml"
            ));
            AnchorPane content = loader.load();
            ManagePlaylistSongsDialogController ctrl = loader.getController();

            long activeId = io.github.guillermodubon.musicplayer.services.playback.PlaybackManager.getInstance().getCurrentPlaylistInViewId();
            ctrl.init(svc, currentSong, activeId);
            ctrl.setCreatePlaylistLauncher(onCreated ->
                    PlaylistManagementDialogLauncher.openCreatePlaylistDialog(
                            svc,
                            resolveOwnerWindow(),
                            resolveParentRoot(),
                            null,
                            onCreated
                    )
            );

            PopOver pop = new PopOver(content);
            ManagePlaylistSongsPopoverSupport.configure(pop, content, btnAddToPlaylist);
            ManagePlaylistSongsPopoverSupport.show(pop, btnAddToPlaylist);
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    private File resolveTargetDir() {
        File targetDir = DownloadPreferences.getDefaultDownloadsDirectory();
        File saved = DownloadPreferences.loadDownloadDirectory();
        if (saved != null && saved.exists() && saved.isDirectory()) {
            targetDir = saved;
        }
        return targetDir;
    }

    private javafx.stage.Window resolveOwnerWindow() {
        return btnAddToPlaylist != null && btnAddToPlaylist.getScene() != null
                ? btnAddToPlaylist.getScene().getWindow()
                : null;
    }

    private BorderPane resolveParentRoot() {
        try {
            if (btnAddToPlaylist != null
                    && btnAddToPlaylist.getScene() != null
                    && btnAddToPlaylist.getScene().getRoot() instanceof BorderPane root) {
                return root;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void notifyLoadedIfAny() {
        if (onLoaded != null) {
            try {
                onLoaded.run();
            } catch (Exception ignored) {
            }
        }
    }

    private void installIconButton(Button button, String iconPath, String accessibleText) {
        if (button == null) return;
        button.setText("");
        button.setAccessibleText(accessibleText);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        Node icon = SvgIconFactory.icon(iconPath, 22);
        SvgIconFactory.setIconColor(icon, ICON_NORMAL);
        button.setGraphic(icon);
        button.hoverProperty().addListener((obs, oldValue, isHover) ->
                updateIconColor(button, icon));
        button.focusedProperty().addListener((obs, oldValue, isFocused) ->
                updateIconColor(button, icon));
    }

    private void updateIconColor(Button button, Node icon) {
        boolean active = button != null && (button.isHover() || button.isFocused());
        SvgIconFactory.setIconColor(icon, active ? ICON_HOVER : ICON_NORMAL);
    }

    public void configureDownloadSource(
            Long sourceCollectionId,
            String sourceCollectionTitle,
            String sourceCollectionType,
            Playlist sourcePlaylistModel
    ) {
        this.sourceCollectionId = sourceCollectionId;
        this.sourceCollectionTitle = sourceCollectionTitle;
        this.sourceCollectionType = sourceCollectionType;
        this.sourcePlaylistModel = sourcePlaylistModel;
    }
}
