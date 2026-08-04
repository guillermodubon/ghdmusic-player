package io.github.guillermodubon.musicplayer.controllers.ui.components.items.musicItems.services;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.scene.Parent;
import javafx.scene.Node;
import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.application.Platform;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import javafx.event.EventHandler;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.preview.PreviewBarController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.dialogs.playbackDialogs.PreviewUnavailableDialog;
import io.github.guillermodubon.musicplayer.services.navigation.PlayerMenuNavigator;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.ArtistOpenCoordinator;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.models.Song;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javafx.fxml.FXMLLoader;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;


public class PreviewService {

    private static final Object PREVIEW_LOCK = new Object();
    private static final AtomicLong REQUEST_SEQUENCE = new AtomicLong();
    private static final Set<Long> SUPPRESS_MAIN_RESUME = ConcurrentHashMap.newKeySet();

    private static long activeRequestId = -1L;
    private static long activeTrackId = -1L;
    private static long activePreviewRequestId = -1L;
    private static Stage activePreviewWindow;
    private static PreviewBarController activePreviewController;
    private static MediaPlayer mainPlayerToResume;
    private static boolean shouldResumeMainPlayer;

    public void handlePreview(Song song,
                              String artistsText,
                              Node ownerNode,
                              StartUpService svc,
                              SongCoverResolver coverResolver) {
        if (song == null || ownerNode == null) return;

        long trackId = song.getSongID();
        if (trackId <= 0) {
            PreviewUnavailableDialog.show(song, ownerNode);
            return;
        }
        long requestId = registerPreviewRequest(trackId);
        if (requestId < 0) return;

        new Thread(() -> {
            try {
                String previewUrl = resolvePreviewUrl(trackId);
                if (previewUrl == null || previewUrl.isBlank()) {
                    boolean shouldShowUnavailableDialog = clearPendingAndClaimUnavailable(requestId);
                    if (shouldShowUnavailableDialog) {
                        Platform.runLater(() -> {
                            if (REQUEST_SEQUENCE.get() == requestId) {
                                PreviewUnavailableDialog.show(song, ownerNode);
                            }
                        });
                    }
                    return;
                }

                // Resolve the preview artwork off the JavaFX thread at the
                // resolution intended for the larger window.
                Image cover = coverResolver != null
                        ? coverResolver.resolvePreviewCover(song)
                        : null;

                Platform.runLater(() -> {
                    try {
                        if (!isCurrentRequest(requestId)) return;

                        PlaybackManager pm = PlaybackManager.getInstance();
                        MediaPlayer mainPlayer = pm.getCurrentPlayer();
                        boolean mainWasPlaying = mainPlayer != null
                                && mainPlayer.getStatus() == MediaPlayer.Status.PLAYING;

                        if (mainWasPlaying) {
                            mainPlayer.pause();
                        }
                        rememberMainPlayerForResume(mainPlayer, mainWasPlaying);

                        FXMLLoader loader = new FXMLLoader(
                                getClass().getResource("/io/github/guillermodubon/musicplayer/Views/components/layoutComponents/playerMenuBar/preview/PreviewBar.fxml")
                        );
                        Parent content = loader.load();
                        PreviewBarController ctrl = loader.getController();

                        String title = song.getTitle() == null ? "" : song.getTitle();
                        String finalArtists = normalizeArtistsText(song, artistsText);

                        ctrl.setInfo(title, finalArtists, cover, artistName -> openArtistFromPreview(artistName, ownerNode, svc));

                        Media media = new Media(previewUrl);
                        MediaPlayer previewPlayer = new MediaPlayer(media);
                        ctrl.bindPlayer(previewPlayer, pm.getLastVolume());

                        Stage previewWindow = createPreviewWindow(content, ownerNode);
                        installPreviewKeyboardHandlers(previewWindow, ctrl);
                        ctrl.attachDragTarget(previewWindow);

                        previewWindow.setOnHidden(evt -> {
                            try {
                                ctrl.dispose();
                            } catch (Exception ignored) {
                            }
                            MediaPlayer playerToResume = takeMainPlayerForResume(requestId);
                            if (playerToResume != null) {
                                playerToResume.play();
                            }
                            clearActiveIfMatches(requestId, previewWindow);
                        });

                        if (!markPreviewActive(requestId, previewWindow, ctrl)) {
                            ctrl.dispose();
                            MediaPlayer playerToResume = takeMainPlayerForResume(requestId);
                            if (playerToResume != null) {
                                playerToResume.play();
                            }
                            return;
                        }

                        try {
                            showPreviewWindow(previewWindow, ownerNode, content);
                        } catch (Exception showEx) {
                            clearActiveIfMatches(requestId, previewWindow);
                            ctrl.dispose();
                            MediaPlayer playerToResume = takeMainPlayerForResume(requestId);
                            if (playerToResume != null) {
                                playerToResume.play();
                            }
                            throw showEx;
                        }
                    } catch (Exception ex) {
                        clearPendingIfCurrent(requestId);
                        ex.printStackTrace();
                    }
                });
            } catch (Exception ex) {
                clearPendingIfCurrent(requestId);
                ex.printStackTrace();
            }
        }, "preview-fetch-" + trackId).start();
    }

    public static boolean isPreviewVisible() {
        Stage preview;
        synchronized (PREVIEW_LOCK) {
            preview = activePreviewWindow;
        }
        return preview != null && preview.isShowing();
    }

    public static boolean handleActivePreviewShortcut(KeyEvent event) {
        PreviewBarController controller;
        synchronized (PREVIEW_LOCK) {
            controller = activePreviewController;
        }

        if (controller == null || !isPreviewVisible()) {
            return false;
        }

        return controller.handleGlobalKeyboardShortcut(event);
    }

    public static boolean closeActivePreview() {
        Stage preview;
        synchronized (PREVIEW_LOCK) {
            preview = activePreviewWindow;
        }

        if (preview == null || !preview.isShowing()) {
            return false;
        }

        if (Platform.isFxApplicationThread()) {
            preview.hide();
        } else {
            Platform.runLater(preview::hide);
        }
        return true;
    }

    private long registerPreviewRequest(long trackId) {
        Stage previousPreview = null;
        long previousPreviewRequestId = -1L;
        boolean closeAsToggle = false;
        long newRequestId = -1L;

        synchronized (PREVIEW_LOCK) {
            if (activeTrackId == trackId) {
                closeAsToggle = true;
                previousPreview = activePreviewWindow;
                previousPreviewRequestId = activePreviewRequestId;
                newRequestId = REQUEST_SEQUENCE.incrementAndGet();
                activeRequestId = newRequestId;
                activeTrackId = -1L;
                activePreviewWindow = null;
                activePreviewController = null;
                activePreviewRequestId = -1L;
            } else {
                previousPreview = activePreviewWindow;
                previousPreviewRequestId = activePreviewRequestId;
                newRequestId = REQUEST_SEQUENCE.incrementAndGet();
                activeRequestId = newRequestId;
                activeTrackId = trackId;
                activePreviewWindow = null;
                activePreviewController = null;
                activePreviewRequestId = -1L;
            }
        }

        if (previousPreview != null) {
            if (!closeAsToggle && previousPreviewRequestId > 0) {
                SUPPRESS_MAIN_RESUME.add(previousPreviewRequestId);
            }
            hidePreviewWindow(previousPreview);
        }

        return closeAsToggle ? -1L : newRequestId;
    }

    private void hidePreviewWindow(Stage previewWindow) {
        if (previewWindow == null) return;
        if (Platform.isFxApplicationThread()) {
            previewWindow.hide();
        } else {
            Platform.runLater(previewWindow::hide);
        }
    }

    private void openArtistFromPreview(String artistName, Node ownerNode, StartUpService svc) {
        if (artistName == null || artistName.isBlank() || ownerNode == null || svc == null) return;
        try {
            PlayerMenuNavigator navigator = new PlayerMenuNavigator(svc);
            ArtistOpenCoordinator artistCoordinator = new ArtistOpenCoordinator(svc, navigator);
            MusicCardActionManager actions = new MusicCardActionManager(svc, navigator, artistCoordinator);
            actions.artistNameClick(ownerNode).accept(artistName);
        } catch (Exception ignored) {
        }
    }

    private boolean isCurrentRequest(long requestId) {
        synchronized (PREVIEW_LOCK) {
            return activeRequestId == requestId;
        }
    }

    private void rememberMainPlayerForResume(MediaPlayer mainPlayer, boolean mainWasPlaying) {
        if (!mainWasPlaying || mainPlayer == null) return;
        synchronized (PREVIEW_LOCK) {
            mainPlayerToResume = mainPlayer;
            shouldResumeMainPlayer = true;
        }
    }

    private MediaPlayer takeMainPlayerForResume(long requestId) {
        synchronized (PREVIEW_LOCK) {
            if (SUPPRESS_MAIN_RESUME.remove(requestId)) {
                return null;
            }
            if (!shouldResumeMainPlayer || mainPlayerToResume == null) {
                return null;
            }
            MediaPlayer player = mainPlayerToResume;
            mainPlayerToResume = null;
            shouldResumeMainPlayer = false;
            return player;
        }
    }

    private boolean markPreviewActive(long requestId, Stage previewWindow, PreviewBarController controller) {
        synchronized (PREVIEW_LOCK) {
            if (activeRequestId != requestId) {
                return false;
            }
            activePreviewWindow = previewWindow;
            activePreviewController = controller;
            activePreviewRequestId = requestId;
            return true;
        }
    }

    private void clearActiveIfMatches(long requestId, Stage previewWindow) {
        synchronized (PREVIEW_LOCK) {
            if (activeRequestId != requestId || activePreviewWindow != previewWindow) return;
            activeTrackId = -1L;
            activePreviewWindow = null;
            activePreviewController = null;
            activePreviewRequestId = -1L;
            activeRequestId = -1L;
        }
    }

    private void clearPendingIfCurrent(long requestId) {
        MediaPlayer playerToResume = null;
        synchronized (PREVIEW_LOCK) {
            if (activeRequestId != requestId || activePreviewWindow != null) return;
            activeTrackId = -1L;
            activePreviewRequestId = -1L;
            activePreviewController = null;
            activeRequestId = -1L;
            if (shouldResumeMainPlayer && mainPlayerToResume != null) {
                playerToResume = mainPlayerToResume;
                mainPlayerToResume = null;
                shouldResumeMainPlayer = false;
            }
        }

        if (playerToResume != null) {
            MediaPlayer finalPlayerToResume = playerToResume;
            Platform.runLater(finalPlayerToResume::play);
        }
    }

    private boolean clearPendingAndClaimUnavailable(long requestId) {
        synchronized (PREVIEW_LOCK) {
            if (activeRequestId != requestId || activePreviewWindow != null) {
                return false;
            }
        }
        clearPendingIfCurrent(requestId);
        return true;
    }

    private String normalizeArtistsText(Song song, String artistsText) {
        if (artistsText != null && !artistsText.isBlank()) return artistsText;
        if (song == null || song.getArtist() == null) return "";
        return song.getArtist().stream()
                .map(Artist::getName)
                .filter(n -> n != null && !n.isBlank())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    private Stage createPreviewWindow(Parent content, Node ownerNode) {
        Stage stage = new Stage(StageStyle.TRANSPARENT);
        stage.setResizable(false);
        stage.setAlwaysOnTop(false);

        Window owner = ownerNode == null || ownerNode.getScene() == null
                ? null
                : ownerNode.getScene().getWindow();
        if (owner != null) {
            stage.initOwner(owner);
        }

        Scene scene = new Scene(content);
        scene.setFill(Color.TRANSPARENT);
        scene.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                stage.hide();
            }
        });
        stage.focusedProperty().addListener((obs, wasFocused, focused) -> {
            if (!focused && stage.isShowing()) {
                Platform.runLater(() -> {
                    if (!stage.isFocused() && stage.isShowing()) {
                        stage.hide();
                    }
                });
            }
        });
        stage.setScene(scene);
        return stage;
    }

    private void installPreviewKeyboardHandlers(Stage previewWindow, PreviewBarController controller) {
        if (previewWindow == null || previewWindow.getScene() == null || controller == null) return;

        previewWindow.getScene().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event == null || event.isConsumed()) return;

            if (event.getCode() == KeyCode.ESCAPE) {
                previewWindow.hide();
                event.consume();
                return;
            }

            if (controller.handleGlobalKeyboardShortcut(event)) {
                event.consume();
            }
        });
    }

    private void showPreviewWindow(Stage previewWindow, Node ownerNode, Parent content) {
        if (previewWindow == null || ownerNode == null) return;

        Bounds anchorBounds = ownerNode.localToScreen(ownerNode.getBoundsInLocal());
        if (anchorBounds == null) {
            previewWindow.show();
            return;
        }

        double prefWidth = 940;
        double prefHeight = 88;
        if (content instanceof javafx.scene.layout.Region region) {
            region.applyCss();
            prefWidth = Math.max(region.getMinWidth(), region.prefWidth(-1));
            prefHeight = Math.max(region.getMinHeight(), region.prefHeight(prefWidth));
        }

        Rectangle2D screen = Screen.getScreensForRectangle(
                        anchorBounds.getMinX(),
                        anchorBounds.getMinY(),
                        anchorBounds.getWidth(),
                        anchorBounds.getHeight()
                ).stream()
                .findFirst()
                .map(Screen::getVisualBounds)
                .orElse(Screen.getPrimary().getVisualBounds());

        double margin = 10.0;
        double x = anchorBounds.getMinX() + (anchorBounds.getWidth() / 2.0) - (prefWidth / 2.0);
        double y = anchorBounds.getMinY() - prefHeight - 8.0;
        if (y < screen.getMinY() + margin) {
            y = anchorBounds.getMaxY() + 8.0;
        }
        x = Math.max(screen.getMinX() + margin, Math.min(x, screen.getMaxX() - prefWidth - margin));
        y = Math.max(screen.getMinY() + margin, Math.min(y, screen.getMaxY() - prefHeight - margin));

        previewWindow.setX(x);
        previewWindow.setY(y);
        previewWindow.show();
        installOwnerOutsideCloseHandler(previewWindow, ownerNode);
        previewWindow.requestFocus();
        previewWindow.toFront();
    }

    private void installOwnerOutsideCloseHandler(Stage previewWindow, Node ownerNode) {
        if (previewWindow == null || ownerNode == null || ownerNode.getScene() == null) return;

        Scene ownerScene = ownerNode.getScene();
        EventHandler<MouseEvent> outsideHandler = event -> {
            if (previewWindow.isShowing()) {
                previewWindow.hide();
            }
        };

        ownerScene.addEventFilter(MouseEvent.MOUSE_PRESSED, outsideHandler);
        previewWindow.addEventHandler(WindowEvent.WINDOW_HIDDEN,
                event -> ownerScene.removeEventFilter(MouseEvent.MOUSE_PRESSED, outsideHandler));
    }

    private String resolvePreviewUrl(long trackId) throws Exception {
        String previewUrl = null;

        JsonObject trackJson = fetchJsonObject("https://api.deezer.com/track/" + trackId);
        previewUrl = extractPreviewFromJson(trackJson);

        if ((previewUrl == null || previewUrl.isBlank())
                && trackId >= Integer.MIN_VALUE && trackId <= Integer.MAX_VALUE) {
            long unsignedCandidate = Integer.toUnsignedLong((int) trackId);
            if (unsignedCandidate != trackId) {
                trackJson = fetchJsonObject("https://api.deezer.com/track/" + unsignedCandidate);
                previewUrl = extractPreviewFromJson(trackJson);
            }
        }

        return previewUrl;
    }

    private JsonObject fetchJsonObject(String urlStr) throws Exception {
        HttpURLConnection con = null;
        try {
            URL url = new URL(urlStr);
            con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.setConnectTimeout(8000);
            con.setReadTimeout(10000);

            int code = con.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
            if (is == null) return null;

            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);

                String body = sb.toString();
                if (body.isBlank()) return null;

                JsonElement je = com.google.gson.JsonParser.parseString(body);
                if (!je.isJsonObject()) return null;

                return je.getAsJsonObject();
            }
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private String extractPreviewFromJson(JsonObject trackJson) {
        if (trackJson == null) return null;
        if (trackJson.has("error") && !trackJson.get("error").isJsonNull()) return null;

        return trackJson.has("preview") && !trackJson.get("preview").isJsonNull()
                ? trackJson.get("preview").getAsString()
                : null;
    }
}
