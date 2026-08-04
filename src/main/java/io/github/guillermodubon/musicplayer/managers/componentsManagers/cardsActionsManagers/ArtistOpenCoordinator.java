package io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers;

import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import io.github.guillermodubon.musicplayer.repository.dao.artist.ArtistOpenDao;
import io.github.guillermodubon.musicplayer.repository.dao.artist.ArtistOpenDaoImpl;
import io.github.guillermodubon.musicplayer.services.navigation.PlayerMenuNavigator;
import io.github.guillermodubon.musicplayer.services.navigation.SceneStateFlowManager;
import io.github.guillermodubon.musicplayer.services.api.DeezerArtistMetadataResolver;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.artistPage.ArtistPageController;
import io.github.guillermodubon.musicplayer.utils.MusicCardHelper;
import io.github.guillermodubon.musicplayer.models.Artist;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

public class ArtistOpenCoordinator {

    private final StartUpService svc;
    private final PlayerMenuNavigator navigator;
    private final ArtistOpenDao artistOpenDao;
    private volatile MusicCardActionManager musicActions;

    private static final ExecutorService IO_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "artist-open-coordinator-io");
        t.setDaemon(true);
        return t;
    });

    private static final AtomicLong ARTIST_OPEN_SEQUENCE = new AtomicLong(0L);

    public ArtistOpenCoordinator(StartUpService svc, PlayerMenuNavigator navigator) {
        this.svc = svc;
        this.navigator = navigator;
        this.artistOpenDao = new ArtistOpenDaoImpl();
    }

    public void handle(String artistName, Node probe) {
        if (artistName == null || artistName.isBlank()) return;
        handle(new Artist(0L, artistName.trim(), null, new ArrayList<>()), probe);
    }

    public void handle(Artist requestedArtist, Node probe) {
        if (requestedArtist == null || requestedArtist.getName() == null || requestedArtist.getName().isBlank()) return;

        Artist requested = copyArtist(requestedArtist);
        if (isSameArtistAlreadyOpen(requested, probe)) return;

        long requestId = ARTIST_OPEN_SEQUENCE.incrementAndGet();
        InitialArtist initial = resolveInitialArtist(requested);

        openArtistPage(initial.artist(), requested, probe, requestId);

        if (shouldFetchRemoteDetails(initial, requested)) {
            IO_POOL.submit(() -> enrichFromDeezer(initial, requested, probe, requestId));
        }
    }

    private void openArtistPage(Artist artist, Artist requested, Node probe, long requestId) {
        Platform.runLater(() -> {
            try {
                if (!isArtistRequestCurrent(requestId)) return;
                if (!isSourceStillAttached(probe)) return;

                BorderPane root = findRoot(probe);
                if (root == null) {
                    System.err.println("openArtistPage: root BorderPane not found");
                    return;
                }

                URL fxml = getClass().getResource("/io/github/guillermodubon/musicplayer/Views/screens/artistPage/ArtistPage.fxml");
                if (fxml == null) {
                    System.err.println("openArtistPage: ArtistPage.fxml not found");
                    return;
                }

                FXMLLoader loader = new FXMLLoader(fxml);
                Parent artistRoot = loader.load();
                Object controllerObj = loader.getController();
                if (!(controllerObj instanceof ArtistPageController ctrl)) {
                    System.err.println("openArtistPage: ArtistPageController not found");
                    return;
                }

                artistRoot.getProperties().put("controller", ctrl);
                artistRoot.getProperties().put("artistOpenRequestId", requestId);
                Artist navigationArtist = copyArtist(artist);
                SceneStateFlowManager.attachNavigationFactory(
                        artistRoot,
                        () -> createArtistPageView(navigationArtist)
                );

                ctrl.init(artist, svc, buildMusicActions());

                if (!isArtistRequestCurrent(requestId)) {
                    ctrl.onDetached();
                    return;
                }

                BorderPane finalRoot = root;
                Supplier<Map<String, Object>> captureCurrent = () -> captureCurrent(finalRoot);
                BiConsumer<Parent, Map<String, Object>> restoreAction = this::restoreControllerState;

                try {
                    SceneStateFlowManager.getInstance().navigateToAndPushCurrent(artistRoot, captureCurrent, restoreAction);
                } catch (Throwable navEx) {
                    finalRoot.setCenter(artistRoot);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    private void enrichFromDeezer(InitialArtist initial, Artist requested, Node probe, long requestId) {
        try {
            if (!isArtistRequestCurrent(requestId)) return;

            RemoteArtist remote = fetchRemoteArtist(initial.artist(), requested);
            if (remote == null || remote.artist() == null) return;
            if (!isArtistRequestCurrent(requestId)) return;

            boolean needsFullRefresh = initial.artist().getArtistID() <= 0 && remote.artist().getArtistID() > 0;

            Platform.runLater(() -> {
                try {
                    if (!isArtistRequestCurrent(requestId)) return;

                    Parent center = currentCenterParent(probe);
                    if (!isViewForRequest(center, requestId)) return;

                    Object ctrlObj = center.getProperties().get("controller");
                    if (!(ctrlObj instanceof ArtistPageController ctrl)) return;
                    if (!ctrl.isDisplayingArtist(initial.artist().getArtistID(), initial.artist().getName())) return;

                    if (needsFullRefresh) {
                        ctrl.init(remote.artist(), svc, buildMusicActions());
                    } else {
                        ctrl.updateArtistHeader(remote.artist());
                    }
                    Artist navigationArtist = copyArtist(remote.artist());
                    SceneStateFlowManager.attachNavigationFactory(
                            center,
                            () -> createArtistPageView(navigationArtist)
                    );
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            });

        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private InitialArtist resolveInitialArtist(Artist requested) {
        Artist memoryArtist = findInMemory(requested.getArtistID(), requested.getName());
        DbArtist dbArtist = findInDb(requested.getArtistID(), requested.getName());

        long id = firstPositive(requested.getArtistID(),
                memoryArtist == null ? 0L : memoryArtist.getArtistID(),
                dbArtist.artist() == null ? 0L : dbArtist.artist().getArtistID());

        String name = firstText(requested.getName(),
                memoryArtist == null ? null : memoryArtist.getName(),
                dbArtist.artist() == null ? null : dbArtist.artist().getName());

        String biography = firstText(requested.getBiography(),
                memoryArtist == null ? null : memoryArtist.getBiography(),
                dbArtist.artist() == null ? null : dbArtist.artist().getBiography());

        String portraitUrl = firstText(
                requested.getPortraitUrl(),
                memoryArtist == null ? null : memoryArtist.getPortraitUrl(),
                dbArtist.artist() == null ? null : dbArtist.artist().getPortraitUrl()
        );

        boolean localHit = memoryArtist != null || dbArtist.artist() != null;
        boolean preferredPortrait = dbArtist.hasPreferredPortrait()
                || (memoryArtist != null && memoryArtist.getPortraitUrl() != null && !memoryArtist.getPortraitUrl().isBlank());

        Artist initial = new Artist(id, name, biography, new ArrayList<>());
        initial.setPortraitUrl(portraitUrl);
        return new InitialArtist(initial, localHit, preferredPortrait);
    }

    private boolean shouldFetchRemoteDetails(InitialArtist initial, Artist requested) {
        if (initial == null || initial.artist() == null) return false;
        if (initial.artist().getArtistID() <= 0) return true;
        if (!initial.hasPreferredPortrait()) return true;
        return !initial.localHit() && (requested.getPortraitUrl() == null || requested.getPortraitUrl().isBlank());
    }

    private RemoteArtist fetchRemoteArtist(Artist initial, Artist requested) {
        long resolvedId = firstPositive(
                requested == null ? 0L : requested.getArtistID(),
                initial == null ? 0L : initial.getArtistID()
        );
        String fallbackName = firstText(
                requested == null ? null : requested.getName(),
                initial == null ? null : initial.getName(),
                "Unknown"
        );

        JsonObject artistJson = DeezerArtistMetadataResolver.resolve(resolvedId, fallbackName);
        if (artistJson != null) {
            resolvedId = DeezerArtistMetadataResolver.artistId(artistJson);
        }

        if (artistJson == null && resolvedId <= 0) return null;

        String name = fallbackName;
        String biography = initial == null ? null : initial.getBiography();
        String pictureUrl = DeezerArtistMetadataResolver.resolvePictureUrl(
                resolvedId,
                fallbackName,
                firstText(
                        requested == null ? null : requested.getPortraitUrl(),
                        initial == null ? null : initial.getPortraitUrl()
                )
        );
        if (artistJson != null) {
            name = firstText(getString(artistJson, "name"), fallbackName);
            biography = firstText(getString(artistJson, "biography"), biography);
        }

        if (resolvedId <= 0 && initial != null) resolvedId = initial.getArtistID();
        Artist artist = new Artist(resolvedId, name, biography, new ArrayList<>());
        artist.setPortraitUrl(pictureUrl != null && !pictureUrl.isBlank()
                ? pictureUrl
                : initial == null ? null : initial.getPortraitUrl());
        return new RemoteArtist(artist);
    }

    private Artist findInMemory(long artistId, String artistName) {
        try {
            String safeName = artistName == null ? "" : artistName.trim();
            return MusicCardHelper.snapshot(svc == null ? List.<Artist>of() : svc.getArtists()).stream()
                    .filter(Objects::nonNull)
                    .filter(a -> {
                        if (artistId > 0) {
                            return a.getArtistID() > 0 && a.getArtistID() == artistId;
                        }
                        return !safeName.isBlank() && a.getName() != null
                                && a.getName().equalsIgnoreCase(safeName);
                    })
                    .findFirst()
                    .map(this::copyArtist)
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private DbArtist findInDb(long artistId, String artistName) {
        String safeName = artistName == null ? "" : artistName.trim();
        try {
            Artist found = null;
            if (artistId > 0) {
                found = artistOpenDao.findByIdIncludingAggregate(artistId).orElse(null);
            }
            if (found == null && artistId <= 0 && !safeName.isBlank()) {
                found = artistOpenDao.findByNameIgnoreCase(safeName).orElse(null);
            }
            if (found == null) return new DbArtist(null, false);

            return new DbArtist(found, artistOpenDao.hasPreferredPortrait(found.getArtistID()));
        } catch (Exception ignored) {
            return new DbArtist(null, false);
        }
    }

    private boolean isSameArtistAlreadyOpen(Artist requested, Node probe) {
        try {
            Parent center = currentCenterParent(probe);
            ArtistPageController ctrl = findCurrentArtistPageController(center);
            if (ctrl == null) return false;
            boolean sameArtist = ctrl.isDisplayingArtist(requested.getArtistID(), requested.getName());
            if (sameArtist && requested.getArtistID() > 0 && ctrl.getCurrentArtistId() <= 0) {
                return false;
            }
            if (sameArtist && !ctrl.hasAnySectionCards()) {
                Platform.runLater(() -> {
                    try {
                        ctrl.refreshSections(null);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                });
            }
            return sameArtist;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Parent currentCenterParent(Node probe) {
        BorderPane root = findRoot(probe);
        if (root == null) return null;
        Node center = root.getCenter();
        return center instanceof Parent parent ? parent : null;
    }

    private BorderPane findRoot(Node probe) {
        BorderPane root = MusicCardHelper.safeGetRootBorderPane(probe);
        if (root != null) return navigationHost(root);

        for (javafx.stage.Window window : javafx.stage.Window.getWindows()) {
            if (!(window instanceof Stage stage) || !stage.isShowing()) continue;
            try {
                Scene scene = stage.getScene();
                if (scene == null) continue;
                Parent sceneRoot = scene.getRoot();
                if (sceneRoot instanceof BorderPane bp) return navigationHost(bp);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private BorderPane navigationHost(BorderPane sceneRoot) {
        if (sceneRoot == null) return null;
        BorderPane configuredCenter = MusicCardHelper.resolveNavigationHost(sceneRoot);
        if (configuredCenter != sceneRoot) return configuredCenter;
        Node center = sceneRoot.getCenter();
        boolean looksLikeAppShell = sceneRoot.getLeft() != null
                || sceneRoot.getTop() != null
                || sceneRoot.getRight() != null
                || sceneRoot.getBottom() != null;
        if (looksLikeAppShell && center instanceof BorderPane centerHost) return centerHost;
        return sceneRoot;
    }

    private boolean isViewForRequest(Parent center, long requestId) {
        if (center == null) return false;
        Object value = center.getProperties().get("artistOpenRequestId");
        return value instanceof Long id && id == requestId;
    }

    private boolean isSourceStillAttached(Node probe) {
        return true;
    }

    private boolean isArtistRequestCurrent(long requestId) {
        return requestId > 0 && ARTIST_OPEN_SEQUENCE.get() == requestId;
    }

    private Map<String, Object> captureCurrent(BorderPane root) {
        try {
            if (root == null) return null;
            Parent current = (Parent) root.getCenter();
            if (current == null) return null;
            Object controller = current.getProperties().get("controller");
            if (controller == null) return null;

            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> snap = (Map<String, Object>) controller.getClass().getMethod("captureState").invoke(controller);
                return snap;
            } catch (NoSuchMethodException ignored) {
                return null;
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private void restoreControllerState(Parent parent, Map<String, Object> state) {
        if (parent == null || state == null) return;
        try {
            Object controller = parent.getProperties().get("controller");
            if (controller == null) return;
            var method = controller.getClass().getMethod("restoreState", Map.class);
            Platform.runLater(() -> {
                try {
                    method.invoke(controller, state);
                } catch (Exception ignored) {
                }
            });
        } catch (NoSuchMethodException ignored) {
        } catch (Exception ignored) {
        }
    }

    private Parent createArtistPageView(Artist artist) {
        try {
            URL fxml = getClass().getResource("/io/github/guillermodubon/musicplayer/Views/screens/artistPage/ArtistPage.fxml");
            if (fxml == null) return null;

            FXMLLoader loader = new FXMLLoader(fxml);
            Parent artistRoot = loader.load();
            Object controllerObj = loader.getController();
            if (!(controllerObj instanceof ArtistPageController ctrl)) return null;

            long requestId = ARTIST_OPEN_SEQUENCE.incrementAndGet();
            Artist navigationArtist = copyArtist(artist);

            artistRoot.getProperties().put("controller", ctrl);
            artistRoot.getProperties().put("artistOpenRequestId", requestId);
            SceneStateFlowManager.attachNavigationFactory(
                    artistRoot,
                    () -> createArtistPageView(navigationArtist)
            );
            ctrl.init(navigationArtist, svc, buildMusicActions());
            return artistRoot;
        } catch (IOException ex) {
            ex.printStackTrace();
            return null;
        }
    }

    private ArtistPageController findCurrentArtistPageController(Node centerNode) {
        if (centerNode == null) return null;

        Deque<Node> stack = new ArrayDeque<>();
        stack.push(centerNode);

        while (!stack.isEmpty()) {
            Node node = stack.pop();
            try {
                Object ctrlObj = node.getProperties().get("controller");
                if (ctrlObj instanceof ArtistPageController controller) return controller;
            } catch (Exception ignored) {
            }

            if (node instanceof Parent parent) {
                try {
                    for (Node child : parent.getChildrenUnmodifiable()) {
                        if (child != null) stack.push(child);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private MusicCardActionManager buildMusicActions() {
        MusicCardActionManager current = musicActions;
        if (current == null) {
            current = new MusicCardActionManager(svc, navigator, this);
            musicActions = current;
        }
        return current;
    }

    private Artist copyArtist(Artist artist) {
        if (artist == null) return new Artist(0L, "Unknown", null, new ArrayList<>());
        Artist copy = new Artist(
                artist.getArtistID(),
                artist.getName(),
                artist.getBiography(),
                new ArrayList<>()
        );
        copy.setPortraitUrl(artist.getPortraitUrl());
        return copy;
    }

    private long firstPositive(long... values) {
        if (values == null) return 0L;
        for (long value : values) {
            if (value > 0) return value;
        }
        return 0L;
    }

    private String firstText(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private String getString(JsonObject object, String key) {
        try {
            if (object != null && object.has(key) && !object.get(key).isJsonNull()) {
                return object.get(key).getAsString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private record InitialArtist(Artist artist, boolean localHit, boolean hasPreferredPortrait) {}
    private record DbArtist(Artist artist, boolean hasPreferredPortrait) {}
    private record RemoteArtist(Artist artist) {}
}
