package io.github.guillermodubon.musicplayer.services.navigation;



import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import io.github.guillermodubon.musicplayer.repository.DbConnectionManager;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.PlayerMenuController;
import io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context.PlayerMenuContext;
import io.github.guillermodubon.musicplayer.utils.MusicCardHelper;
import io.github.guillermodubon.musicplayer.managers.componentsManagers.cardsActionsManagers.MusicCardActionManager;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.playback.PlaybackManager;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.io.IOException;
import java.net.URL;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class PlayerMenuNavigator {

    private static final String PLAYER_MENU_IDENTITY_PREFIX = "player-menu:";

    private final StartUpService svc;
    private MusicCardActionManager musicCardActionManager;
    private final AtomicLong openRequestSequence = new AtomicLong();
    private static final String LOADING_REMOTE_PLAYLIST_AUTHOR = "__LOADING_REMOTE_PLAYLIST__";

    private static final ExecutorService NAV_IO = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "player-menu-nav-io");
        t.setDaemon(true);
        return t;
    });

    public PlayerMenuNavigator(StartUpService svc) {
        this(svc, null);
    }

    public PlayerMenuNavigator(StartUpService svc, MusicCardActionManager musicCardActionManager) {
        this.svc = svc;
        this.musicCardActionManager = musicCardActionManager;
    }

    public void setMusicCardActionManager(MusicCardActionManager musicCardActionManager) {
        this.musicCardActionManager = musicCardActionManager;
    }

    public long beginOpenRequest() {
        return openRequestSequence.incrementAndGet();
    }

    public boolean isOpenRequestCurrent(long requestId) {
        return requestId > 0 && openRequestSequence.get() == requestId;
    }

    private boolean isSourceStillAttached(Node probe) {
        /*
         * Card openings are request-id guarded already. Keeping this tied to the
         * clicked node makes valid opens disappear when the user navigates fast
         * and the source card is detached before the FX handoff runs.
         */
        return true;
    }

    private void bindPlayerMenuController(PlayerMenuController ctrl, BorderPane root) {
        if (ctrl == null) return;

        try { ctrl.setSvc(svc); } catch (Exception ignored) {}
        try { ctrl.setParentRoot(root); } catch (Exception ignored) {}

        if (musicCardActionManager != null) {
            try { ctrl.setMusicCardActionManager(musicCardActionManager); } catch (Exception ignored) {}
        }
    }

    public void openPlayerMenu(Playlist pl, PlayerMenuContext.ContentType type, Node probe) {
        openPlayerMenuIfCurrent(pl, type, probe, beginOpenRequest());
    }

    public void openPlayerMenuIfCurrent(Playlist pl, PlayerMenuContext.ContentType type, Node probe, long requestId) {
        if (!isOpenRequestCurrent(requestId)) return;
        if (!isSourceStillAttached(probe)) return;

        /*
         * Album metadata can arrive in two phases: a fast local view followed
         * by the complete Deezer track list. Update that visible controller in
         * place instead of creating a second history entry for the same view.
         */
        if (updateVisiblePlayerMenuIfSameSource(pl, type, requestId)) return;

        try {
            if (!isOpenRequestCurrent(requestId)) return;
            if (!isSourceStillAttached(probe)) return;

            BorderPane root = MusicCardHelper.safeGetRootBorderPane(probe);
            if (root == null) {
                for (javafx.stage.Window w : javafx.stage.Window.getWindows()) {
                    if (!(w instanceof javafx.stage.Stage) || !w.isShowing()) continue;
                    try {
                        Scene s = ((javafx.stage.Stage) w).getScene();
                        if (s == null) continue;
                        Parent r = s.getRoot();
                        if (r instanceof BorderPane) {
                            root = MusicCardHelper.resolveNavigationHost((BorderPane) r);
                            break;
                        }
                    } catch (Exception ignoredWindow) { }
                }
            }

            if (root == null) {
                System.err.println("openPlayerMenu: root BorderPane not found (UI not available)");
                return;
            }

            URL fxml = getClass().getResource("/io/github/guillermodubon/musicplayer/Views/screens/playerMenu/PlayerMenu.fxml");
            if (fxml == null) {
                System.err.println("openPlayerMenu: PlayerMenuScene.fxml not found");
                return;
            }

            if (!isOpenRequestCurrent(requestId)) return;

            FXMLLoader loader = new FXMLLoader(fxml);
            Parent view = loader.load();
            Object controllerObj = loader.getController();
            if (view != null && controllerObj != null) view.getProperties().put("controller", controllerObj);
            markPlayerMenuIdentity(view, pl, type);
            if (view != null && isLoadingRemotePlaylist(pl)) {
                SceneStateFlowManager.markTransient(view, true);
            }
            BorderPane navigationRoot = root;
            Playlist navigationPlaylist = pl;
            PlayerMenuContext.ContentType navigationType = type;
            SceneStateFlowManager.attachNavigationFactory(
                    view,
                    () -> createPlayerMenuView(navigationPlaylist, navigationType, navigationRoot)
            );

            if (!isOpenRequestCurrent(requestId)) return;

            PlayerMenuController ctrl = null;
            if (controllerObj instanceof PlayerMenuController) {
                ctrl = (PlayerMenuController) controllerObj;
                bindPlayerMenuController(ctrl, root);
            }
            if (ctrl == null) {
                System.err.println("openPlayerMenu: PlayerMenuController not found");
                return;
            }

            BorderPane finalRoot = root;
            PlayerMenuController finalCtrl = ctrl;

            Supplier<Map<String, Object>> captureCurrent = () -> {
                try {
                    Parent current = (Parent) finalRoot.getCenter();
                    if (current == null) return null;
                    Object c = current.getProperties().get("controller");
                    if (c == null) return null;
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> snap = (Map<String, Object>) c.getClass().getMethod("captureState").invoke(c);
                        return snap;
                    } catch (NoSuchMethodException ns) {
                        return null;
                    } catch (Exception invokeEx) {
                        return null;
                    }
                } catch (Exception outerEx) {
                    return null;
                }
            };

            BiConsumer<Parent, Map<String, Object>> restoreAction = (p, state) -> {
                if (p == null || state == null) return;
                try {
                    Object c = p.getProperties().get("controller");
                    if (c != null) {
                        var m = c.getClass().getMethod("restoreState", Map.class);
                        Platform.runLater(() -> {
                            try { m.invoke(c, state); } catch (Exception invokeRestoreEx) {}
                        });
                    }
                } catch (NoSuchMethodException ns2) {
                } catch (Exception restoreEx) { }
            };

            Platform.runLater(() -> {
                try {
                    if (!isOpenRequestCurrent(requestId)) return;
                    if (!isSourceStillAttached(probe)) return;

                    if (finalCtrl != null) {
                        PlaybackManager pm = PlaybackManager.getInstance();

                        try {
                            if (!isOpenRequestCurrent(requestId)) return;
                            finalCtrl.initPlaylist(type, pl);
                        } catch (Throwable t) {
                            t.printStackTrace();
                            return;
                        }

                        pm.setMenuController(finalCtrl);
                    }

                    try {
                        SceneStateFlowManager.getInstance().navigateToAndPushCurrent(view, captureCurrent, restoreAction);
                    } catch (Exception navEx) {
                        finalRoot.setCenter(view);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

            final PlayerMenuController enrichmentTargetCtrl = finalCtrl;

            NAV_IO.submit(() -> {
                try {
                    if (!isOpenRequestCurrent(requestId)) return;

                    Playlist prepared = null;
                    try {
                        java.util.List<Song> preparedSongs = new java.util.ArrayList<>();
                        if (pl != null && pl.getSongList() != null) {
                            Map<Long, Song> svcSongById = Collections.emptyMap();
                            try {
                                java.util.List<Song> svcSongsSnap = MusicCardHelper.snapshot(svc == null ? java.util.List.<Song>of() : svc.getSongs());
                                svcSongById = svcSongsSnap.stream()
                                        .filter(Objects::nonNull)
                                        .filter(s -> s.getSongID() > 0)
                                        .collect(Collectors.toMap(Song::getSongID, Function.identity(), (a, b) -> a));
                            } catch (Exception ignored) {}

                            for (Song s : MusicCardHelper.snapshot(pl.getSongList())) {
                                if (s == null) continue;
                                Song chosen = s;
                                if (s.getSongID() > 0 && svcSongById.containsKey(s.getSongID())) {
                                    Song cachedSong = svcSongById.get(s.getSongID());
                                    // The navigation source can contain the
                                    // fresh Deezer artist object. Do not
                                    // replace it with an older local object
                                    // that may have resolved the artist by a
                                    // duplicate name.
                                    chosen = mergeSourceArtistIdentity(s, cachedSong);
                                }
                                preparedSongs.add(chosen);
                            }
                        }

                        prepared = new Playlist(
                                pl != null ? pl.getId() : -1L,
                                pl != null ? Optional.ofNullable(pl.getTitle()).orElse("") : "",
                                pl != null ? Optional.ofNullable(pl.getAuthorName()).orElse("") : "",
                                pl != null ? Optional.ofNullable(pl.getDescription()).orElse("") : "",
                                pl != null ? Optional.ofNullable(pl.getDate()).orElse("") : "",
                                null,
                                FXCollections.observableArrayList(preparedSongs)
                        );
                    } catch (Throwable prepEx) {
                        prepEx.printStackTrace();
                    }

                    try {
                        if (!isOpenRequestCurrent(requestId)) return;
                        PlayerMenuController current = PlaybackManager.getInstance().getMenuController();
                        if (enrichmentTargetCtrl == null || current != enrichmentTargetCtrl) {
                            return;
                        }
                    } catch (Exception ignore) { }

                    try {
                        if (!isOpenRequestCurrent(requestId)) return;
                        if (prepared != null && prepared.getSongList() != null && !prepared.getSongList().isEmpty()) {
                            Playlist finalPrepared = prepared;
                            DbConnectionManager.getInstance().runInTransaction(conn -> {
                                try (PreparedStatement ps = conn.prepareStatement(
                                        "INSERT OR IGNORE INTO Song(SongID, Title, Album, TrackOrder, IsLocal) VALUES(?, ?, ?, ?, 0)")) {
                                    int pending = 0;
                                    for (Song s : finalPrepared.getSongList()) {
                                        try {
                                            if (s == null || s.getSongID() <= 0) continue;
                                            ps.setLong(1, s.getSongID());
                                            ps.setString(2, Optional.ofNullable(s.getTitle()).orElse(""));
                                            ps.setLong(3, s.getAlbum() != null ? s.getAlbum().getAlbumID() : 0L);
                                            ps.setInt(4, s.getTrackOrder());
                                            ps.addBatch();
                                            pending++;
                                            if (pending % 200 == 0) {
                                                ps.executeBatch();
                                            }
                                        } catch (Exception ignoredPersist) {}
                                    }
                                    if (pending % 200 != 0) {
                                        ps.executeBatch();
                                    }
                                } catch (SQLException ignoreDb) {
                                }
                                return null;
                            });
                        }
                    } catch (Throwable persistEx) {
                        persistEx.printStackTrace();
                    }

                    Playlist finalPrepared1 = prepared;
                    Platform.runLater(() -> {
                        try {
                            if (!isOpenRequestCurrent(requestId)) return;
                            PlayerMenuController active = PlaybackManager.getInstance().getMenuController();
                            if (active != enrichmentTargetCtrl) {
                                return;
                            }

                            if (active != null && finalPrepared1 != null) {
                                Playlist mod = active.getCurrentPlaylistModel();
                                if (mod != null && mod.getId() == finalPrepared1.getId()) {
                                    try {
                                        active.refreshCurrentViewMinimal();
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });

                } catch (Throwable t) {
                    t.printStackTrace();
                }
            });

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Song mergeSourceArtistIdentity(Song source, Song cached) {
        if (source == null || cached == null) return source == null ? cached : source;
        if (!hasPositiveArtistIdentity(source)) return cached;

        String title = source.getTitle() == null || source.getTitle().isBlank()
                ? cached.getTitle() : source.getTitle();
        String filePath = cached.getFilePath() == null || cached.getFilePath().isBlank()
                ? source.getFilePath() : cached.getFilePath();
        int trackOrder = source.getTrackOrder() > 0
                ? source.getTrackOrder() : cached.getTrackOrder();

        return new Song(
                source.getSongID() > 0 ? source.getSongID() : cached.getSongID(),
                title,
                source.getArtist(),
                source.getAlbum() == null ? cached.getAlbum() : source.getAlbum(),
                filePath,
                trackOrder,
                source.isLocal() || cached.isLocal()
        );
    }

    private boolean hasPositiveArtistIdentity(Song song) {
        if (song == null) return false;
        if (song.getArtist() != null && song.getArtist().stream()
                .filter(Objects::nonNull)
                .anyMatch(artist -> artist.getArtistID() > 0)) {
            return true;
        }
        return song.getAlbum() != null
                && song.getAlbum().getArtist() != null
                && song.getAlbum().getArtist().stream()
                .filter(Objects::nonNull)
                .anyMatch(artist -> artist.getArtistID() > 0);
    }

    public void openPlaylist(long playlistId, Node probe) {
        openPlaylistIfCurrent(playlistId, probe, beginOpenRequest());
    }

    public void openPlaylistIfCurrent(long playlistId, Node probe, long requestId) {
        if (!isOpenRequestCurrent(requestId)) return;

        Playlist pl = MusicCardHelper.snapshot(svc == null ? java.util.List.<Playlist>of() : svc.getPlaylists()).stream()
                .filter(x -> x != null && x.getId() == playlistId)
                .findFirst().orElse(null);
        if (pl == null) return;

        openPlayerMenuIfCurrent(pl, PlayerMenuContext.ContentType.PLAYLIST, probe, requestId);
    }

    private Parent createPlayerMenuView(Playlist playlist, PlayerMenuContext.ContentType type, BorderPane root) {
        try {
            URL fxml = getClass().getResource("/io/github/guillermodubon/musicplayer/Views/screens/playerMenu/PlayerMenu.fxml");
            if (fxml == null) return null;

            FXMLLoader loader = new FXMLLoader(fxml);
            Parent view = loader.load();
            Object controllerObj = loader.getController();
            if (!(controllerObj instanceof PlayerMenuController ctrl)) return null;

            view.getProperties().put("controller", ctrl);
            markPlayerMenuIdentity(view, playlist, type);
            if (isLoadingRemotePlaylist(playlist)) {
                SceneStateFlowManager.markTransient(view, true);
            }
            SceneStateFlowManager.attachNavigationFactory(
                    view,
                    () -> createPlayerMenuView(playlist, type, root)
            );

            bindPlayerMenuController(ctrl, root);
            ctrl.initPlaylist(type, playlist);
            PlaybackManager.getInstance().setMenuController(ctrl);
            return view;
        } catch (IOException ex) {
            ex.printStackTrace();
            return null;
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    private boolean updateVisiblePlayerMenuIfSameSource(
            Playlist playlist,
            PlayerMenuContext.ContentType type,
            long requestId
    ) {
        if (playlist == null || type == null || !Platform.isFxApplicationThread()) return false;

        PlayerMenuController visible = PlaybackManager.getInstance().getMenuController();
        if (visible == null
                || !visible.isCurrentCenterViewVisible()
                || visible.getCurrentPlaylistInViewId() != playlist.getId()
                || visible.getCurrentContentTypeInView() != type) {
            return false;
        }

        Platform.runLater(() -> {
            if (!isOpenRequestCurrent(requestId)) return;
            PlayerMenuController current = PlaybackManager.getInstance().getMenuController();
            if (current == visible
                    && current.isCurrentCenterViewVisible()
                    && current.getCurrentPlaylistInViewId() == playlist.getId()
                    && current.getCurrentContentTypeInView() == type) {
                current.updatePlaylistContent(playlist);
            }
        });
        return true;
    }

    private void markPlayerMenuIdentity(
            Parent view,
            Playlist playlist,
            PlayerMenuContext.ContentType type
    ) {
        if (view == null || playlist == null || type == null) return;
        view.getProperties().put(
                SceneStateFlowManager.NAVIGATION_IDENTITY_PROPERTY,
                PLAYER_MENU_IDENTITY_PREFIX + type.name() + ":" + playlist.getId()
        );
    }

    private boolean isLoadingRemotePlaylist(Playlist playlist) {
        return playlist != null
                && LOADING_REMOTE_PLAYLIST_AUTHOR.equals(Optional.ofNullable(playlist.getAuthorName()).orElse(""));
    }
}
