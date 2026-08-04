package io.github.guillermodubon.musicplayer.services.downloads.services;

import io.github.guillermodubon.musicplayer.services.downloads.context.DownloadTaskContext;
import io.github.guillermodubon.musicplayer.services.downloads.logging.DownloadLog;
import io.github.guillermodubon.musicplayer.services.api.DeezerApiService;
import io.github.guillermodubon.musicplayer.models.DeezerApiMetaData;

import java.util.concurrent.CompletableFuture;

public class DownloadMetadataService {

    public CompletableFuture<DeezerApiMetaData> fetchAsync(DownloadTaskContext context, String desiredBaseName) {
        DeezerApiMetaData hint = context == null ? null : context.getMetadataHint();
        if (isSufficientForPostProcessing(hint)
                && hasContributorArtwork(context, hint)) {
            DownloadLog.info(
                    "DownloadMetadataService",
                    "Using existing metadata hint for track id=" + hint.getTrackId()
            );
            return CompletableFuture.completedFuture(hint);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                DownloadLog.info("DownloadMetadataService", "Fetching Deezer metadata for \"" + desiredBaseName + "\"");
                DeezerApiService deezer = new DeezerApiService();
                if (hint != null && hint.getTrackId() > 0) {
                    DeezerApiMetaData exact = deezer.getTrackMetadataById(hint.getTrackId(), hint);
                    DownloadLog.info("DownloadMetadataService", "Fetched metadata using track id=" + hint.getTrackId());
                    return exact == null ? hint : exact;
                }
                DeezerApiMetaData result = deezer.getFetchedApiMetadataObject(desiredBaseName);
                DownloadLog.info("DownloadMetadataService", "Metadata search completed for \"" + desiredBaseName + "\"");
                return result;
            } catch (Exception ex) {
                DownloadLog.error("DownloadMetadataService", "Metadata lookup failed for \"" + desiredBaseName + "\"", ex);
                return hint != null && hint.getTrackId() > 0 ? hint : null;
            }
        }, DownloadPipelineExecutors.metadata());
    }

    private boolean isSufficientForPostProcessing(DeezerApiMetaData hint) {
        return hint != null
                && hint.getTrackId() > 0
                && hint.getAlbumId() > 0
                && hint.getSongName() != null
                && !hint.getSongName().isBlank()
                && hint.getAlbumName() != null
                && !hint.getAlbumName().isBlank()
                && hint.getRecordType() != null
                && !hint.getRecordType().isBlank()
                && hint.getNumberOfTracks() > 0;
    }

    /**
     * A batch task can have all scalar metadata and contributor identities
     * already available while still missing the Deezer portraits needed by
     * ArtistImage persistence. In that case the exact track request must run
     * once so the post-processing pipeline can persist those images.
     */
    private boolean hasContributorArtwork(
            DownloadTaskContext context,
            DeezerApiMetaData metadata
    ) {
        if (metadata == null
                || metadata.getSongContributorNames() == null
                || metadata.getSongContributorNames().isEmpty()) {
            // Bulk album/playlist songs can be created with only the album
            // artist. Fetching the exact track is what reveals collaborators
            // before the metadata is persisted.
            return context == null || !context.isBulkDownload();
        }

        var names = metadata.getSongContributorNames();
        var portraits = metadata.getSongContributorsPortraitBytes();

        for (int index = 0; index < names.size(); index++) {
            String name = names.get(index);
            if (name == null || name.isBlank()) continue;

            if (portraits == null
                    || index >= portraits.size()
                    || !hasImageBytes(portraits.get(index))) {
                return false;
            }
        }

        return true;
    }

    private boolean hasImageBytes(java.util.List<byte[]> images) {
        if (images == null || images.isEmpty()) return false;

        return images.stream()
                .anyMatch(image -> image != null && image.length > 0);
    }

    public CompletableFuture<DeezerApiMetaData> fetchAsync(String desiredBaseName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                DownloadLog.info("DownloadMetadataService", "Fetching Deezer metadata for \"" + desiredBaseName + "\"");
                DeezerApiService deezer = new DeezerApiService();
                return deezer.getFetchedApiMetadataObject(desiredBaseName);
            } catch (Exception ex) {
                DownloadLog.error("DownloadMetadataService", "Metadata lookup failed for \"" + desiredBaseName + "\"", ex);
                return null;
            }
        }, DownloadPipelineExecutors.metadata());
    }
}
