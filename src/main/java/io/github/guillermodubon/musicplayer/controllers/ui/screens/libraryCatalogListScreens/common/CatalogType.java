package io.github.guillermodubon.musicplayer.controllers.ui.screens.libraryCatalogListScreens.common;

public enum CatalogType {
    PLAYLISTS("All Playlists"),
    ALBUMS("All Albums"),
    SINGLES("All Singles");

    private final String title;

    CatalogType(String title) {
        this.title = title;
    }

    public String getTitle() {
        return title;
    }
}
