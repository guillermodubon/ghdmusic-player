package io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.base;

import javafx.application.Platform;
import javafx.scene.CacheHint;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

public abstract class BaseDialogController {

    private static final double MIN_DIALOG_IMAGE_DECODE_SIZE = 640.0;

    protected StartUpService svc;
    protected Stage dialogStage;

    protected void initBase(StartUpService svc, Stage dialogStage) {
        this.svc = Objects.requireNonNull(svc, "svc");
        this.dialogStage = dialogStage;
    }

    protected void closeDialog() {
        if (dialogStage != null) {
            dialogStage.close();
        }
    }

    protected void showError(String message) {
        Platform.runLater(() ->
                new Alert(Alert.AlertType.ERROR, message, ButtonType.OK).showAndWait()
        );
    }

    protected void showInfo(String message) {
        Platform.runLater(() ->
                new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK).showAndWait()
        );
    }

    protected String normalize(String value) {
        return Optional.ofNullable(value).orElse("").trim();
    }

    protected byte[] readFileBytes(File file) throws IOException {
        return file == null ? null : Files.readAllBytes(file.toPath());
    }

    protected void setDefaultImage(ImageView imageView, String resourcePath) {
        if (imageView == null || resourcePath == null || resourcePath.isBlank()) return;

        double visibleSize = Math.max(imageView.getFitWidth(), imageView.getFitHeight());
        double requestedSize = visibleSize > 0
                ? Math.max(MIN_DIALOG_IMAGE_DECODE_SIZE, Math.ceil(visibleSize * 2.0))
                : 0;
        Image image = MediaImageResolver.resourceImage(
                resourcePath,
                requestedSize,
                requestedSize
        );
        if (image == null || image.isError()) return;

        imageView.setImage(image);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        imageView.setCache(true);
        imageView.setCacheHint(CacheHint.QUALITY);
        imageView.setScaleX(1.0);
        imageView.setScaleY(1.0);
    }

    protected void pickImage(Stage owner, Consumer<File> onSelected) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select image");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg")
        );
        File file = fc.showOpenDialog(owner);
        if (file != null) {
            onSelected.accept(file);
        }
    }

    protected void runFx(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

}
