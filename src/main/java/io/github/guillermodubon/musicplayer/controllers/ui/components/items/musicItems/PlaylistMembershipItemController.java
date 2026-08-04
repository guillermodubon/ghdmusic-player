package io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems;

import javafx.fxml.FXML;
import javafx.application.Platform;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;

import java.util.Optional;
import java.util.function.BiConsumer;

public class PlaylistMembershipItemController {

    @FXML private ImageView coverView;
    @FXML private Label titleLabel;
    @FXML private CheckBox membershipCheckBox;

    private boolean suppressSelectionChange;

    public void init(Playlist playlist,
                     boolean selected,
                     BiConsumer<Boolean, Boolean> onSelectionChanged) {
        titleLabel.setText(resolveTitle(playlist));

        setCoverOrFallback(MediaImageResolver.playlistCover(playlist, 42, 42));

        setSelectedSilently(selected);
        membershipCheckBox.selectedProperty().addListener((obs, oldValue, newValue) -> {
            if (suppressSelectionChange) return;
            if (onSelectionChanged != null) {
                onSelectionChanged.accept(oldValue, newValue);
            }
        });
    }

    public void setSelectedSilently(boolean selected) {
        suppressSelectionChange = true;
        try {
            membershipCheckBox.setSelected(selected);
        } finally {
            suppressSelectionChange = false;
        }
    }

    public void setBusy(boolean busy) {
        membershipCheckBox.setDisable(busy);
    }

    public CheckBox getCheckBox() {
        return membershipCheckBox;
    }

    private String resolveTitle(Playlist playlist) {
        return Optional.ofNullable(playlist)
                .map(Playlist::getTitle)
                .map(String::trim)
                .filter(title -> !title.isBlank())
                .orElse("Untitled playlist");
    }

    private void setCoverOrFallback(Image image) {
        if (coverView == null) return;
        Image fallback = MediaImageResolver.defaultCover(42, 42);
        if (image == null || image.isError()) {
            coverView.setImage(fallback);
            return;
        }

        coverView.setImage(image);
        image.errorProperty().addListener((obs, wasError, isError) -> {
            if (Boolean.TRUE.equals(isError)) {
                Platform.runLater(() -> {
                    if (coverView != null) coverView.setImage(fallback);
                });
            }
        });
        if (image.isError()) {
            coverView.setImage(fallback);
        }
    }
}
