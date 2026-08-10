package io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.helpers;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Tooltip;
import io.github.guillermodubon.musicplayer.controllers.ui.components.icons.SvgIconFactory;
import io.github.guillermodubon.musicplayer.controllers.ui.components.popups.SmallPopupTooltip;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.models.lyrics.SongLyrics;
import io.github.guillermodubon.musicplayer.services.lyrics.LyricsPlaybackResolver;

/** Keeps a lyrics action button synchronized with the currently playing song. */
public final class PlayerLyricsButtonSupport {

    private static final String TRANSPARENT_BUTTON_STYLE = """
            -fx-background-color: transparent;
            -fx-background-insets: 0;
            -fx-border-color: transparent;
            -fx-border-width: 0;
            -fx-effect: null;
            -fx-focus-color: transparent;
            -fx-faint-focus-color: transparent;
            -fx-padding: 0;
            """;

    private static final String LYRICS_ICON =
            "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/"
                    + "mic_external_on_27dp_AFAFAF_FILL0_wght400_GRAD0_opsz24.svg";
    private static final String ICON_NORMAL = "#AFAFAF";
    private static final String ICON_HOVER = "#FFFFFF";
    private static final String ICON_DISABLED = "#666666";
    private static final String NO_LYRICS_TOOLTIP =
            "There are no lyrics available for this song.";

    private final Button button;
    private final Node tooltipHost;
    private final LyricsPlaybackResolver resolver;
    private final Runnable onOpen;
    private Tooltip lyricsTooltip;
    private Node tooltipOwner;
    private Node icon;
    private ChangeListener<Boolean> hoverListener;
    private ChangeListener<Boolean> focusListener;
    private long updateToken;
    private boolean suppressed;

    public PlayerLyricsButtonSupport(Button button, Runnable onOpen) {
        this(button, null, onOpen);
    }

    public PlayerLyricsButtonSupport(Button button, Node tooltipHost, Runnable onOpen) {
        this.button = button;
        this.tooltipHost = tooltipHost;
        this.resolver = LyricsPlaybackResolver.getInstance();
        this.onOpen = onOpen == null ? () -> { } : onOpen;
        configure();
    }

    public void configure() {
        if (button == null) return;
        button.setText("");
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setFocusTraversable(false);
        button.setStyle(TRANSPARENT_BUTTON_STYLE);
        button.setCursor(Cursor.HAND);
        icon = SvgIconFactory.icon(LYRICS_ICON, 22.0);
        button.setGraphic(icon);
        hoverListener = (obs, oldValue, newValue) -> updateVisualState();
        focusListener = (obs, oldValue, newValue) -> updateVisualState();
        button.hoverProperty().addListener(hoverListener);
        button.focusedProperty().addListener(focusListener);
        button.setOnAction(event -> onOpen.run());
        updateTooltip(false);
        setAvailable(false);
    }

    public void updateSong(Song song) {
        long token = ++updateToken;
        if (suppressed) {
            setAvailable(false);
            return;
        }
        setAvailable(false);
        if (song == null) return;

        SongLyrics current = song.getLyrics();
        if (current != null && current.isAvailable()) {
            setAvailable(true);
            return;
        }

        resolver.resolve(song).thenAccept(lyrics -> Platform.runLater(() -> {
            if (token != updateToken) return;
            setAvailable(lyrics != null && lyrics.isAvailable());
        }));
    }

    public void setSuppressed(boolean suppressed) {
        this.suppressed = suppressed;
        updateToken++;
        setAvailable(false);
    }

    public void dispose() {
        updateToken++;
        if (lyricsTooltip != null && tooltipOwner != null) {
            Tooltip.uninstall(tooltipOwner, lyricsTooltip);
            lyricsTooltip = null;
            tooltipOwner = null;
        }
        if (button != null) {
            button.setOnAction(null);
            if (hoverListener != null) button.hoverProperty().removeListener(hoverListener);
            if (focusListener != null) button.focusedProperty().removeListener(focusListener);
            hoverListener = null;
            focusListener = null;
        }
    }

    private void setAvailable(boolean available) {
        if (button == null) return;
        boolean hidden = suppressed;
        boolean visible = !hidden && (available || tooltipHost != null);
        button.setDisable(hidden || !available);
        button.setVisible(visible);
        button.setManaged(visible);
        if (tooltipHost != null) {
            tooltipHost.setVisible(visible);
            tooltipHost.setManaged(visible);
        }
        if (visible) {
            updateTooltip(available);
        }
        updateVisualState();
    }

    private void updateTooltip(boolean available) {
        Node owner = available || tooltipHost == null ? button : tooltipHost;
        if (owner == null || owner == tooltipOwner && lyricsTooltip != null) return;

        if (lyricsTooltip != null && tooltipOwner != null) {
            Tooltip.uninstall(tooltipOwner, lyricsTooltip);
        }
        lyricsTooltip = SmallPopupTooltip.install(
                owner,
                available ? "Lyrics" : NO_LYRICS_TOOLTIP
        );
        tooltipOwner = owner;
    }

    private void updateVisualState() {
        if (button == null || icon == null) return;
        String color = button.isDisabled()
                ? ICON_DISABLED
                : (button.isHover() || button.isFocused() ? ICON_HOVER : ICON_NORMAL);
        SvgIconFactory.setIconColor(icon, color);
        button.setCursor(button.isDisabled() ? Cursor.DEFAULT : Cursor.HAND);
        button.setOpacity(button.isDisabled() ? 0.46 : 1.0);
    }
}
