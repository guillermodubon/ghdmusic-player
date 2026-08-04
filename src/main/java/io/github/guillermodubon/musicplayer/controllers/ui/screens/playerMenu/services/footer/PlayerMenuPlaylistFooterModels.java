package io.github.guillermodubon.musicplayer.controllers.ui.screens.playerMenu.services.footer;

import java.util.List;

/** Immutable values shared by the playlist footer modules. */
public final class PlayerMenuPlaylistFooterModels {

    public static final String REMOTE_SUGGESTION_RUN_ID_KEY = "suggestionRunId";
    public static final String REMOTE_RETRY_COUNT_KEY = "remoteSuggestionRetryCount";
    public static final String REMOTE_RETRY_SCHEDULED_KEY = "remoteSuggestionRetryScheduled";

    private PlayerMenuPlaylistFooterModels() {
    }

    public record RemotePlaylistSpec(long id, String title, String coverUrl, String subtitle) {
    }

    public record RemotePlaylistFetchResult(
            List<RemotePlaylistSpec> specs,
            boolean successfulResponse
    ) {
        public static RemotePlaylistFetchResult success(List<RemotePlaylistSpec> specs) {
            return new RemotePlaylistFetchResult(specs == null ? List.of() : List.copyOf(specs), true);
        }

        public static RemotePlaylistFetchResult failure() {
            return new RemotePlaylistFetchResult(List.of(), false);
        }
    }

    public record BulkAddResult(io.github.guillermodubon.musicplayer.models.Playlist playlist,
                                List<io.github.guillermodubon.musicplayer.models.Song> songs,
                                Throwable error) {
    }
}
