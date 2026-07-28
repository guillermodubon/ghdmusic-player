package io.github.guillermodubon.musicplayer.models;

public class WikipediaApiMetadata {

    private String artistName;
    private String artistBiography;
    /**
     * Indicates that the music profile also matches a public-person identity
     * such as a YouTuber, influencer, or other media personality.
     *
     * This is optional metadata; it does not change the biography contract
     * used by existing callers.
     */
    private boolean publicFigure;

    public WikipediaApiMetadata(String artistName,String artistBiography) {
        this(artistName, artistBiography, false);
    }

    public WikipediaApiMetadata(String artistName, String artistBiography, boolean publicFigure) {
        this.artistBiography = artistBiography;
        this.artistName = artistName;
        this.publicFigure = publicFigure;
    }


    public String getArtistBiography() {
        return artistBiography;
    }
    public void setArtistBiography(String artistBiography) {
        this.artistBiography = artistBiography;
    }

    public String getArtistName() {
        return artistName;
    }

    public void setArtistName(String artistName) {
        this.artistName = artistName;
    }

    public boolean isPublicFigure() {
        return publicFigure;
    }

    public void setPublicFigure(boolean publicFigure) {
        this.publicFigure = publicFigure;
    }
}
