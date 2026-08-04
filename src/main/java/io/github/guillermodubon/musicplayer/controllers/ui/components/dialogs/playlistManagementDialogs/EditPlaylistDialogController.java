package io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistManagementDialogs;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.image.Image;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.base.BaseDialogController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.base.DialogKeyboardSupport;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.base.DialogShellController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistManagementDialogs.common.PlaylistFormContentController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.contextMenus.ActionContextMenuFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.components.icons.SvgIconFactory;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.services.PlaylistDialogService;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class EditPlaylistDialogController extends BaseDialogController {

    private static final double DIALOG_COVER_DECODE_SIZE = 640.0;
    private static final String DEFAULT_PLAYLIST_IMAGE = "/io/github/guillermodubon/musicplayer/assets/images/byDefaultImages/defaultPlaylist.png";
    private static final String ICON_CLOSE = "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/close_27dp_FAFAFA_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_ERROR = "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/error_24dp_D32F2F_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_EDIT_COVER = "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/edit_27dp_AFAFAF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_MORE = "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/more_horiz_27dp_FAFAFA_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_CHANGE_COVER = "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/image_27dp_AFAFAF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_REMOVE_COVER = "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/delete_27dp_FFFFFF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_NORMAL = "#DCDCDC";
    private static final String ICON_HOVER = "#FFFFFF";
    private static final String ERROR_STYLE_CLASS = "input-error";
    private static final String ICON_BUTTON_CHROMELESS_STYLE = """
            -fx-background-color: transparent;
            -fx-background-insets: 0;
            -fx-border-color: transparent;
            -fx-border-width: 0;
            -fx-effect: null;
            -fx-focus-color: transparent;
            -fx-faint-focus-color: transparent;
            -fx-padding: 0;
            """;

    @FXML private AnchorPane root;
    @FXML private DialogShellController shellController;
    @FXML private Button closeButton;

    private final PlaylistDialogService playlistDialogService = new PlaylistDialogService();

    private PlaylistFormContentController formController;
    private Playlist playlist;
    private File selectedImage;
    private boolean coverRemovalRequested;
    private Runnable onSaved = () -> {};

    private Button saveButton;
    private Button cancelButton;
    private Node closeIcon;
    private ContextMenu coverOptionsMenu;

    public void setOnSaved(Runnable onSaved) {
        this.onSaved = onSaved == null ? () -> {} : onSaved;
    }

    public void initForEdit(StartUpService svc, Stage dialogStage, Playlist playlist) {
        initBase(svc, dialogStage);
        this.playlist = playlist;
        this.selectedImage = null;
        this.coverRemovalRequested = false;

        shellController.setTitle("Edit Playlist");
        shellController.setSubtitle("");
        installCloseButton();
        PlaylistDialogWindowSupport.installDragHandling(dialogStage, root);

        loadFormContent();
        installInlineErrorIcon();
        installTitleValidationReset();
        installCoverPicker();
        populateForm();

        cancelButton = new Button("Cancel");
        cancelButton.getStyleClass().addAll("dialog-button", "secondary-button");
        cancelButton.setOnAction(e -> onCancel());

        saveButton = new Button("Save");
        saveButton.getStyleClass().addAll("dialog-button", "primary-button");
        saveButton.setOnAction(e -> onSave());

        shellController.setActions(cancelButton, saveButton);
        DialogKeyboardSupport.install(dialogStage, root, saveButton);
    }

    private void loadFormContent() {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/io/github/guillermodubon/musicplayer/Views/components/dialogs/playlistManagmentDialogs/common/PlaylistFormContent.fxml")
            );
            Parent formRoot = loader.load();
            formController = loader.getController();
            shellController.setContent(formRoot);
        } catch (IOException ex) {
            throw new RuntimeException("Could not load edit playlist dialog content.", ex);
        }
    }

    private void populateForm() {
        if (formController == null || playlist == null) return;

        formController.titleField.setText(playlist.getTitle());
        formController.descriptionArea.setText(playlist.getDescription());

        Image currentCover = MediaImageResolver.playlistCover(
                playlist,
                DIALOG_COVER_DECODE_SIZE,
                DIALOG_COVER_DECODE_SIZE
        );
        if (currentCover != null) {
            formController.playlistImage.setImage(currentCover);
        } else {
            setDefaultImage(formController.playlistImage, DEFAULT_PLAYLIST_IMAGE);
        }
    }

    private void installCoverPicker() {
        if (formController == null || formController.playlistImage == null) return;

        if (formController.playlistCoverCaption != null) {
            formController.playlistCoverCaption.setVisible(false);
            formController.playlistCoverCaption.setManaged(false);
        }

        if (formController.playlistCoverEditIconHost != null) {
            Node editIcon = SvgIconFactory.icon(ICON_EDIT_COVER, 30);
            SvgIconFactory.setIconColor(editIcon, "#FFFFFF");
            formController.playlistCoverEditIconHost.getChildren().setAll(editIcon);
        }

        if (formController.playlistCoverMoreButton != null) {
            Node moreIcon = SvgIconFactory.iconPreservingAspectRatio(ICON_MORE, 20);
            SvgIconFactory.setIconColor(moreIcon, "#FAFAFA");

            StackPane moreIconContainer = new StackPane(moreIcon);
            moreIconContainer.setMinSize(20, 20);
            moreIconContainer.setPrefSize(20, 20);
            moreIconContainer.setMaxSize(20, 20);
            formController.playlistCoverMoreButton.setGraphic(moreIconContainer);
            formController.playlistCoverMoreButton.setOnAction(event -> {
                event.consume();
                showCoverOptions();
            });
        }

        if (formController.playlistCoverHoverOverlay != null) {
            formController.playlistCoverHoverOverlay.setMouseTransparent(true);
        }

        if (formController.playlistCoverFrame != null) {
            formController.playlistCoverFrame.setCursor(Cursor.HAND);
            formController.playlistCoverFrame.setOnMouseEntered(event -> setCoverHover(true));
            formController.playlistCoverFrame.setOnMouseExited(event -> setCoverHover(false));
        }

        formController.playlistImage.setCursor(Cursor.HAND);
        formController.playlistImage.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) chooseCover();
        });

        coverOptionsMenu = createCoverOptionsMenu();
        coverOptionsMenu.setOnHidden(event -> {
            if (formController.playlistCoverFrame == null
                    || !formController.playlistCoverFrame.isHover()) {
                setCoverHover(false);
            }
        });
        setCoverHover(false);
    }

    private void setCoverHover(boolean visible) {
        if (formController == null) return;
        if (!visible && coverOptionsMenu != null && coverOptionsMenu.isShowing()) return;
        if (formController.playlistCoverHoverOverlay != null) {
            formController.playlistCoverHoverOverlay.setVisible(visible);
        }
        if (formController.playlistCoverMoreButton != null) {
            formController.playlistCoverMoreButton.setVisible(visible);
        }
    }

    private ContextMenu createCoverOptionsMenu() {
        MenuItem changePhoto = ActionContextMenuFactory.iconItem(
                "Change photo",
                ICON_CHANGE_COVER,
                this::chooseCover
        );
        MenuItem removePhoto = ActionContextMenuFactory.iconItem(
                "Remove photo",
                ICON_REMOVE_COVER,
                "#AFAFAF",
                this::removeCover
        );
        return ActionContextMenuFactory.iconMenu(changePhoto, removePhoto);
    }

    private void showCoverOptions() {
        if (coverOptionsMenu == null || formController == null
                || formController.playlistCoverMoreButton == null) return;
        ActionContextMenuFactory.showNearButton(
                coverOptionsMenu,
                formController.playlistCoverMoreButton
        );
    }

    private void chooseCover() {
        pickImage(dialogStage, file -> {
            selectedImage = file;
            coverRemovalRequested = false;
            formController.playlistImage.setImage(new Image(
                    file.toURI().toString(),
                    DIALOG_COVER_DECODE_SIZE,
                    DIALOG_COVER_DECODE_SIZE,
                    true,
                    true,
                    true
            ));
        });
    }

    private void removeCover() {
        selectedImage = null;
        coverRemovalRequested = true;
        setDefaultImage(formController.playlistImage, DEFAULT_PLAYLIST_IMAGE);
    }

    @FXML
    private void onCancel() {
        closeDialog();
    }

    @FXML
    private void onSave() {
        String newTitle = normalize(formController.titleField.getText());
        String newDesc = normalize(formController.descriptionArea.getText());

        if (newTitle.isBlank()) {
            showTitleError("Playlist name is required.");
            return;
        }

        if (playlistNameExists(newTitle)) {
            showTitleError("A playlist with this name already exists.");
            return;
        }

        clearTitleError();
        setFormDisabled(true);

        Playlist updatePayload = new Playlist(
                playlist.getId(),
                newTitle,
                playlist.getAuthorName(),
                newDesc,
                playlist.getDate(),
                null,
                playlist.getSongList()
        );

        CompletableFuture.runAsync(() -> {
            try {
                byte[] coverBytes = selectedImage != null ? readFileBytes(selectedImage) : null;
                playlistDialogService.updatePlaylist(
                        updatePayload,
                        coverBytes,
                        selectedImage,
                        coverRemovalRequested
                );
            } catch (Exception ex) {
                throw new CompletionException(ex);
            }
        }).thenRun(() -> runFx(() -> {
            try {
                if (selectedImage != null || coverRemovalRequested) {
                    MediaImageResolver.invalidatePlaylistCover(playlist.getId());
                }
                playlist.setTitle(newTitle);
                playlist.setDescription(newDesc);
                if (selectedImage != null || coverRemovalRequested) {
                    playlist.setCoverUrl(null);
                }
                syncPlaylistInMemory(newTitle, newDesc);
                onSaved.run();
                closeDialog();
            } finally {
                setFormDisabled(false);
            }
        })).exceptionally(ex -> {
            runFx(() -> {
                showTitleError(resolveErrorMessage(ex));
                setFormDisabled(false);
            });
            return null;
        });
    }

    private void installCloseButton() {
        if (closeButton == null) return;
        closeButton.setText("");
        closeButton.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        closeButton.setStyle(ICON_BUTTON_CHROMELESS_STYLE);
        closeIcon = SvgIconFactory.icon(ICON_CLOSE, 22);
        SvgIconFactory.setIconColor(closeIcon, ICON_NORMAL);
        closeButton.setGraphic(closeIcon);
        closeButton.hoverProperty().addListener((obs, oldValue, isHover) -> updateCloseIconColor());
        closeButton.focusedProperty().addListener((obs, oldValue, isFocused) -> updateCloseIconColor());
    }

    private void updateCloseIconColor() {
        if (closeIcon == null || closeButton == null) return;
        SvgIconFactory.setIconColor(closeIcon, closeButton.isHover() || closeButton.isFocused() ? ICON_HOVER : ICON_NORMAL);
    }

    private void installInlineErrorIcon() {
        if (formController == null || formController.titleErrorIconHost == null) return;
        Node errorIcon = SvgIconFactory.icon(ICON_ERROR, 16);
        SvgIconFactory.setIconColor(errorIcon, "#D32F2F");
        formController.titleErrorIconHost.getChildren().setAll(errorIcon);
    }

    private void installTitleValidationReset() {
        if (formController == null || formController.titleField == null) return;
        formController.titleField.textProperty().addListener((obs, oldValue, newValue) -> clearTitleError());
    }

    private boolean playlistNameExists(String title) {
        if (title == null || title.isBlank() || svc == null || svc.getPlaylists() == null) return false;
        long currentId = playlist == null ? -1 : playlist.getId();
        return svc.getPlaylists().stream()
                .filter(p -> p != null && p.getId() != currentId && p.getTitle() != null)
                .anyMatch(p -> p.getTitle().trim().equalsIgnoreCase(title.trim()));
    }

    private void showTitleError(String message) {
        if (formController == null) return;
        if (formController.titleField != null
                && !formController.titleField.getStyleClass().contains(ERROR_STYLE_CLASS)) {
            formController.titleField.getStyleClass().add(ERROR_STYLE_CLASS);
        }
        if (formController.titleErrorLabel != null) {
            formController.titleErrorLabel.setText(message == null || message.isBlank()
                    ? "Playlist name is not valid."
                    : message);
        }
        if (formController.titleErrorRow != null) {
            formController.titleErrorRow.setVisible(true);
            formController.titleErrorRow.setManaged(true);
        }
    }

    private void clearTitleError() {
        if (formController == null) return;
        if (formController.titleField != null) {
            formController.titleField.getStyleClass().remove(ERROR_STYLE_CLASS);
        }
        if (formController.titleErrorLabel != null) {
            formController.titleErrorLabel.setText("");
        }
        if (formController.titleErrorRow != null) {
            formController.titleErrorRow.setVisible(false);
            formController.titleErrorRow.setManaged(false);
        }
    }

    private void setFormDisabled(boolean disabled) {
        if (saveButton != null) saveButton.setDisable(disabled);
        if (cancelButton != null) cancelButton.setDisable(disabled);
        if (closeButton != null) closeButton.setDisable(disabled);
        if (formController != null) {
            formController.titleField.setDisable(disabled);
            formController.descriptionArea.setDisable(disabled);
            formController.playlistImage.setDisable(disabled);
            if (formController.playlistCoverMoreButton != null) {
                formController.playlistCoverMoreButton.setDisable(disabled);
            }
        }
    }

    private void syncPlaylistInMemory(String title, String description) {
        if (svc == null || svc.getPlaylists() == null || playlist == null) return;
        svc.getPlaylists().stream()
                .filter(p -> p != null && p.getId() == playlist.getId())
                .forEach(p -> {
                    p.setTitle(title);
                    p.setDescription(description);
                    if (selectedImage != null || coverRemovalRequested) {
                        p.setCoverUrl(null);
                    }
                });
    }

    private String resolveErrorMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        if (message != null && message.toLowerCase().contains("playlist")) {
            return "A playlist with this name already exists.";
        }
        return "Could not save playlist changes. Try again.";
    }
}
