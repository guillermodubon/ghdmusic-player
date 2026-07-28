package io.github.guillermodubon.musicplayer.models;

public class Genre {

    private int GenreID;
    private String name;

    public Genre(int GenreID, String name) {
        this.GenreID = GenreID;
        this.name = name;
    }

    public int getGenreID() {
        return GenreID;
    }

    public void setGenreID(int genreID) {
        GenreID = genreID;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
