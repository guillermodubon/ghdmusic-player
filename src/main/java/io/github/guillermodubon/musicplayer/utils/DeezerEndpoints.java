package io.github.guillermodubon.musicplayer.utils;


public final class DeezerEndpoints {

    private DeezerEndpoints() {}

    public static MainMenuEndpoints defaultMainMenuEndpoints() {
        return new MainMenuEndpoints(
                "https://api.deezer.com/chart/0/tracks",
                "https://api.deezer.com/chart/0/albums",
                "https://api.deezer.com/artist/%d/top?limit=%d",
                "https://api.deezer.com/chart/%d/playlists",
                "https://api.deezer.com/chart/0/playlists",
                "https://api.deezer.com/search/playlist?q=%s",
                "https://api.deezer.com/editorial/0/releases",
                "https://api.deezer.com/editorial/%d/releases",
                "https://api.deezer.com/track/%d",
                "https://api.deezer.com/album/%d",
                "https://api.deezer.com/search/artist?q=%s",
                "https://api.deezer.com/artist/%d/albums",
                "https://api.deezer.com/chart/%d/artists",
                "https://api.deezer.com/artist/%d/related?limit=%d"
        );
    }

    public static DiscoverEndpoints defaultDiscoverEndpoints() {
        return new DiscoverEndpoints(
                "https://api.deezer.com/genre",
                "https://api.deezer.com/chart/%d/artists",
                "https://api.deezer.com/chart/%d/tracks",
                "https://api.deezer.com/chart/%d/albums",
                "https://api.deezer.com/chart/%d/playlists",
                "https://api.deezer.com/search/playlist?q=%s",
                "https://api.deezer.com/track/%d",
                "https://api.deezer.com/album/%d",
                "https://api.deezer.com/artist/%d/albums",
                "https://api.deezer.com/artist/%d/related?limit=%d",
                "https://api.deezer.com/chart/0/tracks",
                "https://api.deezer.com/editorial/0/releases",
                "https://api.deezer.com/editorial?country=SV",
                "https://api.deezer.com/editorial/%d/releases?country=SV&limit=%d"
        );
    }

    public static GenreDetailsControllerEndpoints defaultGenreDetailsControllerEndpoints() {
        return new GenreDetailsControllerEndpoints(
                "https://api.deezer.com/chart/%d/tracks",
                "https://api.deezer.com/chart/%d/albums",
                "https://api.deezer.com/chart/%d/playlists",
                "https://api.deezer.com/search/playlist?q=%s",
                "https://api.deezer.com/search/track?q=%s",
                "https://api.deezer.com/search/album?q=%s",
                "https://api.deezer.com/track/%d",
                "https://api.deezer.com/album/%d",
                "https://api.deezer.com/artist/%d/top?limit=%d",
                "https://api.deezer.com/artist/%d/albums"
        );
    }

    public static SearchResultsEndpoints defaultSearchResultsEndpoints() {
        return new SearchResultsEndpoints(
                "https://api.deezer.com/search/album?q=%s",
                "https://api.deezer.com/search/track?q=%s",
                "https://api.deezer.com/search/playlist?q=%s",
                "https://api.deezer.com/search/artist?q=%s",
                "https://api.deezer.com/search?q=%s",
                "https://api.deezer.com/track/%d",
                "https://api.deezer.com/album/%d"
        );
    }

    public static SearchDropdownEndpoints defaultSearchDropdownEndpoints() {
        return new SearchDropdownEndpoints(
                "https://api.deezer.com/search/album?q=%s",
                "https://api.deezer.com/search/track?q=%s",
                "https://api.deezer.com/search/playlist?q=%s",
                "https://api.deezer.com/search/artist?q=%s",
                "https://api.deezer.com/search?q=%s",
                "https://api.deezer.com/track/%d",
                "https://api.deezer.com/album/%d"
        );
    }

    public static ArtistPageEndpoints defaultArtistPageEndpoints() {
        return new ArtistPageEndpoints(
                "https://api.deezer.com/artist/%d/top?limit=%d",
                "https://api.deezer.com/artist/%d/albums",
                "https://api.deezer.com/search/playlist?q=%s",
                "https://api.deezer.com/album/%d",
                "https://api.deezer.com/track/%d"
        );
    }

    public static String artistById(long id) {
        return String.format("https://api.deezer.com/artist/%d", id);
    }

    public record SearchDropdownEndpoints(
            String searchAlbums,
            String searchTracks,
            String searchPlaylists,
            String searchArtists,
            String searchAll,
            String trackById,
            String albumById
    ) {
        public String searchAlbums(String query) { return String.format(searchAlbums, query); }
        public String searchTracks(String query) { return String.format(searchTracks, query); }
        public String searchPlaylists(String query) { return String.format(searchPlaylists, query); }
        public String searchArtists(String query) { return String.format(searchArtists, query); }
        public String searchAll(String query) { return String.format(searchAll, query); }
        public String albumById(long id) { return String.format(albumById, id); }
    }

    public record SearchResultsEndpoints(
            String searchAlbums,
            String searchTracks,
            String searchPlaylists,
            String searchArtists,
            String searchAll,
            String trackById,
            String albumById
    ) {
        public String searchAlbums(String query) { return String.format(searchAlbums, query); }
        public String searchTracks(String query) { return String.format(searchTracks, query); }
        public String searchPlaylists(String query) { return String.format(searchPlaylists, query); }
        public String searchArtists(String query) { return String.format(searchArtists, query); }
        public String trackById(long id) { return String.format(trackById, id); }
        public String albumById(long id) { return String.format(albumById, id); }
    }

    public record ArtistPageEndpoints(
            String artistTopTracks,
            String artistAlbums,
            String searchPlaylists,
            String albumById,
            String trackById
    ) {
        public String artistTopTracks(long artistId, int limit) { return String.format(artistTopTracks, artistId, limit); }
        public String artistAlbums(long artistId) { return String.format(artistAlbums, artistId); }
        public String searchPlaylists(String query) { return String.format(searchPlaylists, query); }
        public String albumById(long id) { return String.format(albumById, id); }
        public String trackById(long id) { return String.format(trackById, id); }
    }

    public record MainMenuEndpoints(
            String chartTracks,
            String chartAlbums,
            String artistTopTracks,
            String genrePlaylists,
            String chartPlaylists,
            String searchPlaylists,
            String editorialReleases,
            String genreReleases,
            String trackById,
            String albumById,
            String searchArtists,
            String artistAlbums,
            String genreArtists,
            String artistRelated
    ) {
        public String artistTopTracks(long artistId, int limit) { return String.format(artistTopTracks, artistId, limit); }
        public String genrePlaylists(int genreId) { return String.format(genrePlaylists, genreId); }
        public String searchPlaylists(String query) { return String.format(searchPlaylists, query); }
        public String trackById(long id) { return String.format(trackById, id); }
        public String albumById(long id) { return String.format(albumById, id); }
        public String searchArtists(String query) { return String.format(searchArtists, query); }
        public String artistAlbums(long artistId) { return String.format(artistAlbums, artistId); }
        public String artistAlbums(long artistId, int limit, int index) {
            return artistAlbums(artistId) + "?limit=" + Math.max(1, limit) + "&index=" + Math.max(0, index);
        }
        public String genreArtists(int genreId) { return String.format(genreArtists, genreId); }
        public String artistRelated(long artistId, int limit) { return String.format(artistRelated, artistId, Math.max(1, limit)); }
    }

    public record DiscoverEndpoints(
            String genreRoot,
            String genreArtists,
            String genreTracks,
            String genreAlbums,
            String genrePlaylists,
            String searchPlaylists,
            String trackById,
            String albumById,
            String artistAlbums,
            String artistRelated,
            String chartTracks,
            String editorialReleases,
            String editorialCatalog,
            String regionalEditorialReleases
    ) {
        public String genreAlbums(int genreId) { return String.format(genreAlbums, genreId); }
        public String trackById(long id) { return String.format(trackById, id); }
        public String albumById(long id) { return String.format(albumById, id); }
        public String chartTracks() { return chartTracks; }
        public String editorialCatalog() { return editorialCatalog; }
        public String regionalEditorialReleases(long editorialId, int limit) {
            return String.format(regionalEditorialReleases, editorialId, Math.max(1, limit));
        }
    }

    public record GenreDetailsControllerEndpoints(
            String genreTracks,
            String genreAlbums,
            String genrePlaylists,
            String searchPlaylists,
            String searchTracks,
            String searchAlbums,
            String trackById,
            String albumById,
            String artistTopTracks,
            String artistAlbums
    ) {
        public String genreTracks(int genreId) { return String.format(genreTracks, genreId); }
        public String genreTracks(int genreId, int limit) { return withLimit(genreTracks(genreId), limit); }
        public String genreAlbums(int genreId) { return String.format(genreAlbums, genreId); }
        public String genreAlbums(int genreId, int limit) { return withLimit(genreAlbums(genreId), limit); }
        public String searchPlaylists(String query) { return String.format(searchPlaylists, query); }
        public String trackById(long id) { return String.format(trackById, id); }
        public String albumById(long id) { return String.format(albumById, id); }
        public String artistTopTracks(long artistId, int limit) { return String.format(artistTopTracks, artistId, limit); }
        public String artistAlbums(long artistId) { return String.format(artistAlbums, artistId); }
        private String withLimit(String url, int limit) { return url + (url.contains("?") ? "&" : "?") + "limit=" + limit; }
    }
}