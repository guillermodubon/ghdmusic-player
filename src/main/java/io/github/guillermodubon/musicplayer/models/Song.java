package io.github.guillermodubon.musicplayer.models;

import java.util.List;
import java.util.Objects;

public class Song {

    private long songID;
    private String title;
    private List<Artist> artist;
    private Album album;
    private String filePath;
    private int trackOrder;
    private boolean isLocal;


    public Song(long songID, String title, List<Artist> artist, Album album, String filePath, int trackOrder,boolean local) {
        this.songID = songID;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.filePath = filePath;
        this.trackOrder = trackOrder;
        this.isLocal = local;
    }

    public long getSongID() {
        return songID;
    }
    public void setSongID(long songID) {
        this.songID = songID;
    }
    public String getTitle() {
        return title;
    }
    public void setTitle(String title) {
        this.title = title;
    }
    public List<Artist> getArtist() {
        return artist;
    }
    public void setArtist(List<Artist> artist) {
        this.artist = artist;
    }
    public Album getAlbum() {
        return album;
    }
    public void setAlbum(Album album) {
        this.album = album;
    }
    public String getFilePath() {
        return filePath;
    }
    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }
    public int getTrackOrder() {
        return trackOrder;
    }
    public void setTrackOrder(int trackOrder) {
        this.trackOrder = trackOrder;
    }
    public boolean isLocal() {
        return isLocal;
    }
    public void setLocal(boolean local) {
        this.isLocal = local;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Song song = (Song) o;
        if (songID > 0 && song.songID > 0) {
            return songID == song.songID;
        }
        return Objects.equals(title == null ? null : title.toLowerCase(), song.title == null ? null : song.title.toLowerCase());
    }

    @Override
    public int hashCode() {
        if (songID > 0) return Long.hashCode(songID);
        return title == null ? 0 : title.toLowerCase().hashCode();
    }
}
