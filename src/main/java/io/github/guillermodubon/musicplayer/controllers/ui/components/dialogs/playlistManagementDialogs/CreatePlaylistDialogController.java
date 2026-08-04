package io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistManagementDialogs;

import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.TextInputControl;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.AnchorPane;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import io.github.guillermodubon.musicplayer.services.navigation.PlayerMenuNavigator;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.base.BaseDialogController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.base.DialogKeyboardSupport;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.base.DialogShellController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistManagementDialogs.common.PlaylistFormContentController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.icons.SvgIconFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.ArtistOpenCoordinator;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.services.PlaylistDialogService;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

public class CreatePlaylistDialogController extends BaseDialogController {

    private static final double DIALOG_COVER_DECODE_SIZE = 640.0;
    private static final String DEFAULT_PLAYLIST_IMAGE = "/io/github/guillermodubon/musicplayer/assets/images/byDefaultImages/defaultPlaylist.png";
    private static final String ICON_CLOSE = "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/close_27dp_FAFAFA_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_ERROR = "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/error_24dp_D32F2F_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_EDIT_COVER = "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/edit_27dp_AFAFAF_FILL0_wght400_GRAD0_opsz24.svg";
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
    private File selectedImage;

    private Button createButton;
    private Button cancelButton;
    private BorderPane parentRoot;
    private MusicCardActionManager musicActions;
    private Node closeIcon;
    private double dragOffsetX;
    private double dragOffsetY;
    private Consumer<Playlist> onPlaylistCreated = playlist -> {};

    public void init(StartUpService svc, Stage dialogStage) {
        init(svc, dialogStage, null, null);
    }

    public void init(StartUpService svc,
                     Stage dialogStage,
                     BorderPane parentRoot,
                     MusicCardActionManager musicActions) {
        initBase(svc, dialogStage);
        this.parentRoot = parentRoot;
        this.musicActions = musicActions;

        shellController.setTitle("Create playlist");
        shellController.setSubtitle("");
        installCloseButton();
        installDragHandling();

        loadFormContent();
        installInlineErrorIcon();
        installTitleValidationReset();
        setDefaultImage(formController.playlistImage, DEFAULT_PLAYLIST_IMAGE);
        installCoverPicker();

        cancelButton = new Button("Cancel");
        cancelButton.getStyleClass().addAll("dialog-button", "secondary-button");
        cancelButton.setOnAction(e -> onCancel());

        createButton = new Button("Save");
        createButton.getStyleClass().addAll("dialog-button", "primary-button");
        createButton.setOnAction(e -> onCreate());

        shellController.setActions(cancelButton, createButton);
        DialogKeyboardSupport.install(dialogStage, root, createButton);
    }

    public void setOnPlaylistCreated(Consumer<Playlist> onPlaylistCreated) {
        this.onPlaylistCreated = onPlaylistCreated == null ? playlist -> {} : onPlaylistCreated;
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
            throw new RuntimeException("Could not load create playlist dialog content.", ex);
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
            formController.playlistCoverMoreButton.setVisible(false);
            formController.playlistCoverMoreButton.setManaged(false);
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
        setCoverHover(false);
    }

    private void setCoverHover(boolean visible) {
        if (formController == null) return;
        if (formController.playlistCoverHoverOverlay != null) {
            formController.playlistCoverHoverOverlay.setVisible(visible);
        }
        if (formController.playlistCoverMoreButton != null) {
            formController.playlistCoverMoreButton.setVisible(false);
        }
    }

    private void chooseCover() {
        pickImage(dialogStage, file -> {
            selectedImage = file;
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

    @FXML
    private void onCancel() {
        closeDialog();
    }

    @FXML
    private void onCreate() {
        String title = normalize(formController.titleField.getText());
        String description = normalize(formController.descriptionArea.getText());

        if (title.isBlank()) {
            showTitleError("Playlist name is required.");
            return;
        }

        if (playlistNameExists(title)) {
            showTitleError("A playlist with this name already exists.");
            return;
        }

        clearTitleError();
        setFormDisabled(true);
        Image uiImage = formController.playlistImage.getImage();

        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        byte[] coverBytes = selectedImage != null ? readFileBytes(selectedImage) : null;
                        return playlistDialogService.createPlaylist(
                                title,
                                description,
                                uiImage,
                                coverBytes
                        );
                    } catch (Exception ex) {
                        throw new CompletionException(ex);
                    }
                })
                .thenAccept(created -> runFx(() -> {
                    try {
                        hydrateCreatedPlaylistForImmediateView(created);

                        if (svc != null && svc.getPlaylists().stream().noneMatch(p -> p.getId() == created.getId())) {
                            svc.getPlaylists().add(0, created);
                        }

                        onPlaylistCreated.accept(created);
                        closeDialog();
                        openPlaylistScene(created);
                    } finally {
                        setFormDisabled(false);
                    }
                }))
                .exceptionally(ex -> {
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

    private void installDragHandling() {
        if (root == null || dialogStage == null) return;

        root.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() != MouseButton.PRIMARY || isNonDraggableTarget(event.getTarget())) return;
            dragOffsetX = event.getScreenX() - dialogStage.getX();
            dragOffsetY = event.getScreenY() - dialogStage.getY();
            event.consume();
        });

        root.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (!event.isPrimaryButtonDown() || isNonDraggableTarget(event.getTarget())) return;
            dialogStage.setX(event.getScreenX() - dragOffsetX);
            dialogStage.setY(event.getScreenY() - dragOffsetY);
            event.consume();
        });
    }

    private boolean isNonDraggableTarget(Object target) {
        if (!(target instanceof Node node)) return false;

        Node current = node;
        while (current != null) {
            if (current instanceof Button || current instanceof TextInputControl || current instanceof ImageView) {
                return true;
            }
            current = current.getParent();
        }
        return false;
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
        return svc.getPlaylists().stream()
                .filter(p -> p != null && p.getTitle() != null)
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

    private void openPlaylistScene(Playlist playlist) {
        try {
            BorderPane root = resolveParentRoot();
            if (root == null) {
                throw new IllegalStateException("Main container was not found.");
            }

            PlayerMenuNavigator navigator = new PlayerMenuNavigator(svc, resolveMusicActions());
            navigator.openPlayerMenu(playlist, PlayerMenuContext.ContentType.PLAYLIST, root);
        } catch (RuntimeException ex) {
            showTitleError("Playlist was created, but could not be opened.");
        }
    }

    private void hydrateCreatedPlaylistForImmediateView(Playlist playlist) {
        if (playlist == null) return;
        if (playlist.getSongList() == null) {
            playlist.setSongList(FXCollections.observableArrayList());
        }
    }

    private BorderPane resolveParentRoot() {
        if (parentRoot != null) return parentRoot;
        try {
            if (dialogStage != null
                    && dialogStage.getOwner() != null
                    && dialogStage.getOwner().getScene() != null
                    && dialogStage.getOwner().getScene().getRoot() instanceof BorderPane root) {
                return root;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private MusicCardActionManager resolveMusicActions() {
        if (musicActions != null) return musicActions;
        PlayerMenuNavigator navigator = new PlayerMenuNavigator(svc);
        ArtistOpenCoordinator artistCoordinator = new ArtistOpenCoordinator(svc, navigator);
        musicActions = new MusicCardActionManager(svc, navigator, artistCoordinator);
        return musicActions;
    }

    private void setFormDisabled(boolean disabled) {
        if (createButton != null) createButton.setDisable(disabled);
        if (cancelButton != null) cancelButton.setDisable(disabled);
        if (closeButton != null) closeButton.setDisable(disabled);
        if (formController != null) {
            formController.titleField.setDisable(disabled);
            formController.descriptionArea.setDisable(disabled);
            formController.playlistImage.setDisable(disabled);
        }
    }

    private String resolveErrorMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        if (message != null && message.toLowerCase().contains("playlist")) {
            return "A playlist with this name already exists.";
        }
        return "Could not create playlist. Try again.";
    }
}
