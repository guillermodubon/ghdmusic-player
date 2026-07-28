package io.github.guillermodubon.musicplayer.models;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.image.Image;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class Playlist {

    private long id;
    private String title;
    String authorName;
    private String description;
    private String date;
    private String coverUrl;
    private ObservableList<Song> songList;

    public Playlist(long id,
                    String title,
                    String authorName,
                    String description,
                    String date,
                    Image imageCover,
                    ObservableList<Song> songList) {
        this.id = id;
        this.title = title;
        this.authorName=authorName;
        this.description = description;
        this.date = date;
        this.coverUrl = imageUrl(imageCover);
        this.songList = songList;
    }

    public long getId() {
        return id;
    }
    public void setId(long id) {
        this.id = id;
    }
    public String getTitle() {
        return title;
    }
    public void setTitle(String title) {
        this.title = title;
    }
    public String getAuthorName() {
        return authorName;
    }
    public void setAuthorName(String authorName) {
        this.authorName = authorName;
    }
    public String getDescription() {
        return description;
    }
    public void setDescription(String description) {
        this.description = description;
    }
    public String getDate() {
        return date;
    }
    public void setDate(String date) {
        this.date = date;
    }
    public Image getImageCover() {
        return null;
    }
    public void setImageCover(Image imageCover) {
        this.coverUrl = imageUrl(imageCover);
    }
    public String getCoverUrl() {
        return coverUrl;
    }
    public void setCoverUrl(String coverUrl) {
        this.coverUrl = normalizeUrl(coverUrl);
    }
    public ObservableList<Song> getSongList() {
        return this.songList;
    }
    public void setSongList(ObservableList<Song> songList) {
        this.songList = songList;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Playlist that)) return false;
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    @Override
    public String toString() {
        return "Playlist{" +
                "id=" + id +
                ", title='" + title + '\'' +
                ", songs=" + songList.size() +
                '}';
    }


    private static String imageUrl(Image image) {
        if (image == null) return null;
        try {
            return normalizeUrl(image.getUrl());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normalizeUrl(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

}
