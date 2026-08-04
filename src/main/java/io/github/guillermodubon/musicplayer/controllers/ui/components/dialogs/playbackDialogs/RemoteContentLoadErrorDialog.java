package io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playbackDialogs;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.base.DialogKeyboardSupport;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.base.DialogShellController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playlistManagementDialogs.PlaylistDialogWindowSupport;
import io.github.guillermodubon.musicplayer.controllers.ui.components.icons.SvgIconFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/** Dialog shown when a fully remote album, single, or playlist cannot be opened. */
public final class RemoteContentLoadErrorDialog {

    public enum ContentKind {
        ALBUM("album"),
        SINGLE("single"),
        PLAYLIST("playlist");

        private final String label;

        ContentKind(String label) {
            this.label = label;
        }
    }

    private static final String WIFI_OFF_ICON =
            "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/wifi_off_27dp_AFAFAF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final AtomicReference<Stage> ACTIVE_DIALOG = new AtomicReference<>();

    private RemoteContentLoadErrorDialog() {
    }

    public static void show(ContentKind kind, String title, Node probe) {
        Window owner = probe != null && probe.getScene() != null ? probe.getScene().getWindow() : null;
        show(kind, title, owner);
    }

    public static void show(ContentKind kind, String title, Window owner) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> show(kind, title, owner));
            return;
        }

        Stage current = ACTIVE_DIALOG.get();
        if (current != null && current.isShowing()) {
            ACTIVE_DIALOG.compareAndSet(current, null);
            current.close();
        }

        try {
            FXMLLoader loader = new FXMLLoader(RemoteContentLoadErrorDialog.class.getResource(
                    "/io/github/guillermodubon/musicplayer/Views/components/dialogs/base/DialogShell.fxml"
            ));
            Parent shell = loader.load();
            DialogShellController shellController = loader.getController();

            AnchorPane root = new AnchorPane(shell);
            root.getStyleClass().add("remote-content-error-dialog-root");
            root.getStylesheets().add(RemoteContentLoadErrorDialog.class.getResource(
                    "/io/github/guillermodubon/musicplayer/Views/components/dialogs/playbackDialogs/RemoteContentLoadErrorDialog.css"
            ).toExternalForm());
            AnchorPane.setTopAnchor(shell, 0.0);
            AnchorPane.setRightAnchor(shell, 0.0);
            AnchorPane.setBottomAnchor(shell, 0.0);
            AnchorPane.setLeftAnchor(shell, 0.0);

            Stage stage = new Stage();
            stage.initModality(Modality.APPLICATION_MODAL);
            Window effectiveOwner = owner == null ? findActiveWindow() : owner;
            if (effectiveOwner != null) stage.initOwner(effectiveOwner);
            stage.initStyle(StageStyle.TRANSPARENT);

            ContentKind safeKind = kind == null ? ContentKind.ALBUM : kind;
            shellController.setTitle("Unable to open " + safeKind.label);
            shellController.setSubtitle("The content could not be loaded right now.");
            shellController.setContent(createContent(safeKind, title));

            Button close = new Button("Accept");
            close.getStyleClass().addAll("dialog-button", "secondary-button");
            close.setOnAction(event -> stage.close());
            shellController.setActions(close);

            Scene scene = new Scene(root);
            scene.setFill(Color.TRANSPARENT);
            stage.setScene(scene);
            configureDialogSize(stage, root, effectiveOwner);
            PlaylistDialogWindowSupport.installDragHandling(stage, root);
            DialogKeyboardSupport.install(stage, root, close);
            stage.setOnHidden(event -> ACTIVE_DIALOG.compareAndSet(stage, null));
            ACTIVE_DIALOG.set(stage);
            stage.show();
        } catch (IOException | RuntimeException error) {
            ACTIVE_DIALOG.set(null);
            error.printStackTrace();
        }
    }

    private static VBox createContent(ContentKind kind, String title) {
        Node icon = SvgIconFactory.icon(WIFI_OFF_ICON, 42);

        String name = title == null || title.isBlank() ? "this " + kind.label : title.trim();
        Label message = new Label("We could not open " + name + " because an unexpected error occurred. "
                + "Please check your internet connection and try again.");
        message.getStyleClass().add("remote-content-error-message");
        message.setWrapText(true);
        message.setMaxWidth(520);
        message.setMinHeight(Region.USE_PREF_SIZE);

        VBox content = new VBox(14, icon, message);
        content.getStyleClass().add("remote-content-error-content");
        return content;
    }

    private static void configureDialogSize(Stage stage, Region root, Window owner) {
        if (stage == null || root == null) return;

        var bounds = owner == null
                ? Screen.getPrimary().getVisualBounds()
                : Screen.getScreensForRectangle(
                        owner.getX(),
                        owner.getY(),
                        Math.max(1, owner.getWidth()),
                        Math.max(1, owner.getHeight()))
                .stream()
                .findFirst()
                .orElse(Screen.getPrimary())
                .getVisualBounds();

        double width = Math.min(560, Math.max(440, bounds.getWidth() * 0.38));
        double height = Math.min(390, Math.max(330, bounds.getHeight() * 0.40));

        stage.setResizable(false);
        stage.setWidth(width);
        stage.setHeight(height);
        root.setMinSize(width, height);
        root.setPrefSize(width, height);
        root.setMaxSize(width, height);

        stage.setOnShown(event -> {
            stage.setWidth(width);
            stage.setHeight(height);
            stage.setX(bounds.getMinX() + (bounds.getWidth() - width) / 2.0);
            stage.setY(bounds.getMinY() + (bounds.getHeight() - height) / 2.0);
        });
    }

    private static Window findActiveWindow() {
        return Window.getWindows().stream()
                .filter(Window::isShowing)
                .findFirst()
                .orElse(null);
    }
}
