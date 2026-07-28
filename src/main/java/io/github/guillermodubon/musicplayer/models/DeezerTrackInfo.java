package io.github.guillermodubon.musicplayer.models;

public class DeezerTrackInfo{
    private long id;
    private String title;
    private int trackOrder;

    public DeezerTrackInfo(long id, String title, int trackOrder){
        this.id = id;
        this.title = title;
        this.trackOrder = trackOrder;
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

    public int getTrackOrder() {
        return trackOrder;
    }

    public void setTrackOrder(int trackOrder) {
        this.trackOrder = trackOrder;
    }
}
