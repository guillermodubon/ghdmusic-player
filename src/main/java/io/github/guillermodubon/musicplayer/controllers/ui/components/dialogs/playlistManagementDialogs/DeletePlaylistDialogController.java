package io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistManagementDialogs;

import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import io.github.guillermodubon.musicplayer.repository.dao.playlist.PlaylistDao;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.base.BaseDialogController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.base.DialogKeyboardSupport;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.base.DialogShellController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.icons.SvgIconFactory;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.services.PlaylistDeletionService;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class DeletePlaylistDialogController extends BaseDialogController {

    private static final String ICON_CLOSE = "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/close_27dp_FAFAFA_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_NORMAL = "#DCDCDC";
    private static final String ICON_HOVER = "#FFFFFF";
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

    private final PlaylistDeletionService playlistDeletionService = new PlaylistDeletionService();

    private Playlist playlist;
    private PlaylistDao playlistDao;
    private Runnable onDeleted = () -> {};

    private Button cancelButton;
    private Button deleteButton;
    private Node closeIcon;

    public void initForDelete(StartUpService svc,
                              Stage dialogStage,
                              Playlist playlist,
                              PlaylistDao playlistDao,
                              Runnable onDeleted) {
        initBase(svc, dialogStage);
        this.playlist = playlist;
        this.playlistDao = playlistDao;
        this.onDeleted = onDeleted == null ? () -> {} : onDeleted;

        shellController.setTitle("Delete from your library?");
        shellController.setSubtitle("");
        shellController.setContent(createContent());
        installCloseButton();
        PlaylistDialogWindowSupport.installDragHandling(dialogStage, root);

        cancelButton = new Button("Cancel");
        cancelButton.getStyleClass().addAll("dialog-button", "secondary-button");
        cancelButton.setOnAction(e -> onCancel());

        deleteButton = new Button("Delete");
        deleteButton.getStyleClass().addAll("dialog-button", "danger-button");
        deleteButton.setOnAction(e -> onDelete());

        shellController.setActions(cancelButton, deleteButton);
        DialogKeyboardSupport.install(dialogStage, root, deleteButton);
    }

    @FXML
    private void onCancel() {
        closeDialog();
    }

    private VBox createContent() {
        String playlistName = Optional.ofNullable(playlist)
                .map(Playlist::getTitle)
                .filter(title -> !title.isBlank())
                .orElse("This playlist");

        Text name = new Text(playlistName);
        name.getStyleClass().add("delete-playlist-message-highlight");

        Text middle = new Text(" will be deleted from your ");
        middle.getStyleClass().add("delete-playlist-message-text");

        Text library = new Text("library");
        library.getStyleClass().add("delete-playlist-message-highlight");

        TextFlow message = new TextFlow(name, middle, library);
        message.getStyleClass().add("delete-playlist-message");

        VBox body = new VBox(message);
        body.getStyleClass().add("delete-playlist-content");
        return body;
    }

    private void onDelete() {
        setBusy(true);

        CompletableFuture
                .runAsync(() -> {
                    try {
                        playlistDeletionService.deleteFromDatabase(playlist, playlistDao, svc);
                    } catch (Exception ex) {
                        throw new CompletionException(ex);
                    }
                })
                .thenRun(() -> runFx(() -> {
                    try {
                        playlistDeletionService.removeFromMemory(playlist, svc);
                        closeDialog();
                        onDeleted.run();
                    } finally {
                        setBusy(false);
                    }
                }))
                .exceptionally(ex -> {
                    runFx(() -> {
                        showError(resolveErrorMessage(ex));
                        setBusy(false);
                    });
                    return null;
                });
    }

    private void installCloseButton() {
        if (closeButton == null) return;
        closeButton.setText("");
        closeButton.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        closeButton.setStyle(ICON_BUTTON_CHROMELESS_STYLE);
        closeIcon = SvgIconFactory.icon(ICON_CLOSE, 20);
        SvgIconFactory.setIconColor(closeIcon, ICON_NORMAL);
        closeButton.setGraphic(closeIcon);
        closeButton.hoverProperty().addListener((obs, oldValue, isHover) -> updateCloseIconColor());
        closeButton.focusedProperty().addListener((obs, oldValue, isFocused) -> updateCloseIconColor());
    }

    private void updateCloseIconColor() {
        if (closeIcon == null || closeButton == null) return;
        SvgIconFactory.setIconColor(closeIcon, closeButton.isHover() || closeButton.isFocused() ? ICON_HOVER : ICON_NORMAL);
    }

    private void setBusy(boolean busy) {
        if (cancelButton != null) cancelButton.setDisable(busy);
        if (deleteButton != null) deleteButton.setDisable(busy);
        if (closeButton != null) closeButton.setDisable(busy);
    }

    private String resolveErrorMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() != null ? current.getMessage() : "Could not delete playlist. Try again.";
    }
}
