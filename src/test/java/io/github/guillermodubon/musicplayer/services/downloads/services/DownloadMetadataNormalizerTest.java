package io.github.guillermodubon.musicplayer.services.downloads.services;

import io.github.guillermodubon.musicplayer.models.Album;
import io.github.guillermodubon.musicplayer.models.DeezerApiMetaData;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.downloads.context.DownloadTaskContext;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DownloadMetadataNormalizerTest {

    @Test
    void sourceDatabaseIdsNeverBecomeDeezerProviderIds() {
        Album sourceAlbum = new Album(123, "Local release", List.of(), null, "album", null,
                List.of(), List.of(), 4);
        Song sourceSong = new Song(42, "Local track", List.of(), sourceAlbum, null, 2, true);
        DownloadTaskContext context = new DownloadTaskContext("query", new File("."), "Local track");
        context.setSourceSong(sourceSong);

        DeezerApiMetaData normalized = DownloadMetadataNormalizer.normalize(
                new DeezerApiMetaData(), context, "Local track", null
        );

        assertEquals(0, normalized.getTrackId());
        assertEquals(0, normalized.getAlbumId());
        assertEquals(42, normalized.getCanonicalSourceSongIdHint());
    }
}
