package io.github.guillermodubon.musicplayer.models;

import javafx.scene.image.Image;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class Artist {

    private long ArtistID;
    private String name;
    private String biography;
    private String portraitUrl;

    public Artist(long artistID, String name, String biography, List<Image> portrait) {
        this.ArtistID = artistID;
        this.name = name;
        this.biography = biography;
        this.portraitUrl = firstImageUrl(portrait);
    }

    public long getArtistID() {
        return ArtistID;
    }

    public void setArtistID(long artistID) {
        ArtistID = artistID;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBiography() {
        return biography;
    }

    public void setBiography(String biography) {
        this.biography = biography;
    }

    public List<Image> getPortrait() {
        return Collections.emptyList();
    }

    public void setPortrait(List<Image> portrait) {
        this.portraitUrl = firstImageUrl(portrait);
    }

    public String getPortraitUrl() {
        return portraitUrl;
    }

    public void setPortraitUrl(String portraitUrl) {
        this.portraitUrl = normalizeUrl(portraitUrl);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Artist artist = (Artist) o;
        if (ArtistID > 0 && artist.ArtistID > 0) {
            return ArtistID == artist.ArtistID;
        }
        return Objects.equals(name == null ? null : name.toLowerCase(), artist.name == null ? null : artist.name.toLowerCase());
    }

    @Override
    public int hashCode() {
        if (ArtistID > 0) return Long.hashCode(ArtistID);
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
