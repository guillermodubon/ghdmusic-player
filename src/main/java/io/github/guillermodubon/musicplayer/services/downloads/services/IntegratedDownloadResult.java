package io.github.guillermodubon.musicplayer.services.downloads.services;

import io.github.guillermodubon.musicplayer.models.Song;
import java.io.File;

public record IntegratedDownloadResult(
        Song remoteSong,
        Song localSong,
        File downloadedFile,
        Long sourceCollectionId,
        String sourceCollectionType
) {

    public IntegratedDownloadResult(
            Song remoteSong,
            Song localSong,
            File downloadedFile
    ) {
        this(
                remoteSong,
                localSong,
                downloadedFile,
                null,
                null
        );
    }
}
