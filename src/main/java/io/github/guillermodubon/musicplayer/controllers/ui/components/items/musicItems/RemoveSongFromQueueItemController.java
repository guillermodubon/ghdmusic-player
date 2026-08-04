package io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems;

import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.layout.HBox;
import io.github.guillermodubon.musicplayer.controllers.ui.components.icons.SvgIconFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.base.BaseSongCellController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.services.QueueSongItemHoverSupport;
import io.github.guillermodubon.musicplayer.controllers.ui.components.popups.SmallPopupTooltip;
import io.github.guillermodubon.musicplayer.models.Song;

import java.util.function.Consumer;

public class RemoveSongFromQueueItemController extends BaseSongCellController {

    private static final String ICON_REMOVE = "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/close_27dp_FAFAFA_FILL0_wght400_GRAD0_opsz24.svg";
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

    @FXML private Button btnRemove;
    @FXML private HBox rootBox;

    private Runnable onRemove;
    private Consumer<Song> onPlaySong;
    private boolean deferPlayUntilRelease;

    @FXML
    public void initialize() {
        installIconButton(btnRemove, ICON_REMOVE, "Remove this song from queue", 20);
        SmallPopupTooltip.install(btnRemove, "Remove this song from queue");
        configureTitleMarquee(rootBox);
        configureArtistMarquee(rootBox);
        QueueSongItemHoverSupport.install(rootBox, () -> false);
    }

    public void init(Song song, Consumer<Song> onPlaySong, Runnable onRemove) {
        this.song = song;
        this.onPlaySong = onPlaySong;
        bindSongBasics(song);
        setOnRemove(onRemove);
        if (deferPlayUntilRelease) {
            bindPlayActionOnRelease(rootBox, () -> removeThenPlay(song));
        } else {
            bindSongPlayAction(rootBox, this::removeThenPlay);
        }
    }

    /** Prevents the row click from firing before a queue drag is completed. */
    public void setDeferPlayUntilRelease(boolean deferPlayUntilRelease) {
        this.deferPlayUntilRelease = deferPlayUntilRelease;
    }

    public void setOnRemove(Runnable onRemove) {
        this.onRemove = onRemove;
        btnRemove.setOnAction(e -> {
            if (this.onRemove != null) this.onRemove.run();
        });
    }

    private void removeThenPlay(Song songToPlay) {
        if (btnRemove != null) {
            // Reuse the button's existing action, keeping one removal flow.
            btnRemove.fire();
        }
        if (songToPlay != null && onPlaySong != null) {
            onPlaySong.accept(songToPlay);
        }
    }

    private Node installIconButton(Button button, String iconPath, String accessibleText, double size) {
        if (button == null) return null;
        button.setText("");
        button.setAccessibleText(accessibleText);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setStyle(ICON_BUTTON_CHROMELESS_STYLE);
        Node icon = SvgIconFactory.icon(iconPath, size);
        SvgIconFactory.setIconColor(icon, ICON_NORMAL);
        button.setGraphic(icon);
        button.hoverProperty().addListener((obs, oldValue, isHover) -> updateIconColor(button, icon));
        button.focusedProperty().addListener((obs, oldValue, isFocused) -> updateIconColor(button, icon));
        button.armedProperty().addListener((obs, oldValue, isArmed) -> button.setStyle(ICON_BUTTON_CHROMELESS_STYLE));
        button.pressedProperty().addListener((obs, oldValue, isPressed) -> button.setStyle(ICON_BUTTON_CHROMELESS_STYLE));
        return icon;
    }

    private void updateIconColor(Button button, Node icon) {
        if (icon == null) return;
        boolean highlighted = button != null && (button.isHover() || button.isFocused());
        SvgIconFactory.setIconColor(icon, highlighted ? ICON_HOVER : ICON_NORMAL);
    }
}
