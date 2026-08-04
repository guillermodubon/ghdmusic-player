package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.context;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.layout.BorderPane;
import io.github.guillermodubon.musicplayer.controllers.ui.components.layoutComponents.playerMenuBar.PlayerMenuBarController;
import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class PlayerMenuContext {

    public enum ContentType {
        SINGLE, ALBUM, EPISODE, PLAYLIST
    }

    private StartUpService svc;
    private BorderPane parentRoot;

    private boolean barLoaded;
    private PlayerMenuBarController playerMenuBarController;

    private String playlistName;
    private Playlist currentPlaylistModel;

    private ContentType currentContentTypeInView;
    private long currentPlaylistInViewId = -1L;

    private final ObservableList<Song> masterSongList = FXCollections.observableArrayList();
    private final List<Song> currentSongList = new ArrayList<>();
    private final AtomicLong viewRevision = new AtomicLong(0L);

    public StartUpService getSvc() {
        return svc;
    }

    public void setSvc(StartUpService svc) {
        this.svc = svc;
    }

    public BorderPane getParentRoot() {
        return parentRoot;
    }

    public void setParentRoot(BorderPane parentRoot) {
        this.parentRoot = parentRoot;
    }

    public boolean isBarLoaded() {
        return barLoaded;
    }

    public void setBarLoaded(boolean barLoaded) {
        this.barLoaded = barLoaded;
    }

    public PlayerMenuBarController getPlayerMenuBarController() {
        return playerMenuBarController;
    }

    public void setPlayerMenuBarController(PlayerMenuBarController playerMenuBarController) {
        this.playerMenuBarController = playerMenuBarController;
    }

    public String getPlaylistName() {
        return playlistName;
    }

    public void setPlaylistName(String playlistName) {
        this.playlistName = playlistName;
    }

    public Playlist getCurrentPlaylistModel() {
        return currentPlaylistModel;
    }

    public void setCurrentPlaylistModel(Playlist currentPlaylistModel) {
        this.currentPlaylistModel = currentPlaylistModel;
    }

    public ContentType getCurrentContentTypeInView() {
        return currentContentTypeInView;
    }

    public void setCurrentContentTypeInView(ContentType currentContentTypeInView) {
        this.currentContentTypeInView = currentContentTypeInView;
    }

    public long getCurrentPlaylistInViewId() {
        return currentPlaylistInViewId;
    }

    public void setCurrentPlaylistInViewId(long currentPlaylistInViewId) {
        this.currentPlaylistInViewId = currentPlaylistInViewId;
    }

    public ObservableList<Song> getMasterSongList() {
        return masterSongList;
    }

    public void setMasterSongList(List<Song> songs) {
        masterSongList.setAll(songs == null ? List.of() : songs);
    }

    public List<Song> getCurrentSongList() {
        return currentSongList;
    }

    public void setCurrentSongList(List<Song> songs) {
        currentSongList.clear();
        if (songs != null) currentSongList.addAll(songs);
    }


    public void syncPlaylistFromView(Playlist playlist, ContentType type) {
        viewRevision.incrementAndGet();
        this.currentPlaylistModel = playlist;
        this.currentContentTypeInView = type;
        this.currentPlaylistInViewId = playlist == null ? -1L : playlist.getId();
        this.playlistName = playlist == null ? null : playlist.getTitle();
    }

    public long getViewRevision() {
        return viewRevision.get();
    }

    public boolean isViewRevisionCurrent(long revision) {
        return viewRevision.get() == revision;
    }

    public long invalidateViewRevision() {
        return viewRevision.incrementAndGet();
    }

    public void ensurePlaylistNameFallback() {
        if ((playlistName == null || playlistName.isBlank())
                && currentPlaylistModel != null
                && currentPlaylistModel.getTitle() != null) {
            playlistName = currentPlaylistModel.getTitle();
        }
    }


    public Song findCurrentSongById(long songId) {
        for (Song s : currentSongList) {
            if (s != null && s.getSongID() == songId) return s;
        }
        return null;
    }


    @Override
    public String toString() {
        return "PlayerMenuContext{" +
                "barLoaded=" + barLoaded +
                ", playlistName='" + playlistName + '\'' +
                ", currentContentTypeInView=" + currentContentTypeInView +
                ", currentPlaylistInViewId=" + currentPlaylistInViewId +
                ", currentPlaylistModel=" + (currentPlaylistModel == null ? "null" : currentPlaylistModel.getTitle()) +
                ", masterSongListSize=" + masterSongList.size() +
                ", currentSongListSize=" + currentSongList.size() +
                '}';
    }
}
