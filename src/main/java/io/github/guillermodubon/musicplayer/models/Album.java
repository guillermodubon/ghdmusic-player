package io.github.guillermodubon.musicplayer.models;

import javafx.scene.image.Image;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class Album {

    private long AlbumID;
    private String name;
    private List<Artist> artist;
    private Genre genre;
    private String recordType;
    private String releaseDate;
    private String coverUrl;
    private List<Song> songList;
    private int numberOfTracks;

    public Album(long AlbumID, String name, List<Artist> artist, Genre genre, String recordType,String releaseDate,List<Image> cover,List<Song> songList, int numberOfTracks) {
        this.AlbumID = AlbumID;
        this.name = name;
        this.artist = artist;
        this.genre = genre;
        this.recordType = recordType;
        this.releaseDate = releaseDate;
        this.coverUrl = firstImageUrl(cover);
        this.songList = songList;
        this.numberOfTracks = numberOfTracks;
    }

    public long getAlbumID() {
        return AlbumID;
    }

    public void setAlbumID(long albumID) {
        AlbumID = albumID;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<Artist> getArtist() {
        return artist;
    }

    public void setArtist(List<Artist> artist) {
        this.artist = artist;
    }

    public Genre getGenre() {
        return genre;
    }

    public void setGenre(Genre genre) {
        this.genre = genre;
    }

    public String getRecordType() {
        return recordType;
    }

    public void setRecordType(String recordType) {
        this.recordType = recordType;
    }

    public String getReleaseDate() {
        return releaseDate;
    }

    public void setReleaseDate(String releaseDate) {
        this.releaseDate = releaseDate;
    }

    public List<Image> getCover() {
        return Collections.emptyList();
    }

    public void setCover(List<Image> cover) {
        this.coverUrl = firstImageUrl(cover);
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public void setCoverUrl(String coverUrl) {
        this.coverUrl = normalizeUrl(coverUrl);
    }

    public List<Song> getSongList() {
        return songList;
    }

    public void setSongList(List<Song> songList) {
        this.songList = songList;
    }

    public int getNumberOfTracks() {
        return numberOfTracks;
    }

    public void setNumberOfTracks(int numberOfTracks) {
        this.numberOfTracks = numberOfTracks;
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Album album = (Album) o;
        if (AlbumID > 0 && album.AlbumID > 0) {
            return AlbumID == album.AlbumID;
        }
        return Objects.equals(name == null ? null : name.toLowerCase(), album.name == null ? null : album.name.toLowerCase());
    }

    @Override
    public int hashCode() {
        if (AlbumID > 0) return Long.hashCode(AlbumID);
        return name == null ? 0 : name.toLowerCase().hashCode();
    }

    private static String firstImageUrl(List<Image> images) {
        if (images == null || images.isEmpty()) return null;
        for (Image image : images) {
            String url = imageUrl(image);
            if (url != null) return url;
        }
        return null;
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
