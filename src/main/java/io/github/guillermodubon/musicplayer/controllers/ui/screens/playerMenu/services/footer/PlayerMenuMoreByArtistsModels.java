package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.footer;

import javafx.scene.image.Image;
import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.Song;

import java.util.List;

/** Shared immutable models and property keys for the more-by-artists footer. */
public final class PlayerMenuMoreByArtistsModels {

    public static final String FOOTER_RUN_ID_KEY = "footerRunId";
    public static final String LAZY_LOADER_KEY = "moreByArtistLazyLoader";
    public static final String LAZY_LOADED_KEY = "moreByArtistLazyLoaded";
    public static final String REMOTE_FETCH_STARTED_KEY = "fetchRemoteStarted";
    public static final String REMOTE_RETRY_COUNT_KEY = "moreByArtistRemoteRetryCount";
    public static final String REMOTE_RETRY_SCHEDULED_KEY = "moreByArtistRemoteRetryScheduled";
    public static final String REMOTE_ARTIST_CACHE_KEY = "moreByArtistRemoteArtistCacheKey";

    private PlayerMenuMoreByArtistsModels() {
    }

    public record FooterCardSpec(
            String type,
            long id,
            String title,
            Image localCover,
            String coverUrl,
            List<String> artists
    ) {
        public String key() {
            return type + ":" + id;
        }
    }

    public record RemoteFetchResult(
            List<FooterCardSpec> specs,
            boolean responseSucceeded
    ) {
        public static RemoteFetchResult success(List<FooterCardSpec> specs) {
            return new RemoteFetchResult(specs == null ? List.of() : specs, true);
        }

        public static RemoteFetchResult failure() {
            return new RemoteFetchResult(List.of(), false);
        }
    }

    public record RemoteFetchPair(
            RemoteFetchResult albums,
            RemoteFetchResult tracks,
            String cacheKey
    ) {
        public boolean succeededWithoutResults() {
            return albums.responseSucceeded()
                    && tracks.responseSucceeded()
                    && albums.specs().isEmpty()
                    && tracks.specs().isEmpty();
        }
    }

    public record LibrarySnapshot(List<Album> albums, List<Song> songs) {
    }
}
