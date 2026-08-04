package io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.context.ArtistPageContext;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.context.ArtistPageSharedState;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.helpers.ArtistPageResponsiveCoordinator;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.helpers.ArtistPageSectionCoordinator;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.helpers.ArtistPageViewBindings;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.providers.common.ArtistPageSectionRegistry;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.repositories.ArtistPageDeezerRepository;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.repositories.ArtistPageMemoryRepository;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.common.ScreenRequestScope;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.services.images.MediaImageResolver;
import io.github.guillermodubon.musicplayer.services.api.DeezerArtistMetadataResolver;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.utils.DeezerEndpoints;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FXML facade for the artist screen.
 *
 * <p>The controller keeps the screen contract and delegates layout, section
 * lifecycle and remote work to focused collaborators.</p>
 */
public class ArtistPageController {

    private static final double HEADER_IMAGE_DECODE_WIDTH = 1000;
    private static final double HEADER_IMAGE_DECODE_HEIGHT = 1000;
    private static final ExecutorService HEADER_IMAGE_POOL = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "artist-page-header-image");
        thread.setDaemon(true);
        return thread;
    });

    private static final String CONNECTION_ERROR_ICON =
            "/io/github/guillermodubon/musicplayer/assets/icons/ButtonIcons/wifi_off_27dp_AFAFAF_FILL0_wght400_GRAD0_opsz24.svg";

    @FXML private StackPane pageRoot;
    @FXML private StackPane headerRoot;
    @FXML private ImageView headerBackgroundImage;
    @FXML private StackPane headerImageFrame;
    @FXML private ImageView headerImage;
    @FXML private HBox headerOverlay;
    @FXML private VBox headerInfo;
    @FXML private ScrollPane artistScrollPane;
    @FXML private Label artistNameLabel;
    @FXML private Text biographyTextHeader;
    @FXML private Text biographyText;
    @FXML private VBox centerVBox;
    @FXML private VBox mainContent;
    @FXML private StackPane localCarouselHost;
    @FXML private FlowPane localFlow;
    @FXML private FlowPane topTracksFlow;
    @FXML private FlowPane albumsFlow;
    @FXML private FlowPane singlesFlow;
    @FXML private FlowPane playlistsFlow;
    @FXML private Label localTitle;
    @FXML private Label topTracksTitle;
    @FXML private Label albumsTitle;
    @FXML private Label singlesTitle;
    @FXML private Label playlistsTitle;

    private final ScreenRequestScope requestScope = new ScreenRequestScope();
    private Artist artist;
    private ArtistPageContext context;
    private ArtistPageSharedState sharedState;
    private ArtistPageSectionRegistry registry;
    private ArtistPageResponsiveCoordinator responsiveCoordinator;
    private ArtistPageSectionCoordinator sectionCoordinator;
    private volatile boolean alive;
    private volatile String biographyRequestKey;
    private volatile String headerImageRequestKey;

    public void init(Artist artist, StartUpService svc, MusicCardActionManager musicActions) throws IOException {
        init(artist,
                new ArtistPageContext(
                        svc,
                        new ArtistPageMemoryRepository(),
                        new ArtistPageDeezerRepository(),
                        DeezerEndpoints.defaultArtistPageEndpoints(),
                        musicActions,
                        requestScope
                ));
    }

    public void init(Artist artist, ArtistPageContext context) throws IOException {
        disposeCurrentScreen();
        requestScope.restart();

        this.artist = Objects.requireNonNull(artist, "artist");
        this.context = Objects.requireNonNull(context, "context");
        this.sharedState = new ArtistPageSharedState();
        this.alive = true;

        ArtistPageViewBindings view = viewBindings();
        this.registry = new ArtistPageSectionRegistry(context, new UiBindings(
                headerRoot,
                headerImage,
                artistNameLabel,
                biographyTextHeader,
                biographyText,
                centerVBox,
                localTitle,
                localCarouselHost,
                localFlow,
                topTracksTitle,
                topTracksFlow,
                albumsTitle,
                albumsFlow,
                singlesTitle,
                singlesFlow,
                playlistsTitle,
                playlistsFlow
        ));
        this.responsiveCoordinator = new ArtistPageResponsiveCoordinator(view);
        this.sectionCoordinator = new ArtistPageSectionCoordinator(
                view,
                requestScope,
                () -> this.artist,
                () -> alive,
                context,
                sharedState,
                registry
        );

        responsiveCoordinator.configureLayoutBounds();
        sectionCoordinator.resetTitlesAndVisibility();
        sectionCoordinator.renderSections(true);
        applyHeader();
        hydrateBiographyIfNeeded();
    }

    public void updateArtistHeader(Artist updatedArtist) {
        if (updatedArtist == null || !canMergeArtist(updatedArtist)) {
            return;
        }
        artist = mergeArtist(artist, updatedArtist);
        applyHeader();
        hydrateBiographyIfNeeded();
    }

    public String getCurrentArtistName() {
        return artist == null ? null : artist.getName();
    }

    public long getCurrentArtistId() {
        return artist == null ? 0L : artist.getArtistID();
    }

    public boolean isDisplayingArtist(long artistId, String artistName) {
        if (artist == null) {
            return false;
        }
        if (artistId > 0) {
            // A valid ID is authoritative. A same-name match can belong to a
            // different Deezer artist and must not update this page.
            return artist.getArtistID() > 0 && artist.getArtistID() == artistId;
        }
        if (!hasText(artistName) || artist.getName() == null) {
            return false;
        }
        return artist.getName().trim().equalsIgnoreCase(artistName.trim());
    }

    public void refreshSections(String filter) throws IOException {
        if (!alive || context == null || sectionCoordinator == null) {
            return;
        }
        sharedState.clear();
        sectionCoordinator.renderSections(false);
        applyHeader();
    }

    public boolean hasAnySectionCards() {
        if (!Platform.isFxApplicationThread() || sectionCoordinator == null) {
            return false;
        }
        return sectionCoordinator.hasAnySectionCards();
    }

    private void applyHeader() {
        if (artistNameLabel != null) {
            artistNameLabel.setText(artist == null || artist.getName() == null ? "" : artist.getName());
        }

        String biography = artist == null ? null : artist.getBiography();
        if (biographyTextHeader != null) {
            biographyTextHeader.setText(hasText(biography) ? biography : "");
        }
        if (biographyText != null) {
            biographyText.setText(hasText(biography) ? biography : "No biography available.");
        }

        if (responsiveCoordinator != null) {
            responsiveCoordinator.configureLayoutBounds();
            responsiveCoordinator.updateHeaderViewport();
        }
        requestHeaderImage();
        updateSectionTitles();
    }

    private void updateSectionTitles() {
        String name = artist == null || artist.getName() == null ? "Artist" : artist.getName();
        if (localTitle != null) {
            localTitle.setText(name + " from your library");
        }
        if (topTracksTitle != null) {
            topTracksTitle.setText("Top " + (name.equals("Artist") ? "tracks" : name + " tracks"));
        }
        if (albumsTitle != null) {
            albumsTitle.setText("Albums");
        }
        if (singlesTitle != null) {
            singlesTitle.setText("Singles");
        }
        if (playlistsTitle != null) {
            playlistsTitle.setText("Playlists");
        }
    }

    private void hydrateBiographyIfNeeded() {
        if (artist == null || context == null || context.svc() == null
                || hasText(artist.getBiography()) || !hasText(artist.getName())) {
            return;
        }

        long targetId = artist.getArtistID();
        String targetName = artist.getName();
        String requestKey = artistKey(targetId, targetName);
        if (requestKey.equals(biographyRequestKey)) {
            return;
        }
        biographyRequestKey = requestKey;

        context.svc().getArtistBiographyService()
                .resolveBiographyAsync(artist, context.svc())
                .thenAccept(biography -> Platform.runLater(() -> {
                    if (requestKey.equals(biographyRequestKey)) {
                        biographyRequestKey = null;
                    }
                    if (!alive || !hasText(biography) || !isDisplayingArtist(targetId, targetName)) {
                        return;
                    }
                    if (artist != null) {
                        artist.setBiography(biography);
                    }
                    applyHeader();
                }))
                .exceptionally(error -> {
                    if (requestKey.equals(biographyRequestKey)) {
                        biographyRequestKey = null;
                    }
                    return null;
                });
    }

    private void requestHeaderImage() {
        if (artist == null || context == null || context.requestScope() == null || headerImage == null) {
            return;
        }

        Artist snapshot = snapshotArtist(artist);
        String requestKey = artistKey(snapshot.getArtistID(), snapshot.getName())
                + ':' + Objects.toString(snapshot.getPortraitUrl(), "");
        if (requestKey.equals(headerImageRequestKey) && headerImage.getImage() != null) {
            return;
        }

        headerImageRequestKey = requestKey;
        setHeaderImageOrFallback(defaultArtistHeader());
        int renderGeneration = sharedState == null ? 0 : sharedState.generation();

        context.requestScope()
                .supplyAsync(() -> resolveHeaderImage(snapshot), HEADER_IMAGE_POOL)
                .whenComplete((image, error) -> Platform.runLater(() -> {
                    if (!alive || error != null || !requestKey.equals(headerImageRequestKey)) {
                        return;
                    }
                    if (sharedState == null || !sharedState.isCurrent(renderGeneration)) {
                        return;
                    }
                    setHeaderImageOrFallback(image);
                }));
    }

    private Image resolveHeaderImage(Artist source) {
        long artistId = source == null ? 0L : source.getArtistID();
        Image local = artistId > 0
                ? MediaImageResolver.artistPortrait(
                artistId,
                "big",
                HEADER_IMAGE_DECODE_WIDTH,
                HEADER_IMAGE_DECODE_HEIGHT
        )
                : null;
        if (isUsableImage(local)) {
            return local;
        }

        String portraitUrl = DeezerArtistMetadataResolver.resolvePictureUrl(
                artistId,
                source == null ? null : source.getName(),
                source == null ? null : source.getPortraitUrl()
        );
        Image remote = MediaImageResolver.remoteImage(
                optimizeArtistHeaderUrl(portraitUrl),
                HEADER_IMAGE_DECODE_WIDTH,
                HEADER_IMAGE_DECODE_HEIGHT
        );
        return remote == null ? defaultArtistHeader() : remote;
    }

    private void setHeaderImageOrFallback(Image image) {
        if (headerImage == null) {
            return;
        }
        Image fallback = defaultArtistHeader();
        if (image == null || image.isError()) {
            setHeaderImages(fallback);
            return;
        }

        setHeaderImages(image);
        image.errorProperty().addListener((observable, wasError, isError) -> {
            if (Boolean.TRUE.equals(isError)) {
                Platform.runLater(() -> setHeaderImages(fallback));
            }
        });
        if (image.isError()) {
            setHeaderImages(fallback);
        }
    }

    private void setHeaderImages(Image image) {
        if (image == null) {
            return;
        }
        if (headerImage != null && headerImage.getImage() != image) {
            headerImage.setImage(image);
        }
        if (headerBackgroundImage != null && headerBackgroundImage.getImage() != image) {
            headerBackgroundImage.setImage(image);
        }
        if (responsiveCoordinator != null) {
            responsiveCoordinator.updateHeaderViewport();
        }
    }

    private ArtistPageViewBindings viewBindings() {
        return new ArtistPageViewBindings(
                pageRoot,
                headerRoot,
                headerBackgroundImage,
                headerImageFrame,
                headerImage,
                headerOverlay,
                headerInfo,
                artistScrollPane,
                artistNameLabel,
                biographyTextHeader,
                biographyText,
                centerVBox,
                mainContent,
                localCarouselHost,
                localFlow,
                topTracksFlow,
                albumsFlow,
                singlesFlow,
                playlistsFlow,
                localTitle,
                topTracksTitle,
                albumsTitle,
                singlesTitle,
                playlistsTitle
        );
    }

    private void disposeCurrentScreen() {
        if (sectionCoordinator != null) {
            sectionCoordinator.clearAndInvalidate();
        } else if (registry != null) {
            try {
                registry.dispose();
            } catch (Exception ignored) {
            }
        }
        alive = false;
        biographyRequestKey = null;
        headerImageRequestKey = null;
    }

    private Artist mergeArtist(Artist current, Artist updated) {
        if (current == null) {
            return updated;
        }
        long id = updated.getArtistID() > 0 ? updated.getArtistID() : current.getArtistID();
        String name = hasText(updated.getName()) ? updated.getName() : current.getName();
        String biography = hasText(updated.getBiography())
                ? updated.getBiography()
                : current.getBiography();

        Artist merged = new Artist(id, name, biography, new ArrayList<>());
        merged.setPortraitUrl(hasText(updated.getPortraitUrl())
                ? updated.getPortraitUrl()
                : current.getPortraitUrl());
        return merged;
    }

    private boolean canMergeArtist(Artist updated) {
        if (artist == null || updated == null) {
            return false;
        }

        long currentId = artist.getArtistID();
        long updatedId = updated.getArtistID();
        if (currentId > 0 && updatedId > 0) {
            return currentId == updatedId;
        }

        return hasText(artist.getName())
                && hasText(updated.getName())
                && artist.getName().trim().equalsIgnoreCase(updated.getName().trim());
    }

    private Artist snapshotArtist(Artist source) {
        if (source == null) {
            return new Artist(0L, "Unknown", null, new ArrayList<>());
        }
        Artist snapshot = new Artist(
                source.getArtistID(),
                source.getName(),
                source.getBiography(),
                new ArrayList<>()
        );
        snapshot.setPortraitUrl(source.getPortraitUrl());
        return snapshot;
    }

    private Image defaultArtistHeader() {
        try {
            return MediaImageResolver.defaultArtist(HEADER_IMAGE_DECODE_WIDTH, HEADER_IMAGE_DECODE_HEIGHT);
        } catch (Exception ignored) {
            return new javafx.scene.image.WritableImage(1, 1);
        }
    }

    private String optimizeArtistHeaderUrl(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        return url.trim().replaceFirst("/\\d+x\\d+-", "/1000x1000-");
    }

    private String artistKey(long artistId, String artistName) {
        if (artistId > 0) {
            return "id:" + artistId;
        }
        return "name:" + (artistName == null ? "" : artistName.trim().toLowerCase(Locale.ROOT));
    }

    private boolean isUsableImage(Image image) {
        return image != null && !image.isError();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public Map<String, Object> captureState() {
        Map<String, Object> state = new HashMap<>();
        state.put("snapshotTime", System.nanoTime());
        state.put("artistId", artist == null ? 0L : artist.getArtistID());
        state.put("artistName", artist == null ? null : artist.getName());
        return state;
    }

    public void restoreState(Map<String, Object> state) {
        if (state == null) {
            return;
        }
        Object id = state.get("artistId");
        if (id instanceof Number savedId
                && savedId.longValue() > 0
                && (artist == null || artist.getArtistID() != savedId.longValue())) {
            return;
        }
        Object name = state.get("artistName");
        if (name instanceof String value && artistNameLabel != null) {
            artistNameLabel.setText(value);
        }
    }

    public void onDetached() {
        disposeCurrentScreen();
        requestScope.close();
    }

    public Parent getRoot() {
        return centerVBox == null ? null : centerVBox.getParent();
    }

    public record UiBindings(
            StackPane headerRoot,
            ImageView headerImage,
            Label artistNameLabel,
            Text biographyTextHeader,
            Text biographyText,
            VBox centerVBox,
            Label localTitle,
            StackPane localCarouselHost,
            FlowPane localFlow,
            Label topTracksTitle,
            FlowPane topTracksFlow,
            Label albumsTitle,
            FlowPane albumsFlow,
            Label singlesTitle,
            FlowPane singlesFlow,
            Label playlistsTitle,
            FlowPane playlistsFlow
    ) {
    }
}
